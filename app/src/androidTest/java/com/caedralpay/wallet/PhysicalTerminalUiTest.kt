package com.caedralpay.wallet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.caedralpay.wallet.ui.theme.WalletTheme
import org.junit.Rule
import org.junit.Test

class PhysicalTerminalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun initialScreenHasOneTaskAndNoVirtualCardFields() {
        compose.setContent { WalletTheme { PhysicalTerminalSheet(null, onDismiss = {}) } }
        compose.onNodeWithText("1 de 3 · Aproximar cartão").assertIsDisplayed()
        compose.onNodeWithText("Ler cartão físico").assertIsDisplayed()
        compose.onNodeWithText("Nome escolhido").assertDoesNotExist()
        compose.onNodeWithText("Contrato do ambiente de teste").assertDoesNotExist()
        compose.onNodeWithText("Revisar cobrança").assertDoesNotExist()
        compose.onNodeWithText("Compra confirmada").assertDoesNotExist()
    }
}
