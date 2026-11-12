package com.caedralpay.wallet

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

internal class RpcException(val code: Int) : IOException("A rede recusou a solicitação ($code).")
internal fun interface EvmTransport {
    suspend fun call(endpoint: String, method: String, params: List<Any>): Any?
}

internal class HttpsEvmTransport : EvmTransport {
    private val ids = AtomicLong()
    override suspend fun call(endpoint: String, method: String, params: List<Any>): Any? = withContext(Dispatchers.IO) {
        require(BscChain.entries.any { endpoint in it.rpcEndpoints })
        require(method in setOf("eth_chainId", "eth_blockNumber", "eth_getBalance", "eth_call", "eth_getCode",
            "eth_getTransactionCount", "eth_gasPrice", "eth_estimateGas", "eth_sendRawTransaction",
            "eth_getTransactionReceipt", "eth_getTransactionByHash", "eth_getBlockByNumber"))
        val id = ids.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", JSONArray(params))
            .toString().toByteArray(Charsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 8_000; connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw IOException("Serviço de rede indisponível.")
            val limit = if (method == "eth_getBlockByNumber") 1_048_576 else 131_072
            val bytes = connection.inputStream.use { it.readNBytes(limit + 1) }
            if (bytes.size > limit) throw IOException("Resposta de rede excede o limite.")
            coroutineContext.ensureActive()
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            check(response.optString("jsonrpc") == "2.0" && response.optLong("id", -1) == id)
            response.optJSONObject("error")?.let { throw RpcException(it.optInt("code")) }
            check(response.has("result"))
            if (response.isNull("result")) null else response.get("result")
        } finally { connection.disconnect() }
    }
}

internal class EvmGateway(private val transport: EvmTransport = HttpsEvmTransport()) {
    suspend fun <T> read(chain: BscChain, operation: suspend (EvmConnection) -> T): T = withTimeout(25_000) {
        var failure: Exception? = null
        for (endpoint in chain.rpcEndpoints) {
            try {
                val connection = EvmConnection(chain, endpoint, transport)
                check(connection.quantity("eth_chainId") == chain.chainId.toBigInteger()) { "Rede incompatível." }
                return@withTimeout operation(connection)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        throw failure ?: IOException("Rede indisponível.")
    }
}

internal class EvmConnection(val chain: BscChain, val endpoint: String, private val transport: EvmTransport) {
    suspend fun call(method: String, vararg params: Any) = transport.call(endpoint, method, params.toList())
    suspend fun hex(method: String, vararg params: Any): String = call(method, *params) as? String ?: error("Resposta de rede inválida.")
    suspend fun quantity(method: String, vararg params: Any) = EvmBalanceCodec.quantity(hex(method, *params))
}
