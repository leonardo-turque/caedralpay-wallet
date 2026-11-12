package com.caedralpay.wallet

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.caedralpay.wallet.ui.theme.WalletTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WalletQrUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepareIsolatedActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        check(activity.packageName == "com.caedralpay.wallet.validation")
        instrumentation.uiAutomation.grantRuntimePermission(activity.packageName, Manifest.permission.CAMERA)
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun receivingShowsQrAndUpdatesWhenAmountChanges() {
        compose.setContent { WalletTheme {
            WalletTransferSheet(true, "Principal", WalletAccount(0, "Conta 1", "0x" + "1".repeat(40)), {}, WalletAsset.BNB, BscChain.Testnet)
        } }
        compose.onNodeWithContentDescription("QR Code para receber").assertIsDisplayed()
        compose.onNodeWithText("Valor a receber (opcional)").performTextInput("0,1")
        compose.onNodeWithContentDescription("QR Code para receber").assertExists()
        compose.onNodeWithText("Valor a receber (opcional)").performTextReplacement("0")
        compose.onNodeWithContentDescription("QR Code para receber").assertDoesNotExist()
    }

    @Test fun initialImportOffersQrReading() {
        compose.setContent { WalletTheme { ImportWalletScreen(onBack = {}) } }
        compose.onNodeWithText("Importar por QR Code").performScrollTo().assertIsEnabled()
    }

    @Test fun embeddedImportAlsoOffersQrReading() {
        compose.setContent { WalletTheme {
            ImportWalletScreen(onBack = {}, onAdditionalImport = { _, _, _ -> }, embedded = true)
        } }
        compose.onNodeWithText("Importar por QR Code").performScrollTo().assertIsEnabled()
    }

    @Test fun sendOpensAndCancelsInternalCamera() {
        compose.setContent { WalletTheme {
            WalletTransferSheet(false, "Principal", WalletAccount(0, "Conta 1", "0x" + "1".repeat(40)), {})
        } }
        compose.onNodeWithText("Ler QR Code").performClick()
        compose.onNodeWithText("Ler QR Code para pagar").assertIsDisplayed()
        compose.waitUntil(15_000) {
            var streaming = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fun hasStream(view: View): Boolean = if (view is PreviewView) view.previewStreamState.value == PreviewView.StreamState.STREAMING
                    else view is ViewGroup && (0 until view.childCount).any { hasStream(view.getChildAt(it)) }
                streaming = WindowInspector.getGlobalWindowViews().any(::hasStream)
            }
            streaming
        }
        compose.onNodeWithText("Cancelar leitura").performScrollTo().performClick()
        compose.onNodeWithText("Endereço de destino").assertExists()
        compose.onNodeWithText("Ler QR Code para pagar").assertDoesNotExist()
    }

    @Test fun importedQrStillRequiresValidBip39Checksum() {
        val valid = List(11) { "abandon" }.plus("about").joinToString(" ")
        assertTrue(isValidRecoveryPhrase(RecoveryQr.parse(valid)))
        assertFalse(isValidRecoveryPhrase(RecoveryQr.parse(List(12) { "abandon" }.joinToString(" "))))
    }
}
