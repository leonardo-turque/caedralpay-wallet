package com.caedralpay.wallet

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

internal object WalletOperationGate { val mutex = Mutex() }
internal enum class OriginAction { Register, ResetApproval, Approve, Pay }
internal data class OriginCall(val kind: OriginAction, val cardId: String, val expiry: Long = 0, val invoice: OriginInvoice? = null, val registrationSalt:String="",val settlement:String=OriginNetwork.address) {
    val token get() = invoice?.request?.token ?: CaedralProtocol.ZERO_ADDRESS
    val to get() = if(kind==OriginAction.Approve || kind==OriginAction.ResetApproval) requireNotNull(OriginNetwork.chain.usdtContract) else settlement
    val value get() = if(kind==OriginAction.Pay && token==CaedralProtocol.ZERO_ADDRESS) requireNotNull(invoice).request.gross else BigInteger.ZERO
    val data get() = when(kind) {
        OriginAction.Register -> OriginAbi.register(cardId,registrationSalt,expiry)
        OriginAction.ResetApproval -> OriginAbi.approve(BigInteger.ZERO,settlement)
        OriginAction.Approve -> OriginAbi.approve(requireNotNull(invoice).request.gross,settlement)
        OriginAction.Pay -> OriginAbi.pay(requireNotNull(invoice),cardId)
    }
    fun validate() {
        require(cardId.matches(Regex("[0-9a-f]{64}")) && cardId != "0".repeat(64)); OriginNetwork.requireAvailable(OriginNetwork.chain)
        require(settlement.equals(OriginNetwork.address,true))
        if(invoice!=null)require(invoice.request.settlement.equals(settlement,true))
        if(kind==OriginAction.Register) require(expiry>Instant.now().epochSecond && registrationSalt.matches(Regex("[0-9a-f]{64}")))
        else { requireNotNull(invoice).validate(); if(kind==OriginAction.Approve || kind==OriginAction.ResetApproval) require(token!=CaedralProtocol.ZERO_ADDRESS) }
    }
    fun json() = JSONObject().put("kind",kind.name).put("card",cardId).put("expiry",expiry).put("salt",registrationSalt).put("settlement",settlement).put("wire",invoice?.wire()?.hex() ?: JSONObject.NULL)
    companion object {
        fun decode(o:JSONObject): OriginCall {
            val wire=if(o.isNull("wire"))null else o.getString("wire").hexBytes()
            // Historical invoices are decoded without freshness checks; freshness is mandatory before signing.
            val invoice=wire?.let { val base=it.copyOfRange(0,CaedralProtocol.WIRE_SIZE)
                val issued=java.nio.ByteBuffer.wrap(base.copyOfRange(136,144)).long
                OriginInvoice(CaedralProtocol.decode(base,issued),it.copyOfRange(CaedralProtocol.WIRE_SIZE,it.size).hex()) }
            return OriginCall(OriginAction.valueOf(o.getString("kind")),o.getString("card"),o.getLong("expiry"),invoice,o.optString("salt",""),o.optString("settlement",invoice?.request?.settlement ?: OriginNetwork.previousEnrollmentAddress))
        }
    }
}
internal data class OriginQuote(val call: OriginCall,val from: String,val nonce: BigInteger,val gasPrice: BigInteger,val gasLimit: BigInteger,
    val created: Long=System.currentTimeMillis(),val id:String=UUID.randomUUID().toString()) {
    val digest get()=OriginAbi.sha(listOf(id,from.lowercase(),call.to.lowercase(),call.data,call.value,nonce,gasPrice,gasLimit,created).joinToString("|"))
    val fee get()=gasPrice*gasLimit
    fun rpc()=mapOf("from" to from,"to" to call.to,"value" to call.value.rpcHex(),"data" to call.data)
    fun validate() {
        call.validate()
        if(call.kind==OriginAction.Register)require(java.security.MessageDigest.getInstance("SHA-256").digest("CaedralOriginCardId/v1".toByteArray()+from.hexBytes()+call.registrationSalt.hexBytes()).hex()==call.cardId)
        require(TransferDraft.isAddressShapeValid(from) && nonce.signum()>=0 && nonce.bitLength()<=64)
        require(gasPrice>BigInteger.ZERO && gasPrice<=BigInteger.TEN.pow(12) && gasLimit in 21000.toBigInteger()..2000000.toBigInteger())
        require(System.currentTimeMillis() in created..created+90000) { "A revisão expirou. Atualize as taxas." }
    }
}
internal data class OriginRecord(val quote: OriginQuote,val hash:String,val raw:String?,val status:TransactionStatus) {
    val terminal get()=status==TransactionStatus.Confirmed || status==TransactionStatus.Failed
}
internal class OriginJournal(context:Context) {
    private val store=PrivateRecordStore(context,"origin_outbox")
    companion object { private val lock=Any() }
    fun all():List<OriginRecord> = synchronized(lock) {
        val raw=store.read() ?: return@synchronized emptyList()
        try { val list=JSONArray(raw.toString(Charsets.UTF_8)); require(list.length()<=16); List(list.length()){ index ->
            val o=list.getJSONObject(index); val q=o.getJSONObject("quote")
            val quote=OriginQuote(OriginCall.decode(q.getJSONObject("call")),q.getString("from"),q.getString("nonce").toBigInteger(),q.getString("price").toBigInteger(),q.getString("gas").toBigInteger(),q.getLong("created"),q.getString("id"))
            OriginRecord(quote,o.getString("hash"),if(o.isNull("raw"))null else o.getString("raw"),TransactionStatus.valueOf(o.getString("status")))
        } } finally { raw.fill(0) }
    }
    fun put(record:OriginRecord) = synchronized(lock) {
        require(record.hash.matches(Regex("0x[0-9a-f]{64}")))
        val rows=all().toMutableList(); val index=rows.indexOfFirst { it.hash==record.hash }
        if(index>=0) { check(rows[index].quote.digest==record.quote.digest); check(!rows[index].terminal || rows[index]==record); rows[index]=record }
        else {
            check(rows.none { !it.terminal && it.quote.from.equals(record.quote.from,true) })
            while(rows.size>=16) { val oldest=rows.indexOfFirst{it.terminal}; check(oldest>=0); rows.removeAt(oldest) }; rows+=record
        }
        val bytes=JSONArray(rows.map { r -> val q=r.quote; JSONObject().put("quote",JSONObject().put("call",q.call.json()).put("from",q.from)
            .put("nonce",q.nonce.toString()).put("price",q.gasPrice.toString()).put("gas",q.gasLimit.toString()).put("created",q.created).put("id",q.id))
            .put("hash",r.hash).put("raw",r.raw ?: JSONObject.NULL).put("status",r.status.name) }).toString().toByteArray()
        try { store.write(bytes) } finally { bytes.fill(0) }
    }
}
internal class OriginEngine(private val context:Context,private val gateway:EvmGateway=EvmGateway()) {
    private val journal=OriginJournal(context)
    suspend fun prepare(call:OriginCall,from:String):OriginQuote = gateway.read(OriginNetwork.chain) { rpc ->
        call.validate(); OriginNetwork.verify(rpc)
        if(call.kind!=OriginAction.Register) {
            val state=cardState(rpc,from)
            require(state.first==call.cardId && state.second>Instant.now().epochSecond) { "Ative ou atualize este cartão antes de pagar." }
            require(!call.invoice!!.request.merchant.equals(from,true)) { "Escolha uma conta de lojista diferente." }
        }
        require(rpc.quantity("eth_getBalance",from,"pending")>call.value) { "Saldo de tBNB insuficiente para o valor e a taxa de rede." }
        verifyTokens(rpc,call,from)
        val nonce=rpc.quantity("eth_getTransactionCount",from,"pending")
        val price=(rpc.quantity("eth_gasPrice")*110.toBigInteger()+99.toBigInteger())/100.toBigInteger()
        val draft=OriginQuote(call,from,nonce,price,21000.toBigInteger())
        val gas=(rpc.quantity("eth_estimateGas",draft.rpc())*120.toBigInteger()+99.toBigInteger())/100.toBigInteger()
        draft.copy(gasLimit=gas).also { q -> q.validate(); require(rpc.quantity("eth_getBalance",from,"pending")>=q.call.value+q.fee) { "Saldo insuficiente para o valor e a taxa de rede." } }
    }
    suspend fun isActive(card:OriginCard):Boolean = gateway.read(OriginNetwork.chain) { rpc ->
        OriginNetwork.verify(rpc); val state=cardState(rpc,card.holder); state.first==card.id && state.second>Instant.now().epochSecond
    }
    private suspend fun verifyTokens(rpc:EvmConnection,call:OriginCall,from:String) {
        if(call.kind!=OriginAction.Register && call.token!=CaedralProtocol.ZERO_ADDRESS) {
            val balance=EvmBalanceCodec.abiUint(rpc.hex("eth_call",mapOf("to" to requireNotNull(OriginNetwork.chain.usdtContract),"data" to EvmBalanceCodec.balanceOfData(from)),"pending"))
            require(balance>=call.invoice!!.request.gross){"Saldo de USDT insuficiente para esta compra."}
        }
    }
    private suspend fun cardState(rpc:EvmConnection,holder:String):Pair<String,Long> {
        val result=rpc.hex("eth_call",mapOf("to" to OriginNetwork.address,"data" to "0x"+OriginAbi.selector("cards(address)")+OriginAbi.addr(holder)),"latest").removePrefix("0x")
        require(result.length==128); return result.take(64) to BigInteger(result.takeLast(64),16).longValueExact()
    }
    suspend fun allowance(from:String):BigInteger = gateway.read(OriginNetwork.chain) { rpc ->
        EvmBalanceCodec.abiUint(rpc.hex("eth_call",mapOf("to" to requireNotNull(OriginNetwork.chain.usdtContract),
            "data" to "0xdd62ed3e"+OriginAbi.addr(from)+OriginAbi.addr(OriginNetwork.address)),"pending"))
    }
    suspend fun send(q:OriginQuote,walletId:String,index:Int,token:VaultAuthorization):OriginRecord=WalletOperationGate.mutex.withLock {
        q.validate()
        check(journal.all().none{!it.terminal && it.quote.from.equals(q.from,true)} && TransactionJournal(context).all().none{!it.terminal && it.quote.chain==OriginNetwork.chain && it.quote.from.equals(q.from,true)}) { "Há uma operação anterior em acompanhamento." }
        gateway.read(OriginNetwork.chain) { rpc ->
            OriginNetwork.verify(rpc); verifyTokens(rpc,q.call,q.from)
            require(rpc.quantity("eth_getTransactionCount",q.from,"pending")==q.nonce)
            require(rpc.quantity("eth_gasPrice")<=q.gasPrice && rpc.quantity("eth_estimateGas",q.rpc())<=q.gasLimit)
            require(rpc.quantity("eth_getBalance",q.from,"pending")>=q.call.value+q.fee)
        }
        withContext(NonCancellable+Dispatchers.IO) {
            val signed=WalletVault.signOriginOperation(context,walletId,index,q,token)
            val record=OriginRecord(q,signed.first,signed.second,TransactionStatus.Signed); journal.put(record); broadcast(record)
        }
    }
    suspend fun resume(record:OriginRecord):OriginRecord=WalletOperationGate.mutex.withLock {
        val saved=journal.all().first{it.hash==record.hash}; if(saved.terminal)saved else broadcast(saved)
    }
    private suspend fun broadcast(r:OriginRecord):OriginRecord {
        return try {
            gateway.read(OriginNetwork.chain){rpc->require(rpc.hex("eth_sendRawTransaction",requireNotNull(r.raw)).equals(r.hash,true))}
            r.copy(status=TransactionStatus.Pending).also{journal.put(it)}
        } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){r}
    }
    suspend fun refresh(r:OriginRecord):OriginRecord {
        if(r.terminal)return r
        return gateway.read(OriginNetwork.chain) { rpc ->
            val receipt=rpc.call("eth_getTransactionReceipt",r.hash) as? JSONObject ?: return@read r
            check(receipt.getString("transactionHash").equals(r.hash,true) && receipt.getString("from").equals(r.quote.from,true) && receipt.getString("to").equals(r.quote.call.to,true))
            val height=EvmBalanceCodec.quantity(receipt.getString("blockNumber"))
            if(rpc.quantity("eth_blockNumber")<height+14.toBigInteger())return@read r
            val block=rpc.call("eth_getBlockByNumber",height.rpcHex(),false) as? JSONObject ?: error("Bloco indisponível")
            check(block.getString("hash")==receipt.getString("blockHash"))
            val status=EvmBalanceCodec.quantity(receipt.getString("status"));require(status==BigInteger.ZERO || status==BigInteger.ONE)
            val success=status==BigInteger.ONE
            if(success) OriginReceipt.verify(r.quote.call,r.quote.from,receipt,block.getString("timestamp"))
            val final=r.copy(status=if(success)TransactionStatus.Confirmed else TransactionStatus.Failed,raw=null)
            if(success && r.quote.call.kind==OriginAction.Register) {
                val pending=OriginCardStore(context,true).findById(r.quote.call.cardId)
                if(pending!=null)OriginCardStore(context).save(pending)
            }
            journal.put(final)
            if(success && r.quote.call.kind==OriginAction.Pay) WalletAlerts.publish(context,WalletAlert(AlertKind.CardPurchaseConfirmed,"97:${r.hash}"))
            final
        }
    }
    fun history()=journal.all()
}
internal object OriginReceipt {
    fun verify(call:OriginCall,payer:String,receipt:JSONObject,timestamp:String) {
        val logs=receipt.getJSONArray("logs")
        val topic=when(call.kind) {
            OriginAction.Register->OriginAbi.hash("CardRegistered(address,bytes32,uint64)").hex()
            OriginAction.ResetApproval,OriginAction.Approve->OriginAbi.hash("Approval(address,address,uint256)").hex()
            OriginAction.Pay->OriginAbi.hash("Paid(bytes32,address,address,address,uint256,uint256,uint256)").hex()
        }
        require((0 until logs.length()).any { index ->
            val log=logs.getJSONObject(index);val topics=log.optJSONArray("topics") ?: return@any false
            if(!log.optString("address").equals(call.to,true) || !topics.optString(0).equals("0x"+topic,true))return@any false
            when(call.kind) {
                OriginAction.Register->topics.length()==3 && topics.optString(1).equals("0x"+OriginAbi.addr(payer),true) &&
                    topics.optString(2).equals("0x"+call.cardId,true) && log.optString("data").equals("0x"+OriginAbi.word(call.expiry.toBigInteger()),true)
                OriginAction.ResetApproval,OriginAction.Approve->topics.length()==3 && topics.optString(1).equals("0x"+OriginAbi.addr(payer),true) &&
                    topics.optString(2).equals("0x"+OriginAbi.addr(call.settlement),true) && log.optString("data").equals("0x"+OriginAbi.word(if(call.kind==OriginAction.ResetApproval)BigInteger.ZERO else call.invoice!!.request.gross),true)
                OriginAction.Pay->{ val i=call.invoice!!.request; val split=CaedralProtocol.split(i.gross,18,CaedralProtocol.Tier.Origin,Instant.ofEpochSecond(EvmBalanceCodec.quantity(timestamp).longValueExact()))
                    topics.length()==4 && topics.optString(1).equals("0x"+i.id,true) && topics.optString(2).equals("0x"+OriginAbi.addr(i.merchant),true) &&
                        topics.optString(3).equals("0x"+OriginAbi.addr(payer),true) && log.optString("data").equals("0x"+OriginAbi.addr(i.token)+OriginAbi.word(i.gross)+OriginAbi.word(split.fee)+OriginAbi.word(split.cashback),true)
                }
            }
        }) { "O recibo não comprova a operação Caedral." }
    }
}
