package com.caedralpay.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant

internal class OriginCard(val walletId: String, val index: Int, val holder: String, val id: String, val name: String,
    val number: String, val securityCode: String, val issuedAt: Long, val expiresAt: Long, val sealed: String = "", val lastFour: String = number.takeLast(4), val salt:String = "") {
    override fun toString() = "OriginCard([REDACTED])"
    val fingerprint get() = OriginAbi.sha("$walletId:$index:$id")
    fun json(): JSONObject {
        require(sealed.isNotBlank() && number.isEmpty() && securityCode.isEmpty())
        return JSONObject().put("wallet",walletId).put("index",index).put("holder",holder).put("id",id).put("name",name)
            .put("salt",salt).put("sealed",sealed).put("lastFour",lastFour).put("issued",issuedAt).put("expires",expiresAt)
    }
    companion object {
        fun generate(walletId: String, account: WalletAccount, first: String, last: String): OriginCard {
            val random = SecureRandom(); val now = Instant.now()
            val prefix = "99" + (1..13).joinToString("") { random.nextInt(10).toString() }
            var sum = 0
            prefix.reversed().forEachIndexed { i,c -> val n=c.digitToInt() * if (i % 2 == 0) 2 else 1; sum += if(n>9)n-9 else n }
            val salt=CaedralProtocol.newId()
            val id=java.security.MessageDigest.getInstance("SHA-256").digest("CaedralOriginCardId/v1".toByteArray()+account.address.hexBytes()+salt.hexBytes()).hex()
            return OriginCard(walletId, account.index, account.address, id, CaedralProtocol.cardName(first,last),
                prefix+((10-sum%10)%10), random.nextInt(1_000_000).toString().padStart(6,'0'), now.epochSecond, CaedralProtocol.cardExpiry(now).epochSecond,salt=salt)
        }
        fun decode(o: JSONObject) = OriginCard(o.getString("wallet"),o.getInt("index"),o.getString("holder"),o.getString("id"),o.getString("name"),
            "","",o.getLong("issued"),o.getLong("expires"),o.getString("sealed"),o.getString("lastFour"),o.getString("salt"))
    }
}

/** Local credentials, encrypted; one current/pending record per account. Never sent through NFC. */
internal class OriginCardStore(context: Context, pending: Boolean = false) {
    private val store = PrivateRecordStore(context,if(pending) "origin_card_drafts" else "origin_cards")
    companion object {
        private val lock = Any()
        // Card identity belongs to an account, never to a chain. Pending enrollment
        // retains exactly the same credentials while network activation is outstanding.
        fun latest(context:Context,wallet:String,index:Int):OriginCard? = synchronized(lock) {
            OriginCardStore(context,true).find(wallet,index) ?: OriginCardStore(context).find(wallet,index)
        }
    }
    fun find(wallet: String, index: Int): OriginCard? = synchronized(lock) { all().firstOrNull { it.walletId==wallet && it.index==index } }
    fun findById(id: String): OriginCard? = synchronized(lock) { all().firstOrNull { it.id==id } }
    fun save(card: OriginCard) = synchronized(lock) {
        val cards=all().filterNot { it.walletId==card.walletId && it.index==card.index }+card
        require(cards.size<=32)
        val bytes=JSONArray(cards.map { it.json() }).toString().toByteArray()
        try { store.write(bytes) } finally { bytes.fill(0) }
    }
    private fun all(): List<OriginCard> {
        val raw=store.read() ?: return emptyList()
        return try { val a=JSONArray(raw.toString(Charsets.UTF_8)); require(a.length()<=32); List(a.length()){ OriginCard.decode(a.getJSONObject(it)) } }
        finally { raw.fill(0) }
    }
}

internal class OriginCardDetails(val number:String,val securityCode:String) { override fun toString()="OriginCardDetails([REDACTED])" }
