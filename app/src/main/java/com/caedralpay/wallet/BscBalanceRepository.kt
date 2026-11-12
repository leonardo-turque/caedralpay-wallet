package com.caedralpay.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

internal fun interface ReadOnlyRpcTransport {
    suspend fun call(endpoint: String, method: String, params: List<Any>): String
}

internal class HttpsReadOnlyRpcTransport : ReadOnlyRpcTransport {
    private val nextId = AtomicLong()
    private val allowedMethods = setOf("eth_chainId", "eth_blockNumber", "eth_getBalance", "eth_call")

    override suspend fun call(endpoint: String, method: String, params: List<Any>): String = withContext(Dispatchers.IO) {
        require(BscChain.entries.any { endpoint in it.rpcEndpoints } && method in allowedMethods)
        coroutineContext.ensureActive()
        val id = nextId.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", JSONArray(params)).toString().toByteArray(Charsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw IOException("RPC indisponível.")
            val bytes = connection.inputStream.use { it.readNBytes(65_537) }
            if (bytes.size > 65_536) throw IOException("Resposta RPC excede o limite.")
            coroutineContext.ensureActive()
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            if (response.optString("jsonrpc") != "2.0" || response.optLong("id", -1) != id ||
                (response.has("error") && !response.isNull("error"))) throw IOException("Resposta RPC inválida.")
            response.opt("result") as? String ?: throw IOException("Resultado RPC ausente.")
        } finally {
            connection.disconnect()
        }
    }
}

internal class BscBalanceRepository(private val transport: ReadOnlyRpcTransport = HttpsReadOnlyRpcTransport()) {
    suspend fun load(address: String, network: BscChain = BscChain.Mainnet): BscAccountBalances = withTimeout(30_000) {
        require(address.matches(Regex("0x[0-9a-fA-F]{40}")))
        var lastFailure: Exception? = null
        for (endpoint in network.rpcEndpoints) {
            coroutineContext.ensureActive()
            try {
                val chain = EvmBalanceCodec.quantity(transport.call(endpoint, "eth_chainId", emptyList()))
                check(chain == network.chainId.toBigInteger()) { "Rede RPC diferente da esperada." }
                val block = transport.call(endpoint, "eth_blockNumber", emptyList())
                val blockNumber = EvmBalanceCodec.quantity(block)
                network.usdtContract?.let { contract ->
                    val decimals = EvmBalanceCodec.abiUint(transport.call(endpoint, "eth_call",
                        listOf(mapOf("to" to contract, "data" to "0x313ce567"), block)))
                    check(decimals == BscNetwork.usdtDecimals.toBigInteger()) { "Decimais do token diferentes da configuração." }
                }
                val native = EvmBalanceCodec.quantity(transport.call(endpoint, "eth_getBalance", listOf(address, block)))
                val usdt = network.usdtContract?.let { contract -> EvmBalanceCodec.abiUint(transport.call(endpoint, "eth_call",
                    listOf(mapOf("to" to contract, "data" to EvmBalanceCodec.balanceOfData(address)), block))) }
                coroutineContext.ensureActive()
                return@withTimeout BscAccountBalances(address, blockNumber,
                    EvmBalanceCodec.units(native, BscNetwork.nativeDecimals), usdt?.let { EvmBalanceCodec.units(it, BscNetwork.usdtDecimals) },
                    System.currentTimeMillis(), endpoint, network)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IOException("Não foi possível consultar os saldos na BNB Smart Chain.", lastFailure)
    }
}
