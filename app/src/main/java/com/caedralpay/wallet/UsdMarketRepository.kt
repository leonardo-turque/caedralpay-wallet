package com.caedralpay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Public market request: no account address, PIN or wallet material is transmitted. */
internal class UsdMarketRepository {
    private var cachedAt = 0L
    private var cached = emptyMap<WalletAsset, BigDecimal>()

    suspend fun load(): Map<WalletAsset, BigDecimal> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now >= cachedAt && now - cachedAt < 60_000 && cached.isNotEmpty()) return@withContext cached
        val connection = URL("https://api.coingecko.com/api/v3/simple/price?ids=binancecoin,tether&vs_currencies=usd&include_last_updated_at=true&precision=full")
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IOException("Cotação indisponível.")
            val bytes = connection.inputStream.use { it.readNBytes(16_385) }
            if (bytes.size > 16_384) throw IOException("Resposta de cotação excede o limite.")
            coroutineContext.ensureActive()
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            val result = mapOf(WalletAsset.BNB to "binancecoin", WalletAsset.USDT to "tether").mapNotNull { (asset, id) ->
                val quote = response.optJSONObject(id) ?: return@mapNotNull null
                val timestamp = quote.optLong("last_updated_at", 0)
                val ageSeconds = System.currentTimeMillis() / 1_000 - timestamp
                val price = quote.optString("usd").toBigDecimalOrNull()
                if (ageSeconds !in -60..600 || price == null || price.signum() <= 0) null else asset to price
            }.toMap()
            cached = result
            cachedAt = System.currentTimeMillis()
            result
        } finally {
            connection.disconnect()
        }
    }
}
