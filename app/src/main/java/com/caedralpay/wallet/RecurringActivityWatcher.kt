package com.caedralpay.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Foreground activity observer. Collection itself is independent of the Android app. */
internal class RecurringActivityWatcher(private val gateway:EvmGateway=EvmGateway()) {
    suspend fun poll(context:Context,address:String)=gateway.read(OriginNetwork.chain){rpc->
        RecurringNetwork.verify(rpc)
        val store=PrivateRecordStore(context,"recurring_activity")
        val raw=store.read();val cursors=try{raw?.let{JSONObject(it.toString(Charsets.UTF_8))} ?: JSONObject()}finally{raw?.fill(0)}
        val key=RecurringNetwork.config.address.lowercase()+":"+address.lowercase()
        val final=(rpc.quantity("eth_blockNumber")-14.toBigInteger()).max(BigInteger.ZERO).longValueExact()
        val first=cursors.optLong(key,maxOf(0,final-1));if(first>final)return@read
        val end=minOf(final,first+199);val topic="0x"+OriginAbi.hash("Collected(bytes32,address,address,address,uint256,uint256,uint256,uint64)").hex()
        val seen=mutableSetOf<String>()
        for(payer in listOf(true,false)) {
            val topics=if(payer)listOf(topic,null,null,"0x"+OriginAbi.addr(address))else listOf(topic,null,"0x"+OriginAbi.addr(address))
            val logs=rpc.call("eth_getLogs",mapOf("address" to RecurringNetwork.config.address,"fromBlock" to first.toBigInteger().rpcHex(),"toBlock" to end.toBigInteger().rpcHex(),"topics" to topics)) as JSONArray
            require(logs.length()<=1000)
            for(i in 0 until logs.length()){
                val l=logs.getJSONObject(i);require(!l.optBoolean("removed"));val hash=l.getString("transactionHash");if(!seen.add(hash))continue
                val receipt=rpc.call("eth_getTransactionReceipt",hash) as JSONObject
                val height=EvmBalanceCodec.quantity(receipt.getString("blockNumber"));require(height.longValueExact() in first..end)
                val block=rpc.call("eth_getBlockByNumber",height.rpcHex(),false) as JSONObject
                require(receipt.getString("transactionHash").equals(hash,true) && EvmBalanceCodec.quantity(receipt.getString("status"))==BigInteger.ONE && receipt.getString("blockHash")==block.getString("hash") && l.getString("blockHash")==block.getString("hash"))
                val receiptLogs=receipt.getJSONArray("logs");require((0 until receiptLogs.length()).any{j->val r=receiptLogs.getJSONObject(j);r.optString("address").equals(RecurringNetwork.config.address,true) && r.optString("logIndex")==l.optString("logIndex") && r.optString("data")==l.optString("data") && r.getJSONArray("topics").toString()==l.getJSONArray("topics").toString()})
                WalletAlerts.publish(context,WalletAlert(if(payer)AlertKind.CardPurchaseConfirmed else AlertKind.MerchantPaymentConfirmed,"97:$hash"))
            }
        }
        cursors.put(key,end+1);val clear=cursors.toString().toByteArray();try{store.write(clear)}finally{clear.fill(0)}
    }
}
