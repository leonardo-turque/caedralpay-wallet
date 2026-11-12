package com.caedralpay.wallet

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.SystemClock
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Development transport only. No signing, seed access, token allowance or broadcast. */
internal object CaedralNfcSession {
    private val incoming = MutableStateFlow<CaedralProtocol.Invoice?>(null)
    val invoice = incoming.asStateFlow()
    private var armedUntil = 0L
    private var ephemeral: CaedralNfcCrypto.Ephemeral? = null

    @Synchronized fun arm() {
        clear()
        check(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW)
        ephemeral = CaedralNfcCrypto.Ephemeral()
        armedUntil = SystemClock.elapsedRealtime() + 120_000
    }
    @Synchronized fun clear() { armedUntil = 0; incoming.value = null; ephemeral = null }
    @Synchronized fun publicKey(): ByteArray? = if (isArmed()) ephemeral?.publicKey else null
    @Synchronized fun isArmed() = BuildConfig.CAEDRAL_PROTOCOL_PREVIEW &&
        SystemClock.elapsedRealtime() < armedUntil && incoming.value == null

    @Synchronized fun receive(payload: ByteArray): Boolean {
        if (!isArmed()) return false
        return try {
            val clear = CaedralNfcCrypto.open(requireNotNull(ephemeral), payload)
            incoming.value = try { CaedralProtocol.decode(clear, Instant.now().epochSecond) } finally { clear.fill(0) }
            armedUntil = 0 // exactly one request per explicit foreground session
            ephemeral = null
            true
        } catch (_: java.security.GeneralSecurityException) { false }
        catch (_: IllegalArgumentException) { false }
    }
}

/** Experimental locally assigned AID, not an EMV payment application or registered scheme AID. */
internal object CaedralApdu {
    val aid: ByteArray get() = byteArrayOf(0xF0.toByte(), 0x43, 0x41, 0x45, 0x44, 0x52, 0x01)
    val select: ByteArray get() = byteArrayOf(0, 0xA4.toByte(), 4, 0, 7) + aid
    val ok: ByteArray get() = byteArrayOf(0x90.toByte(), 0)
    fun put(payload: ByteArray): ByteArray {
        require(payload.size == CaedralNfcCrypto.ENVELOPE_SIZE)
        return byteArrayOf(0x80.toByte(), 0x10, 0, 0, payload.size.toByte()) + payload
    }
    fun payload(command: ByteArray): ByteArray? =
        if (command.size == 5 + CaedralNfcCrypto.ENVELOPE_SIZE &&
            command.copyOfRange(0, 5).contentEquals(byteArrayOf(0x80.toByte(), 0x10, 0, 0, CaedralNfcCrypto.ENVELOPE_SIZE.toByte())))
            command.copyOfRange(5, command.size) else null
}

class CaedralHostService : HostApduService() {
    private var selected = false
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val command = commandApdu ?: return status(0x67, 0)
        if (!CaedralNfcSession.isArmed()) { selected = false; return status(0x69, 0x85) }
        if (command.contentEquals(CaedralApdu.select)) {
            selected = true
            return (CaedralNfcSession.publicKey() ?: return status(0x69, 0x85)) + CaedralApdu.ok
        }
        if (!selected) return status(0x69, 0x85)
        val payload = CaedralApdu.payload(command) ?: return status(0x6D, 0)
        selected = false
        return if (CaedralNfcSession.receive(payload)) CaedralApdu.ok else status(0x6A, 0x80)
    }
    override fun onDeactivated(reason: Int) { selected = false }
    private fun status(first: Int, second: Int) = byteArrayOf(first.toByte(), second.toByte())
}

/** Merchant phone is the reader; buyer's explicitly armed phone receives the request. */
internal class CaedralNfcReader(private val activity: Activity) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    val supported: Boolean get() = adapter != null && adapter.isEnabled

    fun start(invoice: CaedralProtocol.Invoice, onResult: (Boolean) -> Unit) {
        check(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW && supported)
        val payload = CaedralProtocol.encode(invoice, Instant.now().epochSecond)
        adapter.enableReaderMode(activity, { tag ->
            val result = runCatching {
                val iso = IsoDep.get(tag) ?: return@runCatching false
                iso.use {
                    it.connect()
                    it.timeout = 2_000
                    val hello = it.transceive(CaedralApdu.select)
                    if (hello.size != CaedralNfcCrypto.PUBLIC_SIZE + 2 ||
                        !hello.takeLast(2).toByteArray().contentEquals(CaedralApdu.ok)) return@use false
                    val encrypted = CaedralNfcCrypto.seal(hello.copyOfRange(0, CaedralNfcCrypto.PUBLIC_SIZE), payload)
                    // A transport acknowledgement means delivered, never paid.
                    CaedralProtocol.decode(payload, Instant.now().epochSecond)
                    it.transceive(CaedralApdu.put(encrypted)).contentEquals(CaedralApdu.ok)
                }
            }.getOrDefault(false)
            activity.runOnUiThread { onResult(result) }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
    fun stop() { adapter?.disableReaderMode(activity) }
}
