// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";

/// Self-service Origin only. No privileged party can spend or register for another holder.
contract CaedralOriginPayments is EIP712, ReentrancyGuard {
    using SafeERC20 for IERC20;
    uint256 public constant CASHBACK_CHANGE = 1801450800;
    bytes32 public constant INVOICE_TYPEHASH = keccak256("Invoice(address merchant,address token,uint256 gross,bytes32 id,uint64 issuedAt,uint64 expiresAt)");
    address public immutable treasury;
    address public immutable stableToken;
    struct Card { bytes32 id; uint64 expiresAt; }
    struct Invoice { address merchant; address token; uint256 gross; bytes32 id; uint64 issuedAt; uint64 expiresAt; }
    mapping(address => Card) public cards;
    mapping(bytes32 => bool) public issued;
    mapping(address => mapping(bytes32 => bool)) public paid;
    event CardRegistered(address indexed holder, bytes32 indexed id, uint64 expiresAt);
    event Paid(bytes32 indexed id, address indexed merchant, address indexed payer, address token, uint256 gross, uint256 fee, uint256 cashback);
    constructor(address treasury_, address stable_) EIP712("CaedralOrigin", "1") {
        require(treasury_ != address(0), "treasury");
        require(stable_.code.length > 0 && IERC20Metadata(stable_).decimals() == 18, "token");
        treasury = treasury_; stableToken = stable_;
    }
    // Metadata is bound to the transaction signer. First use is part of the
    // purchase, never a separate activation or funding transaction.
    function _useCard(bytes32 id, bytes32 salt, uint64 expiresAt) private {
        require(id != bytes32(0) && id == sha256(abi.encodePacked("CaedralOriginCardId/v1",msg.sender,salt)), "card id binding");
        require(expiresAt > block.timestamp && expiresAt <= block.timestamp + 1827 days, "expiry");
        Card memory current=cards[msg.sender];
        if(current.id==id) require(current.expiresAt==expiresAt,"card expiry changed");
        else {
            require(!issued[id],"replaced card");
            issued[id]=true; cards[msg.sender]=Card(id,expiresAt);
            emit CardRegistered(msg.sender,id,expiresAt);
        }
    }
    function quote(uint256 gross) public view returns (uint256 fee, uint256 cashback) {
        fee = Math.max(Math.ceilDiv(gross, 100), 0.1 ether);
        require(gross > fee, "below minimum");
        cashback = Math.mulDiv(gross, block.timestamp < CASHBACK_CHANGE ? 10 : 100, 10000);
    }
    function invoiceDigest(Invoice calldata i) public view returns (bytes32) {
        return _hashTypedDataV4(keccak256(abi.encode(INVOICE_TYPEHASH, i.merchant, i.token, i.gross, i.id, i.issuedAt, i.expiresAt)));
    }
    function pay(Invoice calldata i, bytes calldata signature, bytes32 cardId, bytes32 cardSalt, uint64 cardExpiry) external payable nonReentrant {
        _useCard(cardId,cardSalt,cardExpiry);
        require(i.merchant != address(0) && i.merchant != address(this) && i.merchant != msg.sender, "merchant");
        require(i.token == address(0) || i.token == stableToken, "token");
        require(i.id != bytes32(0) && !paid[i.merchant][i.id], "invoice used");
        require(i.issuedAt <= block.timestamp && i.expiresAt > block.timestamp && i.expiresAt > i.issuedAt && i.expiresAt - i.issuedAt <= 120, "invoice expiry");
        require(ECDSA.recover(invoiceDigest(i), signature) == i.merchant, "invoice signature");
        (uint256 fee, uint256 cashback) = quote(i.gross);
        paid[i.merchant][i.id] = true;
        if (i.token == address(0)) require(msg.value == i.gross, "native value");
        else {
            require(msg.value == 0, "unexpected native");
            require(IERC20(stableToken).balanceOf(msg.sender)>=i.gross,"insufficient token balance");
            uint256 before_ = IERC20(stableToken).balanceOf(address(this));
            IERC20(stableToken).safeTransferFrom(msg.sender, address(this), i.gross);
            require(IERC20(stableToken).balanceOf(address(this)) - before_ == i.gross, "nonstandard token");
        }
        _send(i.token, i.merchant, i.gross - fee);
        _send(i.token, msg.sender, cashback);
        _send(i.token, treasury, fee - cashback);
        emit Paid(i.id, i.merchant, msg.sender, i.token, i.gross, fee, cashback);
    }
    function _send(address token, address to, uint256 amount) private {
        if (amount == 0) return;
        if (token == address(0)) { (bool ok,) = to.call{value: amount}(""); require(ok, "transfer"); }
        else IERC20(token).safeTransfer(to, amount);
    }
}
