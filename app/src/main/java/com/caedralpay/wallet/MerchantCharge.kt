package com.caedralpay.wallet

import java.math.BigInteger
import java.time.*

internal enum class MerchantStep { Network,Asset,Kind,Schedule,Amount,Review,Present,Receipt }
internal enum class MerchantPaymentKind { Once,Recurring }
internal data class MerchantCharge(val chain:BscChain,val asset:WalletAsset,val amount:String,val kind:MerchantPaymentKind,
    val intervalDays:Int=0,val endAt:Long=0) {
    val gross:BigInteger get()=requireNotNull(TransferDraft.amount(amount)){"Informe um valor válido."}.movePointRight(18).toBigIntegerExact()
    fun validate(now:Instant=Instant.now()) {
        require(asset in chain.assets)
        CaedralProtocol.split(gross,18,CaedralProtocol.Tier.Origin,now)
        if(kind==MerchantPaymentKind.Recurring){require(intervalDays in 1..3650){"Use um intervalo de 1 a 3650 dias."};require(endAt==0L || endAt>=now.epochSecond){"Escolha uma data a partir de hoje."}}
        else require(intervalDays==0 && endAt==0L)
    }
    companion object {
        fun endOfDay(date:LocalDate,zone:ZoneId)=date.plusDays(1).atStartOfDay(zone).minusSeconds(1).toEpochSecond()
        fun interval(value:String)=value.toIntOrNull()?.takeIf{it in 1..3650}
    }
}
