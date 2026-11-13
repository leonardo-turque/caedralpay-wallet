// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;
import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {CaedralOriginPayments} from "../src/CaedralOriginPayments.sol";
contract OriginToken is ERC20 { constructor() ERC20("Test", "TEST") {} function mint(address a,uint256 v) external {_mint(a,v);} }
contract CaedralOriginPaymentsTest is Test {
    CaedralOriginPayments s; OriginToken t; address buyer=address(0xB0B); uint256 key=12345; address merchant; address treasury=address(0xCAED);
    bytes32 salt=keccak256("salt"); bytes32 id; uint64 expiry; CaedralOriginPayments.Invoice i;
    function setUp() public {
        vm.warp(1794481200); merchant=vm.addr(key); t=new OriginToken(); s=new CaedralOriginPayments(treasury,address(t));
        vm.deal(buyer,1000 ether);t.mint(buyer,1000 ether);
        id=sha256(abi.encodePacked("CaedralOriginCardId/v1",buyer,salt));expiry=uint64(block.timestamp+1826 days);
        vm.prank(buyer);t.approve(address(s),1000 ether);
        i=CaedralOriginPayments.Invoice(merchant,address(0),1 ether,keccak256("invoice"),uint64(block.timestamp),uint64(block.timestamp+120));
    }
    function sigFor(CaedralOriginPayments target) internal view returns(bytes memory) {(uint8 v,bytes32 r,bytes32 ss)=vm.sign(key,target.invoiceDigest(i));return abi.encodePacked(r,ss,v);}
    function sig() internal view returns(bytes memory){return sigFor(s);}
    function testFirstNativePurchaseNeedsNoActivation() public {
        (bytes32 beforeId,)=s.cards(buyer);assertEq(beforeId,bytes32(0));bytes memory a=sig();
        vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);
        assertEq(merchant.balance,0.9 ether);assertEq(treasury.balance,0.099 ether);assertTrue(s.paid(merchant,i.id));assertEq(address(s).balance,0);
        (bytes32 afterId,uint64 afterExpiry)=s.cards(buyer);assertEq(afterId,id);assertEq(afterExpiry,expiry);
    }
    function testFirstTokenPurchaseNeedsNoCardActivation() public {
        i.token=address(t);bytes memory a=sig();vm.prank(buyer);s.pay(i,a,id,salt,expiry);
        assertEq(t.balanceOf(merchant),0.9 ether);assertEq(t.balanceOf(treasury),0.099 ether);assertEq(t.balanceOf(buyer),999.001 ether);assertEq(t.balanceOf(address(s)),0);
    }
    function testCashbackIsPaidToBuyerFromFeeInSameNativePurchase() public {
        i.gross=100 ether;bytes memory a=sig();vm.prank(buyer);s.pay{value:i.gross}(i,a,id,salt,expiry);
        assertEq(merchant.balance,99 ether);assertEq(buyer.balance,900.1 ether);assertEq(treasury.balance,0.9 ether);assertEq(address(s).balance,0);
    }
    function testCashbackIsPaidInThePurchasedTokenFromFee() public {
        i.token=address(t);i.gross=100 ether;bytes memory a=sig();vm.prank(buyer);s.pay(i,a,id,salt,expiry);
        assertEq(t.balanceOf(merchant),99 ether);assertEq(t.balanceOf(buyer),900.1 ether);assertEq(t.balanceOf(treasury),0.9 ether);assertEq(t.balanceOf(address(s)),0);assertEq(buyer.balance,1000 ether);
    }
    function testRegeneratedCardIsUsedInPurchaseAndOldIdIsRejected() public {
        bytes memory a=sig();vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);
        bytes32 nextSalt=keccak256("new");bytes32 nextId=sha256(abi.encodePacked("CaedralOriginCardId/v1",buyer,nextSalt));
        i.id=keccak256("second");a=sig();vm.prank(buyer);s.pay{value:1 ether}(i,a,nextId,nextSalt,expiry);
        i.id=keccak256("third");a=sig();vm.expectRevert(bytes("replaced card"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);
    }
    function testCannotUseAnotherPersonsCardId() public {bytes memory a=sig();vm.expectRevert(bytes("card id binding"));vm.prank(address(33));s.pay(i,a,id,salt,expiry);}
    function testReplayedInvoiceReverts() public {bytes memory a=sig();vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);vm.expectRevert(bytes("invoice used"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);}
    function testTamperingReverts() public {bytes memory a=sig();i.gross+=1;vm.expectRevert(bytes("invoice signature"));vm.prank(buyer);s.pay{value:i.gross}(i,a,id,salt,expiry);}
    function testOriginFebruaryRateDoesNotNeedSubsidy() public {vm.warp(s.CASHBACK_CHANGE());(uint256 fee,uint256 cb)=s.quote(100 ether);assertEq(fee,1 ether);assertEq(cb,1 ether);}
    function testExpiredInvoiceReverts() public {bytes memory a=sig();vm.warp(i.expiresAt);vm.expectRevert(bytes("invoice expiry"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);}
    function testCrossChainSignatureRejected() public {bytes memory a=sig();vm.chainId(block.chainid+1);vm.expectRevert(bytes("invoice signature"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);}
    function testSameCardFirstPurchaseOnAnotherNetworkLaterWithoutActivation() public {
        vm.warp(block.timestamp+90 days);vm.chainId(56);CaedralOriginPayments other=new CaedralOriginPayments(treasury,address(t));
        i.issuedAt=uint64(block.timestamp);i.expiresAt=uint64(block.timestamp+120);bytes memory a=sigFor(other);
        vm.prank(buyer);other.pay{value:1 ether}(i,a,id,salt,expiry);
        (bytes32 afterId,uint64 afterExpiry)=other.cards(buyer);assertEq(afterId,id);assertEq(afterExpiry,expiry);
    }
    function testInsufficientTokensCannotBeFinancedByCashback() public {
        i.token=address(t);i.gross=100 ether;vm.prank(buyer);t.transfer(address(55),900.01 ether);
        bytes memory a=sig();vm.expectRevert(bytes("insufficient token balance"));vm.prank(buyer);s.pay(i,a,id,salt,expiry);
        assertEq(t.balanceOf(buyer),99.99 ether);assertEq(t.balanceOf(merchant),0);assertEq(t.balanceOf(treasury),0);assertFalse(s.paid(merchant,i.id));assertFalse(s.issued(id));
    }
    function testZeroBalanceCannotBuyTokens() public {
        i.token=address(t);vm.prank(buyer);t.transfer(address(55),1000 ether);bytes memory a=sig();
        vm.expectRevert(bytes("insufficient token balance"));vm.prank(buyer);s.pay(i,a,id,salt,expiry);assertEq(t.balanceOf(buyer),0);
    }
    function testNativeValueMustCoverFullPurchase() public {bytes memory a=sig();vm.expectRevert(bytes("native value"));vm.prank(buyer);s.pay{value:0.999 ether}(i,a,id,salt,expiry);assertFalse(s.paid(merchant,i.id));assertFalse(s.issued(id));}
    function testExpiredCardCannotPay() public {bytes memory a=sig();vm.warp(expiry);vm.expectRevert(bytes("expiry"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);}
    function testCannotExtendSameCardExpiry() public {
        bytes memory a=sig();vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry);i.id=keccak256("second");a=sig();
        vm.expectRevert(bytes("card expiry changed"));vm.prank(buyer);s.pay{value:1 ether}(i,a,id,salt,expiry+1);
    }
    function testFuzzConservation(uint128 amount) public {uint256 gross=bound(amount,0.1 ether+1,1000000 ether);(uint256 fee,uint256 cb)=s.quote(gross);assertEq(gross-fee+cb+fee-cb,gross);assertLe(cb,fee);}
}
