package com.caedralpay.wallet
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    @Test fun originVisualHasBrandAndMasksNumber() {
        compose.setContent{WalletTheme{Column(Modifier.fillMaxSize().padding(24.dp)){OriginCardVisual("Ana Silva","4527");Spacer(Modifier.height(20.dp));OriginCardVisual("Ana Silva","4527",portrait=true)}}}
        compose.onAllNodesWithText("ORIGIN").assertCountEquals(2)
        compose.onAllNodesWithText("••••  4527").assertCountEquals(2)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.filesDir,"origin-card-preview.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
