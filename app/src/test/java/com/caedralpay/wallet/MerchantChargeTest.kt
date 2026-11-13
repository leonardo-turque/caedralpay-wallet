package com.caedralpay.wallet
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class MerchantChargeTest {
 @Test fun intervalHasFiniteLimits(){assertEquals(1,MerchantCharge.interval("1"));assertEquals(3650,MerchantCharge.interval("3650"));listOf("","0","-1","3651","1.5","999999999999").forEach{assertNull(MerchantCharge.interval(it))}}
 @Test fun endDateUsesLocalEndOfDay(){val zone=ZoneId.of("America/Sao_Paulo");val day=LocalDate.of(2026,11,13);assertEquals(Instant.parse("2026-11-14T02:59:59Z").epochSecond,MerchantCharge.endOfDay(day,zone))}
 @Test fun noEndIsValidButPastEndIsRejected(){val now=Instant.parse("2026-11-13T15:00:00Z");val c=MerchantCharge(BscChain.Testnet,WalletAsset.USDT,"10",MerchantPaymentKind.Recurring,30);c.validate(now);try{c.copy(endAt=now.epochSecond-1).validate(now);fail()}catch(_:IllegalArgumentException){}}
 @Test fun onceDoesNotAcceptSchedule(){try{MerchantCharge(BscChain.Testnet,WalletAsset.USDT,"10",MerchantPaymentKind.Once,30).validate();fail()}catch(_:IllegalArgumentException){}}
}
