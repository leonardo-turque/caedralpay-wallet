package com.caedralpay.wallet
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class OriginModelTest {
    @Test fun generatedCredentialsAreWellFormedAndRedacted() {
        repeat(50) {
            val c=OriginCard.generate("wallet",WalletAccount(0,"Principal","0x"+"1".repeat(40)),"Ana","Silva")
            assertEquals(16,c.number.length);assertTrue(c.number.startsWith("99"));assertTrue(c.securityCode.matches(Regex("[0-9]{6}")))
            val sum=c.number.reversed().mapIndexed{i,ch->val n=ch.digitToInt()*(if(i%2==1)2 else 1);if(n>9)n-9 else n}.sum()
            assertEquals(0,sum%10);assertTrue(c.id.matches(Regex("[0-9a-f]{64}")))
            assertEquals(CaedralProtocol.cardExpiry(Instant.ofEpochSecond(c.issuedAt)).epochSecond,c.expiresAt)
            assertFalse(c.toString().contains(c.number));assertFalse(c.toString().contains(c.securityCode))
        }
    }
    @Test fun nativeTokenZeroAddressIsAbiDataNotDestination() {
        assertEquals("0".repeat(64),OriginAbi.addr(CaedralProtocol.ZERO_ADDRESS))
        assertFalse(TransferDraft.isAddressShapeValid(CaedralProtocol.ZERO_ADDRESS))
    }
    @Test(expected=IllegalArgumentException::class) fun rawCardCannotBePersistedWithoutVaultSeal() {
        OriginCard.generate("w",WalletAccount(0,"P","0x"+"1".repeat(40)),"Ana","Silva").json()
    }
    @Test fun apduFramesAreShortBoundedAndOrdered() {
        val frames=OriginApdu.chunks(ByteArray(289))
        assertEquals(2,frames.size);assertEquals(205,frames[0].size);assertEquals(94,frames[1].size)
        assertEquals(0,frames[0][2].toInt());assertEquals(1,frames[1][2].toInt());assertEquals(1,frames[1][3].toInt())
    }
    @Test fun nativeBalanceMustCoverFullValueAndMaximumGas() {
        val value=100.toBigInteger();val gas=2.toBigInteger()
        OriginFunding.requireNative(102.toBigInteger(),value,gas)
        for(balance in listOf(0,99,100,101)) {
            try{OriginFunding.requireNative(balance.toBigInteger(),value,gas);fail("Insufficient balance accepted")}
            catch(_:IllegalArgumentException){}
        }
    }
    @Test fun tokenBalanceCannotUseExpectedCashbackAsCredit() {
        OriginFunding.requireToken(100.toBigInteger(),100.toBigInteger())
        for(balance in listOf(0,99)) {
            try{OriginFunding.requireToken(balance.toBigInteger(),100.toBigInteger());fail("Token overdraft accepted")}
            catch(_:IllegalArgumentException){}
        }
    }
    @Test(expected=IllegalArgumentException::class) fun negativeBalanceFailsClosed() {
        OriginFunding.requireNative((-1).toBigInteger(),0.toBigInteger(),1.toBigInteger())
    }
}
