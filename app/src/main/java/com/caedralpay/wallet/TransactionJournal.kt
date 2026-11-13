package com.caedralpay.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class TransactionStatus(val label: String) {
    Signed("Aguardando transmissão"), Pending("Pendente"), Confirmed("Confirmada"), Failed("Falhou")
}
internal data class WalletTransaction(val quote: PreparedTransfer, val hash: String, val raw: String?,
    val status: TransactionStatus, val block: Long? = null, val actualFee: String? = null) {
    val terminal get() = status == TransactionStatus.Confirmed || status == TransactionStatus.Failed
    override fun toString() = "WalletTransaction(hash=$hash,status=$status)"
}

/** Durable encrypted outbox: persist BEFORE broadcasting. Never overwrite an unreadable journal. */
internal class TransactionJournal(context: Context, private val capacity: Int = 500) {
    private val context = context
    init { require(capacity in 1..500) }
    private val file = AtomicFile(context.filesDir.resolve("wallet_activity.bin"))
    private val aad = "CaedralPay/activity/v1".toByteArray()
    companion object { private val monitor = Any() }

    fun all(): List<WalletTransaction> = synchronized(monitor) {
        if (!file.baseFile.exists() && !java.io.File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val bytes = file.openRead().use { it.readNBytes(2_097_153) }
        require(bytes.size in 29..2_097_152)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); cipher.updateAAD(aad)
        val clear = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val array = JSONArray(clear.toString(Charsets.UTF_8))
            require(array.length() <= 500)
            List(array.length()) { i -> decode(array.getJSONObject(i)) }
        } finally { clear.fill(0) }
    }

    fun put(record: WalletTransaction) = synchronized(monitor) {
        require(record.hash.matches(Regex("0x[0-9a-f]{64}")))
        val records = all().toMutableList()
        val old = records.indexOfFirst { it.hash == record.hash }
        if (old >= 0) {
            require(records[old].quote.digest == record.quote.digest)
            require(!records[old].terminal || records[old] == record) { "Transação já finalizada." }
            records[old] = record
        } else {
            require(records.none { !it.terminal && it.quote.chain == record.quote.chain && it.quote.from.equals(record.quote.from, true) }) {
                "Aguarde a transação anterior desta conta ou retome sua transmissão."
            }
            // Retain pending envelopes; prune finalized history instead of disabling new sends.
            while (records.size >= capacity) {
                val oldestFinalized = records.indexOfFirst { it.terminal }
                require(oldestFinalized >= 0) { "Aguarde a conclusão dos envios em acompanhamento." }
                records.removeAt(oldestFinalized)
            }
            records.add(record)
        }
        val clear = JSONArray(records.map(::encode)).toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            val encrypted = cipher.iv + cipher.doFinal(clear)
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) } catch (failure: Throwable) { file.failWrite(stream); throw failure }
        } finally { clear.fill(0) }
        WalletAlerts.outgoing(context, record)
    }

    private fun key(create: Boolean): SecretKey {
        val alias = "caedral_activity_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Histórico indisponível." }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true).build())
        }.generateKey()
    }

    private fun encode(record: WalletTransaction): JSONObject {
        val q = record.quote
        return JSONObject().put("id", q.id).put("chain", q.chain.name).put("asset", q.asset.name).put("from", q.from)
            .put("recipient", q.recipient).put("units", q.units.toString()).put("nonce", q.nonce.toString())
            .put("gasPrice", q.gasPrice.toString()).put("gasLimit", q.gasLimit.toString()).put("createdAt", q.createdAt)
            .put("hash", record.hash).put("raw", record.raw ?: JSONObject.NULL).put("status", record.status.name)
            .put("block", record.block ?: JSONObject.NULL).put("fee", record.actualFee ?: JSONObject.NULL)
    }
    private fun decode(o: JSONObject): WalletTransaction {
        val quote = PreparedTransfer(o.getString("id"), BscChain.valueOf(o.getString("chain")), WalletAsset.valueOf(o.getString("asset")),
            o.getString("from"), o.getString("recipient"), o.getString("units").toBigInteger(), o.getString("nonce").toBigInteger(),
            o.getString("gasPrice").toBigInteger(), o.getString("gasLimit").toBigInteger(), o.getLong("createdAt"))
        val hash = o.getString("hash"); require(hash.matches(Regex("0x[0-9a-f]{64}")))
        val raw = if (o.isNull("raw")) null else o.getString("raw").also { require(it.matches(Regex("0x[0-9a-f]{2,8192}"))) }
        return WalletTransaction(quote, hash, raw, TransactionStatus.valueOf(o.getString("status")),
            if (o.isNull("block")) null else o.getLong("block"), if (o.isNull("fee")) null else o.getString("fee"))
    }
}
