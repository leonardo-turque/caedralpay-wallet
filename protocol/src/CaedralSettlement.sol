// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";

/**
 * Experimental, not deployed. Atomic gross payment, merchant fee and funded cashback.
 * No backend spends user funds: payer must submit the transaction personally.
 * Card display names are deliberately absent from chain data and signatures.
 */
contract CaedralSettlement is EIP712, ReentrancyGuard {
    using SafeERC20 for IERC20;
    uint256 public constant CASHBACK_CHANGE = 1801450800; // 2027-02-01 00:00 America/Sao_Paulo
    bytes32 public constant INVOICE_TYPEHASH = keccak256(
        "Invoice(address merchant,address token,uint256 gross,bytes32 id,uint64 issuedAt,uint64 expiresAt)"
    );
    bytes32 public constant CARD_TYPEHASH = keccak256(
        "Card(address holder,bytes32 id,uint8 tier,uint64 issuedAt,uint64 expiresAt)"
    );
    address public immutable treasury;
    address public immutable cardIssuer;
    mapping(address => uint256) public minimumFee;
    mapping(address => bool) public supported;
    mapping(address => uint256) public reserve;
    mapping(address => mapping(bytes32 => bool)) public consumed;
    mapping(bytes32 => bool) public revoked;

    struct Invoice { address merchant; address token; uint256 gross; bytes32 id; uint64 issuedAt; uint64 expiresAt; }
    struct Card { address holder; bytes32 id; uint8 tier; uint64 issuedAt; uint64 expiresAt; }
    event Paid(bytes32 indexed invoiceId, address indexed merchant, address indexed payer,
        address token, uint256 gross, uint256 fee, uint256 cashback);
    event ReserveFunded(address indexed token, uint256 amount);
    event CardRevoked(bytes32 indexed cardId);

    constructor(address treasury_, address issuer_, address[] memory tokens, uint8[] memory decimals_)
        EIP712("Caedral", "1")
    {
        require(treasury_ != address(0) && issuer_ != address(0), "zero authority");
        require(tokens.length == decimals_.length && tokens.length > 0, "assets");
        treasury = treasury_; cardIssuer = issuer_;
        for (uint256 i; i < tokens.length; ++i) {
            require(!supported[tokens[i]] && decimals_[i] >= 1 && decimals_[i] <= 18, "asset config");
            require(tokens[i] == address(0) ? decimals_[i] == 18 : tokens[i].code.length > 0, "asset code");
            if (tokens[i] != address(0)) require(IERC20Metadata(tokens[i]).decimals() == decimals_[i], "decimals");
            supported[tokens[i]] = true;
            minimumFee[tokens[i]] = 10 ** (decimals_[i] - 1);
        }
    }

    function quote(address token, uint256 gross, uint8 tier) public view
        returns (uint256 fee, uint256 cashback, uint256 subsidy)
    {
        require(supported[token] && tier <= 2, "unsupported");
        fee = Math.max(Math.ceilDiv(gross, 100), minimumFee[token]);
        require(fee < gross, "below minimum");
        uint256 bps = block.timestamp < CASHBACK_CHANGE ? 10 : tier == 0 ? 100 : tier == 1 ? 250 : 500;
        cashback = Math.mulDiv(gross, bps, 10_000);
        subsidy = cashback > fee ? cashback - fee : 0;
    }

    function invoiceDigest(Invoice calldata i) public view returns (bytes32) {
        return _hashTypedDataV4(keccak256(abi.encode(INVOICE_TYPEHASH,
            i.merchant, i.token, i.gross, i.id, i.issuedAt, i.expiresAt)));
    }
    function cardDigest(Card calldata c) public view returns (bytes32) {
        return _hashTypedDataV4(keccak256(abi.encode(CARD_TYPEHASH,
            c.holder, c.id, c.tier, c.issuedAt, c.expiresAt)));
    }

    function revokeCard(bytes32 id) external {
        require(msg.sender == cardIssuer, "issuer only");
        revoked[id] = true;
        emit CardRevoked(id);
    }

    function cancelInvoice(bytes32 id) external {
        require(id != bytes32(0) && !consumed[msg.sender][id], "invoice used");
        consumed[msg.sender][id] = true;
    }

    /** Voluntary treasury funding, no user deposits, no withdrawal or arbitrary-call function. */
    function fundReserve(address token, uint256 amount) external payable nonReentrant {
        require(supported[token] && amount > 0, "asset/amount");
        _collect(token, amount);
        reserve[token] += amount;
        emit ReserveFunded(token, amount);
    }

    function pay(Invoice calldata i, bytes calldata merchantSignature, Card calldata c, bytes calldata cardSignature)
        external payable nonReentrant
    {
        require(i.merchant != address(0) && i.merchant != address(this) && i.merchant != msg.sender, "merchant");
        require(i.id != bytes32(0) && !consumed[i.merchant][i.id], "invoice used");
        require(i.issuedAt <= block.timestamp && i.expiresAt > block.timestamp &&
            i.expiresAt > i.issuedAt && i.expiresAt - i.issuedAt <= 120, "invoice expired");
        require(ECDSA.recover(invoiceDigest(i), merchantSignature) == i.merchant, "merchant signature");
        require(c.holder == msg.sender && c.id != bytes32(0) && !revoked[c.id] && c.tier <= 2, "card");
        // Calendar-exact five years is enforced at issuance; bounded here to at most 1,827 days.
        require(c.issuedAt <= block.timestamp && c.expiresAt > block.timestamp && c.expiresAt > c.issuedAt &&
            c.expiresAt - c.issuedAt <= 1827 days, "card expired");
        require(ECDSA.recover(cardDigest(c), cardSignature) == cardIssuer, "card signature");
        (uint256 fee, uint256 cashback, uint256 subsidy) = quote(i.token, i.gross, c.tier);
        require(reserve[i.token] >= subsidy, "cashback unfunded");
        consumed[i.merchant][i.id] = true;
        reserve[i.token] -= subsidy;
        _collect(i.token, i.gross);
        _send(i.token, i.merchant, i.gross - fee);
        _send(i.token, msg.sender, cashback);
        _send(i.token, treasury, fee > cashback ? fee - cashback : 0);
        emit Paid(i.id, i.merchant, msg.sender, i.token, i.gross, fee, cashback);
    }

    function _collect(address token, uint256 amount) private {
        if (token == address(0)) { require(msg.value == amount, "native amount"); }
        else {
            require(msg.value == 0, "unexpected native");
            uint256 before_ = IERC20(token).balanceOf(address(this));
            IERC20(token).safeTransferFrom(msg.sender, address(this), amount);
            require(IERC20(token).balanceOf(address(this)) - before_ == amount, "nonstandard token");
        }
    }
    function _send(address token, address to, uint256 amount) private {
        if (amount == 0) return;
        if (token == address(0)) {
            (bool ok,) = to.call{value: amount}(""); require(ok, "native transfer");
        } else IERC20(token).safeTransfer(to, amount);
    }
}
