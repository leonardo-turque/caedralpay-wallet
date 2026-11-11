package com.caedralpay.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import wallet.core.jni.CoinType
import wallet.core.jni.HDWallet

@RunWith(AndroidJUnit4::class)
class RecoveryPhraseTest {
    // Vetor público BIP39; nunca representa uma carteira de usuário.
    private val publicVector = List(11) { "abandon" } + "about"

    @Test
    fun validatesChecksumAndRejectsIncorrectWordsOrLength() {
        assertTrue(isValidRecoveryPhrase(publicVector))
        assertFalse(isValidRecoveryPhrase(List(12) { "abandon" }))
        assertFalse(isValidRecoveryPhrase(publicVector.dropLast(1)))
        assertFalse(isValidRecoveryPhrase(publicVector.dropLast(1) + "notabip39word"))
    }

    @Test
    fun restoresSameEvmAddressFromGeneratedPhrase() {
        System.loadLibrary("TrustWalletCore")
        val original = HDWallet(128, "")
        val words = original.mnemonic().split(" ")
        assertTrue(isValidRecoveryPhrase(words))
        val restored = HDWallet(words.joinToString(" "), "")
        // Não imprimir frase, chaves ou endereços de carteiras reais.
        assertTrue(
            original.getAddressForCoin(CoinType.ETHEREUM) ==
                restored.getAddressForCoin(CoinType.ETHEREUM)
        )
    }

    @Test
    fun acceptsCapitalizationAndSpacesWithoutChangingPhrase() {
        val normalized = publicVector.map { normalizeRecoveryWord("  ${it.uppercase()}  ") }
        assertEquals(publicVector, normalized)
        assertTrue(isValidRecoveryPhrase(normalized))
    }
}
