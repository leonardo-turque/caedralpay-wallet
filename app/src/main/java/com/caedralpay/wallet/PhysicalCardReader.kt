package com.caedralpay.wallet

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.MifareClassic
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class PhysicalCard(val fingerprint: String, val bytes: Int, val sectors: Int)

/** Explicit read-only Classic prototype. UID and memory content are not spending credentials. */
internal class PhysicalCardReader(private val activity: Activity) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val busy = AtomicBoolean()
    private var generation = 0
    val supported get() = adapter?.isEnabled == true
    fun start(onResult: (Result<PhysicalCard>) -> Unit) {
        check(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW && supported)
        val session = ++generation
        adapter.enableReaderMode(activity, { tag ->
            if (!busy.compareAndSet(false, true)) return@enableReaderMode
            val result = runCatching {
                val classic = MifareClassic.get(tag) ?: error("Este teste requer um cartão MIFARE Classic.")
                require(classic.size == 1024) { "Este teste requer o cartão de 1 KB." }
                classic.use {
                    it.connect(); it.timeout = 1_500
                    require(it.authenticateSectorWithKeyA(1, MifareClassic.KEY_DEFAULT)) { "Não foi possível ler o cartão com a chave de teste." }
                    val block = it.readBlock(4)
                    require(block.size == 16)
                    val fingerprint = MessageDigest.getInstance("SHA-256")
                        .digest("Caedral/Classic-test/v1".toByteArray() + tag.id + block).hex()
                    PhysicalCard(fingerprint, it.size, it.sectorCount)
                }
            }
            busy.set(false)
            activity.runOnUiThread { if (session == generation) { stop(); onResult(result) } }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
    fun stop() { generation++; adapter?.disableReaderMode(activity) }
}
