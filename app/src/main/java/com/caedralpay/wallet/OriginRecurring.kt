package com.caedralpay.wallet

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.math.BigInteger
import java.time.Instant

internal object RecurringNetwork {
    private const val addressValue="0xBB4096a2cE016eCb8306Cb51EB6AbfeA4668a1F4"
    private const val hashValue="0x9713d8b0ce0aacc51e5e2de86b52db7a41b0be55c73cd4e5bcf4e82f5b21a9cf"
    private const val accountValue="0x608b2c74938bd76d510A8DFAE50641f8a67e21C4"
    private const val accountHashValue="0x34017bb5cd93fe69fd04ff920a23dadbdce42d5c7bf34e540f7c2b5527ca4503"
    data class Config(val address:String,val hash:String,val account:String,val accountHash:String)
    @Volatile private var fixture:Config?=null
    val config get()=fixture ?: Config(addressValue,hashValue,accountValue,accountHashValue)
    fun configureForTest(value:Config?){check(BuildConfig.DEBUG);fixture=value}
    fun requireAvailable(chain:BscChain){require(chain==OriginNetwork.chain && TransferDraft.isAddressShapeValid(config.address)){"Recorrência indisponível nesta rede."}}
    suspend fun verify(rpc:EvmConnection){
        requireAvailable(rpc.chain);System.loadLibrary("TrustWalletCore")
        for((address,hash) in listOf(config.address to config.hash,config.account to config.accountHash)) {
            require("0x"+wallet.core.jni.Hash.keccak256(rpc.hex("eth_getCode",address,"latest").hexBytes()).hex()==hash){"Não foi possível validar a cobrança."}
        }
    }
    val delegatedCode get()="0xef0100"+config.account.removePrefix("0x").lowercase()
}

internal data class RecurringOffer(val chainId:Long,val settlement:String,val merchant:String,val token:String,val gross:BigInteger,
    val id:String,val intervalDays:Long,val endAt:Long,val issuedAt:Long,val expiresAt:Long,val signature:String="") {
    fun validate(now:Long=Instant.now().epochSecond,signed:Boolean=true){
        RecurringNetwork.requireAvailable(BscChain.entries.single{it.chainId.toLong()==chainId})
        require(settlement.equals(RecurringNetwork.config.address,true))
        require(TransferDraft.isAddressShapeValid(merchant) && !merchant.equals(settlement,true))
        require(token==CaedralProtocol.ZERO_ADDRESS || token.equals(OriginNetwork.chain.usdtContract,true))
        require(id.matches(Regex("[0-9a-f]{64}")) && id!="0".repeat(64))
        require(intervalDays in 1..3650 && (endAt==0L || endAt>=now))
        require(issuedAt in 0..now && expiresAt>now && expiresAt>issuedAt && expiresAt-issuedAt<=120){"A cobrança expirou."}
        CaedralProtocol.split(gross,18,CaedralProtocol.Tier.Origin,Instant.ofEpochSecond(now))
        if(signed)verifySignature()
    }
    private fun verifySignature(){
        require(signature.matches(Regex("[0-9a-fA-F]{130}")));System.loadLibrary("TrustWalletCore")
        val raw=signature.hexBytes().also{if((it[64].toInt() and 255)>=27)it[64]=((it[64].toInt() and 255)-27).toByte()}
        val order=BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16)
        require(raw[64].toInt() in 0..1 && (BigInteger(1,raw.copyOfRange(0,32))>=BigInteger.ONE && BigInteger(1,raw.copyOfRange(0,32))<order) && BigInteger(1,raw.copyOfRange(32,64)) in BigInteger.ONE..order/BigInteger.TWO)
        val key=wallet.core.jni.PublicKey.recover(raw,digest()).uncompressed().data()
        require(("0x"+wallet.core.jni.Hash.keccak256(key.drop(1).toByteArray()).takeLast(20).toByteArray().hex()).equals(merchant,true)){"Assinatura inválida."}
    }
    fun words()=OriginAbi.addr(merchant)+OriginAbi.addr(token)+OriginAbi.word(gross)+id+OriginAbi.word(intervalDays.toBigInteger())+OriginAbi.word(endAt.toBigInteger())+OriginAbi.word(issuedAt.toBigInteger())+OriginAbi.word(expiresAt.toBigInteger())
    fun digest():ByteArray {
        val domain=wallet.core.jni.Hash.keccak256((OriginAbi.hash("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)").hex()+OriginAbi.hash("CaedralRecurring").hex()+OriginAbi.hash("1").hex()+OriginAbi.word(chainId.toBigInteger())+OriginAbi.addr(settlement)).hexBytes())
        val message=wallet.core.jni.Hash.keccak256((OriginAbi.hash(TYPE).hex()+words()).hexBytes())
        return wallet.core.jni.Hash.keccak256(byteArrayOf(0x19,1)+domain+message)
    }
    fun typed():String {
        fun field(n:String,t:String)=JSONObject().put("name",n).put("type",t)
        val fields=listOf("merchant" to "address","token" to "address","gross" to "uint256","id" to "bytes32","intervalDays" to "uint64","endAt" to "uint64","issuedAt" to "uint64","expiresAt" to "uint64")
        return JSONObject().put("types",JSONObject().put("EIP712Domain",JSONArray(listOf(field("name","string"),field("version","string"),field("chainId","uint256"),field("verifyingContract","address")))).put("Offer",JSONArray(fields.map{field(it.first,it.second)})))
            .put("primaryType","Offer").put("domain",JSONObject().put("name","CaedralRecurring").put("version","1").put("chainId",chainId).put("verifyingContract",settlement))
            .put("message",JSONObject().put("merchant",merchant).put("token",token).put("gross",gross.toString()).put("id","0x"+id).put("intervalDays",intervalDays).put("endAt",endAt).put("issuedAt",issuedAt).put("expiresAt",expiresAt)).toString()
    }
    fun wire():ByteArray {
        val out=ByteArrayOutputStream();DataOutputStream(out).use{d->d.writeInt(0x43445201);d.writeLong(chainId);listOf(settlement,merchant,token).forEach{d.write(it.hexBytes())};d.write(OriginAbi.word(gross).hexBytes());d.write(id.hexBytes());listOf(intervalDays,endAt,issuedAt,expiresAt).forEach(d::writeLong);d.write(signature.hexBytes())};return out.toByteArray()
    }
    companion object {
        const val SIZE=233
        const val TYPE="Offer(address merchant,address token,uint256 gross,bytes32 id,uint64 intervalDays,uint64 endAt,uint64 issuedAt,uint64 expiresAt)"
        fun decode(bytes:ByteArray,fresh:Boolean=true):RecurringOffer {
            require(bytes.size==SIZE)
            val d=DataInputStream(ByteArrayInputStream(bytes));require(d.readInt()==0x43445201)
            fun read(n:Int)=ByteArray(n).also(d::readFully)
            val value=RecurringOffer(d.readLong(),"0x"+read(20).hex(),"0x"+read(20).hex(),"0x"+read(20).hex(),BigInteger(1,read(32)),read(32).hex(),d.readLong(),d.readLong(),d.readLong(),d.readLong(),read(65).hex())
            if(fresh)value.validate();return value
        }
    }
}
internal data class RecurringSubscription(val id:String,val payer:String,val merchant:String,val token:String,val gross:BigInteger,val intervalDays:Long,val endAt:Long,val startedAt:Long,val cardExpiry:Long,val lastPeriod:Long,val cancelled:Boolean) {
    val nextDue get()=startedAt+(lastPeriod+1)*intervalDays*86400
    fun ended(now:Long=Instant.now().epochSecond)=cancelled || now>=cardExpiry || (endAt!=0L && (now>endAt || nextDue>endAt))
}
internal enum class RecurringAction { Authorize,Cancel }
internal data class RecurringQuote(val action:RecurringAction,val from:String,val id:String,val nonce:BigInteger,val gasPrice:BigInteger,val gasLimit:BigInteger,
    val delegate:Boolean,val offer:RecurringOffer?=null,val cardId:String="",val salt:String="",val cardExpiry:Long=0,
    val settlement:String=RecurringNetwork.config.address,val accountImplementation:String=RecurringNetwork.config.account,val created:Long=System.currentTimeMillis()) {
    val fee get()=gasLimit*gasPrice
    val data get()=if(action==RecurringAction.Cancel)"0x"+OriginAbi.selector("cancel(bytes32)")+id else {
        val o=requireNotNull(offer)
        "0x"+OriginAbi.selector("authorize((address,address,uint256,bytes32,uint64,uint64,uint64,uint64),bytes,bytes32,bytes32,uint64)")+o.words()+OriginAbi.word(384.toBigInteger())+cardId+salt+OriginAbi.word(cardExpiry.toBigInteger())+OriginAbi.word(65.toBigInteger())+o.signature.lowercase().padEnd(192,'0')
    }
    val digest get()=OriginAbi.sha("RecurringTx/v1|${action.name}|${from.lowercase()}|${settlement.lowercase()}|$data|$nonce|$gasPrice|$gasLimit|$delegate|${accountImplementation.lowercase()}|$created")
    fun rpc()=mapOf("from" to from,"to" to settlement,"data" to data,"value" to "0x0")
    fun validate(){
        require(settlement.equals(RecurringNetwork.config.address,true) && accountImplementation.equals(RecurringNetwork.config.account,true))
        require(TransferDraft.isAddressShapeValid(from) && id.matches(Regex("[0-9a-f]{64}")))
        require(nonce.signum()>=0 && nonce<(BigInteger.ONE.shiftLeft(64)-BigInteger.ONE) && gasPrice in BigInteger.ONE..BigInteger.TEN.pow(12) && gasLimit in 21000.toBigInteger()..1_500_000.toBigInteger())
        require(System.currentTimeMillis() in created..created+90000){"Atualize a revisão da cobrança."}
        if(action==RecurringAction.Authorize){val o=requireNotNull(offer);o.validate();require(cardExpiry>Instant.now().epochSecond && salt.matches(Regex("[0-9a-f]{64}")))
            require(java.security.MessageDigest.getInstance("SHA-256").digest("CaedralOriginCardId/v1".toByteArray()+from.hexBytes()+salt.hexBytes()).hex()==cardId)
            require(id==subscriptionId(from,o.merchant,o.id))}else require(!delegate && offer==null)
    }
    fun json()=JSONObject().put("action",action.name).put("from",from).put("id",id).put("nonce",nonce.toString()).put("price",gasPrice.toString()).put("gas",gasLimit.toString()).put("delegate",delegate).put("wire",offer?.wire()?.hex() ?: JSONObject.NULL).put("card",cardId).put("salt",salt).put("expiry",cardExpiry).put("settlement",settlement).put("account",accountImplementation).put("created",created)
    companion object {
        fun subscriptionId(payer:String,merchant:String,offerId:String)=OriginAbi.hashBytes((OriginAbi.addr(payer)+OriginAbi.addr(merchant)+offerId).hexBytes()).hex()
        fun decode(o:JSONObject)=RecurringQuote(RecurringAction.valueOf(o.getString("action")),o.getString("from"),o.getString("id"),o.getString("nonce").toBigInteger(),o.getString("price").toBigInteger(),o.getString("gas").toBigInteger(),o.getBoolean("delegate"),if(o.isNull("wire"))null else RecurringOffer.decode(o.getString("wire").hexBytes(),false),o.getString("card"),o.getString("salt"),o.getLong("expiry"),o.getString("settlement"),o.getString("account"),o.getLong("created"))
    }
}
internal data class RecurringRecord(val quote:RecurringQuote,val hash:String,val raw:String?,val status:TransactionStatus){val terminal get()=status==TransactionStatus.Confirmed || status==TransactionStatus.Failed}
internal class RecurringJournal(context:Context) {
    private val store=PrivateRecordStore(context,"recurring_outbox")
    companion object{private val lock=Any()}
    fun all():List<RecurringRecord> = synchronized(lock){val raw=store.read() ?: return@synchronized emptyList();try{val a=JSONArray(raw.toString(Charsets.UTF_8));require(a.length()<=16);List(a.length()){i->val o=a.getJSONObject(i);RecurringRecord(RecurringQuote.decode(o.getJSONObject("q")),o.getString("hash"),if(o.isNull("raw"))null else o.getString("raw"),TransactionStatus.valueOf(o.getString("status")))}}finally{raw.fill(0)}}
    fun put(r:RecurringRecord)=synchronized(lock){
        require(r.hash.matches(Regex("0x[0-9a-f]{64}")));val rows=all().toMutableList();val index=rows.indexOfFirst{it.hash==r.hash}
        if(index>=0){val old=rows[index];require(old.quote.digest==r.quote.digest);if(old.terminal){require(old.status==r.status && r.raw==null);return@synchronized};rows[index]=r}
        else{require(rows.none{!it.terminal && it.quote.from.equals(r.quote.from,true)});while(rows.size>=16){val first=rows.indexOfFirst{it.terminal};require(first>=0);rows.removeAt(first)};rows+=r}
        val bytes=JSONArray(rows.map{JSONObject().put("q",it.quote.json()).put("hash",it.hash).put("raw",it.raw ?: JSONObject.NULL).put("status",it.status.name)}).toString().toByteArray();try{store.write(bytes)}finally{bytes.fill(0)}
    }
}
internal class RecurringEngine(private val context:Context,private val gateway:EvmGateway=EvmGateway()) {
    private val journal=RecurringJournal(context)
    suspend fun draft(charge:MerchantCharge,merchant:String):RecurringOffer {
        charge.validate();RecurringNetwork.requireAvailable(charge.chain)
        return gateway.read(charge.chain){rpc->RecurringNetwork.verify(rpc);val now=Instant.now().epochSecond;RecurringOffer(97,RecurringNetwork.config.address,merchant,if(charge.asset==WalletAsset.BNB)CaedralProtocol.ZERO_ADDRESS else charge.chain.usdtContract!!,charge.gross,CaedralProtocol.newId(),charge.intervalDays.toLong(),charge.endAt,now,now+120).also{it.validate(signed=false)}}
    }
    suspend fun prepare(offer:RecurringOffer,card:OriginCard):RecurringQuote=gateway.read(OriginNetwork.chain){rpc->
        offer.validate();RecurringNetwork.verify(rpc);require(!offer.merchant.equals(card.holder,true))
        val code=rpc.hex("eth_getCode",card.holder,"pending").lowercase();require(code=="0x" || code==RecurringNetwork.delegatedCode){"Esta conta tem outra configuração de autorização."}
        val delegate=code=="0x";val nonce=rpc.quantity("eth_getTransactionCount",card.holder,"pending");val price=(rpc.quantity("eth_gasPrice")*110.toBigInteger()+99.toBigInteger())/100.toBigInteger()
        var q=RecurringQuote(RecurringAction.Authorize,card.holder,RecurringQuote.subscriptionId(card.holder,offer.merchant,offer.id),nonce,price,1_000_000.toBigInteger(),delegate,offer,card.id,card.salt,card.expiresAt)
        funding(rpc,q)
        val estimate=try{if(delegate)rpc.quantity("eth_estimateGas",q.rpc(),"pending",mapOf(card.holder to mapOf("code" to RecurringNetwork.delegatedCode)))else rpc.quantity("eth_estimateGas",q.rpc())}catch(c:CancellationException){throw c}catch(_:Exception){1_000_000.toBigInteger()}
        val limit=if(estimate==1_000_000.toBigInteger())estimate else (estimate*120.toBigInteger()+99.toBigInteger())/100.toBigInteger()+(if(delegate)60_000 else 0).toBigInteger()
        q=q.copy(gasLimit=limit);q.validate();funding(rpc,q);q
    }
    suspend fun prepareCancel(subscription:RecurringSubscription,from:String):RecurringQuote=gateway.read(OriginNetwork.chain){rpc->
        RecurringNetwork.verify(rpc);val current=subscription(rpc,subscription.id);require(current.payer.equals(from,true) && !current.cancelled)
        val price=(rpc.quantity("eth_gasPrice")*110.toBigInteger()+99.toBigInteger())/100.toBigInteger()
        val q=RecurringQuote(RecurringAction.Cancel,from,current.id,rpc.quantity("eth_getTransactionCount",from,"pending"),price,150000.toBigInteger(),false)
        val gas=rpc.quantity("eth_estimateGas",q.rpc())*120.toBigInteger()/100.toBigInteger()
        q.copy(gasLimit=gas).also{it.validate();funding(rpc,it)}
    }
    private suspend fun funding(rpc:EvmConnection,q:RecurringQuote){
        val o=q.offer
        val nativeValue=if(o?.token==CaedralProtocol.ZERO_ADDRESS)o.gross else BigInteger.ZERO
        OriginFunding.requireNative(rpc.quantity("eth_getBalance",q.from,"pending"),nativeValue,q.fee)
        if(o!=null && o.token!=CaedralProtocol.ZERO_ADDRESS)OriginFunding.requireToken(EvmBalanceCodec.abiUint(rpc.hex("eth_call",mapOf("to" to o.token,"data" to EvmBalanceCodec.balanceOfData(q.from)),"pending")),o.gross)
    }
    suspend fun send(q:RecurringQuote,walletId:String,index:Int,token:VaultAuthorization):RecurringRecord=WalletOperationGate.mutex.withLock {
        q.validate();require(journal.all().none{!it.terminal && it.quote.from.equals(q.from,true)} && OriginJournal(context).all().none{!it.terminal && it.quote.from.equals(q.from,true)} && TransactionJournal(context).all().none{!it.terminal && it.quote.chain==OriginNetwork.chain && it.quote.from.equals(q.from,true)}){"Há um pagamento em andamento."}
        gateway.read(OriginNetwork.chain){rpc->RecurringNetwork.verify(rpc);funding(rpc,q);require(rpc.quantity("eth_getTransactionCount",q.from,"pending")==q.nonce && rpc.quantity("eth_gasPrice")<=q.gasPrice)
            if(q.action==RecurringAction.Authorize){val code=rpc.hex("eth_getCode",q.from,"pending").lowercase();require(if(q.delegate)code=="0x" else code==RecurringNetwork.delegatedCode)}else require(subscription(rpc,q.id).payer.equals(q.from,true))}
        withContext(NonCancellable+Dispatchers.IO){val signed=WalletVault.signRecurring(context,walletId,index,q,token);val r=RecurringRecord(q,signed.first,signed.second,TransactionStatus.Signed);journal.put(r);broadcast(r)}
    }
    private suspend fun broadcast(r:RecurringRecord):RecurringRecord=try{gateway.read(OriginNetwork.chain){rpc->require(rpc.hex("eth_sendRawTransaction",requireNotNull(r.raw)).equals(r.hash,true))};r.copy(status=TransactionStatus.Pending).also(journal::put)}catch(c:CancellationException){throw c}catch(_:Exception){r}
    suspend fun resume(r:RecurringRecord):RecurringRecord=WalletOperationGate.mutex.withLock{val current=journal.all().first{it.hash==r.hash};if(current.terminal)current else broadcast(current)}
    suspend fun refresh(r:RecurringRecord):RecurringRecord {
        val saved=journal.all().first{it.hash==r.hash};require(saved.quote.digest==r.quote.digest);if(saved.terminal)return saved
        return gateway.read(OriginNetwork.chain){rpc->
            val receipt=rpc.call("eth_getTransactionReceipt",r.hash) as? JSONObject ?: return@read r
            require(receipt.getString("transactionHash").equals(r.hash,true) && receipt.getString("from").equals(r.quote.from,true) && receipt.getString("to").equals(r.quote.settlement,true))
            val height=EvmBalanceCodec.quantity(receipt.getString("blockNumber"));if(rpc.quantity("eth_blockNumber")<height+14.toBigInteger())return@read r
            val block=rpc.call("eth_getBlockByNumber",height.rpcHex(),false) as JSONObject;require(block.getString("hash")==receipt.getString("blockHash"))
            val status=EvmBalanceCodec.quantity(receipt.getString("status"));require(status==BigInteger.ZERO || status==BigInteger.ONE)
            if(status==BigInteger.ONE){
                verifyEvent(r.quote,receipt)
                if(r.quote.action==RecurringAction.Authorize)RecurringReceipt.verifyCollected(r.quote.id,r.quote.offer!!,r.quote.from,receipt,block.getString("timestamp"),0)
                if(r.quote.action==RecurringAction.Authorize && r.quote.delegate)require(rpc.hex("eth_getCode",r.quote.from,"latest").equals(RecurringNetwork.delegatedCode,true))
            }
            r.copy(raw=null,status=if(status==BigInteger.ONE)TransactionStatus.Confirmed else TransactionStatus.Failed).also{journal.put(it);if(it.status==TransactionStatus.Confirmed && it.quote.action==RecurringAction.Authorize)WalletAlerts.publish(context,WalletAlert(AlertKind.CardPurchaseConfirmed,"97:${it.hash}"))}
        }
    }
    private fun verifyEvent(q:RecurringQuote,receipt:JSONObject){
        val topic="0x"+OriginAbi.hash(if(q.action==RecurringAction.Cancel)"Cancelled(bytes32,address)" else "Authorized(bytes32,bytes32,address,address,address,uint256,uint64,uint64,uint64)").hex()
        val logs=receipt.getJSONArray("logs")
        require((0 until logs.length()).any{i->val log=logs.getJSONObject(i);val t=log.optJSONArray("topics") ?: return@any false
            if(!log.optString("address").equals(q.settlement,true) || !t.optString(0).equals(topic,true))return@any false
            if(q.action==RecurringAction.Cancel)t.length()==3 && t.optString(1).equals("0x"+q.id,true) && t.optString(2).equals("0x"+OriginAbi.addr(q.from),true)
            else {val o=q.offer!!;t.length()==4 && t.optString(1).equals("0x"+o.id,true) && t.optString(2).equals("0x"+q.id,true) && t.optString(3).equals("0x"+OriginAbi.addr(q.from),true) && log.optString("data").equals("0x"+OriginAbi.addr(o.merchant)+OriginAbi.addr(o.token)+OriginAbi.word(o.gross)+OriginAbi.word(o.intervalDays.toBigInteger())+OriginAbi.word(o.endAt.toBigInteger())+OriginAbi.word(q.cardExpiry.toBigInteger()),true)}
        }){"Não foi possível comprovar a autorização."}
    }
    suspend fun list(payer:String,offset:Int=0):List<RecurringSubscription> = gateway.read(OriginNetwork.chain){rpc->
        RecurringNetwork.verify(rpc)
        val data="0x"+OriginAbi.selector("subscriptionIds(address,uint256,uint256)")+OriginAbi.addr(payer)+OriginAbi.word(offset.toBigInteger())+OriginAbi.word(25.toBigInteger())
        val hex=rpc.hex("eth_call",mapOf("to" to RecurringNetwork.config.address,"data" to data),"latest").removePrefix("0x")
        require(hex.length>=128 && BigInteger(hex.take(64),16)==32.toBigInteger());val count=BigInteger(hex.substring(64,128),16).intValueExact();require(count in 0..25 && hex.length==128+count*64)
        List(count){subscription(rpc,hex.substring(128+it*64,192+it*64))}
    }
    private suspend fun subscription(rpc:EvmConnection,id:String):RecurringSubscription {
        val hex=rpc.hex("eth_call",mapOf("to" to RecurringNetwork.config.address,"data" to "0x"+OriginAbi.selector("subscriptions(bytes32)")+id),"latest").removePrefix("0x");require(hex.length==640)
        val w=hex.chunked(64);fun n(i:Int)=BigInteger(w[i],16).longValueExact()
        return RecurringSubscription(id,"0x"+w[0].takeLast(40),"0x"+w[1].takeLast(40),"0x"+w[2].takeLast(40),BigInteger(w[3],16),n(4),n(5),n(6),n(7),n(8),n(9)==1L)
    }
    fun history()=journal.all()
}
