package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import java.io.File
import java.net.Socket
import java.util.UUID
import java.math.BigInteger
import java.time.Instant

/** Explicit local-chain test, emulator only. No public-chain keys or funds. */
class OriginIntegrationTest {
    @get:Rule val activity=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var context:Context
    private lateinit var gateway:EvmGateway
    private lateinit var engine:OriginEngine
    private lateinit var wallet:String
    private lateinit var payer:WalletAccount
    private lateinit var merchant:WalletAccount
    private lateinit var directory:File
    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("originLocal")=="true")
        val target=instrumentation.targetContext;check(target.packageName=="com.caedralpay.wallet.validation")
        instrumentation.runOnMainSync{activity.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val config=JSONObject(File(target.filesDir,"origin-local-fixture.json").readText())
        OriginNetwork.configureForTest(config.getString("address") to config.getString("runtimeHash"))
        directory=File(target.cacheDir,"origin-${UUID.randomUUID()}").apply{mkdirs()}
        context=object:ContextWrapper(target){override fun getFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${directory.name}-$name",mode)}
        WalletVault.lock();WalletVault.create(context,"Origin fixture",List(11){"abandon"}+"about","123456".toCharArray())
        val first=WalletVault.accountsSnapshot();WalletVault.addAccount(context,first.selectedWalletId,"Merchant")
        val snapshot=WalletVault.accountsSnapshot();wallet=snapshot.selectedWalletId;payer=snapshot.selectedWallet.accounts[0];merchant=snapshot.selectedWallet.accounts[1]
        gateway=EvmGateway(EvmTransport{_,method,params->local(method,params)});engine=OriginEngine(context,gateway)
    }
    @After fun finish(){OriginTapSession.clear();if(BuildConfig.DEBUG)OriginNetwork.configureForTest(null);WalletVault.lock();if(::directory.isInitialized)directory.deleteRecursively()}
    private suspend fun local(method:String,params:List<Any>):Any?=withContext(Dispatchers.IO){
        val body=JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",JSONArray(params)).toString().toByteArray()
        Socket("127.0.0.1",18797).use{s->s.soTimeout=10000;s.getOutputStream().apply{
            write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
            val reply=JSONObject(s.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n"))
            check(!reply.has("error")){"Local RPC rejected $method"};if(reply.isNull("result"))null else reply.get("result")
        }
    }
    private suspend fun submit(call:OriginCall):OriginRecord {
        val q=engine.prepare(call,payer.address)
        val token=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.SignOrigin,wallet,0,q.digest)
        val sent=engine.send(q,wallet,0,token)
        local("anvil_mine",listOf("0xf","0x0"))
        return engine.refresh(sent).also{Assert.assertEquals(TransactionStatus.Confirmed,it.status);Assert.assertNull(it.raw)}
    }
    private suspend fun invoice(asset:WalletAsset):Pair<OriginInvoice,Long> {
        val draft=OriginMerchant(context,gateway).draft(BscChain.Testnet,merchant.address,asset,"1")
        val token=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.SignInvoice,wallet,1,OriginAbi.sha(OriginAbi.invoiceDigest(draft.first).hex()))
        return WalletVault.signOriginInvoice(context,wallet,1,draft.first,token) to draft.second
    }
    private suspend fun assetBalance(address:String,asset:WalletAsset):BigInteger {
        val hex=if(asset==WalletAsset.BNB)local("eth_getBalance",listOf(address,"latest")) as String
        else local("eth_call",listOf(mapOf("to" to OriginNetwork.chain.usdtContract!!,"data" to "0x70a08231"+OriginAbi.addr(address)),"latest")) as String
        return if(asset==WalletAsset.BNB)EvmBalanceCodec.quantity(hex)else EvmBalanceCodec.abiUint(hex)
    }
    @Test fun nativeAndTokenSettlementCardRegenerationAndMerchantReceipt()=runBlocking {
        val card=WalletVault.createOriginCard(context,wallet,0,"Ana","Silva")
        Assert.assertTrue(card.number.isEmpty());Assert.assertTrue(card.securityCode.isEmpty())
        submit(OriginCall(OriginAction.Register,card.id,card.expiresAt,registrationSalt=card.salt));Assert.assertTrue(engine.isActive(card))
        val token=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.ViewCard,wallet,0,card.fingerprint)
        val details=WalletVault.originCardDetails(card,token);Assert.assertEquals(16,details.number.length);Assert.assertEquals(6,details.securityCode.length)
        Assert.assertFalse(File(directory,"origin_cards.bin").readText().contains(details.number))
        // Grants are single use; changing encrypted metadata invalidates the inner AEAD.
        try { WalletVault.originCardDetails(card,token);Assert.fail("Authorization reused") }
        catch(_:IllegalStateException) {} catch(_:IllegalArgumentException) {}
        val altered=OriginCard.decode(card.json().put("name","Altered Name"))
        val reveal=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.ViewCard,wallet,0,card.fingerprint)
        try { WalletVault.originCardDetails(altered,reveal);Assert.fail("Metadata tampering accepted") }
        catch(_:javax.crypto.AEADBadTagException) {}
        for(asset in listOf(WalletAsset.BNB,WalletAsset.USDT)) {
            val (invoice,first)=invoice(asset);invoice.validate()
            // Same signed binary and encrypted/chunked APDU path used by the two real phones.
            OriginTapSession.arm(card)
            lateinit var service:OriginHostService
            instrumentation.runOnMainSync{service=OriginHostService()}
            val hello=service.processCommandApdu(OriginApdu.select,null)
            val sealed=CaedralNfcCrypto.seal(hello.copyOfRange(0,44),invoice.wire())
            Assert.assertEquals(289,sealed.size)
            for(chunk in OriginApdu.chunks(sealed))Assert.assertArrayEquals(OriginApdu.ok,service.processCommandApdu(chunk,null))
            Assert.assertEquals(invoice.request.id,OriginTapSession.request.value!!.invoice.request.id)
            Assert.assertFalse(service.processCommandApdu(OriginApdu.chunks(sealed).last(),null).contentEquals(OriginApdu.ok))
            val merchantEngine=OriginMerchant(context,gateway);val sale=merchantEngine.save(invoice,first)
            if(asset==WalletAsset.USDT)submit(OriginCall(OriginAction.Approve,card.id,invoice=invoice))
            val treasury="0x"+(local("eth_call",listOf(mapOf("to" to OriginNetwork.address,"data" to "0x"+OriginAbi.selector("treasury()")),"latest")) as String).takeLast(40)
            val beforePayer=assetBalance(payer.address,asset)
            val beforeMerchant=assetBalance(merchant.address,asset)
            val beforeTreasury=assetBalance(treasury,asset)
            val paid=submit(OriginCall(OriginAction.Pay,card.id,invoice=invoice))
            val receipt=local("eth_getTransactionReceipt",listOf(paid.hash)) as JSONObject
            val block=local("eth_getBlockByNumber",listOf(receipt.getString("blockNumber"),false)) as JSONObject
            val split=CaedralProtocol.split(invoice.request.gross,18,CaedralProtocol.Tier.Origin,Instant.ofEpochSecond(EvmBalanceCodec.quantity(block.getString("timestamp")).longValueExact()))
            val gas=EvmBalanceCodec.quantity(receipt.getString("gasUsed"))*EvmBalanceCodec.quantity(receipt.getString("effectiveGasPrice"))
            Assert.assertEquals(beforePayer-invoice.request.gross+split.cashback-(if(asset==WalletAsset.BNB)gas else BigInteger.ZERO),assetBalance(payer.address,asset))
            Assert.assertEquals(beforeMerchant+split.merchant,assetBalance(merchant.address,asset))
            Assert.assertEquals(beforeTreasury+split.fee-split.cashback,assetBalance(treasury,asset))
            Assert.assertEquals(BigInteger.ZERO,assetBalance(OriginNetwork.address,asset))
            val confirmed=merchantEngine.poll(sale);Assert.assertEquals(paid.hash,confirmed.hash)
            // A late foreground poll must not overwrite a terminal merchant receipt.
            Assert.assertEquals(paid.hash,OriginSales(context).put(sale.copy(expired=true)).hash)
            val transaction=local("eth_getTransactionByHash",listOf(paid.hash)) as JSONObject
            Assert.assertEquals(OriginNetwork.address.lowercase(),transaction.getString("to").lowercase())
            Assert.assertEquals(paid.quote.call.data,transaction.getString("input"))
            Assert.assertEquals(paid.hash,OriginEngine(context,gateway).history().last().hash)
        }
        val saved=engine.history().first()
        val configured=OriginNetwork.address to OriginNetwork.runtimeHash
        try {
            OriginNetwork.configureForTest(("0x"+"f".repeat(40)) to configured.second)
            val restored=engine.history().first()
            Assert.assertEquals(saved.quote.digest,restored.quote.digest)
            Assert.assertEquals(configured.first.lowercase(),restored.quote.call.to.lowercase())
        } finally { OriginNetwork.configureForTest(configured) }
        val regenerate=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.RegenerateCard,wallet,0,card.fingerprint)
        val next=WalletVault.createOriginCard(context,wallet,0,"Ana","Costa",regenerate)
        Assert.assertNotEquals(next.id,card.id);submit(OriginCall(OriginAction.Register,next.id,next.expiresAt,registrationSalt=next.salt))
        Assert.assertFalse(engine.isActive(card));Assert.assertTrue(engine.isActive(next))
        val replay=invoice(WalletAsset.BNB).first
        try{engine.prepare(OriginCall(OriginAction.Pay,card.id,invoice=replay),payer.address);Assert.fail("Revoked card accepted")}
        catch(_:IllegalArgumentException){}
    }
}
