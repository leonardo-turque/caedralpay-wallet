package com.caedralpay.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WalletData(val name: String, val mnemonic: String) {
    override fun toString(): String = "WalletData([REDACTED])"
}

class WrongPinException : Exception()
class PinTemporarilyLockedException(val remainingSeconds: Long) : Exception()
class VaultUnavailableException(cause: Throwable? = null) : Exception(cause)
class VaultSaveException(val stage: String, cause: Throwable) : Exception(cause)

object WalletVault {
    private const val FILE_NAME = "wallet_vault.bin"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DEVICE_KEY_ALIAS = "caedral_device_vault_v1"
    private const val FILE_MAGIC = 0x43445631 // CDV1
    private const val RECORD_VERSION = 1
    private const val PAYLOAD_MAGIC = 0x43575031 // CWP1
    private const val KDF_ITERATIONS = 600_000
    private const val MAX_FIELD_SIZE = 1_048_576
    private const val ATTEMPT_PREFS = "wallet_pin_attempts"
    private val random = SecureRandom()
    private val outerAad = "CaedralPay/device-vault/v1".toByteArray(Charsets.UTF_8)
    private val payloadAad = "CaedralPay/wallet-payload/v1".toByteArray(Charsets.UTF_8)
    private val pinAad = "CaedralPay/pin-wrapped-key/v1".toByteArray(Charsets.UTF_8)

    fun exists(context: Context): Boolean = vaultFile(context).baseFile.isFile

    fun hasBiometric(context: Context): Boolean = readRecord(context).biometricWrappedKey != null

    fun create(
        context: Context,
        walletName: String,
        words: List<String>,
        pin: CharArray,
        authenticatedBiometricCipher: Cipher? = null
    ) {
        require(walletName.isNotBlank())
        require(words.size == 12)
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        check(!exists(context)) { "Já existe uma carteira neste dispositivo." }

        val dataKeyBytes = ByteArray(32).also(random::nextBytes)
        val pinSalt = ByteArray(32).also(random::nextBytes)
        var pinKeyBytes: ByteArray? = null
        var stage = "payload-encryption"
        try {
            val dataKey = SecretKeySpec(dataKeyBytes, "AES")
            val payload = encodePayload(walletName.trim(), words.joinToString(" "))
            val payloadEncrypted = try {
                encrypt(dataKey, payload, payloadAad)
            } finally {
                payload.fill(0)
            }

            stage = "pin-key-derivation"
            pinKeyBytes = derivePinKey(pin, pinSalt)
            stage = "pin-key-wrapping"
            val pinEncrypted = encrypt(SecretKeySpec(pinKeyBytes, "AES"), dataKeyBytes, pinAad)

            stage = "biometric-key-wrapping"
            val biometricWrapped = authenticatedBiometricCipher?.let {
                BiometricCrypto.finishAuthenticatedOperation(it, dataKeyBytes)
            }
            val biometricIv = authenticatedBiometricCipher?.iv?.clone()

            stage = "device-keystore-and-file-write"
            writeRecord(
                context,
                VaultRecord(
                    pinSalt = pinSalt,
                    pinIv = pinEncrypted.iv,
                    pinWrappedKey = pinEncrypted.ciphertext,
                    payloadIv = payloadEncrypted.iv,
                    payloadCiphertext = payloadEncrypted.ciphertext,
                    biometricIv = biometricIv,
                    biometricWrappedKey = biometricWrapped
                )
            )
        } catch (error: Exception) {
            throw VaultSaveException(stage, error)
        } finally {
            pin.fill('\u0000')
            dataKeyBytes.fill(0)
            pinKeyBytes?.fill(0)
        }
    }

    fun unlockWithPin(context: Context, pin: CharArray): WalletData {
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        val remaining = remainingLockMillis(context)
        if (remaining > 0) {
            pin.fill('\u0000')
            throw PinTemporarilyLockedException((remaining + 999) / 1_000)
        }
        var pinKeyBytes: ByteArray? = null
        var dataKeyBytes: ByteArray? = null
        try {
            val record = readRecord(context)
            pinKeyBytes = derivePinKey(pin, record.pinSalt)
            dataKeyBytes = try {
                decrypt(SecretKeySpec(pinKeyBytes, "AES"), record.pinIv, record.pinWrappedKey, pinAad)
            } catch (_: AEADBadTagException) {
                recordFailedAttempt(context)
                throw WrongPinException()
            }
            return decryptPayload(record, dataKeyBytes).also { clearFailedAttempts(context) }
        } finally {
            pin.fill('\u0000')
            pinKeyBytes?.fill(0)
            dataKeyBytes?.fill(0)
        }
    }

    fun biometricCipherForUnlock(context: Context): Cipher {
        val record = readRecord(context)
        val iv = record.biometricIv ?: throw VaultUnavailableException()
        if (record.biometricWrappedKey == null) throw VaultUnavailableException()
        return BiometricCrypto.cipherForDecryption(iv)
    }

    fun unlockWithBiometric(context: Context, authenticatedCipher: Cipher): WalletData {
        val record = readRecord(context)
        val wrappedKey = record.biometricWrappedKey ?: throw VaultUnavailableException()
        var dataKeyBytes: ByteArray? = null
        try {
            dataKeyBytes = BiometricCrypto.finishAuthenticatedOperation(authenticatedCipher, wrappedKey)
            return decryptPayload(record, dataKeyBytes)
        } finally {
            dataKeyBytes?.fill(0)
        }
    }

    private fun decryptPayload(record: VaultRecord, dataKeyBytes: ByteArray): WalletData {
        val clear = decrypt(
            SecretKeySpec(dataKeyBytes, "AES"),
            record.payloadIv,
            record.payloadCiphertext,
            payloadAad
        )
        return try {
            decodePayload(clear)
        } finally {
            clear.fill(0)
        }
    }

    private fun writeRecord(context: Context, record: VaultRecord) {
        val clearRecord = encodeRecord(record)
        try {
            val encrypted = encrypt(deviceKey(create = true), clearRecord, outerAad)
            val atomicFile = vaultFile(context)
            val output = atomicFile.startWrite()
            try {
                DataOutputStream(output).apply {
                    writeInt(FILE_MAGIC)
                    writeInt(RECORD_VERSION)
                    writeSized(encrypted.iv)
                    writeSized(encrypted.ciphertext)
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        } finally {
            clearRecord.fill(0)
        }
    }

    private fun readRecord(context: Context): VaultRecord {
        try {
            DataInputStream(vaultFile(context).openRead()).use { input ->
                check(input.readInt() == FILE_MAGIC)
                check(input.readInt() == RECORD_VERSION)
                val iv = input.readSized(32)
                val encrypted = input.readSized(MAX_FIELD_SIZE)
                val clear = decrypt(deviceKey(create = false), iv, encrypted, outerAad)
                return try {
                    decodeRecord(clear)
                } finally {
                    clear.fill(0)
                }
            }
        } catch (error: VaultUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw VaultUnavailableException(error)
        }
    }

    private fun deviceKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(DEVICE_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw VaultUnavailableException()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                DEVICE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun derivePinKey(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, KDF_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun remainingLockMillis(context: Context): Long {
        val until = context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE)
            .getLong("locked_until", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun recordFailedAttempt(context: Context) {
        val preferences = context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE)
        val failures = preferences.getInt("failures", 0) + 1
        val lockSeconds = if (failures < 5) {
            0L
        } else {
            (30L shl (failures - 5).coerceAtMost(7)).coerceAtMost(3_600L)
        }
        preferences.edit()
            .putInt("failures", failures)
            .putLong("locked_until", System.currentTimeMillis() + lockSeconds * 1_000L)
            .apply()
    }

    private fun clearFailedAttempts(context: Context) {
        context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun encrypt(key: SecretKey, clear: ByteArray, aad: ByteArray): EncryptedValue {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return EncryptedValue(cipher.iv.clone(), cipher.doFinal(clear))
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun encodePayload(name: String, mnemonic: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PAYLOAD_MAGIC)
                output.writeUTF(name)
                output.writeUTF(mnemonic)
            }
            bytes.toByteArray()
        }

    private fun decodePayload(bytes: ByteArray): WalletData =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == PAYLOAD_MAGIC)
            val name = input.readUTF()
            val mnemonic = input.readUTF()
            check(mnemonic.trim().split(Regex("\\s+")).size == 12)
            WalletData(name, mnemonic)
        }

    private fun encodeRecord(record: VaultRecord): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(RECORD_VERSION)
                output.writeSized(record.pinSalt)
                output.writeSized(record.pinIv)
                output.writeSized(record.pinWrappedKey)
                output.writeSized(record.payloadIv)
                output.writeSized(record.payloadCiphertext)
                output.writeBoolean(record.biometricWrappedKey != null)
                if (record.biometricWrappedKey != null) {
                    output.writeSized(requireNotNull(record.biometricIv))
                    output.writeSized(record.biometricWrappedKey)
                }
            }
            bytes.toByteArray()
        }

    private fun decodeRecord(bytes: ByteArray): VaultRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == RECORD_VERSION)
            val pinSalt = input.readSized(64)
            val pinIv = input.readSized(32)
            val pinWrapped = input.readSized(256)
            val payloadIv = input.readSized(32)
            val payload = input.readSized(MAX_FIELD_SIZE)
            val hasBiometric = input.readBoolean()
            val biometricIv = if (hasBiometric) input.readSized(32) else null
            val biometricWrapped = if (hasBiometric) input.readSized(256) else null
            VaultRecord(pinSalt, pinIv, pinWrapped, payloadIv, payload, biometricIv, biometricWrapped)
        }

    private fun DataOutputStream.writeSized(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(maxSize: Int): ByteArray {
        val size = readInt()
        require(size in 1..maxSize)
        return ByteArray(size).also(::readFully)
    }

    private fun vaultFile(context: Context) = AtomicFile(context.filesDir.resolve(FILE_NAME))

    private data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray)

    private data class VaultRecord(
        val pinSalt: ByteArray,
        val pinIv: ByteArray,
        val pinWrappedKey: ByteArray,
        val payloadIv: ByteArray,
        val payloadCiphertext: ByteArray,
        val biometricIv: ByteArray?,
        val biometricWrappedKey: ByteArray?
    )
}
