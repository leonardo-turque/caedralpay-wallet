package com.caedralpay.wallet
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.caedralpay.wallet.ui.theme.WalletTheme
import java.io.File
import java.util.UUID
import org.junit.*
class OriginUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun keepDeviceUnlockedDuringVaultChecks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @Test fun notificationChoiceIsNotRepeatedAfterDecline() {
        val suffix=UUID.randomUUID().toString()
        val context=object:ContextWrapper(compose.activity){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("$suffix-$name",mode)}
        var shown by mutableStateOf(true)
        compose.setContent{WalletTheme{CompositionLocalProvider(LocalContext provides context){if(shown)NotificationWelcome()}}}
        compose.onNodeWithText("Agora não").performClick()
        compose.onNodeWithText("Quer receber notificações?").assertDoesNotExist()
        compose.runOnIdle{shown=false};compose.waitForIdle();compose.runOnIdle{shown=true}
        compose.onNodeWithText("Quer receber notificações?").assertDoesNotExist()
        Assert.assertTrue(context.getSharedPreferences("wallet_alerts",Context.MODE_PRIVATE).getBoolean("welcome_asked",false))
    }
    @Test fun sameCardAndDetailsSurviveNetworkChangesAndReopening() {
        val suffix=UUID.randomUUID().toString()
        val folder=File(compose.activity.cacheDir,"origin-ui-$suffix").apply{mkdirs()}
        val context=object:ContextWrapper(compose.activity){override fun getFilesDir()=folder
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("$suffix-$name",mode)}
        var shown by mutableStateOf(true)
        WalletVault.lock()
        try {
            WalletVault.create(context,"Fixture",List(11){"abandon"}+"about","123456".toCharArray())
            val snapshot=WalletVault.accountsSnapshot()
            var network by mutableStateOf(BscChain.Mainnet)
            // Network unavailable: loading/failure must never create or replace credentials.
            val offline=EvmGateway(EvmTransport{_,method,_->if(method=="eth_chainId")"0x61" else throw java.io.IOException("Offline fixture")})
            compose.setContent{WalletTheme{CompositionLocalProvider(LocalContext provides context){if(shown)OriginCardsSheet(snapshot,network,onDismiss={shown=false},gateway=offline)}}}
            compose.waitUntil(25_000){compose.onAllNodesWithText("Nome no cartão").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Nome no cartão").performTextInput("Ana")
            compose.onNodeWithText("Sobrenome").performTextInput("Silva")
            compose.onNodeWithText("Criar meu Origin").performScrollTo().performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("Ver dados").fetchSemanticsNodes().isNotEmpty()}
            val original=OriginCardStore.latest(context,snapshot.selectedWalletId,0)!!
            val grant=WalletVault.authorizeWithPin(context,"123456".toCharArray(),VaultAction.ViewCard,original.walletId,0,original.fingerprint)
            val originalDetails=WalletVault.originCardDetails(original,grant)
            for(chain in listOf(BscChain.Testnet,BscChain.Mainnet)) {
                compose.runOnIdle{network=chain}
                compose.waitForIdle()
                compose.waitUntil(25_000){compose.onAllNodesWithText("Ver dados").fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled)==false}
                compose.onNodeWithText("••••  "+original.lastFour).assertExists()
                val reloaded=OriginCardStore.latest(context,snapshot.selectedWalletId,0)!!
                Assert.assertEquals(original.json().toString(),reloaded.json().toString())
            }
            compose.onNodeWithContentDescription("Fechar painel").performClick()
            compose.waitUntil(25_000){!shown}
            compose.waitUntil(25_000){compose.onAllNodesWithText("Seu cartão Origin").fetchSemanticsNodes().isEmpty()}
            compose.waitForIdle()
            compose.runOnIdle{shown=true}
            compose.waitForIdle()
            compose.onNodeWithText("Seu cartão Origin").assertExists()
            compose.waitUntil(25_000){compose.onAllNodesWithText("Ver dados").fetchSemanticsNodes().isNotEmpty()}
            val preview=compose.onNodeWithText("Seu cartão Origin").onParent().captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"origin-universal-preview.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            compose.onNodeWithText("Ver dados").performScrollTo().performClick()
            compose.onNodeWithText("PIN de 6 dígitos").performTextInput("123456")
            compose.onNodeWithText("Confirmar").performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText(originalDetails.number.chunked(4).joinToString(" ")).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(originalDetails.securityCode).assertExists()
        } finally {compose.runOnIdle{shown=false};compose.waitForIdle();WalletVault.lock();folder.deleteRecursively()}
    }
    @Test fun originVisualHasBrandAndMasksNumber() {
        compose.setContent{WalletTheme{Column(Modifier.fillMaxSize().padding(24.dp)){OriginCardVisual("Ana Silva","4527");Spacer(Modifier.height(20.dp));OriginCardVisual("Ana Silva","4527",portrait=true)}}}
        compose.onAllNodesWithText("ORIGIN").assertCountEquals(2)
        compose.onAllNodesWithText("••••  4527").assertCountEquals(2)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.filesDir,"origin-card-preview.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
