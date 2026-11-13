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

/** Opt-in QA: existing credentials and merchant-selected invoice, no activation or purchase sent. */
class OriginPublicRegistrationTest {
    @get:Rule val activity=createAndroidComposeRule<ComponentActivity>()
    @Test fun readyWithoutActivationAndMerchantSelectsCurrency()=runBlocking {
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
            var snapshot=WalletVault.accountsSnapshot();snapshot=WalletVault.selectAccount(context,snapshot.selectedWalletId,0);val account=snapshot.selectedAccount
            check(account.address.equals(fixture.getString("address"),true))
            if(snapshot.selectedWallet.accounts.none{it.index==1})WalletVault.addAccount(context,snapshot.selectedWalletId,"QA lojista")
            snapshot=WalletVault.selectAccount(context,snapshot.selectedWalletId,0)
            val card=OriginCardStore.latest(context,snapshot.selectedWalletId,account.index) ?: WalletVault.createOriginCard(context,snapshot.selectedWalletId,account.index,"QA","Teste")
            val engine=OriginEngine(context)
            val historyBefore=engine.history().size
            val auth=WalletVault.authorizeWithPin(context,fixture.getString("pin").toCharArray(),VaultAction.ArmCard,card.walletId,card.index,card.fingerprint)
            WalletVault.authorizeOriginTap(context,card,auth);OriginTapSession.arm(card)
            Assert.assertNotNull(OriginTapSession.hello());OriginTapSession.clear()
            val merchant=snapshot.selectedWallet.accounts.first{it.index==1}
            val draft=OriginMerchant(context).draft(BscChain.Testnet,merchant.address,WalletAsset.BNB,"0.12")
            val sign=WalletVault.authorizeWithPin(context,fixture.getString("pin").toCharArray(),VaultAction.SignInvoice,card.walletId,1,OriginAbi.sha(OriginAbi.invoiceDigest(draft.first).hex()))
            val invoice=WalletVault.signOriginInvoice(context,card.walletId,1,draft.first,sign)
            var insufficient=false
            try{engine.prepare(OriginCall.forCard(OriginAction.Pay,card,invoice),account.address)}
            catch(e:IllegalArgumentException){Assert.assertTrue(e.message.orEmpty().contains("Saldo"));insufficient=true}
            Assert.assertEquals(historyBefore,engine.history().size)
            File(target.filesDir,"origin-public-result.json").writeText(JSONObject().put("chainId",97).put("settlement",OriginNetwork.address)
                .put("holder",account.address).put("cardId",card.id).put("expiresAt",card.expiresAt).put("readyForTap",true)
                .put("activationSent",false).put("purchaseSent",false).put("merchantInvoiceVerified",true).put("insufficientFundsBlocked",insufficient).toString())
        }finally{pin.fill('\u0000');OriginTapSession.clear();WalletVault.lock()}
    }
}
