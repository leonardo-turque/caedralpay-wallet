package com.caedralpay.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.io.IOException

class EvmTransferTest {
    private val from = "0x" + "1".repeat(40)
    private val to = "0x" + "2".repeat(40)
    private fun abi(n: BigInteger) = "0x" + n.toString(16).padStart(64, '0')
    private fun transport(chain: BscChain, balance: BigInteger = BigInteger.TEN.pow(21), gas: String = "0x5208") = EvmTransport { _, method, params ->
        when (method) {
            "eth_chainId" -> chain.chainId.toBigInteger().rpcHex()
            "eth_getTransactionCount" -> "0x0"
            "eth_gasPrice" -> "0x3b9aca00"
            "eth_estimateGas" -> gas
            "eth_getBalance" -> balance.rpcHex()
            "eth_getCode" -> "0x6000"
            "eth_call" -> {
                val request = params[0] as Map<*, *>
                when (request["data"]) { "0x313ce567" -> abi(18.toBigInteger()); else ->
                    if ((request["data"] as String).startsWith("0xa9059cbb")) abi(BigInteger.ONE) else abi(balance) }
            }
            else -> error("Unexpected method $method")
        }
    }

    @Test fun preparesBothChainsWithoutMixingContracts() = runBlocking {
        BscChain.entries.forEach { chain -> WalletAsset.entries.forEach { asset ->
            val quote = TransferRepository(EvmGateway(transport(chain))).prepare(chain, asset, from, to, "1.000000000000000001")
            assertEquals("1000000000000000001".toBigInteger(), quote.units)
            assertEquals(chain, quote.chain)
            if (asset == WalletAsset.BNB) { assertNull(quote.contract); assertEquals(to, quote.to); assertEquals("0x", quote.data) }
            else { assertEquals(chain.usdtContract, quote.to); assertTrue(quote.data.startsWith("0xa9059cbb")); assertEquals(BigInteger.ZERO, quote.value) }
            assertTrue(quote.fee.signum() > 0)
        } }
    }

    @Test fun rejectsInsufficientGasAndExcessPrecision() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            TransferRepository(EvmGateway(transport(BscChain.Mainnet, BigInteger.ZERO))).prepare(BscChain.Mainnet, WalletAsset.USDT, from, to, "1")
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            TransferRepository().prepare(BscChain.Testnet, WalletAsset.BNB, from, to, "0.0000000000000000001")
        } }
    }

    @Test fun rejectsWrongChainBeforeNonceOrBalanceQueries() {
        var otherCalls = 0
        assertThrows(IllegalStateException::class.java) { runBlocking {
            EvmGateway(EvmTransport { _, method, _ -> if (method != "eth_chainId") otherCalls++; "0x1" })
                .read(BscChain.Testnet) { it.quantity("eth_getBalance", from, "latest") }
        } }
        assertEquals(0, otherCalls)
    }

    @Test fun fallsBackAndBindsEveryReviewedField() = runBlocking<Unit> {
        val good = transport(BscChain.Testnet)
        val quote = TransferRepository(EvmGateway(EvmTransport { endpoint, method, params ->
            if (endpoint == BscChain.Testnet.rpcEndpoints.first()) throw IOException("offline")
            good.call(endpoint, method, params)
        })).prepare(BscChain.Testnet, WalletAsset.BNB, from, to, "0.1")
        assertNotEquals(quote.digest, quote.copy(recipient = from).digest)
        assertNotEquals(quote.digest, quote.copy(chain = BscChain.Mainnet).digest)
        assertNotEquals(quote.digest, quote.copy(units = BigInteger.ONE).digest)
        assertNotEquals(quote.digest, quote.copy(gasPrice = BigInteger.ONE).digest)
        assertThrows(IllegalArgumentException::class.java) { quote.validate(quote.createdAt + 90_001) }
        assertThrows(IllegalArgumentException::class.java) { quote.validate(quote.createdAt - 1) }
    }

    @Test fun rejectsExcessiveGasAndSelfTransfers() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            TransferRepository(EvmGateway(transport(BscChain.Testnet, gas = "0x1000000"))).prepare(BscChain.Testnet, WalletAsset.BNB, from, to, "1")
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            TransferRepository(EvmGateway(transport(BscChain.Testnet))).prepare(BscChain.Testnet, WalletAsset.BNB, from, from, "1")
        } }
    }
}
