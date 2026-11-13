package com.caedralpay.wallet

import org.junit.Test
import org.junit.Assert.*

class OriginTapWindowTest {
    @Test fun tapExpiresAtSixtySecondsAndOnlyNewArmRestoresIt(){
        var now=1000L
        OriginTapSession.configureClockForTest{now}
        try {
            val card=OriginCard.generate("fixture",WalletAccount(0,"Fixture","0x"+"1".repeat(40)),"Ana","Silva")
            OriginTapSession.arm(card);val first=OriginTapSession.hello()!!
            now+=59_999;assertArrayEquals(first,OriginTapSession.hello())
            now+=1;assertNull(OriginTapSession.hello());assertFalse(OriginTapSession.accept(ByteArray(289)))
            OriginTapSession.arm(card);assertNotNull(OriginTapSession.hello());assertFalse(first.contentEquals(OriginTapSession.hello()))
            OriginTapSession.clear();assertNull(OriginTapSession.hello())
        }finally{OriginTapSession.configureClockForTest(null)}
    }
}
