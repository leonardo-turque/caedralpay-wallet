package com.caedralpay.wallet

import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BscBalanceRepositoryTest {
    private val address = "0x" + "1".repeat(40)
    private fun abi(value: String) = "0x" + BigInteger(value).toString(16).padStart(64, '0')

    @Test fun exactConversionDoesNotLoseSmallBalances() {
        assertEquals(BigDecimal("0.000000000000000001"), EvmBalanceCodec.units(BigInteger.ONE, 18))
        assertEquals(BigInteger.ZERO, EvmBalanceCodec.quantity("0x0"))
    }

    @Test fun testnetReadsNativeBalanceWithoutAnyMainnetTokenCall() = runBlocking {
        val methods = mutableListOf<String>()
        val transport = ReadOnlyRpcTransport { endpoint, method, params ->
            assertTrue(endpoint in BscChain.Testnet.rpcEndpoints)
            methods += method
            when (method) {
                "eth_chainId" -> "0x61"
                "eth_blockNumber" -> "0x12"
                "eth_getBalance" -> "0x1"
                "eth_call" -> {
                    val request = params.first() as Map<*, *>
                    assertEquals(BscChain.Testnet.usdtContract, request["to"])
                    assertNotEquals(BscChain.Mainnet.usdtContract, request["to"])
                    if (request["data"] == "0x313ce567") abi("18") else abi("5")
                }
                else -> error("Testnet não deve consultar contrato de mainnet")
            }
        }
        val result = BscBalanceRepository(transport).load(address, BscChain.Testnet)
        assertEquals(BscChain.Testnet, result.chain)
        assertEquals(BigDecimal("0.000000000000000005"), result.usdtBalance)
        assertEquals(listOf(WalletAsset.USDT, WalletAsset.BNB), result.positions().map { it.asset })
        assertEquals(BigDecimal("0.000000000000000001"), result.nativeBalance)
        assertEquals(listOf("eth_chainId", "eth_blockNumber", "eth_call", "eth_getBalance", "eth_call"), methods)
    }

    @Test fun testnetRejectsEndpointReportingMainnet() {
        var nonChainCalls = 0
        val transport = ReadOnlyRpcTransport { _, method, _ ->
            if (method != "eth_chainId") nonChainCalls++
            "0x38"
        }
        assertThrows(IOException::class.java) {
            runBlocking { BscBalanceRepository(transport).load(address, BscChain.Testnet) }
        }
        assertEquals(0, nonChainCalls)
    }

    @Test fun malformedOrEmptyBalancesAreNotZero() {
        listOf("0x", "-1", "0xZZ", "0x" + "f".repeat(65)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { EvmBalanceCodec.quantity(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { EvmBalanceCodec.abiUint("0x0") }
    }

    @Test fun balanceCallEncodesOnlyPublicAddress() {
        assertEquals("0x70a08231" + "0".repeat(24) + "1".repeat(40), EvmBalanceCodec.balanceOfData(address))
    }

    @Test fun readsConsistentBlockAndExactNativeAndTokenAmounts() = runBlocking {
        val usedBlocks = mutableListOf<String>()
        val transport = ReadOnlyRpcTransport { _, method, params ->
            when (method) {
                "eth_chainId" -> "0x38"
                "eth_blockNumber" -> "0x1234"
                "eth_getBalance" -> { usedBlocks += params.last() as String; "0xde0b6b3a7640000" }
                "eth_call" -> {
                    usedBlocks += params.last() as String
                    if ((params.first() as Map<*, *>)["data"] == "0x313ce567") abi("18") else abi("5000000000000000001")
                }
                else -> error("Método inesperado")
            }
        }
        val result = BscBalanceRepository(transport).load(address)
        assertEquals(0, BigDecimal.ONE.compareTo(result.nativeBalance))
        assertEquals(BigDecimal("5.000000000000000001"), result.usdtBalance)
        assertTrue(usedBlocks.all { it == "0x1234" })
    }

    @Test fun rejectsWrongNetworkBeforeLookingUpBalances() {
        var balanceCalls = 0
        val transport = ReadOnlyRpcTransport { _, method, _ ->
            if (method != "eth_chainId") balanceCalls++
            "0x1"
        }
        assertThrows(IOException::class.java) { runBlocking { BscBalanceRepository(transport).load(address) } }
        assertEquals(0, balanceCalls)
    }

    @Test fun rejectsUnexpectedTokenDecimals() {
        val transport = ReadOnlyRpcTransport { _, method, _ ->
            when (method) { "eth_chainId" -> "0x38"; "eth_blockNumber" -> "0x12"; else -> abi("6") }
        }
        assertThrows(IOException::class.java) { runBlocking { BscBalanceRepository(transport).load(address) } }
    }

    @Test fun fallsBackOnlyToConfiguredEndpoint() = runBlocking {
        val endpoints = mutableSetOf<String>()
        val transport = ReadOnlyRpcTransport { endpoint, method, params ->
            endpoints += endpoint
            if (endpoint == BscNetwork.rpcEndpoints.first()) throw IOException("Offline")
            when (method) {
                "eth_chainId" -> "0x38"; "eth_blockNumber" -> "0x12"; "eth_getBalance" -> "0x0"
                else -> if ((params.first() as Map<*, *>)["data"] == "0x313ce567") abi("18") else abi("0")
            }
        }
        val result = BscBalanceRepository(transport).load(address)
        assertEquals(BscNetwork.rpcEndpoints.last(), result.rpcEndpoint)
        assertEquals(BscNetwork.rpcEndpoints.toSet(), endpoints)
    }
}
