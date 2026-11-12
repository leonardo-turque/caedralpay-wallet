package com.caedralpay.wallet

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

internal fun BigInteger.rpcHex(): String = "0x" + toString(16)
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.hexBytes(): ByteArray {
    val text = removePrefix("0x")
    require(text.length % 2 == 0 && text.matches(Regex("[0-9a-fA-F]*")))
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal data class PreparedTransfer(
    val id: String = UUID.randomUUID().toString(), val chain: BscChain, val asset: WalletAsset,
    val from: String, val recipient: String, val units: BigInteger, val nonce: BigInteger,
    val gasPrice: BigInteger, val gasLimit: BigInteger, val createdAt: Long = System.currentTimeMillis()
) {
    val contract: String? get() = if (asset == WalletAsset.BNB) null else chain.usdtContract
    val to: String get() = contract ?: recipient
    val value: BigInteger get() = if (asset == WalletAsset.BNB) units else BigInteger.ZERO
    val data: String get() = if (contract == null) "0x" else "0xa9059cbb" + recipient.drop(2).lowercase().padStart(64, '0') + units.toString(16).padStart(64, '0')
    val fee: BigInteger get() = gasPrice * gasLimit
    val amount: BigDecimal get() = BigDecimal(units, 18)
    val digest: String get() = MessageDigest.getInstance("SHA-256").digest(
        listOf(id, chain.chainId, asset.symbol, from.lowercase(), recipient.lowercase(), units, nonce, gasPrice, gasLimit, createdAt, contract)
            .joinToString("|").toByteArray()).hex()
    fun validate(now: Long = System.currentTimeMillis()) {
        require(TransferDraft.isAddressShapeValid(from) && TransferDraft.isAddressShapeValid(recipient))
        require(!from.equals(recipient, true)) { "Escolha uma conta de destino diferente." }
        require(asset in chain.assets && (asset == WalletAsset.BNB || contract != null))
        require(units.signum() > 0 && units.bitLength() <= 256 && nonce.signum() >= 0 && nonce.bitLength() <= 64)
        require(gasPrice.signum() > 0 && gasPrice <= BigInteger.TEN.pow(12) && gasLimit in 21_000.toBigInteger()..1_000_000.toBigInteger())
        require(now >= createdAt && now - createdAt <= 90_000) { "A cotação expirou. Revise as taxas novamente." }
    }
    fun rpcTransaction() = mapOf("from" to from, "to" to to, "value" to value.rpcHex(), "data" to data)
}

internal class TransferRepository(private val gateway: EvmGateway = EvmGateway()) {
    suspend fun prepare(chain: BscChain, asset: WalletAsset, from: String, recipient: String, amount: String): PreparedTransfer {
        require(TransferDraft.isAddressShapeValid(from) && TransferDraft.isAddressShapeValid(recipient))
        val decimal = requireNotNull(TransferDraft.amount(amount)) { "Quantidade inválida." }
        val units = try { decimal.movePointRight(18).toBigIntegerExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Use no máximo 18 casas decimais.")
        }
        require(units.signum() > 0 && units.bitLength() <= 256)
        return gateway.read(chain) { rpc ->
            val nonce = rpc.quantity("eth_getTransactionCount", from, "pending")
            val gasPrice = rpc.quantity("eth_gasPrice").multiply(110.toBigInteger()).add(99.toBigInteger()).divide(100.toBigInteger())
            val draft = PreparedTransfer(chain = chain, asset = asset, from = from, recipient = recipient, units = units,
                nonce = nonce, gasPrice = gasPrice, gasLimit = 21_000.toBigInteger())
            draft.validate()
            if (asset != WalletAsset.BNB) verifyToken(rpc, requireNotNull(draft.contract))
            val gas = rpc.quantity("eth_estimateGas", draft.rpcTransaction()).multiply(120.toBigInteger()).add(99.toBigInteger()).divide(100.toBigInteger())
            val prepared = draft.copy(gasLimit = gas.coerceAtLeast(21_000.toBigInteger()))
            prepared.validate()
            verifyBalances(rpc, prepared)
            prepared
        }
    }

    suspend fun validateCurrent(quote: PreparedTransfer) {
        quote.validate()
        gateway.read(quote.chain) { rpc ->
            require(rpc.quantity("eth_getTransactionCount", quote.from, "pending") == quote.nonce) { "A conta mudou. Revise o envio novamente." }
            require(rpc.quantity("eth_gasPrice") <= quote.gasPrice) { "As taxas mudaram. Revise o envio novamente." }
            require(rpc.quantity("eth_estimateGas", quote.rpcTransaction()) <= quote.gasLimit) { "A estimativa mudou. Revise o envio novamente." }
            if (quote.contract != null) verifyToken(rpc, requireNotNull(quote.contract))
            verifyBalances(rpc, quote)
        }
    }

    private suspend fun verifyToken(rpc: EvmConnection, contract: String) {
        require(rpc.hex("eth_getCode", contract, "latest").length > 2) { "Contrato indisponível nesta rede." }
        require(EvmBalanceCodec.abiUint(rpc.hex("eth_call", mapOf("to" to contract, "data" to "0x313ce567"), "latest")) == 18.toBigInteger())
        // Simulate transfer separately through estimateGas; no approvals or arbitrary calls.
    }

    private suspend fun verifyBalances(rpc: EvmConnection, quote: PreparedTransfer) {
        val balance = rpc.quantity("eth_getBalance", quote.from, "pending")
        require(balance >= quote.value + quote.fee) { "Saldo de ${quote.chain.nativeSymbol} insuficiente para o valor e a taxa de rede." }
        quote.contract?.let { contract ->
            val tokens = EvmBalanceCodec.abiUint(rpc.hex("eth_call", mapOf("to" to contract, "data" to EvmBalanceCodec.balanceOfData(quote.from)), "latest"))
            require(tokens >= quote.units) { "Saldo do token insuficiente." }
            val result = rpc.hex("eth_call", quote.rpcTransaction(), "pending")
            require(result == "0x" || EvmBalanceCodec.abiUint(result) == BigInteger.ONE) { "O contrato recusou a transferência." }
        }
    }
}
