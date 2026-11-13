package com.caedralpay.wallet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.ContextWrapper
import com.caedralpay.wallet.ui.theme.WalletTheme
import com.caedralpay.wallet.ui.theme.ThemeMode
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import org.junit.*
import java.io.File
import java.util.UUID

class MerchantWizardUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var folder:File
    private lateinit var context:Context
    private lateinit var snapshot:WalletAccountsSnapshot
    @Before fun setup(){
        compose.runOnUiThread{compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        folder=File(compose.activity.cacheDir,"merchant-${UUID.randomUUID()}").apply{mkdirs()}
        context=object:ContextWrapper(compose.activity){override fun getFilesDir()=folder;override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${folder.name}-$name",mode)}
        WalletVault.lock();WalletVault.create(context,"Loja",List(11){"abandon"}+"about","123456".toCharArray());snapshot=WalletVault.accountsSnapshot()
    }
    @After fun cleanup(){WalletVault.lock();folder.deleteRecursively()}
    private fun click(text:String){compose.onNodeWithText(text).performClick();compose.waitForIdle()}
    private fun open(mode:ThemeMode=ThemeMode.Dark){compose.setContent{WalletTheme(mode){CompositionLocalProvider(LocalContext provides context){OriginMerchantSheet(snapshot,BscChain.Mainnet,onDismiss={})}}};compose.waitForIdle()}
    private fun preview(name:String){val image=compose.onNodeWithText("Maquininha").onParent().onParent().captureToImage().asAndroidBitmap();File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun recurringWizardAllowsIntervalAndNoEndWithoutTouchingNfc(){
        open();compose.onNodeWithText("Escolha a rede").assertIsDisplayed();click("Testnet");click("USDT");click("Recorrente")
        compose.onNodeWithText("Defina a recorrência").assertIsDisplayed();click("7 dias");click("Continuar")
        compose.onNodeWithText("Valor").performTextInput("10");click("Continuar")
        compose.onNodeWithText("Revise a cobrança").assertIsDisplayed();compose.onNodeWithText("7 dias").assertIsDisplayed();compose.onNodeWithText("Sem encerramento").assertIsDisplayed()
        preview("merchant-recurring-dark.png");compose.onNodeWithText("Cobrar por aproximação").assertIsEnabled();compose.onNodeWithText("Conta vinculada").assertDoesNotExist()
        compose.onNodeWithContentDescription("Etapa anterior").performClick();compose.onNodeWithText("Valor por cobrança").assertIsDisplayed()
    }
    @Test fun oneTimeFlowHasNoScheduleAndRejectsTooSmallAmount(){
        open(ThemeMode.Light);click("Testnet");click("tBNB");click("Pagamento único")
        compose.onNodeWithText("Qual é o valor?").assertIsDisplayed();compose.onNodeWithText("Valor").performTextInput("0.01");click("Continuar")
        compose.onNodeWithText("Qual é o valor?").assertIsDisplayed();compose.onNodeWithText("Valor").performTextReplacement("1");click("Continuar")
        compose.onNodeWithText("Revise a cobrança").assertIsDisplayed();compose.onNodeWithText("Intervalo").assertDoesNotExist();preview("merchant-once-light.png")
    }
    @Test fun endDateIsRequiredWhenNoEndIsDisabled(){
        open();click("Testnet");click("USDT");click("Recorrente")
        compose.onNode(isToggleable()).performClick();click("Continuar")
        compose.onNodeWithText("Escolha a data de encerramento.").assertIsDisplayed();click("Data de encerramento")
        compose.onNodeWithText("Selecionar").assertIsDisplayed();click("Selecionar");click("Continuar")
        compose.onNodeWithText("Valor por cobrança").assertIsDisplayed()
    }
}
