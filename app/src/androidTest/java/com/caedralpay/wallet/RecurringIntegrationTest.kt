package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import java.io.File
import java.net.Socket
import java.util.UUID
import java.math.BigInteger

/** Actual WalletCore type-4 signing and EVM execution; only the local emulator fixture. */
class RecurringIntegrationTest {
    private val target=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context:Context
    private lateinit var directory:File
    private lateinit var engine:RecurringEngine
    private lateinit var gateway:EvmGateway
    private lateinit var wallet:String
    private lateinit var card:OriginCard
    private lateinit var payer:WalletAccount
    private lateinit var merchant:WalletAccount
    private var snapshot:String?=null
    private suspend fun rpc(method:String,params:List<Any> = emptyList()):Any?=withContext(Dispatchers.IO){
        val body=JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",JSONArray(params)).toString().toByteArray()
        Socket("127.0.0.1",18797).use{s->s.soTimeout=10000;s.getOutputStream().apply{write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
            val reply=JSONObject(s.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n"));check(!reply.has("error")){"RPC $method: ${reply.optJSONObject("error")?.optString("message")}"};if(reply.isNull("result"))null else reply.get("result")}
    }
    @Before fun setup()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("originLocal")=="true")
        check(target.packageName=="com.caedralpay.wallet.validation")
        val fixture=JSONObject(File(target.filesDir,"origin-local-fixture.json").readText());val c=fixture.getJSONObject("recurring")
        OriginNetwork.configureForTest(fixture.getString("address") to fixture.getString("runtimeHash"))
        RecurringNetwork.configureForTest(RecurringNetwork.Config(c.getString("address"),c.getString("hash"),c.getString("account"),c.getString("accountHash")))
        snapshot=rpc("evm_snapshot") as String
        directory=File(target.cacheDir,"recurring-${UUID.randomUUID()}").apply{mkdirs()}
        context=object:ContextWrapper(target){override fun getFilesDir()=directory;override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${directory.name}-$name",mode)}
        WalletVault.lock();WalletVault.create(context,"Recurring fixture",List(11){"abandon"}+"about","123456".toCharArray())
        wallet=WalletVault.accountsSnapshot().selectedWalletId;WalletVault.addAccount(context,wallet,"Merchant")
        val accounts=WalletVault.accountsSnapshot().selectedWallet.accounts;payer=accounts[0];merchant=accounts[1]
        card=WalletVault.createOriginCard(context,wallet,0,"Ana","Silva")
        gateway=EvmGateway(EvmTransport{_,method,params->rpc(method,params)});engine=RecurringEngine(context,gateway)
    }
    @After fun cleanup()=runBlocking {
        snapshot?.let{rpc("evm_revert",listOf(it))};RecurringNetwork.configureForTest(null);OriginNetwork.configureForTest(null)
        WalletVault.lock();if(::directory.isInitialized)directory.deleteRecursively();Unit
    }
    private suspend fun offer(asset:WalletAsset):RecurringOffer {
        val o=engine.draft(MerchantCharge(BscChain.Testnet,asset,"1",MerchantPaymentKind.Recurring,1),merchant.address)
        val t=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.SignInvoice,wallet,1,OriginAbi.sha(o.digest().hex()))
        return WalletVault.signRecurringOffer(context,wallet,1,o,t)
    }
    private suspend fun sign(q:RecurringQuote):RecurringRecord {
        val token=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.SignOrigin,wallet,0,q.digest)
        val r=engine.send(q,wallet,0,token);rpc("anvil_mine",listOf("0xf","0x0"))
        return engine.refresh(r).also{Assert.assertEquals(TransactionStatus.Confirmed,it.status);Assert.assertNull(it.raw)}
    }
    private suspend fun collect(id:String):String {
        val operator=(rpc("eth_accounts") as JSONArray).getString(0)
        return rpc("eth_sendTransaction",listOf(mapOf("from" to operator,"to" to RecurringNetwork.config.address,"data" to "0x"+OriginAbi.selector("collect(bytes32)")+id,"gas" to "0x7a120"))) as String
    }
    private suspend fun rejected(id:String) {
        val hash=collect(id);rpc("anvil_mine",listOf("0x1","0x0"))
        Assert.assertEquals("0x0",(rpc("eth_getTransactionReceipt",listOf(hash)) as JSONObject).getString("status"))
    }
    private suspend fun balance(who:String,asset:WalletAsset):BigInteger=if(asset==WalletAsset.BNB)EvmBalanceCodec.quantity(rpc("eth_getBalance",listOf(who,"latest")) as String)
        else EvmBalanceCodec.abiUint(rpc("eth_call",listOf(mapOf("to" to OriginNetwork.chain.usdtContract!!,"data" to EvmBalanceCodec.balanceOfData(who)),"latest")) as String)
    @Test fun nativeAutomaticDebitKeepsOriginalAccountAndCanBeCancelled()=runBlocking{exercise(WalletAsset.BNB)}
    @Test fun tokenAutomaticDebitNeedsNoAllowanceAndCanBeCancelled()=runBlocking{exercise(WalletAsset.USDT)}
    private suspend fun exercise(asset:WalletAsset) {
        val o=offer(asset)
        OriginTapSession.arm(card);lateinit var service:OriginHostService
        InstrumentationRegistry.getInstrumentation().runOnMainSync{service=OriginHostService()}
        val hello=service.processCommandApdu(OriginApdu.select,null)
        val envelope=CaedralNfcCrypto.seal(hello.copyOfRange(0,44),o.wire());Assert.assertEquals(305,envelope.size)
        OriginApdu.chunks(envelope).forEach{Assert.assertArrayEquals(OriginApdu.ok,service.processCommandApdu(it,null))}
        Assert.assertEquals(o.id,OriginTapSession.request.value!!.recurring!!.id);OriginTapSession.clear()
        val first=sign(engine.prepare(o,card))
        val tx=rpc("eth_getTransactionByHash",listOf(first.hash)) as JSONObject
        Assert.assertEquals("0x4",tx.getString("type"))
        val authorization=tx.getJSONArray("authorizationList").getJSONObject(0)
        Assert.assertEquals(first.quote.nonce+BigInteger.ONE,EvmBalanceCodec.quantity(authorization.getString("nonce")))
        Assert.assertEquals("0x61",authorization.getString("chainId"))
        Assert.assertEquals(RecurringNetwork.delegatedCode,rpc("eth_getCode",listOf(payer.address,"latest")))
        val saved=engine.list(payer.address).first();Assert.assertEquals(first.quote.id,saved.id)
        // Recipient accepts ordinary native transfers with calldata after delegation.
        val operator=(rpc("eth_accounts") as JSONArray).getString(0)
        rpc("eth_sendTransaction",listOf(mapOf("from" to operator,"to" to payer.address,"value" to "0x1","data" to "0x1234")))
        rpc("anvil_mine",listOf("0x1","0x0"))
        if(asset==WalletAsset.USDT)Assert.assertEquals(BigInteger.ZERO,EvmBalanceCodec.abiUint(rpc("eth_call",listOf(mapOf("to" to o.token,"data" to "0xdd62ed3e"+OriginAbi.addr(payer.address)+OriginAbi.addr(o.settlement)),"latest")) as String))
        val oldBalance=balance(payer.address,asset);val merchantBalance=balance(merchant.address,asset)
        // The payer vault is locked; only the independent operator signs and pays gas.
        WalletVault.lock();rpc("evm_increaseTime",listOf(86401));rpc("evm_mine")
        val hash=collect(saved.id);rpc("anvil_mine",listOf("0x1","0x0"));val receipt=rpc("eth_getTransactionReceipt",listOf(hash)) as JSONObject
        Assert.assertEquals("0x1",receipt.getString("status"))
        val block=rpc("eth_getBlockByNumber",listOf(receipt.getString("blockNumber"),false)) as JSONObject
        RecurringReceipt.verifyCollected(saved.id,o,payer.address,receipt,block.getString("timestamp"),1)
        val split=CaedralProtocol.split(o.gross,18,CaedralProtocol.Tier.Origin,java.time.Instant.ofEpochSecond(EvmBalanceCodec.quantity(block.getString("timestamp")).longValueExact()))
        Assert.assertEquals(oldBalance-o.gross+split.cashback,balance(payer.address,asset));Assert.assertEquals(merchantBalance+split.merchant,balance(merchant.address,asset))
        rejected(saved.id)
        WalletVault.unlockWithPin(context,"123456".toCharArray())
        val subscription=engine.list(payer.address).first();sign(engine.prepareCancel(subscription,payer.address))
        Assert.assertTrue(engine.list(payer.address).first().cancelled)
        rpc("evm_increaseTime",listOf(86401));rpc("evm_mine")
        rejected(saved.id)
    }
}
