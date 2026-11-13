// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;
import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {CaedralRecurringPayments,CaedralRecurringAccount} from "../src/CaedralRecurringPayments.sol";
contract RecurringToken is ERC20 {constructor() ERC20("Test","TEST"){}function mint(address a,uint256 v) external{_mint(a,v);}}
contract CaedralRecurringPaymentsTest is Test {
    CaedralRecurringPayments r;CaedralRecurringAccount implementation;RecurringToken t;
    address payer=address(0xB0B);address merchant;address treasury=address(0xCAED);uint256 key=12345;
    bytes32 salt=keccak256("salt");bytes32 cardId;uint64 expiry;CaedralRecurringPayments.Offer o;
    function setUp() public {
        vm.warp(1794481200);merchant=vm.addr(key);t=new RecurringToken();r=new CaedralRecurringPayments(treasury,address(t));
        implementation=new CaedralRecurringAccount(address(r),address(t));
        // Account context is identical to delegated execution; actual EIP-7702 is covered by the Android/Anvil test.
        vm.etch(payer,address(implementation).code);vm.deal(payer,1000 ether);t.mint(payer,1000 ether);
        cardId=sha256(abi.encodePacked("CaedralOriginCardId/v1",payer,salt));expiry=uint64(block.timestamp+1826 days);
        o=CaedralRecurringPayments.Offer(merchant,address(t),100 ether,keccak256("offer"),7,0,uint64(block.timestamp),uint64(block.timestamp+120));
    }
    function sig() internal view returns(bytes memory){(uint8 v,bytes32 rr,bytes32 ss)=vm.sign(key,r.offerDigest(o));return abi.encodePacked(rr,ss,v);}
    function authorize() internal returns(bytes32 id){bytes memory s=sig();vm.prank(payer);id=r.authorize(o,s,cardId,salt,expiry);}
    function testInitialTokenChargeAndNextCycleNeedNoCustomerSignature() public {
        bytes32 id=authorize();assertEq(t.balanceOf(payer),900.1 ether);assertEq(t.balanceOf(merchant),99 ether);assertEq(t.balanceOf(treasury),0.9 ether);
        vm.warp(block.timestamp+7 days);r.collect(id);
        assertEq(t.balanceOf(payer),800.2 ether);assertEq(t.balanceOf(merchant),198 ether);assertEq(t.balanceOf(treasury),1.8 ether);
    }
    function testNativeFundsStayAtOriginalAccount() public {
        o.token=address(0);bytes32 id=authorize();assertEq(payer.balance,900.1 ether);
        vm.warp(block.timestamp+7 days);r.collect(id);assertEq(payer.balance,800.2 ether);assertEq(merchant.balance,198 ether);assertEq(address(r).balance,0);
    }
    function testNoEarlyOrDuplicateCharge() public {bytes32 id=authorize();vm.expectRevert(bytes("not due"));r.collect(id);vm.warp(block.timestamp+7 days);r.collect(id);vm.expectRevert(bytes("not due"));r.collect(id);}
    function testMissedCyclesDoNotBecomeDebt() public {bytes32 id=authorize();vm.warp(block.timestamp+35 days);r.collect(id);assertEq(t.balanceOf(payer),800.2 ether);vm.expectRevert(bytes("not due"));r.collect(id);}
    function testNoFundsNoDebitAndCanRetryCurrentCycle() public {
        bytes32 id=authorize();uint256 balance=t.balanceOf(payer);vm.prank(payer);t.transfer(address(55),balance);vm.warp(block.timestamp+7 days);
        vm.expectRevert(bytes("insufficient balance"));r.collect(id);assertEq(t.balanceOf(payer),0);assertEq(t.balanceOf(merchant),99 ether);
        t.mint(payer,100 ether);r.collect(id);assertEq(t.balanceOf(payer),0.1 ether);
    }
    function testCancellationByOwnerOnly() public {bytes32 id=authorize();vm.expectRevert(bytes("payer"));r.cancel(id);vm.prank(payer);r.cancel(id);vm.warp(block.timestamp+7 days);vm.expectRevert(bytes("inactive"));r.collect(id);}
    function testEndDateStopsCharges() public {o.endAt=uint64(block.timestamp+10 days);bytes32 id=authorize();vm.warp(block.timestamp+11 days);vm.expectRevert(bytes("ended"));r.collect(id);}
    function testCardExpiryStopsNoEndSchedule() public {bytes32 id=authorize();vm.warp(expiry);vm.expectRevert(bytes("ended"));r.collect(id);}
    function testCannotChangeAmountOrMerchantInSignedOffer() public {bytes memory s=sig();o.gross+=1;vm.expectRevert(bytes("signature"));vm.prank(payer);r.authorize(o,s,cardId,salt,expiry);}
    function testOfferCannotBeReplayed() public {authorize();bytes memory s=sig();vm.expectRevert(bytes("used offer"));vm.prank(payer);r.authorize(o,s,cardId,salt,expiry);}
    function testAccountCannotExecuteArbitraryPulls() public {vm.expectRevert(bytes("caller"));CaedralRecurringAccount(payable(payer)).pull(address(t),100 ether);}
    function testRestoreCanEnumerateMandates() public {bytes32 id=authorize();assertEq(r.subscriptionCount(payer),1);bytes32[] memory ids=r.subscriptionIds(payer,0,10);assertEq(ids.length,1);assertEq(ids[0],id);}
    function testCrossChainOfferRejected() public {bytes memory s=sig();vm.chainId(56);vm.expectRevert(bytes("signature"));vm.prank(payer);r.authorize(o,s,cardId,salt,expiry);}
    function testCashbackChangesOnScheduledBoundary() public {
        vm.warp(r.CASHBACK_CHANGE()-1);o.issuedAt=uint64(block.timestamp);o.expiresAt=uint64(block.timestamp+120);
        bytes32 id=authorize();assertEq(t.balanceOf(treasury),0.9 ether);
        vm.warp(block.timestamp+7 days);r.collect(id);
        assertEq(t.balanceOf(payer),801.1 ether);assertEq(t.balanceOf(treasury),0.9 ether);
    }
    function testNativeInsufficientBalanceDoesNotCreateDebt() public {
        o.token=address(0);bytes32 id=authorize();vm.deal(payer,0);vm.warp(block.timestamp+7 days);
        vm.expectRevert(bytes("insufficient balance"));r.collect(id);assertEq(payer.balance,0);assertEq(merchant.balance,99 ether);
        vm.deal(payer,100 ether);r.collect(id);assertEq(payer.balance,0.1 ether);
    }
    function testIntervalAndEndDateAreBoundToConsent() public {
        bytes memory s=sig();o.intervalDays=1;vm.expectRevert(bytes("signature"));vm.prank(payer);r.authorize(o,s,cardId,salt,expiry);
        o.intervalDays=7;o.endAt=uint64(block.timestamp+8 days);vm.expectRevert(bytes("signature"));vm.prank(payer);r.authorize(o,s,cardId,salt,expiry);
    }
}
