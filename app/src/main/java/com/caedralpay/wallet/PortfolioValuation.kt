package com.caedralpay.wallet

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

internal fun formatUsd(value: BigDecimal): String = NumberFormat.getCurrencyInstance(
    Locale.forLanguageTag("pt-BR")
).apply { currency = Currency.getInstance("USD") }.format(value)

/** Unknown prices never become a partial total presented as the full balance. */
internal fun valuePortfolio(
    positions: List<AssetPosition>,
    chain: BscChain,
    prices: Map<WalletAsset, BigDecimal>
): List<AssetPosition> = positions.map { position ->
    val value = position.quantity?.let { quantity ->
        when {
            chain.isTestnet || quantity.signum() == 0 -> BigDecimal.ZERO
            else -> prices[position.asset]?.takeIf { it.signum() > 0 }?.multiply(quantity)
        }
    }
    position.copy(valueUsd = value)
}

internal fun totalUsd(positions: List<AssetPosition>): BigDecimal? {
    if (positions.any { it.valueUsd == null }) return null
    return positions.fold(BigDecimal.ZERO) { total, position -> total + requireNotNull(position.valueUsd) }
}
