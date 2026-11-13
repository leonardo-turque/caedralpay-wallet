// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;
import {EIP712} from "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";
interface ICaedralRecurringAccount { function pull(address token,uint256 gross) external; }
/// Immutable collection rules. An executor pays gas but never receives permission to vary a debit.
contract CaedralRecurringPayments is EIP712,ReentrancyGuard {
    using SafeERC20 for IERC20;
    uint256 public constant CASHBACK_CHANGE=1801450800;
    bytes32 public constant OFFER_TYPEHASH=keccak256("Offer(address merchant,address token,uint256 gross,bytes32 id,uint64 intervalDays,uint64 endAt,uint64 issuedAt,uint64 expiresAt)");
    address public immutable treasury;address public immutable stableToken;
    struct Offer {address merchant;address token;uint256 gross;bytes32 id;uint64 intervalDays;uint64 endAt;uint64 issuedAt;uint64 expiresAt;}
    struct Subscription {address payer;address merchant;address token;uint256 gross;uint64 intervalDays;uint64 endAt;uint64 startedAt;uint64 cardExpiry;uint64 lastPeriod;bool cancelled;}
    mapping(bytes32=>Subscription) public subscriptions;
    mapping(address=>bytes32[]) private payerIds;
    mapping(address=>mapping(bytes32=>bool)) public consumedOffers;
    event Authorized(bytes32 indexed offerId,bytes32 indexed id,address indexed payer,address merchant,address token,uint256 gross,uint64 intervalDays,uint64 endAt,uint64 cardExpiry);
    event Collected(bytes32 indexed id,address indexed merchant,address indexed payer,address token,uint256 gross,uint256 fee,uint256 cashback,uint64 period);
    event Cancelled(bytes32 indexed id,address indexed payer);
    constructor(address treasury_,address stable_) EIP712("CaedralRecurring","1") {
        require(treasury_!=address(0) && stable_.code.length>0 && IERC20Metadata(stable_).decimals()==18,"config");treasury=treasury_;stableToken=stable_;
    }
    receive() external payable {}
    function offerDigest(Offer calldata o) public view returns(bytes32) {
        return _hashTypedDataV4(keccak256(abi.encode(OFFER_TYPEHASH,o.merchant,o.token,o.gross,o.id,o.intervalDays,o.endAt,o.issuedAt,o.expiresAt)));
    }
    function subscriptionId(address payer,address merchant,bytes32 offerId) public pure returns(bytes32){return keccak256(abi.encode(payer,merchant,offerId));}
    function authorize(Offer calldata o,bytes calldata signature,bytes32 cardId,bytes32 salt,uint64 cardExpiry) external nonReentrant returns(bytes32 id) {
        require(cardId==sha256(abi.encodePacked("CaedralOriginCardId/v1",msg.sender,salt)),"card binding");
        require(cardExpiry>block.timestamp && cardExpiry<=block.timestamp+1827 days,"card expiry");
        require(o.merchant!=address(0) && o.merchant!=msg.sender && o.merchant!=address(this),"merchant");
        require(o.token==address(0) || o.token==stableToken,"token");
        require(o.id!=bytes32(0) && o.intervalDays>=1 && o.intervalDays<=3650,"schedule");
        require(o.endAt==0 || o.endAt>=block.timestamp,"end");
        require(o.issuedAt<=block.timestamp && o.expiresAt>block.timestamp && o.expiresAt>o.issuedAt && o.expiresAt-o.issuedAt<=120,"offer expiry");
        require(ECDSA.recover(offerDigest(o),signature)==o.merchant,"signature");
        id=subscriptionId(msg.sender,o.merchant,o.id);require(!consumedOffers[o.merchant][o.id],"used offer");consumedOffers[o.merchant][o.id]=true;
        subscriptions[id]=Subscription(msg.sender,o.merchant,o.token,o.gross,o.intervalDays,o.endAt,uint64(block.timestamp),cardExpiry,0,false);
        payerIds[msg.sender].push(id);
        emit Authorized(o.id,id,msg.sender,o.merchant,o.token,o.gross,o.intervalDays,o.endAt,cardExpiry);
        _debit(id,subscriptions[id],0);
    }
    function collect(bytes32 id) external nonReentrant {
        Subscription storage s=subscriptions[id];require(s.payer!=address(0) && !s.cancelled,"inactive");
        require(block.timestamp<s.cardExpiry && (s.endAt==0 || block.timestamp<=s.endAt),"ended");
        uint64 period=uint64((block.timestamp-s.startedAt)/(uint256(s.intervalDays)*1 days));
        require(period>s.lastPeriod,"not due");
        // Missed periods never accumulate debt. At most one debit for the current period.
        s.lastPeriod=period;_debit(id,s,period);
    }
    function cancel(bytes32 id) external {Subscription storage s=subscriptions[id];require(s.payer==msg.sender && !s.cancelled,"payer");s.cancelled=true;emit Cancelled(id,msg.sender);}
    function subscriptionCount(address payer) external view returns(uint256){return payerIds[payer].length;}
    function subscriptionIds(address payer,uint256 offset,uint256 limit) external view returns(bytes32[] memory ids){
        require(limit<=50,"page");uint256 total=payerIds[payer].length;if(offset>=total)return new bytes32[](0);
        uint256 length=Math.min(limit,total-offset);ids=new bytes32[](length);
        for(uint256 n;n<length;n++)ids[n]=payerIds[payer][total-1-offset-n];
    }
    function nextDue(bytes32 id) external view returns(uint256){Subscription storage s=subscriptions[id];return uint256(s.startedAt)+(uint256(s.lastPeriod)+1)*s.intervalDays*1 days;}
    function _debit(bytes32 id,Subscription storage s,uint64 period) private {
        uint256 fee=Math.max(Math.ceilDiv(s.gross,100),0.1 ether);require(s.gross>fee,"minimum");
        uint256 cashback=Math.mulDiv(s.gross,block.timestamp<CASHBACK_CHANGE?10:100,10000);
        uint256 before_=s.token==address(0)?address(this).balance:IERC20(s.token).balanceOf(address(this));
        require(s.payer.code.length>0,"account authorization");
        ICaedralRecurringAccount(s.payer).pull(s.token,s.gross);
        uint256 after_=s.token==address(0)?address(this).balance:IERC20(s.token).balanceOf(address(this));
        require(after_-before_==s.gross,"funding");
        _send(s.token,s.merchant,s.gross-fee);_send(s.token,s.payer,cashback);_send(s.token,treasury,fee-cashback);
        emit Collected(id,s.merchant,s.payer,s.token,s.gross,fee,cashback,period);
    }
    function _send(address token,address to,uint256 amount) private {if(amount==0)return;if(token==address(0)){(bool ok,)=to.call{value:amount}("");require(ok,"transfer");}else IERC20(token).safeTransfer(to,amount);}
}

/// EIP-7702 account logic: no arbitrary execution, no owner initializer, no upgrade path.
/// Funds stay at the holder's original address. Only the immutable schedule contract can pull.
contract CaedralRecurringAccount {
    using SafeERC20 for IERC20;
    address public immutable settlement;address public immutable stableToken;
    constructor(address settlement_,address stable_){require(settlement_.code.length>0 && stable_.code.length>0,"config");settlement=settlement_;stableToken=stable_;}
    receive() external payable {}
    fallback() external payable {}
    function pull(address token,uint256 gross) external {
        require(msg.sender==settlement && gross>0,"caller");
        require(token==address(0) || token==stableToken,"token");
        if(token==address(0)){require(address(this).balance>=gross,"insufficient balance");(bool ok,)=settlement.call{value:gross}("");require(ok,"transfer");}
        else {require(IERC20(token).balanceOf(address(this))>=gross,"insufficient balance");IERC20(token).safeTransfer(settlement,gross);}
    }
}
