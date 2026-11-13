package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.caedralpay.wallet.ui.theme.WalletTheme
import org.json.JSONObject
import org.junit.*
import java.io.File

/** Opt-in physical test, dedicated QA recovery only, never signs or spends. */
class PhysicalTerminalValidationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun readAndBindPhysicalCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("caedralPhysicalCard") == "true")
        val target = instrumentation.targetContext
        check(target.packageName == "com.caedralpay.wallet.validation" && BuildConfig.CAEDRAL_PROTOCOL_PREVIEW)
        val fixture = JSONObject(File(target.filesDir, "nfc-qa-fixture.json").readText())
        val directory = File(target.filesDir, "public-network-vault").apply { mkdirs() }
        val context = object : ContextWrapper(compose.activity) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("public-qa-$name", mode)
        }
        instrumentation.runOnMainSync { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val pin = fixture.getString("pin").toCharArray()
        try {
            if (WalletVault.exists(context)) WalletVault.unlockWithPin(context, pin)
            else WalletVault.create(context, "NFC QA", fixture.getString("mnemonic").split(" "), pin)
            val snapshot = WalletVault.accountsSnapshot()
            val account = snapshot.selectedWallet.accounts.first { it.index == 0 }
            check(account.address.equals(fixture.getString("address"), true))
            compose.setContent { WalletTheme {
                CompositionLocalProvider(LocalContext provides context) {
                    PhysicalTerminalSheet(account, onDismiss = {})
                }
            } }
            compose.onNodeWithText("Ler cartão físico").performScrollTo().performClick()
            compose.waitUntil(180_000) {
                compose.onAllNodesWithText("Cartão reconhecido").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Vincular à conta selecionada") and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Cartão vinculado").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("Cartão vinculado").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithText("Vincular à conta selecionada").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Cartão vinculado").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Cartão vinculado").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Conta: ${account.address.take(8)}…${account.address.takeLast(6)}").performScrollTo().assertIsDisplayed()
            File(target.filesDir, "nfc-qa-result.json").writeText(JSONObject()
                .put("chainId", 97).put("read", true).put("bound", true).put("payer", account.address)
                .put("cardWritten", false).put("transactionSigned", false).toString())
        } finally { pin.fill('\u0000'); WalletVault.lock() }
    }
}
