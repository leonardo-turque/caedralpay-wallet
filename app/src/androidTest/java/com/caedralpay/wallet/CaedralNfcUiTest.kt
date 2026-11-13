package com.caedralpay.wallet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import com.caedralpay.wallet.ui.theme.WalletTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CaedralNfcUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun chosenNamePreviewDoesNotIssueOrSpend() {
        assumeTrue(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW)
        compose.setContent { WalletTheme {
            CaedralNfcSheet(WalletAccount(0, "Conta 1", "0x" + "1".repeat(40)), BscChain.Testnet, onDismiss = {})
        } }
        compose.onNodeWithText("Nome escolhido").performTextInput("Ana")
        compose.onNodeWithText("Sobrenome escolhido").performTextInput("Silva")
        compose.onNodeWithText("Prévia do cartão").performScrollTo().performClick()
        compose.onNodeWithText("Ana Silva · validade", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Teste de entrega NFC. Não emite cartão nem movimenta fundos.").performScrollTo().assertIsDisplayed()
    }
}
