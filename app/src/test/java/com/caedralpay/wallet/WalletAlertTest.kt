package com.caedralpay.wallet

import org.junit.Assert.*
import org.junit.Test

class WalletAlertTest {
    @Test fun duplicatesHaveStableOpaqueIds() {
        val first = WalletAlert(AlertKind.OutgoingPending, "97:0x123")
        assertEquals(first.dedupKey, first.copy().dedupKey)
        assertEquals(64, first.dedupKey.length)
        assertFalse(first.dedupKey.contains("0x123"))
    }
    @Test fun networkAndStatusAreNotConflated() {
        val pending = WalletAlert(AlertKind.OutgoingPending, "97:tx")
        assertNotEquals(pending.dedupKey, pending.copy(kind = AlertKind.OutgoingConfirmed).dedupKey)
        assertNotEquals(pending.dedupKey, pending.copy(reference = "56:tx").dedupKey)
    }
    @Test fun proximityIsNeverPurchaseConfirmation() {
        assertNotEquals(AlertKind.CardDetected.title, AlertKind.CardPurchaseConfirmed.title)
        assertFalse(AlertKind.CardDetected.title.contains("compra", true))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedReference() {
        WalletAlert(AlertKind.IncomingConfirmed, "a".repeat(257))
    }
}
