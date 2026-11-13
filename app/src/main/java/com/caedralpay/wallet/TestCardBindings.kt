package com.caedralpay.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class TestCardBinding(val fingerprint: String, val walletId: String, val accountIndex: Int,
    val address: String, val expiresAt: Long)

/** Testnet-only metadata. Never contains seed, private key or an authorization grant. */
internal class TestCardBindings(context: Context) {
    private val store = PrivateRecordStore(context, "test_card_bindings")
    companion object { private val monitor = Any() }
    fun find(fingerprint: String): TestCardBinding? = synchronized(monitor) {
        all().firstOrNull { it.fingerprint == fingerprint && it.expiresAt > Instant.now().epochSecond }
    }
    fun bind(card: PhysicalCard, walletId: String, account: WalletAccount): TestCardBinding = synchronized(monitor) {
        check(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW)
        require(card.fingerprint.matches(Regex("[0-9a-f]{64}")) && TransferDraft.isAddressShapeValid(account.address))
        val binding = TestCardBinding(card.fingerprint, walletId, account.index, account.address,
            CaedralProtocol.cardExpiry(Instant.now()).epochSecond)
        val bindings = all().filter { it.fingerprint != card.fingerprint } + binding
        require(bindings.size <= 32)
        val clear = JSONArray(bindings.map { b -> JSONObject().put("fingerprint", b.fingerprint).put("walletId", b.walletId)
            .put("accountIndex", b.accountIndex).put("address", b.address).put("expiresAt", b.expiresAt) }).toString().toByteArray()
        try { store.write(clear) } finally { clear.fill(0) }
        binding
    }
    private fun all(): List<TestCardBinding> {
        val clear = store.read() ?: return emptyList()
        return try {
            val data = JSONArray(clear.toString(Charsets.UTF_8)); require(data.length() <= 32)
            List(data.length()) { index -> data.getJSONObject(index).let { b ->
                TestCardBinding(b.getString("fingerprint"), b.getString("walletId"), b.getInt("accountIndex"),
                    b.getString("address"), b.getLong("expiresAt"))
            } }
        } finally { clear.fill(0) }
    }
}
