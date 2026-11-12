package com.caedralpay.wallet

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.Instant

class CaedralProtocolTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private fun units(value: String) = value.toBigDecimal().movePointRight(18).toBigIntegerExact()
    private fun invoice() = CaedralProtocol.Invoice(97,
        "0x1111111111111111111111111111111111111111", "0x2222222222222222222222222222222222222222",
        "0x3333333333333333333333333333333333333333", units("100"), "ab".repeat(32), now.epochSecond, now.epochSecond + 120)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid input to be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun initialSplitMatchesContract() {
        val s = CaedralProtocol.split(units("100"), 18, CaedralProtocol.Tier.Sovereign, now)
        assertEquals(units("99"), s.merchant); assertEquals(units("1"), s.fee)
        assertEquals(units("0.1"), s.cashback); assertEquals(units("0.9"), s.treasuryNet)
    }
    @Test fun futureCashbackBoundary() {
        val at = CaedralProtocol.cashbackChange
        assertEquals(1801450800L, at.epochSecond)
        assertEquals(units("0.1"), CaedralProtocol.split(units("100"), 18, CaedralProtocol.Tier.Sovereign, at.minusSeconds(1)).cashback)
        listOf("1", "2.5", "5").forEachIndexed { index, expected ->
            val s = CaedralProtocol.split(units("100"), 18, CaedralProtocol.Tier.entries[index], at)
            assertEquals(units(expected), s.cashback)
            assertEquals(s.gross + s.reserveRequired, s.merchant + s.cashback + s.treasuryNet)
        }
    }
    @Test fun floorAndRoundingAcrossDecimals() {
        for (decimals in 1..18) {
            val scale = BigInteger.TEN.pow(decimals)
            val s = CaedralProtocol.split(scale, decimals, CaedralProtocol.Tier.Origin, now)
            assertEquals(scale / BigInteger.TEN, s.fee)
        }
        val s = CaedralProtocol.split(units("100") + BigInteger.ONE, 18, CaedralProtocol.Tier.Origin, now)
        assertEquals(units("1") + BigInteger.ONE, s.fee)
    }
    @Test fun invalidAmountsRejected() {
        listOf(BigInteger.ZERO, -BigInteger.ONE, units("0.1"), BigInteger.ONE.shiftLeft(256)).forEach {
            rejects { CaedralProtocol.split(it, 18, CaedralProtocol.Tier.Origin, now) }
        }
        rejects { CaedralProtocol.split(units("100"), 0, CaedralProtocol.Tier.Origin, now) }
    }
    @Test fun chosenNameHasExactlyTwoParts() {
        assertEquals("José D’Ávila", CaedralProtocol.cardName(" José ", "D’Ávila"))
        assertEquals("Ana-Maria Silva", CaedralProtocol.cardName("Ana-Maria", "Silva"))
        listOf("", "Ana Maria", "Ana\nMaria", "Ana1", "Ana\u202ES", "A".repeat(41)).forEach {
            rejects { CaedralProtocol.cardName(it, "Silva") }
        }
    }
    @Test fun fiveCalendarYearsIncludingLeapDay() {
        assertEquals(Instant.parse("2033-02-28T10:00:00Z"),
            CaedralProtocol.cardExpiry(Instant.parse("2028-02-29T10:00:00Z")))
    }
    @Test fun fixedWireRoundTrip() {
        val original = invoice(); val wire = CaedralProtocol.encode(original, now.epochSecond)
        assertEquals(152, wire.size); assertEquals(original, CaedralProtocol.decode(wire, now.epochSecond))
    }
    @Test fun malformedWireRejected() {
        val wire = CaedralProtocol.encode(invoice(), now.epochSecond)
        for (size in listOf(0, 1, 151, 153, 10000)) rejects { CaedralProtocol.decode(wire.copyOf(size), now.epochSecond) }
        wire[3] = 2; rejects { CaedralProtocol.decode(wire, now.epochSecond) }
    }
    @Test fun expiryAndFutureIssueRejected() {
        rejects { invoice().validate(now.epochSecond + 120) }
        rejects { invoice().validate(now.epochSecond - 1) }
        rejects { invoice().copy(expiresAt = now.epochSecond + 121).validate(now.epochSecond) }
        rejects { invoice().copy(expiresAt = Long.MAX_VALUE).validate(now.epochSecond) }
    }
    @Test fun unsupportedChainAndZeroDestinationRejected() {
        rejects { invoice().copy(chainId = 1).validate(now.epochSecond) }
        rejects { invoice().copy(merchant = CaedralProtocol.ZERO_ADDRESS).validate(now.epochSecond) }
        rejects { invoice().copy(id = "00".repeat(32)).validate(now.epochSecond) }
    }
    @Test fun encryptedChannelRejectsAlterationAndOtherRecipient() {
        val recipient = CaedralNfcCrypto.Ephemeral()
        val clear = CaedralProtocol.encode(invoice(), now.epochSecond)
        val encrypted = CaedralNfcCrypto.seal(recipient.publicKey, clear)
        assertEquals(CaedralNfcCrypto.ENVELOPE_SIZE, encrypted.size)
        assertArrayEquals(clear, CaedralNfcCrypto.open(recipient, encrypted))
        assertFalse(encrypted.contentEquals(CaedralNfcCrypto.seal(recipient.publicKey, clear)))
        fun fails(action: () -> Unit) {
            try { action(); fail("Ciphertext accepted") } catch (_: java.security.GeneralSecurityException) { }
        }
        fails { CaedralNfcCrypto.open(CaedralNfcCrypto.Ephemeral(), encrypted) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        fails { CaedralNfcCrypto.open(recipient, encrypted) }
    }
    @Test fun apduRequiresExactCommandAndLength() {
        val wire = ByteArray(CaedralNfcCrypto.ENVELOPE_SIZE)
        assertArrayEquals(wire, CaedralApdu.payload(CaedralApdu.put(wire)))
        val command = CaedralApdu.put(wire); command[0] = 0
        assertNull(CaedralApdu.payload(command))
        assertNull(CaedralApdu.payload(ByteArray(10000)))
    }
}
