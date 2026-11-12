package com.caedralpay.wallet

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class TransferDraftTest {
    @Test fun rejectsZeroAndMalformedAddresses() {
        assertFalse(TransferDraft.isAddressShapeValid("0x" + "0".repeat(40)))
        assertFalse(TransferDraft.isAddressShapeValid("0x123"))
        assertFalse(TransferDraft.isAddressShapeValid("0x" + "z".repeat(40)))
        assertTrue(TransferDraft.isAddressShapeValid("0x" + "1".repeat(40)))
    }

    @Test fun acceptsDecimalCommaAndPreservesPrecision() {
        assertEquals(BigDecimal("1.000000000000000001"), TransferDraft.amount("1,000000000000000001"))
    }

    @Test fun rejectsAmbiguousNegativeAndZeroAmounts() {
        listOf("", "0", "0,00", "-1", "1,2.3", "1.234,56", "1e5").forEach {
            assertNull(TransferDraft.amount(it))
        }
    }
}
