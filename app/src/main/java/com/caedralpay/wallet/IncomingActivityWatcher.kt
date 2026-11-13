package com.caedralpay.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Foreground observer. Starts at the confirmed head; not a historical or background indexer. */
internal class IncomingActivityWatcher(private val gateway: EvmGateway = EvmGateway()) {
    private var cursor: BigInteger? = null
    private var cursorHash: String? = null
    private var identity: String? = null
    suspend fun poll(context: Context, chain: BscChain, account: String) {
        require(TransferDraft.isAddressShapeValid(account))
        val key = "${chain.chainId}:${account.lowercase()}"
        if (identity != key) { identity = key; cursor = null; cursorHash = null }
        gateway.read(chain) { rpc ->
            val head = (rpc.quantity("eth_blockNumber") - 14.toBigInteger()).max(BigInteger.ZERO)
            val previous = cursor
            if (previous == null) {
                val block = rpc.call("eth_getBlockByNumber", head.rpcHex(), false) as? JSONObject ?: error("Block unavailable")
                cursorHash = block.getString("hash"); cursor = head
                return@read
            }
            if (head <= previous) return@read
            val checkpoint = rpc.call("eth_getBlockByNumber", previous.rpcHex(), false) as? JSONObject ?: error("Block unavailable")
            check(checkpoint.getString("hash") == cursorHash) { "Chain checkpoint changed" }
            val end = head.min(previous + 6.toBigInteger())
            val blockHashes = mutableMapOf<BigInteger, String>()
            var expectedParent = cursorHash
            var current = previous + BigInteger.ONE
            val alerts = mutableListOf<WalletAlert>()
            while (current <= end) {
                val block = rpc.call("eth_getBlockByNumber", current.rpcHex(), true) as? JSONObject ?: error("Block unavailable")
                check(EvmBalanceCodec.quantity(block.getString("number")) == current && block.getString("parentHash") == expectedParent)
                val hash = block.getString("hash"); blockHashes[current] = hash; expectedParent = hash
                val transactions = block.getJSONArray("transactions")
                for (index in 0 until transactions.length()) {
                    val tx = transactions.getJSONObject(index)
                    if (!tx.optString("to").equals(account, true) || tx.optString("from").equals(account, true) ||
                        EvmBalanceCodec.quantity(tx.getString("value")) == BigInteger.ZERO) continue
                    val receipt = rpc.call("eth_getTransactionReceipt", tx.getString("hash")) as? JSONObject ?: error("Receipt unavailable")
                    verifyReceipt(receipt, tx.getString("hash"), hash)
                    if (EvmBalanceCodec.quantity(receipt.getString("status")) == BigInteger.ONE) {
                        check(receipt.getString("to").equals(account, true) && receipt.getString("from").equals(tx.getString("from"), true))
                        alerts += WalletAlert(AlertKind.IncomingConfirmed, "${chain.chainId}:${tx.getString("hash")}:native")
                    }
                }
                current++
            }
            chain.usdtContract?.let { token ->
                val topic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
                val recipient = "0x" + account.drop(2).lowercase().padStart(64, '0')
                val filter = mapOf("address" to token, "fromBlock" to (previous + BigInteger.ONE).rpcHex(),
                    "toBlock" to end.rpcHex(), "topics" to listOf(topic, JSONObject.NULL, recipient))
                val logs = rpc.call("eth_getLogs", filter) as? JSONArray ?: error("Logs unavailable")
                require(logs.length() <= 256)
                for (index in 0 until logs.length()) {
                    val log = logs.getJSONObject(index); val topics = log.getJSONArray("topics")
                    check(!log.optBoolean("removed") && log.getString("address").equals(token, true) && topics.length() == 3)
                    check(topics.getString(0).equals(topic, true) && topics.getString(2).equals(recipient, true))
                    if (topics.getString(1).equals(recipient, true) || EvmBalanceCodec.abiUint(log.getString("data")) == BigInteger.ZERO) continue
                    val block = EvmBalanceCodec.quantity(log.getString("blockNumber"))
                    val hash = requireNotNull(blockHashes[block]); check(log.getString("blockHash") == hash)
                    val txHash = log.getString("transactionHash")
                    val receipt = rpc.call("eth_getTransactionReceipt", txHash) as? JSONObject ?: error("Receipt unavailable")
                    verifyReceipt(receipt, txHash, hash)
                    check(EvmBalanceCodec.quantity(receipt.getString("status")) == BigInteger.ONE)
                    val receiptLogs = receipt.getJSONArray("logs")
                    check((0 until receiptLogs.length()).any { offset ->
                        val proof = receiptLogs.getJSONObject(offset)
                        proof.optString("logIndex") == log.getString("logIndex") &&
                            proof.optString("address").equals(token, true) && proof.optString("data") == log.getString("data") &&
                            proof.optJSONArray("topics")?.toString() == topics.toString()
                    })
                    alerts += WalletAlert(AlertKind.IncomingConfirmed, "${chain.chainId}:$txHash:${log.getString("logIndex")}")
                }
            }
            for (alert in alerts) WalletAlerts.publish(context, alert)
            cursor = end; cursorHash = blockHashes[end]
        }
    }
    private fun verifyReceipt(receipt: JSONObject, txHash: String, blockHash: String) {
        require(txHash.matches(Regex("0x[0-9a-fA-F]{64}")))
        check(receipt.getString("transactionHash").equals(txHash, true) && receipt.getString("blockHash") == blockHash)
    }
}
