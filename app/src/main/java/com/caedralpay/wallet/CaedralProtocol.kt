package com.caedralpay.wallet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

/** Caedral protocol v1 draft. Amounts are unsigned atomic units, never floats. */
internal object CaedralProtocol {
    enum class Tier { Origin, Obsidian, Sovereign }
    val cashbackChange: Instant = Instant.parse("2027-02-01T03:00:00Z") // midnight São Paulo
    private val uint256Max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
    private val address = Regex("0x[0-9a-fA-F]{40}")

    data class Split(val gross: BigInteger, val merchant: BigInteger, val fee: BigInteger, val cashback: BigInteger) {
        val reserveRequired: BigInteger get() = (cashback - fee).max(BigInteger.ZERO)
        val treasuryNet: BigInteger get() = (fee - cashback).max(BigInteger.ZERO)
    }

    fun split(gross: BigInteger, decimals: Int, tier: Tier, at: Instant): Split {
        require(decimals in 1..18 && gross.signum() > 0 && gross <= uint256Max)
        // Round merchant fee upward to avoid charging less than the agreed 1%.
        val fee = ((gross + BigInteger.valueOf(99)) / BigInteger.valueOf(100))
            .max(BigInteger.TEN.pow(decimals - 1))
        require(fee < gross) { "O valor deve superar a taxa mínima de 0,10 token." }
        val bps = if (at < cashbackChange) 10 else when (tier) {
            Tier.Origin -> 100
            Tier.Obsidian -> 250
            Tier.Sovereign -> 500
        }
        return Split(gross, gross - fee, fee, gross * bps.toBigInteger() / BigInteger.valueOf(10_000))
    }

    /** Chosen display name, not a verified identity. Never used to authorize funds. */
    fun cardName(first: String, last: String): String {
        fun part(raw: String): String {
            val value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
            require(value.length in 1..40 && value.matches(Regex("\\p{L}[\\p{L}\\p{M}]*(?:[-’']\\p{L}[\\p{L}\\p{M}]*)*"))) {
                "Informe apenas um nome e um sobrenome."
            }
            return value
        }
        return "${part(first)} ${part(last)}"
    }

    fun cardExpiry(issuedAt: Instant): Instant = issuedAt.atZone(ZoneOffset.UTC).plusYears(5).toInstant()

    data class Invoice(
        val chainId: Long,
        val settlement: String,
        val merchant: String,
        val token: String, // zero address means native currency
        val gross: BigInteger,
        val id: String, // random 256-bit identifier; no wallet name or card number
        val issuedAt: Long,
        val expiresAt: Long
    ) {
        fun validate(now: Long) {
            require(chainId == 56L || chainId == 97L)
            require(listOf(settlement, merchant, token).all { it.matches(address) })
            require(settlement != ZERO_ADDRESS && merchant != ZERO_ADDRESS)
            require(id.matches(Regex("[0-9a-f]{64}")) && id != "0".repeat(64))
            require(gross.signum() > 0 && gross <= uint256Max)
            require(issuedAt >= 0 && issuedAt <= now && expiresAt > now)
            require(expiresAt > issuedAt && expiresAt - issuedAt <= 120)
        }
    }

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    const val WIRE_SIZE = 152
    fun newId(): String = ByteArray(32).also(SecureRandom()::nextBytes).hex()

    /** Fixed length, big endian, no URI, JSON, text or executable contract calldata. */
    fun encode(invoice: Invoice, now: Long): ByteArray {
        invoice.validate(now)
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(0x43445001)
                out.writeLong(invoice.chainId)
                listOf(invoice.settlement, invoice.merchant, invoice.token).forEach { out.write(it.drop(2).unhex()) }
                out.write(invoice.gross.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it })
                out.write(invoice.id.unhex())
                out.writeLong(invoice.issuedAt)
                out.writeLong(invoice.expiresAt)
            }
        }.toByteArray().also { check(it.size == WIRE_SIZE) }
    }

    fun decode(bytes: ByteArray, now: Long): Invoice {
        require(bytes.size == WIRE_SIZE)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x43445001)
            fun read(size: Int) = ByteArray(size).also(input::readFully)
            Invoice(input.readLong(), "0x" + read(20).hex(), "0x" + read(20).hex(), "0x" + read(20).hex(),
                BigInteger(1, read(32)), read(32).hex(), input.readLong(), input.readLong())
                .also { it.validate(now) }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
