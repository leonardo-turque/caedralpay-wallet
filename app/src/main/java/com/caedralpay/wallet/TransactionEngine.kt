package com.caedralpay.wallet

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger

internal class TransactionEngine(context: Context, private val gateway: EvmGateway = EvmGateway()) {
    private val context = context
    private val journal = TransactionJournal(context)
    private val repository = TransferRepository(gateway)
    companion object { private val submission = WalletOperationGate.mutex }

    suspend fun send(quote: PreparedTransfer, walletId: String, accountIndex: Int, token: VaultAuthorization): WalletTransaction = submission.withLock {
        require(OriginJournal(context).all().none { !it.terminal && quote.chain==OriginNetwork.chain && it.quote.from.equals(quote.from,true) }) { "Há uma operação do cartão em acompanhamento." }
        require(RecurringJournal(context).all().none{!it.terminal && quote.chain==OriginNetwork.chain && it.quote.from.equals(quote.from,true)}){"Há uma autorização em andamento."}
        // Once signing starts, cancellation must not lose the durable signed envelope.
        repository.validateCurrent(quote)
        withContext(NonCancellable + Dispatchers.IO) {
            require(journal.all().none { !it.terminal && it.quote.chain == quote.chain && it.quote.from.equals(quote.from, true) }) {
                "Há uma transação anterior em acompanhamento nesta conta."
            }
            val signed = WalletVault.signTransfer(context, walletId, accountIndex, quote, token)
            val record = WalletTransaction(quote, signed.first, signed.second, TransactionStatus.Signed)
            journal.put(record)
            broadcast(record)
        }
    }

    suspend fun resume(record: WalletTransaction): WalletTransaction = submission.withLock {
        require(!record.terminal)
        val current = journal.all().first { it.hash == record.hash }
        if (current.terminal) current else broadcast(current)
    }

    private suspend fun broadcast(record: WalletTransaction): WalletTransaction {
        val raw = record.raw ?: throw IOException("Transmissão indisponível.")
        try {
            gateway.read(record.quote.chain) { rpc ->
                val result = rpc.hex("eth_sendRawTransaction", raw)
                require(result.equals(record.hash, true)) { "Hash retornado pela rede é diferente do assinado." }
            }
            return record.copy(status = TransactionStatus.Pending).also(journal::put)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Timeout/"already known"/nonce error is NOT proof of failure. Keep the
            // same signed bytes and nonce; a retry can never create a second payment.
            return record
        }
    }

    suspend fun refresh(record: WalletTransaction): WalletTransaction {
        if (record.terminal) return record
        return gateway.read(record.quote.chain) { rpc ->
            val receipt = rpc.call("eth_getTransactionReceipt", record.hash) as? JSONObject
            if (receipt == null) {
                if (rpc.call("eth_getTransactionByHash", record.hash) is JSONObject)
                    record.copy(status = TransactionStatus.Pending).also(journal::put) else record
            } else {
                require(receipt.getString("transactionHash").equals(record.hash, true))
                require(receipt.getString("from").equals(record.quote.from, true) && receipt.getString("to").equals(record.quote.to, true))
                val block = EvmBalanceCodec.quantity(receipt.getString("blockNumber"))
                val head = rpc.quantity("eth_blockNumber")
                if (head < block + 14.toBigInteger()) return@read record.copy(status = TransactionStatus.Pending).also(journal::put)
                val canonical = rpc.call("eth_getBlockByNumber", block.rpcHex(), false) as? JSONObject ?: error("Bloco indisponível.")
                require(canonical.getString("hash").equals(receipt.getString("blockHash"), true)) { "A confirmação mudou. Aguarde nova consulta." }
                val status = EvmBalanceCodec.quantity(receipt.getString("status"))
                require(status == BigInteger.ZERO || status == BigInteger.ONE)
                if (status == BigInteger.ONE && record.quote.contract != null) {
                    val logs = receipt.getJSONArray("logs")
                    val transferTopic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
                    require((0 until logs.length()).any { index ->
                        val log = logs.getJSONObject(index); val topics = log.optJSONArray("topics")
                        log.optString("address").equals(record.quote.contract, true) && topics != null && topics.length() == 3 &&
                            topics.optString(0).equals(transferTopic, true) &&
                            topics.optString(1).equals("0x" + record.quote.from.drop(2).padStart(64, '0'), true) &&
                            topics.optString(2).equals("0x" + record.quote.recipient.drop(2).padStart(64, '0'), true) &&
                            EvmBalanceCodec.abiUint(log.getString("data")) == record.quote.units
                    }) { "O recibo não comprova a transferência do token. Confira no explorador." }
                }
                val fee = EvmBalanceCodec.quantity(receipt.getString("gasUsed")) * EvmBalanceCodec.quantity(receipt.getString("effectiveGasPrice"))
                record.copy(status = if (status == BigInteger.ONE) TransactionStatus.Confirmed else TransactionStatus.Failed,
                    raw = null, block = block.longValueExact(), actualFee = fee.toString()).also(journal::put)
            }
        }
    }

    suspend fun history(): List<WalletTransaction> = withContext(Dispatchers.IO) { journal.all() }
}
