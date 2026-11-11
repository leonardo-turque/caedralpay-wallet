package com.caedralpay.wallet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BiometricCrypto {
    private const val ALIAS = "caedral_biometric_vault_v1"
    private const val PROVIDER = "AndroidKeyStore"
    private val aad = "CaedralPay/biometric-wrapped-key/v1".toByteArray(Charsets.UTF_8)

    fun cipherForEncryption(): Cipher {
        val key = getOrCreateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    fun cipherForDecryption(iv: ByteArray): Cipher {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = store.getKey(ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Chave biométrica indisponível.")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    // A chave exige autenticação a cada uso: só executar após o BiometricPrompt.
    fun finishAuthenticatedOperation(cipher: Cipher, data: ByteArray): ByteArray {
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
