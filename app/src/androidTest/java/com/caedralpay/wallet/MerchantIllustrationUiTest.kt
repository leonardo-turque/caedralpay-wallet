package com.caedralpay.wallet

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.caedralpay.wallet.ui.theme.WalletTheme
import com.caedralpay.wallet.ui.theme.ThemeMode
import android.graphics.Bitmap
import java.io.File
import org.junit.Test

class MerchantIllustrationUiTest {
    @get:org.junit.Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun customerCardApproachesPhoneAndStopsAfterDelivery(){
        var delivered by mutableStateOf(false)
        compose.setContent{WalletTheme(ThemeMode.Light){Surface{MerchantTapAnimation(delivered)}}}
        compose.onNodeWithText("Aproxime o celular").assertDoesNotExist()
        compose.onNodeWithText("Apresentar cobrança").assertDoesNotExist()
        val image=compose.onNodeWithContentDescription("Cartão aproximando do leitor NFC do celular").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"merchant-tap-illustration.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnIdle{delivered=true}
        compose.onNodeWithText("Aguardando pagamento").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cartão lido. Aguardando pagamento").assertIsDisplayed()
    }
}
