package com.caedralpay.wallet

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.caedralpay.wallet.ui.theme.*
import org.junit.*
import java.io.File
import android.graphics.Bitmap

class WalletManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var snapshot: WalletAccountsSnapshot

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.caedralpay.wallet.validation")
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        WalletVault.lock(); File(context.filesDir, "wallet_vault.bin").delete()
        context.getSharedPreferences("wallet_pin_attempts", 0).edit().clear().commit()
        WalletVault.create(context, "Principal", List(11) { "abandon" } + "about", "123456".toCharArray())
        snapshot = WalletVault.accountsSnapshot()
    }
    @After fun clear() {
        WalletVault.lock()
        if (::context.isInitialized) File(context.filesDir, "wallet_vault.bin").delete()
    }

    @Test fun overflowMenusExposeAccountAndWalletActions() {
        compose.setContent { WalletTheme(mode = ThemeMode.Light) { WalletAccountsSheet(snapshot, {}, {}) } }
        compose.onNodeWithContentDescription("Opções da carteira Principal").performClick()
        listOf("Renomear carteira", "Frase secreta", "Trocar PIN").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Renomear carteira").performClick()
        compose.onNodeWithText("Novo nome").assertIsDisplayed()
    }

    @Test fun revealRequiresCorrectPinAndCanBeHidden() {
        compose.setContent { WalletTheme(mode = ThemeMode.Dark) {
            WalletManagementSheet(ManagementTarget(snapshot.selectedWalletId, null, ManagementAction.RevealPhrase), snapshot, {}, {})
        } }
        compose.onNodeWithText("12. about").assertDoesNotExist()
        compose.onNodeWithText("PIN de 6 dígitos").performTextInput("999999")
        compose.onNodeWithText("Confirmar PIN").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("PIN incorreto.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("12. about").assertDoesNotExist()
        compose.onNodeWithText("PIN de 6 dígitos").performTextInput("123456")
        compose.onNodeWithText("Confirmar PIN").performClick()
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("12. about").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Exception) {
            val states = listOf("PIN incorreto.", "Não foi possível concluir.", "Frase de recuperação", "Ocultar e fechar").associateWith {
                compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            throw AssertionError("Estado da tela: $states; clock=${compose.mainClock.currentTime}", failure)
        }
        compose.onNodeWithText("Ocultar e fechar").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("12. about").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun removingSelectedAccountRequiresPinAndKeepsOtherAddress() {
        snapshot = WalletVault.addAccount(context, snapshot.selectedWalletId, "Reserva")
        var result by mutableStateOf<WalletAccountsSnapshot?>(null)
        compose.setContent { WalletTheme(mode = ThemeMode.Light) {
            WalletManagementSheet(ManagementTarget(snapshot.selectedWalletId, 1, ManagementAction.RemoveAccount), snapshot, { result = it }, {})
        } }
        compose.onNodeWithText("Confirmar remoção").assertIsNotEnabled()
        compose.onNodeWithText("PIN de 6 dígitos").performTextInput("123456")
        compose.onNodeWithText("Confirmar remoção").performClick()
        compose.waitUntil(10_000) { result != null }
        Assert.assertEquals(snapshot.selectedWallet.accounts.first().address, result!!.selectedAccount.address)
        Assert.assertEquals(1, result!!.selectedWallet.accounts.size)
    }

    @Test fun renamingAccountPersistsWithoutChangingItsAddress() {
        var result by mutableStateOf<WalletAccountsSnapshot?>(null)
        compose.setContent { WalletTheme(mode = ThemeMode.Light) {
            WalletManagementSheet(ManagementTarget(snapshot.selectedWalletId, 0, ManagementAction.RenameAccount), snapshot, { result = it }, {})
        } }
        compose.onNodeWithText("Novo nome").performTextReplacement("Recebimentos")
        compose.onNodeWithText("Salvar nome").performClick()
        compose.waitUntil(10_000) { result != null }
        Assert.assertEquals("Recebimentos", result!!.selectedAccount.name)
        Assert.assertEquals(snapshot.selectedAccount.address, result!!.selectedAccount.address)
    }

    @Test fun pinChangeRequiresAuthenticationAndMatchingConfirmation() {
        var closed by mutableStateOf(false)
        compose.setContent { WalletTheme(mode = ThemeMode.Light) {
            WalletManagementSheet(ManagementTarget(snapshot.selectedWalletId, null, ManagementAction.ChangePin), snapshot, {}, { closed = true })
        } }
        compose.onNodeWithText("Novo PIN de 6 dígitos").assertDoesNotExist()
        compose.onNodeWithText("PIN de 6 dígitos").performTextInput("123456")
        compose.onNodeWithText("Confirmar PIN").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Novo PIN de 6 dígitos").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Novo PIN de 6 dígitos").performTextInput("654321")
        compose.onNodeWithText("Confirmar novo PIN").performTextInput("654320")
        compose.onNodeWithText("Salvar novo PIN").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirmar novo PIN").performTextReplacement("654321")
        compose.onNodeWithText("Salvar novo PIN").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed }
        WalletVault.lock(); WalletVault.unlockWithPin(context, "654321".toCharArray())
    }

    @Test fun captureLightAndDarkHome() {
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent { WalletTheme(mode = mode) {
            WalletHomeScreen("Minha carteira", dailyChangeUsd = "1.25".toBigDecimal(), dailyChangePercent = "0.5".toBigDecimal(),
                assetsState = WalletAssetsState.Ready(listOf(AssetPosition(WalletAsset.USDT, "250".toBigDecimal(), "250".toBigDecimal()))))
        } }
        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            compose.runOnIdle { mode = theme }
            compose.waitForIdle()
            File(context.getExternalFilesDir(null), "home-${theme.name}.png").outputStream().use { output ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            compose.onNodeWithText("Comprar").assertIsNotEnabled()
            compose.onNodeWithText("Receber").assertIsEnabled()
        }
    }
}
