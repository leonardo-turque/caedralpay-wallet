package com.caedralpay.wallet

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class PaymentQrTest {
    private val recipient = "0x" + "1".repeat(40)

    @Test fun nativeRequestPreservesNetworkRecipientAndExactAmount() {
        val payload = PaymentQr.encode(recipient, BscChain.Testnet, WalletAsset.BNB, "0.000000000000000001".toBigDecimal())
        assertEquals("ethereum:$recipient@97?value=1", payload)
        val request = PaymentQr.parse(payload, BscChain.Testnet)
        assertEquals(recipient, request.address)
        assertEquals(WalletAsset.BNB, request.asset)
        assertEquals(0, requireNotNull(request.amount).compareTo("0.000000000000000001".toBigDecimal()))
    }

    @Test fun tokenRequestUsesTheConfiguredContractAndAtomicUnits() {
        val payload = PaymentQr.encode(recipient, BscChain.Mainnet, WalletAsset.USDT, "1.25".toBigDecimal())
        assertTrue(payload.contains("${BscNetwork.usdtContract}@56/transfer?address=$recipient&uint256=1250000000000000000"))
        val request = PaymentQr.parse(payload, BscChain.Mainnet)
        assertEquals(WalletAsset.USDT, request.asset)
        assertEquals(0, request.amount!!.compareTo("1.25".toBigDecimal()))
    }

    @Test fun rawAddressDoesNotInventAmountOrAsset() {
        val request = PaymentQr.parse(recipient, BscChain.Testnet)
        assertEquals(recipient, request.address)
        assertNull(request.amount)
        assertNull(request.asset)
    }

    @Test fun requestWithoutAmountAllowsUserToEnterIt() {
        BscChain.entries.forEach { chain -> chain.assets.forEach { asset ->
            val request = PaymentQr.parse(PaymentQr.encode(recipient, chain, asset), chain)
            assertEquals(recipient, request.address)
            assertEquals(asset, request.asset)
            assertNull(request.amount)
        } }
    }

    @Test fun supportsIntegerScientificNotationFromOtherWallets() {
        assertEquals(0, PaymentQr.parse("ethereum:pay-$recipient@97?value=2.5e17", BscChain.Testnet).amount!!.compareTo("0.25".toBigDecimal()))
    }

    @Test fun rejectsWrongNetworkUnknownContractsAndUnrequestedOperations() {
        listOf("ethereum:$recipient@56", "ethereum:$recipient", "ethereum:$recipient@97/approve?address=$recipient&uint256=1",
            "ethereum:$recipient@97/transfer?address=$recipient&uint256=1", "https://example.org/$recipient", "wc:example").forEach(::reject)
    }

    @Test fun rejectsDuplicateAmountsUnknownParametersAndInvalidIntegers() {
        listOf("value=1&value=2", "value=1&gas=2", "value=-1", "value=0", "value=0.1", "value=1e999",
            "value=%ZZ", "value=" + "9".repeat(100)).forEach { reject("ethereum:$recipient@97?$it") }
    }

    @Test fun rejectsPrecisionLossWhenGeneratingRequest() {
        assertThrows(ArithmeticException::class.java) {
            PaymentQr.encode(recipient, BscChain.Testnet, WalletAsset.BNB, "0.0000000000000000001".toBigDecimal())
        }
        assertTrue(PaymentQr.encode(recipient, BscChain.Testnet, WalletAsset.USDT, BigDecimal.ONE).contains("${BscChain.Testnet.usdtContract}@97"))
    }

    @Test fun qrPixelsRoundTripIntoThePaymentParser() {
        val payload = PaymentQr.encode(recipient, BscChain.Testnet, WalletAsset.BNB, "0.1".toBigDecimal())
        val matrix = WalletQrCodec.matrix(payload, 320)
        val bytes = ByteArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) 0 else 255.toByte() }
        try {
            val result = WalletQrCodec.decodeLuminance(bytes, matrix.width, matrix.height)
            assertEquals(payload, result)
            assertEquals(recipient, PaymentQr.parse(requireNotNull(result), BscChain.Testnet).address)
        } finally { bytes.fill(0) }
    }

    @Test fun recoveryQrAcceptsTwelveWordsAndRejectsAddressesKeysAndOversizedInput() {
        val phrase = List(11) { "abandon" }.plus("about").joinToString(" ")
        assertEquals(12, RecoveryQr.parse(phrase.uppercase()).size)
        assertEquals(12, RecoveryQr.parse("caedralpay:recovery?words=" + phrase.replace(" ", "%20")).size)
        listOf(recipient, "a".repeat(64), "a".repeat(513), "word word", "caedralpay:recovery?words=$phrase&other=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { RecoveryQr.parse(it) }
        }
    }

    private fun reject(payload: String) {
        assertThrows(IllegalArgumentException::class.java) { PaymentQr.parse(payload, BscChain.Testnet) }
    }
}
