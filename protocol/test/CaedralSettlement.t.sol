// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {CaedralSettlement} from "../src/CaedralSettlement.sol";

contract TestToken is ERC20 {
    constructor() ERC20("Test asset", "TEST") {}
    function mint(address to, uint256 amount) external { _mint(to, amount); }
}
contract SixDecimals is TestToken { function decimals() public pure override returns (uint8) { return 6; } }
contract TaxedAsset is TestToken {
    function _update(address from, address to, uint256 amount) internal override {
        if (from != address(0) && to != address(0) && amount > 0) {
            super._update(from, to, amount - 1); super._update(from, address(0), 1);
        } else super._update(from, to, amount);
    }
}
contract ReenterTreasury {
    address public target;
    bool public attempted;
    bool public succeeded;
    function setTarget(address target_) external { target = target_; }
    receive() external payable {
        attempted = true;
        (succeeded,) = target.call(abi.encodeWithSignature("fundReserve(address,uint256)", address(0), 0));
    }
}
contract CaedralSettlementTest is Test {
    CaedralSettlement s;
    TestToken token;
    address merchant;
    address buyer = address(0xB0B);
    address treasury = address(0xCAED);
    uint256 merchantKey = 12345;
    uint256 issuerKey = 67890;
    CaedralSettlement.Invoice i;
    CaedralSettlement.Card c;

    function setUp() public {
        vm.warp(1794481200); // November 2026
        token = new TestToken();
        address[] memory assets = new address[](2); assets[0] = address(token);
        uint8[] memory decimals_ = new uint8[](2); decimals_[0] = 18; decimals_[1] = 18;
        s = new CaedralSettlement(treasury, vm.addr(issuerKey), assets, decimals_);
        merchant = vm.addr(merchantKey);
        token.mint(buyer, 10000 ether); vm.deal(buyer, 10000 ether);
        vm.prank(buyer); token.approve(address(s), type(uint256).max);
        i = CaedralSettlement.Invoice(merchant, address(token), 100 ether, keccak256("invoice"),
            uint64(block.timestamp), uint64(block.timestamp + 120));
        c = CaedralSettlement.Card(buyer, keccak256("card"), 2, uint64(block.timestamp), uint64(block.timestamp + 1826 days));
    }
    function sign(uint256 key, bytes32 digest) internal pure returns (bytes memory) {
        (uint8 v, bytes32 r, bytes32 ss) = vm.sign(key, digest);
        return abi.encodePacked(r, ss, v);
    }
    function pay() internal {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i));
        bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.prank(buyer); s.pay{value: i.token == address(0) ? i.gross : 0}(i, a, c, b);
    }
    function advance() internal {
        vm.warp(s.CASHBACK_CHANGE()); i.issuedAt = uint64(block.timestamp); i.expiresAt = uint64(block.timestamp + 120);
    }
    function fund(uint256 amount) internal {
        token.mint(address(this), amount); token.approve(address(s), amount); s.fundReserve(address(token), amount);
    }
    function testInitialCashbackAndAtomicSplit() public {
        uint256 before_ = token.balanceOf(buyer); pay();
        assertEq(token.balanceOf(merchant), 99 ether);
        assertEq(token.balanceOf(treasury), 0.9 ether);
        assertEq(token.balanceOf(buyer), before_ - 99.9 ether);
        assertEq(token.balanceOf(address(s)), 0);
        assertTrue(s.consumed(merchant, i.id));
    }
    function testFloorInTokenUnits() public {
        (uint256 fee,,) = s.quote(address(token), 1 ether, 2); assertEq(fee, 0.1 ether);
        i.gross = 1 ether; pay(); assertEq(token.balanceOf(merchant), 0.9 ether);
    }
    function testSixDecimalAssetFloor() public {
        SixDecimals six = new SixDecimals();
        address[] memory assets = new address[](1); assets[0] = address(six);
        uint8[] memory dec = new uint8[](1); dec[0] = 6;
        CaedralSettlement other = new CaedralSettlement(treasury, vm.addr(issuerKey), assets, dec);
        (uint256 fee,,) = other.quote(address(six), 1e6, 0); assertEq(fee, 100000);
    }
    function testRejectAmountAtFloor() public {
        vm.expectRevert(bytes("below minimum")); s.quote(address(token), 0.1 ether, 0);
    }
    function testCashbackBoundaryAndAllTiers() public {
        vm.warp(s.CASHBACK_CHANGE() - 1);
        (,uint256 reward,) = s.quote(address(token), 100 ether, 2); assertEq(reward, 0.1 ether);
        advance();
        (,reward,) = s.quote(address(token), 100 ether, 0); assertEq(reward, 1 ether);
        (,reward,) = s.quote(address(token), 100 ether, 1); assertEq(reward, 2.5 ether);
        (,reward,) = s.quote(address(token), 100 ether, 2); assertEq(reward, 5 ether);
    }
    function testFundedSovereign() public {
        advance(); fund(4 ether); uint256 before_ = token.balanceOf(buyer); pay();
        assertEq(token.balanceOf(merchant), 99 ether); assertEq(token.balanceOf(buyer), before_ - 95 ether);
        assertEq(token.balanceOf(treasury), 0); assertEq(s.reserve(address(token)), 0);
        assertEq(token.balanceOf(address(s)), 0);
    }
    function testUnfundedCashbackRevertsWithoutDebit() public {
        advance(); uint256 before_ = token.balanceOf(buyer);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("cashback unfunded")); vm.prank(buyer); s.pay(i, a, c, b);
        assertEq(token.balanceOf(buyer), before_); assertFalse(s.consumed(merchant, i.id));
    }
    function testNoReplay() public {
        pay(); bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("invoice used")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testTamperedAmountRejected() public {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        i.gross += 1;
        vm.expectRevert(bytes("merchant signature")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testCrossChainSignatureRejected() public {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.chainId(block.chainid + 1);
        vm.expectRevert(bytes("merchant signature")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testTierCannotBeSelfElevated() public {
        c.tier = 0; bytes memory b = sign(issuerKey, s.cardDigest(c)); c.tier = 2;
        bytes memory a = sign(merchantKey, s.invoiceDigest(i));
        vm.expectRevert(bytes("card signature")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testCardBoundToHolder() public {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("card")); s.pay(i, a, c, b);
    }
    function testExpiredInvoice() public {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.warp(i.expiresAt);
        vm.expectRevert(bytes("invoice expired")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testExpiredCard() public {
        c.expiresAt = uint64(block.timestamp);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("card expired")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testRevocationDoesNotTouchWallet() public {
        vm.prank(vm.addr(issuerKey)); s.revokeCard(c.id);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("card")); vm.prank(buyer); s.pay(i, a, c, b);
        vm.prank(buyer); token.transfer(merchant, 1 ether); assertEq(token.balanceOf(merchant), 1 ether);
    }
    function testNativeSplit() public {
        i.token = address(0); pay(); assertEq(merchant.balance, 99 ether); assertEq(treasury.balance, 0.9 ether);
        assertEq(address(s).balance, 0);
    }
    function testRejectsUnexpectedNativeValue() public {
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("unexpected native")); vm.prank(buyer); s.pay{value: 1}(i, a, c, b);
    }
    function testCancelledInvoiceCannotBePaid() public {
        vm.prank(merchant); s.cancelInvoice(i.id);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("invoice used")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testOtherMerchantCannotCancelInvoice() public {
        vm.prank(buyer); s.cancelInvoice(i.id); pay(); assertTrue(s.consumed(merchant, i.id));
    }
    function testRevocationRestrictedToIssuer() public {
        vm.expectRevert(bytes("issuer only")); vm.prank(buyer); s.revokeCard(c.id);
    }
    function testCardCannotHaveArbitraryLifetime() public {
        c.expiresAt = c.issuedAt + uint64(1828 days);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("card expired")); vm.prank(buyer); s.pay(i, a, c, b);
    }
    function testDeploymentSeparatesSignatures() public {
        address[] memory assets = new address[](1); assets[0] = address(token);
        uint8[] memory dec = new uint8[](1); dec[0] = 18;
        CaedralSettlement other = new CaedralSettlement(treasury, vm.addr(issuerKey), assets, dec);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("merchant signature")); vm.prank(buyer); other.pay(i, a, c, b);
    }
    function testWrongAssetDecimalsRejected() public {
        address[] memory assets = new address[](1); assets[0] = address(token);
        uint8[] memory dec = new uint8[](1); dec[0] = 6;
        vm.expectRevert(bytes("decimals")); new CaedralSettlement(treasury, vm.addr(issuerKey), assets, dec);
    }
    function testFeeOnTransferRevertsAtomically() public {
        TaxedAsset taxed = new TaxedAsset();
        address[] memory assets = new address[](1); assets[0] = address(taxed);
        uint8[] memory dec = new uint8[](1); dec[0] = 18;
        s = new CaedralSettlement(treasury, vm.addr(issuerKey), assets, dec);
        taxed.mint(buyer, 100 ether); vm.prank(buyer); taxed.approve(address(s), 100 ether);
        i.token = address(taxed);
        bytes memory a = sign(merchantKey, s.invoiceDigest(i)); bytes memory b = sign(issuerKey, s.cardDigest(c));
        vm.expectRevert(bytes("nonstandard token")); vm.prank(buyer); s.pay(i, a, c, b);
        assertEq(taxed.balanceOf(buyer), 100 ether); assertEq(taxed.balanceOf(merchant), 0);
        assertFalse(s.consumed(merchant, i.id));
    }
    function testTreasuryCallbackCannotReenter() public {
        ReenterTreasury callback = new ReenterTreasury();
        address[] memory assets = new address[](1);
        uint8[] memory dec = new uint8[](1); dec[0] = 18;
        s = new CaedralSettlement(address(callback), vm.addr(issuerKey), assets, dec);
        callback.setTarget(address(s)); i.token = address(0); pay();
        assertTrue(callback.attempted()); assertFalse(callback.succeeded());
        assertEq(merchant.balance, 99 ether); assertEq(address(callback).balance, 0.9 ether);
        assertEq(address(s).balance, 0);
    }
    function testFuzzAccounting(uint128 gross_, uint8 tier_) public {
        uint256 gross = bound(gross_, 0.1 ether + 1, 1000000 ether);
        uint8 tier = uint8(bound(tier_, 0, 2));
        advance(); (uint256 fee, uint256 cashback, uint256 subsidy) = s.quote(address(token), gross, tier);
        uint256 merchantNet = gross - fee;
        uint256 treasuryNet = fee > cashback ? fee - cashback : 0;
        assertEq(merchantNet + cashback + treasuryNet, gross + subsidy);
        assertGe(fee, 0.1 ether); assertLt(fee, gross);
    }
}
