package com.caedralpay.wallet

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder

/** Only the supported payment subset of ERC-681; never arbitrary contract calls. */
internal object PaymentQr {
    data class Request(val address: String, val asset: WalletAsset?, val amount: BigDecimal?)

    fun encode(address: String, chain: BscChain, asset: WalletAsset, amount: BigDecimal? = null): String {
        require(TransferDraft.isAddressShapeValid(address) && asset in chain.assets)
        val atomic = amount?.let {
            require(it.signum() > 0)
            it.movePointRight(18).toBigIntegerExact().also { value -> require(value.bitLength() <= 256) }.toString()
        }
        return if (asset == WalletAsset.BNB) {
            "ethereum:$address@${chain.chainId}" + (atomic?.let { "?value=$it" } ?: "")
        } else {
            "ethereum:${requireNotNull(chain.usdtContract)}@${chain.chainId}/transfer?address=$address" +
                (atomic?.let { "&uint256=$it" } ?: "")
        }
    }

    fun parse(raw: String, chain: BscChain): Request {
        require(raw.length <= 2_048) { "QR Code de pagamento inválido." }
        val text = raw.trim()
        if (TransferDraft.isAddressShapeValid(text)) return Request(text, null, null)
        require(text.startsWith("ethereum:", ignoreCase = true)) { "Use um QR Code de endereço ou pagamento compatível." }
        val body = text.substringAfter(':').removePrefix("pay-")
        require('#' !in body && body.count { it == '?' } <= 1) { "QR Code de pagamento inválido." }
        val target = body.substringBefore('?').split('/')
        require(target.size in 1..2) { "QR Code de pagamento inválido." }
        val addressAndChain = target[0].split('@')
        // A URI without chain_id defaults to Ethereum mainnet in ERC-681, not BSC.
        require(addressAndChain.size == 2 && addressAndChain[1] == chain.chainId.toString()) {
            "Este QR Code usa outra rede. Selecione a rede correspondente antes de ler."
        }
        val address = addressAndChain[0]
        require(TransferDraft.isAddressShapeValid(address)) { "Endereço do QR Code inválido." }
        val parameters = linkedMapOf<String, String>()
        if ('?' in body) {
            val query = body.substringAfter('?')
            require(query.isNotEmpty()) { "QR Code de pagamento inválido." }
            query.split('&').forEach { pair ->
                val entry = pair.split('=', limit = 2)
                require(entry.size == 2) { "QR Code de pagamento inválido." }
                val key = decode(entry[0])
                require(key !in parameters) { "QR Code contém parâmetros repetidos." }
                parameters[key] = decode(entry[1])
            }
        }
        if (target.size == 1) {
            require(parameters.keys.all { it == "value" }) { "Parâmetros de pagamento não suportados." }
            return Request(address, WalletAsset.BNB, parameters["value"]?.let(::units))
        }
        require(target[1] == "transfer" && chain.usdtContract?.equals(address, ignoreCase = true) == true) {
            "Token ou operação do QR Code não suportado nesta rede."
        }
        require(parameters.keys.all { it == "address" || it == "uint256" }) { "Parâmetros de pagamento não suportados." }
        val recipient = parameters["address"].orEmpty()
        require(TransferDraft.isAddressShapeValid(recipient)) { "Endereço do QR Code inválido." }
        return Request(recipient, WalletAsset.USDT, parameters["uint256"]?.let(::units))
    }

    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun units(value: String): BigDecimal {
        require(value.length <= 100 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+]?[0-9]{1,3})?"))) {
            "Quantidade do QR Code inválida."
        }
        val atomic = try { value.toBigDecimal().toBigIntegerExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Quantidade do QR Code inválida.")
        }
        require(atomic > BigInteger.ZERO && atomic.bitLength() <= 256) { "Quantidade do QR Code inválida." }
        return BigDecimal(atomic, 18)
    }
}

/** No public address or private key is treated as a recovery phrase. */
internal object RecoveryQr {
    fun parse(raw: String): List<String> {
        require(raw.length <= 512) { "QR Code de recuperação inválido." }
        val text = raw.trim()
        val phrase = if (text.startsWith("caedralpay:recovery?")) {
            val query = text.removePrefix("caedralpay:recovery?")
            require(query.startsWith("words=") && '&' !in query && '#' !in query) { "QR Code de recuperação inválido." }
            URLDecoder.decode(query.removePrefix("words="), "UTF-8")
        } else text
        val words = phrase.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+"))
        require(words.size == 12 && words.all { it.matches(Regex("[a-z]{1,8}")) }) {
            "Este QR Code não contém uma frase de recuperação de 12 palavras."
        }
        return words
    }
}
