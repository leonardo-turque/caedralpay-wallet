package com.caedralpay.wallet

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class WalletCatalogCodecTest {
    private val publicPhrase = List(11) { "abandon" }.joinToString(" ") + " about"

    @Test fun readsV2WithoutDroppingAccountNamesOrSelection() {
        val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).use {
            it.writeInt(0x43575032); it.writeInt(1); it.writeUTF("existing"); it.writeUTF("Principal"); it.writeUTF(publicPhrase)
            it.writeInt(2); it.writeUTF("Conta 1"); it.writeUTF("Reserva"); it.writeUTF("existing"); it.writeInt(1)
        } }.toByteArray()
        val decoded = WalletCatalogCodec.decode(bytes)
        assertEquals("Reserva", decoded.selected.accountNames[1])
        assertTrue(decoded.selected.hiddenIndices.isEmpty())
    }

    @Test fun hiddenIndicesSurviveEncodingWithoutRenumberingAccounts() {
        val catalog = WalletCatalog(listOf(StoredWallet("w", "Principal", publicPhrase, listOf("Um", "Dois", "Três"), setOf(1))), "w", 2)
        val decoded = WalletCatalogCodec.decode(WalletCatalogCodec.encode(catalog))
        assertEquals(setOf(1), decoded.selected.hiddenIndices)
        assertEquals(3, decoded.selected.accountNames.size)
        assertEquals(2, decoded.selectedAccountIndex)
    }

    @Test fun refusesHiddenSelectedAccount() {
        val catalog = WalletCatalog(listOf(StoredWallet("w", "Principal", publicPhrase, listOf("Um", "Dois"), setOf(1))), "w", 1)
        assertThrows(Exception::class.java) { WalletCatalogCodec.decode(WalletCatalogCodec.encode(catalog)) }
    }

    @Test
    fun readsLegacyPayloadWithoutChangingIdentityBetweenReads() {
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeInt(0x43575031)
                it.writeUTF("Principal")
                it.writeUTF(publicPhrase)
            }
        }.toByteArray()
        val first = WalletCatalogCodec.decode(bytes)
        val second = WalletCatalogCodec.decode(bytes)
        assertEquals(first.selectedWalletId, second.selectedWalletId)
        assertEquals("Principal", first.selected.name)
        assertEquals(0, first.selectedAccountIndex)
        assertEquals(listOf("Conta 1"), first.selected.accountNames)
        assertEquals(publicPhrase, first.selected.mnemonic)
    }

    @Test
    fun roundTripPreservesMultipleWalletsAccountsAndSelection() {
        val original = WalletCatalog(
            listOf(
                StoredWallet("first", "Pessoal", publicPhrase, listOf("Conta 1", "Compras")),
                StoredWallet("second", "Trabalho", publicPhrase, listOf("Recebimentos", "Reserva"))
            ), "second", 1
        )
        val decoded = WalletCatalogCodec.decode(WalletCatalogCodec.encode(original))
        assertEquals(2, decoded.wallets.size)
        assertEquals("Trabalho", decoded.selected.name)
        assertEquals("Reserva", decoded.selected.accountNames[decoded.selectedAccountIndex])
        assertEquals(publicPhrase, decoded.wallets.first().mnemonic)
        assertFalse(decoded.toString().contains(publicPhrase))
        assertFalse(decoded.selected.toString().contains(publicPhrase))
    }

    @Test
    fun rejectsTruncatedPayload() {
        val bytes = WalletCatalogCodec.encode(WalletCatalog.first("Pessoal", publicPhrase))
        assertThrows(Exception::class.java) { WalletCatalogCodec.decode(bytes.copyOf(bytes.size - 3)) }
    }

    @Test
    fun rejectsSelectionOutsideStoredAccounts() {
        val wallet = StoredWallet("first", "Pessoal", publicPhrase, listOf("Conta 1"))
        val bytes = WalletCatalogCodec.encode(WalletCatalog(listOf(wallet), "first", 4))
        assertThrows(Exception::class.java) { WalletCatalogCodec.decode(bytes) }
    }
}
