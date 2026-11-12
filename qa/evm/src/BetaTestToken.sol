// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;
// Local test fixture only. Never deployed by the app or represented as real USDT.
contract BetaTestToken {
    function name() external pure returns (string memory) { return "Local Beta Test Token"; }
    function symbol() external pure returns (string memory) { return "USDT"; }
    function decimals() external pure returns (uint8) { return 18; }
    mapping(address => uint256) public balanceOf;
    event Transfer(address indexed from, address indexed to, uint256 value);
    function mint(address to, uint256 value) external { balanceOf[to] += value; emit Transfer(address(0), to, value); }
    function transfer(address to, uint256 value) external returns (bool) {
        require(balanceOf[msg.sender] >= value, "balance");
        balanceOf[msg.sender] -= value; balanceOf[to] += value;
        emit Transfer(msg.sender, to, value); return true;
    }
}
