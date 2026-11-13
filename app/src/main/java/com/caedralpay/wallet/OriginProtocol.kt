package com.caedralpay.wallet

import org.json.JSONObject
import org.json.JSONArray
import java.math.BigInteger
import java.time.Instant
import java.security.MessageDigest

/** Addresses are release configuration, never supplied by an NFC peer. */
internal object OriginNetwork {
    const val previousEnrollmentAddress = "0x4231568495F9A11C35d08220B1ECb6cE1d076C6a"
    private const val deploymentAddress = "0x1c8151E63461F46058E1196E9A321E0e273C9300"
    private const val deploymentHash = "0xb3661e12669ca3f93752c4646991fc947bf76cd26fffde7ee35ce47e23e268e0"
    @Volatile private var testConfig: Pair<String,String>? = null
    val address get() = testConfig?.first ?: deploymentAddress
    val runtimeHash get() = testConfig?.second ?: deploymentHash
    fun configureForTest(value: Pair<String,String>?) { check(BuildConfig.DEBUG); testConfig=value }
    val chain = BscChain.Testnet
    fun requireAvailable(selected: BscChain) {
        require(selected == chain && TransferDraft.isAddressShapeValid(address)) { "Pagamentos Caedral ainda não disponíveis nesta rede." }
    }
    suspend fun verify(rpc: EvmConnection) {
        requireAvailable(rpc.chain)
        val code = rpc.hex("eth_getCode", address, "latest")
        System.loadLibrary("TrustWalletCore")
        require("0x" + wallet.core.jni.Hash.keccak256(code.hexBytes()).hex() == runtimeHash) { "Não foi possível validar a rede Caedral." }
    }
}

internal data class OriginInvoice(val request: CaedralProtocol.Invoice, val signature: String) {
    fun validate(now: Long = Instant.now().epochSecond) {
        require(request.expiresAt>now) { "A cobrança expirou. Solicite uma nova ao lojista." }
        request.validate(now); OriginNetwork.requireAvailable(BscChain.entries.single { it.chainId.toLong() == request.chainId })
        require(request.settlement.equals(OriginNetwork.address, true))
        require(request.token == CaedralProtocol.ZERO_ADDRESS || request.token.equals(OriginNetwork.chain.usdtContract, true))
        require(signature.matches(Regex("[0-9a-fA-F]{130}")))
        System.loadLibrary("TrustWalletCore")
        val raw = signature.hexBytes().also { if ((it[64].toInt() and 255) >= 27) it[64] = ((it[64].toInt() and 255) - 27).toByte() }
        val order=BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16)
        val r=BigInteger(1,raw.copyOfRange(0,32));val s=BigInteger(1,raw.copyOfRange(32,64))
        require(raw[64].toInt() in 0..1 && r>BigInteger.ZERO && r<order && s>BigInteger.ZERO && s<=order/BigInteger.TWO)
        val key = wallet.core.jni.PublicKey.recover(raw, OriginAbi.invoiceDigest(request))
        val recovered = "0x" + wallet.core.jni.Hash.keccak256(key.uncompressed().data().drop(1).toByteArray()).takeLast(20).toByteArray().hex()
        require(recovered.equals(request.merchant, true)) { "Assinatura da cobrança inválida." }
    }
    fun wire(): ByteArray = CaedralProtocol.encode(request, request.issuedAt) + signature.hexBytes()
    companion object {
        const val SIZE = CaedralProtocol.WIRE_SIZE + 65
        fun decode(value: ByteArray): OriginInvoice {
            require(value.size == SIZE)
            return OriginInvoice(CaedralProtocol.decode(value.copyOfRange(0, CaedralProtocol.WIRE_SIZE), Instant.now().epochSecond),
                value.copyOfRange(CaedralProtocol.WIRE_SIZE, value.size).hex()).also { it.validate() }
        }
    }
}

internal object OriginAbi {
    fun hash(text: String): ByteArray { System.loadLibrary("TrustWalletCore"); return wallet.core.jni.Hash.keccak256(text.toByteArray()) }
    fun word(value: BigInteger) = value.toString(16).padStart(64, '0').also { require(value.signum() >= 0 && it.length == 64) }
    fun addr(value: String) = value.drop(2).lowercase().padStart(64, '0').also { require(value.matches(Regex("0x[0-9a-fA-F]{40}"))) }
    fun selector(signature: String) = hash(signature).take(4).toByteArray().hex()
    fun invoiceWords(i: CaedralProtocol.Invoice) = addr(i.merchant) + addr(i.token) + word(i.gross) + i.id + word(i.issuedAt.toBigInteger()) + word(i.expiresAt.toBigInteger())
    fun invoiceDigest(i: CaedralProtocol.Invoice): ByteArray {
        val domain = wallet.core.jni.Hash.keccak256((hash("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)").hex() +
            hash("CaedralOrigin").hex() + hash("1").hex() + word(i.chainId.toBigInteger()) + addr(i.settlement)).hexBytes())
        val data = wallet.core.jni.Hash.keccak256((hash("Invoice(address merchant,address token,uint256 gross,bytes32 id,uint64 issuedAt,uint64 expiresAt)").hex() + invoiceWords(i)).hexBytes())
        return wallet.core.jni.Hash.keccak256(byteArrayOf(0x19, 1) + domain + data)
    }
    fun typedInvoice(i: CaedralProtocol.Invoice): String {
        fun field(n: String,t: String) = JSONObject().put("name", n).put("type", t)
        return JSONObject().put("types", JSONObject()
            .put("EIP712Domain", JSONArray(listOf(field("name","string"),field("version","string"),field("chainId","uint256"),field("verifyingContract","address"))))
            .put("Invoice", JSONArray(listOf(field("merchant","address"),field("token","address"),field("gross","uint256"),field("id","bytes32"),field("issuedAt","uint64"),field("expiresAt","uint64")))))
            .put("primaryType","Invoice").put("domain", JSONObject().put("name","CaedralOrigin").put("version","1").put("chainId",i.chainId).put("verifyingContract",i.settlement))
            .put("message", JSONObject().put("merchant",i.merchant).put("token",i.token).put("gross",i.gross.toString()).put("id","0x"+i.id)
                .put("issuedAt",i.issuedAt).put("expiresAt",i.expiresAt)).toString()
    }
    fun register(id: String, salt:String, expiry: Long) = "0x"+selector("registerCard(bytes32,bytes32,uint64)")+id+salt+word(expiry.toBigInteger())
    fun approve(units: BigInteger,settlement:String=OriginNetwork.address) = "0x095ea7b3"+addr(settlement)+word(units)
    fun pay(invoice: OriginInvoice, cardId: String): String {
        val signature = invoice.signature.lowercase()
        // Six static tuple slots + dynamic bytes offset + card ID. Tail: bytes length and padding.
        return "0x"+selector("pay((address,address,uint256,bytes32,uint64,uint64),bytes,bytes32)")+invoiceWords(invoice.request)+
            word(256.toBigInteger())+cardId+word(65.toBigInteger())+signature.padEnd(192, '0')
    }
    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex()
}
