package com.caedralpay.wallet
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.math.BigDecimal
/** Explicit testnet enrollment only. No purchase and no mainnet transaction. */
class OriginPublicRegistrationTest {
    @get:Rule val activity=createAndroidComposeRule<ComponentActivity>()
    @Test fun enrollDedicatedQaCardOnTestnet()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("originPublic")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        check(target.packageName=="com.caedralpay.wallet.validation")
        instrumentation.runOnMainSync{activity.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val fixture=JSONObject(File(target.filesDir,"origin-public-fixture.json").readText())
        val folder=File(target.filesDir,"origin-public-qa").apply{mkdirs()}
        val context=object:ContextWrapper(target){override fun getFilesDir()=folder
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("origin-public-$name",mode)}
        val pin=fixture.getString("pin").toCharArray()
        try {
            WalletVault.lock()
            if(WalletVault.exists(context))WalletVault.unlockWithPin(context,pin)else WalletVault.create(context,"Origin Public QA",fixture.getString("mnemonic").split(" "),pin)
            val snapshot=WalletVault.accountsSnapshot();val account=snapshot.selectedAccount
            check(account.address.equals(fixture.getString("address"),true))
            val engine=OriginEngine(context)
            var record=engine.history().lastOrNull{it.quote.call.kind==OriginAction.Register && it.quote.call.settlement.equals(OriginNetwork.address,true)}
            if(record==null){
                val card=OriginCardStore.latest(context,snapshot.selectedWalletId,account.index) ?: WalletVault.createOriginCard(context,snapshot.selectedWalletId,account.index,"QA","Teste")
                val q=engine.prepare(OriginCall(OriginAction.Register,card.id,card.expiresAt,registrationSalt=card.salt),account.address)
                check(q.fee<=BigDecimal("0.0005").movePointRight(18).toBigIntegerExact())
                val auth=WalletVault.authorizeWithPin(context,fixture.getString("pin").toCharArray(),VaultAction.SignOrigin,snapshot.selectedWalletId,account.index,q.digest)
                record=engine.send(q,snapshot.selectedWalletId,account.index,auth)
            }
            withTimeout(180_000){while(!record!!.terminal){if(record!!.status==TransactionStatus.Signed)record=engine.resume(record!!);record=engine.refresh(record!!);if(!record!!.terminal)delay(1500)}}
            Assert.assertEquals(TransactionStatus.Confirmed,record!!.status)
            val saved=OriginCardStore(context).find(snapshot.selectedWalletId,account.index)!!;Assert.assertTrue(engine.isActive(saved))
            File(target.filesDir,"origin-public-result.json").writeText(JSONObject().put("chainId",97).put("hash",record!!.hash).put("holder",account.address)
                .put("settlement",OriginNetwork.address).put("cardId",saved.id).put("expiresAt",saved.expiresAt).put("registered",true).put("confirmed",true).put("purchaseSent",false).toString())
        }finally{pin.fill('\u0000');WalletVault.lock()}
    }
}
