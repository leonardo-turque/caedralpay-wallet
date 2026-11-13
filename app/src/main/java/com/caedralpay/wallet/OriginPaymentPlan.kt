package com.caedralpay.wallet

import java.math.BigInteger

/** A consent for one merchant invoice, including bounded gas for its exact token calls. */
internal class OriginPaymentDraft(val first:OriginQuote,steps:List<OriginAction>) {
    val steps:List<OriginAction> = steps.toList()
    val maximumGas:BigInteger get()=first.fee+steps.drop(1).sumOf{gasCap(it)}*first.gasPrice
    val digest get()=OriginAbi.sha("OriginPayment/v1|${first.digest}|${steps.joinToString(",")}|$maximumGas")
    fun validate() {
        first.validate()
        require(first.call.automaticCard && steps.firstOrNull()==first.call.kind)
        val expected=when(first.call.kind) {
            OriginAction.Pay->listOf(OriginAction.Pay)
            OriginAction.Approve->listOf(OriginAction.Approve,OriginAction.Pay)
            OriginAction.ResetApproval->listOf(OriginAction.ResetApproval,OriginAction.Approve,OriginAction.Pay)
            OriginAction.Register->emptyList()
        }
        require(expected.isNotEmpty() && steps==expected)
    }
    companion object {
        fun gasCap(action:OriginAction)=when(action) {
            OriginAction.Pay->600_000
            OriginAction.Approve->120_000
            OriginAction.ResetApproval->80_000
            OriginAction.Register->0
        }.toBigInteger()
    }
}

/** Opaque, memory-only capability; never serialized or sent through NFC. */
internal class OriginPaymentPermit { override fun toString()="OriginPaymentPermit([REDACTED])" }
