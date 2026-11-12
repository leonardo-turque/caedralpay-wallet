package com.caedralpay.wallet

import java.math.BigDecimal

/** Validação do formulário; não substitui saldo, taxas, checksum e validação on-chain. */
internal object TransferDraft {
    fun isAddressShapeValid(address: String): Boolean =
        address.matches(Regex("0x[0-9a-fA-F]{40}")) && address.drop(2).any { it != '0' }

    fun amount(value: String): BigDecimal? {
        if (!value.matches(Regex("[0-9]+([.,][0-9]+)?"))) return null
        return value.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }

    fun canReview(address: String, value: String): Boolean = isAddressShapeValid(address) && amount(value) != null
}
