package com.caedralpay.wallet

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class PortfolioValuationTest {
    @Test fun sumsEverySupportedTokenWithoutRoundingBeforeTheTotal() {
        val positions = valuePortfolio(listOf(
            AssetPosition(WalletAsset.BNB, "0.1".toBigDecimal(), null),
            AssetPosition(WalletAsset.USDT, "100.50".toBigDecimal(), null)
        ), BscChain.Mainnet, mapOf(WalletAsset.BNB to "600.12".toBigDecimal(), WalletAsset.USDT to "0.999".toBigDecimal()))
        assertEquals(0, requireNotNull(totalUsd(positions)).compareTo("160.41150".toBigDecimal()))
    }

    @Test fun missingQuoteDoesNotProduceMisleadingPartialTotal() {
        val positions = valuePortfolio(listOf(
            AssetPosition(WalletAsset.BNB, BigDecimal.ONE, null),
            AssetPosition(WalletAsset.USDT, BigDecimal.TEN, null)
        ), BscChain.Mainnet, mapOf(WalletAsset.USDT to BigDecimal.ONE))
        assertNull(totalUsd(positions))
    }

    @Test fun testTokensNeverAcquireMainnetMarketValue() {
        val positions = valuePortfolio(listOf(AssetPosition(WalletAsset.BNB, "0.1".toBigDecimal(), null)),
            BscChain.Testnet, mapOf(WalletAsset.BNB to "600".toBigDecimal()))
        assertEquals(BigDecimal.ZERO, totalUsd(positions))
        assertEquals("0.1".toBigDecimal(), positions.single().quantity)
    }

    @Test fun unknownQuantityRemainsUnknownEvenOnTestnet() {
        assertNull(totalUsd(valuePortfolio(listOf(AssetPosition(WalletAsset.BNB, null, null)), BscChain.Testnet, emptyMap())))
    }

    @Test fun knownEmptyBalanceNeedsNoQuote() {
        assertEquals(BigDecimal.ZERO, totalUsd(valuePortfolio(listOf(AssetPosition(WalletAsset.BNB, BigDecimal.ZERO, null)),
            BscChain.Mainnet, emptyMap())))
    }
}
