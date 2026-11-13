package com.caedralpay.wallet

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Draft channel: X25519 + HKDF-SHA256 + AES-256-GCM via platform JCA.
 * Protects against passive capture and altered ciphertext, NOT an active MITM or NFC relay.
 * Merchant signature verification and a trusted settlement deployment remain mandatory before payment.
 */
internal object CaedralNfcCrypto {
    const val PUBLIC_SIZE = 44
    const val ENVELOPE_SIZE = PUBLIC_SIZE + 12 + CaedralProtocol.WIRE_SIZE + 16
    private val context = "Caedral/NFC/v1/request".toByteArray(Charsets.US_ASCII)
    private val prefix = byteArrayOf(0x30, 0x2A, 0x30, 5, 6, 3, 0x2B, 0x65, 0x6E, 3, 0x21, 0)
    class Ephemeral {
        private val pair = KeyPairGenerator.getInstance("XDH").apply {
            initialize(255)
        }.generateKeyPair()
        val publicKey: ByteArray get() = pair.public.encoded.also { validatePublic(it) }
        internal fun secret(peer: ByteArray): ByteArray {
            validatePublic(peer)
            val public = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(peer))
            return KeyAgreement.getInstance("XDH").run {
                init(pair.private); doPhase(public, true); generateSecret()
            }.also { require(it.size == 32 && it.any { byte -> byte != 0.toByte() }) }
        }
    }
    fun seal(recipient: ByteArray, clear: ByteArray): ByteArray {
        require(clear.size == CaedralProtocol.WIRE_SIZE || clear.size == OriginInvoice.SIZE)
        val sender = Ephemeral()
        val key = key(sender.secret(recipient), recipient, sender.publicKey)
        return try {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            sender.publicKey + iv + Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(context + recipient + sender.publicKey); doFinal(clear)
            }
        } finally { key.fill(0) }
    }
    fun open(recipient: Ephemeral, envelope: ByteArray): ByteArray {
        require(envelope.size == ENVELOPE_SIZE || envelope.size == OriginInvoice.SIZE + 72)
        val sender = envelope.copyOfRange(0, PUBLIC_SIZE)
        val key = key(recipient.secret(sender), recipient.publicKey, sender)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.copyOfRange(44, 56)))
                updateAAD(context + recipient.publicKey + sender); doFinal(envelope.copyOfRange(56, envelope.size))
            }
        } finally { key.fill(0) }
    }
    private fun validatePublic(value: ByteArray) {
        require(value.size == PUBLIC_SIZE && value.copyOfRange(0, prefix.size).contentEquals(prefix))
    }
    private fun key(secret: ByteArray, recipient: ByteArray, sender: ByteArray): ByteArray {
        fun hmac(k: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(k, "HmacSHA256")); doFinal(value)
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(context + recipient + sender)
        val prk = try { hmac(salt, secret) } finally { secret.fill(0) }
        return try { hmac(prk, context + byteArrayOf(1)) } finally { prk.fill(0) }
    }
}
