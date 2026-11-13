package com.caedralpay.wallet

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.caedralpay.wallet.ui.theme.WalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class WalletHomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestActivityAwake() {
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun testnetReceiptNeverShowsMainnetUsdtContract() {
        compose.setContent { WalletTheme {
            WalletTransferSheet(true, "Principal", WalletAccount(0, "Conta 1", "0x" + "1".repeat(40)),
                onDismiss = {}, asset = WalletAsset.BNB, network = BscChain.Testnet)
        } }
        compose.onNodeWithText("Receber tBNB").assertIsDisplayed()
        compose.onNodeWithText("TESTNET · Chain ID 97").assertDoesNotExist()
        compose.onAllNodesWithText(BscNetwork.usdtContract, substring = true).assertCountEquals(0)
        compose.onNodeWithText("Obter tBNB no faucet oficial").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun homeKeepsUsdLayoutWithoutNetworkBanners() {
        compose.setContent { WalletTheme { WalletHomeScreen("Principal", initialNetwork = BscChain.Testnet) } }
        compose.onNodeWithText("TESTNET · Chain ID 97").assertDoesNotExist()
        compose.onNodeWithText("BNB Smart Chain", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Atualizar").assertDoesNotExist()
        compose.onNodeWithTag("wallet-total-balance").assertTextEquals("US$ —")
        compose.onNodeWithText("US$ — (—%) · Hoje").assertIsDisplayed()
        compose.onNodeWithTag("wallet-total-balance").assertIsDisplayed()
        compose.onAllNodesWithText("R$", substring = true).assertCountEquals(0)
        compose.onNodeWithText("USDT").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun usdTotalSumsPositionsAndDisplaysDailyPnl() {
        compose.setContent { WalletTheme {
            WalletHomeScreen("Principal", dailyChangeUsd = "2.50".toBigDecimal(),
                dailyChangePercent = "1.25".toBigDecimal(), assetsState = WalletAssetsState.Ready(listOf(
                    AssetPosition(WalletAsset.BNB, "0.1".toBigDecimal(), "60".toBigDecimal()),
                    AssetPosition(WalletAsset.USDT, "140".toBigDecimal(), "140".toBigDecimal())
                )))
        } }
        compose.onNodeWithTag("wallet-total-balance").assertTextEquals(formatUsd("200".toBigDecimal()))
        compose.onNodeWithText("+${formatUsd("2.50".toBigDecimal())} (+1,25%) · Hoje").assertIsDisplayed()
    }

    @Test
    fun networkSelectorAllowsExplicitTestnetSelection() {
        var chosen: BscChain? = null
        compose.setContent { WalletTheme {
            BscNetworkSheet(null, BscChain.Mainnet, onSelected = { chosen = it }, onDismiss = {})
        } }
        compose.onNodeWithText("BNB Smart Chain Testnet").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(BscChain.Testnet, chosen) }
    }

    @Test
    fun captureHomeWithUnavailableBalances() {
        compose.setContent { WalletTheme { WalletHomeScreen("Minha carteira") } }
        compose.onNodeWithTag("wallet-total-balance").assertIsDisplayed()
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(target.getExternalFilesDir(null), "home-ui.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun accountsFooterRemainsVisibleWithLongList() {
        val snapshot = WalletAccountsSnapshot(listOf(WalletGroup("sample", "Principal", List(40) {
            WalletAccount(it, "Conta ${it + 1}", "0x" + "1".repeat(40))
        })), "sample", 0)
        compose.setContent { WalletTheme { WalletAccountsSheet(snapshot, onChanged = {}, onDismiss = {}) } }
        compose.onNodeWithText("Criar conta").assertIsDisplayed()
        compose.onNodeWithTag("wallet-account-list").performScrollToNode(hasText("Conta 40"))
        compose.onNodeWithText("Conta 40").assertIsDisplayed()
        compose.onNodeWithText("Importar outra carteira").assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsAllQuickActionsReachable() {
        compose.setContent {
            val current = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(current.density, 2f)
            ) { WalletTheme { WalletHomeScreen("Carteira com um nome muito longo") } }
        }
        listOf("Trocar", "Enviar", "Receber", "Maquininha").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun assetsNeverPresentUnknownBalanceAsZero() {
        compose.setContent { WalletTheme { WalletHomeScreen("Principal") } }
        compose.onNodeWithText("BNB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("— BNB").assertIsDisplayed()
        compose.onNodeWithText("USDT").performScrollTo().performClick()
        compose.onNodeWithText("Quantidade não consultada").assertIsDisplayed()
    }

    @Test
    fun assetFailureOffersRetry() {
        var retried = false
        compose.setContent {
            WalletTheme {
                WalletHomeScreen("Principal", assetsState = WalletAssetsState.Failure("Sem conexão"), onRetryAssets = { retried = true })
            }
        }
        compose.onNodeWithText("Tentar novamente").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(retried) }
    }

    @Test
    fun reviewRequiresPinAndDoesNotBroadcastAutomatically() {
        val address = "0x" + "1".repeat(40)
        val repository = TransferRepository(EvmGateway(EvmTransport { _, method, params ->
            fun abi(value: java.math.BigInteger) = "0x" + value.toString(16).padStart(64, '0')
            when (method) {
                "eth_chainId" -> "0x38"; "eth_getTransactionCount" -> "0x0"; "eth_gasPrice" -> "0x3b9aca00"
                "eth_estimateGas" -> "0x10000"; "eth_getBalance" -> "0x3635c9adc5dea00000"; "eth_getCode" -> "0x6000"
                "eth_call" -> {
                    val data = (params.first() as Map<*, *>)["data"] as String
                    abi(when { data == "0x313ce567" -> 18.toBigInteger(); data.startsWith("0xa9059cbb") -> java.math.BigInteger.ONE
                        else -> java.math.BigInteger.TEN.pow(21) })
                }
                else -> error("Unexpected RPC method")
            }
        }))
        compose.setContent { WalletTheme {
            WalletTransferSheet(false, "Principal", WalletAccount(0, "Conta 1", address), onDismiss = {}, transferRepository = repository)
        } }
        compose.onNodeWithText("Endereço de destino").performTextInput("0x" + "2".repeat(40))
        compose.onNodeWithText("Quantidade em USDT").performTextInput("1,50")
        compose.onNodeWithText("Revisar dados").performScrollTo().performClick()
        compose.onNodeWithText("1,5 USDT").assertIsDisplayed()
        compose.onNodeWithText("Confirmar envio").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("PIN de 6 dígitos").assertExists()
        compose.onNodeWithText("Autorizar pagamento").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Editar dados").performScrollTo().performClick()
        compose.onNodeWithText("Quantidade em USDT").assertIsDisplayed()
    }

    @Test
    fun merchantReplacesBuyAndOnlySwapRemainsDisabled() {
        compose.setContent { WalletTheme { WalletHomeScreen("Principal") } }
        compose.onNodeWithText("Comprar").assertDoesNotExist()
        compose.onNodeWithText("Maquininha").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Trocar").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Enviar").assertIsEnabled()
        compose.onNodeWithText("Receber").assertIsEnabled()
    }

    @Test
    fun sendOpensFormWithoutEnablingTransaction() {
        compose.setContent { WalletTheme { WalletHomeScreen("Principal") } }
        compose.onNodeWithText("Enviar").performClick()
        compose.onNodeWithText("Endereço de destino").assertIsDisplayed()
        compose.onNodeWithText("Quantidade em USDT").assertIsDisplayed()
        compose.onNodeWithText("Revisar dados").assertIsNotEnabled()
    }

    @Test
    fun receiveShowsAddressOfSelectedAccount() {
        val address = "0x0000000000000000000000000000000000000001"
        compose.setContent {
            WalletTheme {
                WalletTransferSheet(true, "Principal", WalletAccount(1, "Reserva", address), onDismiss = {})
            }
        }
        compose.onNodeWithText("Principal · Reserva").assertIsDisplayed()
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copiar endereço").assertIsEnabled()
    }
}
