package com.caedralpay.wallet

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigInteger

internal data class RecurringSale(val offer:RecurringOffer,val firstBlock:Long,val cursor:Long=firstBlock,val hash:String?=null,val subscriptionId:String?=null,val expired:Boolean=false)
internal class RecurringOffers(context:Context) {
    private val store=PrivateRecordStore(context,"recurring_offers")
    companion object{private val lock=Any()}
    fun all():List<RecurringSale> = synchronized(lock){val raw=store.read() ?: return@synchronized emptyList();try{val a=JSONArray(raw.toString(Charsets.UTF_8));List(a.length()){val o=a.getJSONObject(it);RecurringSale(RecurringOffer.decode(o.getString("wire").hexBytes(),false),o.getLong("first"),o.getLong("cursor"),o.optString("hash").ifEmpty{null},o.optString("subscription").ifEmpty{null},o.getBoolean("expired"))}}finally{raw.fill(0)}}
    fun put(s:RecurringSale):RecurringSale=synchronized(lock){
        val rows=all().toMutableList();val i=rows.indexOfFirst{it.offer.id==s.offer.id}
        if(i>=0){val old=rows[i];require(old.offer.wire().contentEquals(s.offer.wire()));if(old.hash!=null || (s.hash==null && (old.expired || old.cursor>s.cursor)))return@synchronized old;rows[i]=s}
        else{while(rows.size>=32){val first=rows.indexOfFirst{it.hash!=null || it.expired};require(first>=0){"Conclua as cobranças em andamento."};rows.removeAt(first)};rows+=s}
        val bytes=JSONArray(rows.map{JSONObject().put("wire",it.offer.wire().hex()).put("first",it.firstBlock).put("cursor",it.cursor).put("hash",it.hash ?: "").put("subscription",it.subscriptionId ?: "").put("expired",it.expired)}).toString().toByteArray();try{store.write(bytes)}finally{bytes.fill(0)};s
    }
}
internal class RecurringMerchant(private val context:Context,private val gateway:EvmGateway=EvmGateway()) {
    private val store=RecurringOffers(context)
    suspend fun draft(charge:MerchantCharge,merchant:String):Pair<RecurringOffer,Long> {
        val offer=RecurringEngine(context,gateway).draft(charge,merchant)
        return offer to gateway.read(OriginNetwork.chain){it.quantity("eth_blockNumber").longValueExact()}
    }
    fun save(o:RecurringOffer,first:Long)=store.put(RecurringSale(o,first))
    suspend fun poll(s:RecurringSale):RecurringSale {
        if(s.hash!=null || s.expired)return s
        return gateway.read(OriginNetwork.chain){rpc->
            val confirmed=(rpc.quantity("eth_blockNumber")-14.toBigInteger()).max(BigInteger.ZERO).longValueExact();if(confirmed<s.cursor)return@read s
            val end=minOf(confirmed,s.cursor+199);val topic="0x"+OriginAbi.hash("Authorized(bytes32,bytes32,address,address,address,uint256,uint64,uint64,uint64)").hex()
            val logs=rpc.call("eth_getLogs",mapOf("address" to s.offer.settlement,"fromBlock" to s.cursor.toBigInteger().rpcHex(),"toBlock" to end.toBigInteger().rpcHex(),"topics" to listOf(topic,"0x"+s.offer.id))) as JSONArray
            require(logs.length()<=1)
            if(logs.length()==1){
                val log=logs.getJSONObject(0);require(!log.optBoolean("removed"));val hash=log.getString("transactionHash");val receipt=rpc.call("eth_getTransactionReceipt",hash) as JSONObject
                require(receipt.getString("transactionHash").equals(hash,true) && receipt.getString("to").equals(s.offer.settlement,true) && EvmBalanceCodec.quantity(receipt.getString("status"))==BigInteger.ONE)
                val height=EvmBalanceCodec.quantity(receipt.getString("blockNumber"));require(height<=confirmed.toBigInteger())
                val block=rpc.call("eth_getBlockByNumber",height.rpcHex(),false) as JSONObject;require(block.getString("hash")==log.getString("blockHash") && block.getString("hash")==receipt.getString("blockHash"))
                val topics=log.getJSONArray("topics");val id=topics.getString(2).removePrefix("0x");val payer="0x"+topics.getString(3).takeLast(40)
                require(id==RecurringQuote.subscriptionId(payer,s.offer.merchant,s.offer.id))
                RecurringReceipt.verifyCollected(id,s.offer,payer,receipt,block.getString("timestamp"),0)
                store.put(s.copy(hash=hash,subscriptionId=id)).also{WalletAlerts.publish(context,WalletAlert(AlertKind.MerchantPaymentConfirmed,"97:$hash"))}
            }else{val block=rpc.call("eth_getBlockByNumber",end.toBigInteger().rpcHex(),false) as JSONObject;store.put(s.copy(cursor=end+1,expired=EvmBalanceCodec.quantity(block.getString("timestamp")).longValueExact()>s.offer.expiresAt))}
        }
    }
}
internal object RecurringReceipt {
    fun verifyCollected(id:String,o:RecurringOffer,payer:String,receipt:JSONObject,timestamp:String,period:Long){
        val topic="0x"+OriginAbi.hash("Collected(bytes32,address,address,address,uint256,uint256,uint256,uint64)").hex()
        val split=CaedralProtocol.split(o.gross,18,CaedralProtocol.Tier.Origin,java.time.Instant.ofEpochSecond(EvmBalanceCodec.quantity(timestamp).longValueExact()))
        val a=receipt.getJSONArray("logs");require((0 until a.length()).any{i->val l=a.getJSONObject(i);val t=l.optJSONArray("topics") ?: return@any false
            l.optString("address").equals(o.settlement,true) && t.length()==4 && t.optString(0).equals(topic,true) && t.optString(1).equals("0x"+id,true) && t.optString(2).equals("0x"+OriginAbi.addr(o.merchant),true) && t.optString(3).equals("0x"+OriginAbi.addr(payer),true) &&
                l.optString("data").equals("0x"+OriginAbi.addr(o.token)+OriginAbi.word(o.gross)+OriginAbi.word(split.fee)+OriginAbi.word(split.cashback)+OriginAbi.word(period.toBigInteger()),true)
        }){"Não foi possível comprovar o pagamento."}
    }
}
