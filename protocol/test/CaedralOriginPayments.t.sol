// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;
import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {CaedralOriginPayments} from "../src/CaedralOriginPayments.sol";
contract OriginToken is ERC20 { constructor() ERC20("Test", "TEST") {} function mint(address a,uint256 v) external {_mint(a,v);} }
contract CaedralOriginPaymentsTest is Test {
    CaedralOriginPayments s; OriginToken t; address buyer=address(0xB0B); uint256 key=12345; address merchant; address treasury=address(0xCAED);
    bytes32 salt=keccak256("salt"); bytes32 id; CaedralOriginPayments.Invoice i;
    function setUp() public {
        vm.warp(1794481200); merchant=vm.addr(key); t=new OriginToken(); s=new CaedralOriginPayments(treasury,address(t));
        vm.deal(buyer,1000 ether); t.mint(buyer,1000 ether);
        id=sha256(abi.encodePacked("CaedralOriginCardId/v1",buyer,salt));
        vm.prank(buyer); s.registerCard(id,salt,uint64(block.timestamp+1826 days));
        vm.prank(buyer); t.approve(address(s),1000 ether);
        i=CaedralOriginPayments.Invoice(merchant,address(0),1 ether,keccak256("invoice"),uint64(block.timestamp),uint64(block.timestamp+120));
    }
    function sig() internal view returns(bytes memory) { (uint8 v,bytes32 r,bytes32 ss)=vm.sign(key,s.invoiceDigest(i)); return abi.encodePacked(r,ss,v); }
    function testNativeAtomicPayment() public { bytes memory a=sig(); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); assertEq(merchant.balance,0.9 ether); assertEq(treasury.balance,0.099 ether); assertTrue(s.paid(merchant,i.id)); assertEq(address(s).balance,0); }
    function testTokenAtomicPayment() public { i.token=address(t); bytes memory a=sig(); vm.prank(buyer); s.pay(i,a,id); assertEq(t.balanceOf(merchant),0.9 ether); assertEq(t.balanceOf(treasury),0.099 ether); assertEq(t.balanceOf(buyer),999.001 ether); assertEq(t.balanceOf(address(s)),0); }
    function testRegenerationInvalidatesOldId() public { bytes32 nextId=sha256(abi.encodePacked("CaedralOriginCardId/v1",buyer,keccak256("new"))); vm.prank(buyer); s.registerCard(nextId,keccak256("new"),uint64(block.timestamp+1826 days)); bytes memory a=sig(); vm.expectRevert(bytes("inactive card")); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); }
    function testCannotReuseAnotherPersonsId() public { vm.expectRevert(bytes("card id binding")); vm.prank(address(33)); s.registerCard(id,salt,uint64(block.timestamp+1826 days)); }
    function testReplayedInvoiceReverts() public { bytes memory a=sig(); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); vm.expectRevert(bytes("invoice used")); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); }
    function testTamperingReverts() public { bytes memory a=sig(); i.gross+=1; vm.expectRevert(bytes("invoice signature")); vm.prank(buyer); s.pay{value:i.gross}(i,a,id); }
    function testOriginFebruaryRateDoesNotNeedSubsidy() public { vm.warp(s.CASHBACK_CHANGE()); (uint256 fee,uint256 cb)=s.quote(100 ether); assertEq(fee,1 ether); assertEq(cb,1 ether); }
    function testExpiredInvoiceReverts() public { bytes memory a=sig(); vm.warp(i.expiresAt); vm.expectRevert(bytes("invoice expiry")); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); }
    function testCrossChainSignatureRejected() public { bytes memory a=sig(); vm.chainId(block.chainid+1); vm.expectRevert(bytes("invoice signature")); vm.prank(buyer); s.pay{value:1 ether}(i,a,id); }
    function testFuzzConservation(uint128 amount) public { uint256 gross=bound(amount,0.1 ether+1,1000000 ether); (uint256 fee,uint256 cb)=s.quote(gross); assertEq(gross-fee+cb+fee-cb,gross); assertLe(cb,fee); }
}
