package com.caedralpay.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.time.Instant

internal data class OriginSale(val invoice:OriginInvoice,val firstBlock:Long,val cursor:Long=firstBlock,val hash:String?=null,val expired:Boolean=false)
internal class OriginSales(context:Context) {
    private val store=PrivateRecordStore(context,"origin_sales")
    companion object {private val lock=Any()}
    fun all():List<OriginSale> = synchronized(lock) {
        val raw=store.read() ?: return@synchronized emptyList()
        try {val a=JSONArray(raw.toString(Charsets.UTF_8));require(a.length()<=16);List(a.length()){i ->
            val o=a.getJSONObject(i);val call=OriginCall.decode(JSONObject().put("kind","Pay").put("card","0".repeat(64)).put("expiry",0).put("wire",o.getString("wire")))
            OriginSale(requireNotNull(call.invoice),o.getLong("first"),o.getLong("cursor"),if(o.isNull("hash"))null else o.getString("hash"),o.getBoolean("expired"))
        }}finally{raw.fill(0)}
    }
    fun put(sale:OriginSale):OriginSale=synchronized(lock) {
        val records=all()
        val previous=records.firstOrNull{it.invoice.request.id==sale.invoice.request.id}
        if(previous!=null) {
            require(previous.invoice.wire().contentEquals(sale.invoice.wire()) && previous.firstBlock==sale.firstBlock)
            if(previous.hash!=null || (sale.hash==null && (previous.expired || previous.cursor>sale.cursor)))return@synchronized previous
        }
        val old=records.filterNot{it.invoice.request.id==sale.invoice.request.id}.toMutableList()
        while(old.size>=16){val index=old.indexOfFirst{it.hash!=null || it.expired};require(index>=0){"Conclua as cobranças anteriores."};old.removeAt(index)}
        old+=sale
        val bytes=JSONArray(old.map{ s->JSONObject().put("wire",s.invoice.wire().hex()).put("first",s.firstBlock).put("cursor",s.cursor)
            .put("hash",s.hash ?: JSONObject.NULL).put("expired",s.expired) }).toString().toByteArray()
        try{store.write(bytes)}finally{bytes.fill(0)}
        sale
    }
}
internal class OriginMerchant(private val context:Context,private val gateway:EvmGateway=EvmGateway()) {
    private val sales=OriginSales(context)
    suspend fun draft(chain:BscChain,merchant:String,asset:WalletAsset,amount:String):Pair<CaedralProtocol.Invoice,Long> {
        OriginNetwork.requireAvailable(chain)
        val gross=requireNotNull(TransferDraft.amount(amount)){"Informe um valor válido."}.movePointRight(18).toBigIntegerExact()
        CaedralProtocol.split(gross,18,CaedralProtocol.Tier.Origin,Instant.now())
        return gateway.read(chain){rpc->OriginNetwork.verify(rpc);val block=rpc.quantity("eth_blockNumber").longValueExact();val now=Instant.now().epochSecond
            CaedralProtocol.Invoice(97,OriginNetwork.address,merchant,if(asset==WalletAsset.BNB)CaedralProtocol.ZERO_ADDRESS else requireNotNull(chain.usdtContract),
                gross,CaedralProtocol.newId(),now,now+120) to block
        }
    }
    fun save(invoice:OriginInvoice,first:Long)=sales.put(OriginSale(invoice,first))
    suspend fun poll(sale:OriginSale):OriginSale {
        if(sale.hash!=null || sale.expired)return sale
        return gateway.read(OriginNetwork.chain){rpc->
            val confirmed=(rpc.quantity("eth_blockNumber")-14.toBigInteger()).max(BigInteger.ZERO).longValueExact()
            if(confirmed<sale.cursor)return@read sale
            val end=minOf(confirmed,sale.cursor+199)
            val topic="0x"+OriginAbi.hash("Paid(bytes32,address,address,address,uint256,uint256,uint256)").hex()
            val logs=rpc.call("eth_getLogs",mapOf("address" to sale.invoice.request.settlement,"fromBlock" to sale.cursor.toBigInteger().rpcHex(),
                "toBlock" to end.toBigInteger().rpcHex(),"topics" to listOf(topic,"0x"+sale.invoice.request.id,"0x"+OriginAbi.addr(sale.invoice.request.merchant)))) as? JSONArray ?: error("Recibos indisponíveis")
            require(logs.length()<=1)
            if(logs.length()==1){
                val log=logs.getJSONObject(0);check(!log.optBoolean("removed"))
                val hash=log.getString("transactionHash");val receipt=rpc.call("eth_getTransactionReceipt",hash) as? JSONObject ?: error("Recibo indisponível")
                require(receipt.getString("transactionHash").equals(hash,true) && EvmBalanceCodec.quantity(receipt.getString("status"))==BigInteger.ONE && receipt.getString("to").equals(sale.invoice.request.settlement,true))
                val height=EvmBalanceCodec.quantity(receipt.getString("blockNumber"));require(height<=confirmed.toBigInteger() && height>=sale.firstBlock.toBigInteger())
                val block=rpc.call("eth_getBlockByNumber",height.rpcHex(),false) as? JSONObject ?: error("Bloco indisponível")
                check(block.getString("hash")==receipt.getString("blockHash") && block.getString("hash")==log.getString("blockHash"))
                OriginReceipt.verify(OriginCall(OriginAction.Pay,"0".repeat(64),invoice=sale.invoice,settlement=sale.invoice.request.settlement),receipt.getString("from"),receipt,block.getString("timestamp"))
                sales.put(sale.copy(hash=hash)).also{WalletAlerts.publish(context,WalletAlert(AlertKind.MerchantPaymentConfirmed,"97:$hash"))}
            }else {
                val block=rpc.call("eth_getBlockByNumber",end.toBigInteger().rpcHex(),false) as? JSONObject ?: error("Bloco indisponível")
                sales.put(sale.copy(cursor=end+1,expired=EvmBalanceCodec.quantity(block.getString("timestamp")).longValueExact()>sale.invoice.request.expiresAt))
            }
        }
    }
}
