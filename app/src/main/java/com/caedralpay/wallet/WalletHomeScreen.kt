package com.caedralpay.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.caedralpay.wallet.ui.theme.LocalThemeController
import com.caedralpay.wallet.ui.theme.ThemeMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import com.caedralpay.wallet.ui.theme.WalletTheme
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/*
 * Referência visual: MetaMask Mobile — ConsenSys Software Inc.
 * https://github.com/MetaMask/metamask-mobile/blob/a216ff1ef6c0af89eafbf8a19aab1a15b4b281af/app/components/Views/Wallet/components/WalletHeader/WalletHeaderAccountPickerRow.tsx
 * Adaptação CaedralPay: composição nativa Compose, identidade mineral e seletor.
 * Autorização visual informada pelo responsável CaedralPay em 11/11/2026.
 * UI própria; este crédito não se aplica ao cofre/criptografia.
 */
@Composable
fun WalletHomeScreen(
    walletName: String,
    balanceUsd: BigDecimal? = null,
    dailyChangeUsd: BigDecimal? = null,
    dailyChangePercent: BigDecimal? = null,
    enableAccountManagement: Boolean = false,
    assetsState: WalletAssetsState = WalletAssetsState.Unavailable,
    onRetryAssets: (() -> Unit)? = null,
    connectToNetwork: Boolean = enableAccountManagement,
    initialNetwork: BscChain? = null
) {
    val context = LocalContext.current.applicationContext
    val alerts = remember { SnackbarHostState() }
    var network by remember {
        mutableStateOf(initialNetwork ?: if (connectToNetwork) NetworkPreference.load(context) else BscChain.Mainnet)
    }
    var accounts by remember { mutableStateOf(if (enableAccountManagement) WalletVault.accountsSnapshot() else null) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    LaunchedEffect(sheet) {
        if (sheet == null) WalletAlerts.popups.collect { alert -> alerts.showSnackbar(alert.kind.title, withDismissAction = true) }
    }
    var transferAsset by remember { mutableStateOf(WalletAsset.USDT) }
    var assetDetails by remember { mutableStateOf<AssetPosition?>(null) }
    var transferFromDetails by remember { mutableStateOf(false) }
    val percent = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 } }
    if (enableAccountManagement) NotificationWelcome()
    val account = accounts?.selectedAccount
    val accountKey = "${network.chainId}:" + account?.let { "${accounts?.selectedWalletId}:${it.index}:${it.address}" }
    LaunchedEffect(accountKey, connectToNetwork) {
        if (!connectToNetwork || account == null) return@LaunchedEffect
        val incoming = IncomingActivityWatcher()
        val outgoing = TransactionEngine(context)
        val origin = OriginEngine(context)
        val originMerchant = OriginMerchant(context)
        while (true) {
            try {
                incoming.poll(context, network, account.address)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No unverified incoming alerts. */ }
            try {
                outgoing.history().filter { !it.terminal && it.quote.chain == network && it.quote.from.equals(account.address, true) }
                    .forEach { outgoing.refresh(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep cursor/outbox and retry; no unverified alerts. */ }
            if(network==OriginNetwork.chain) try {
                origin.history().filter{!it.terminal && it.quote.from.equals(account.address,true)}.forEach{origin.refresh(it)}
                OriginSales(context).all().filter{it.hash==null && !it.expired && it.invoice.request.merchant.equals(account.address,true)}.forEach{originMerchant.poll(it)}
            } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){ }
            delay(5_000)
        }
    }
    val repository = remember { BscBalanceRepository() }
    val marketRepository = remember { UsdMarketRepository() }
    var prices by remember(accountKey) { mutableStateOf(emptyMap<WalletAsset, BigDecimal>()) }
    var networkSnapshot by remember(accountKey) { mutableStateOf<BscAccountBalances?>(null) }
    var networkLoading by remember(accountKey) { mutableStateOf(connectToNetwork && account != null) }
    var networkError by remember(accountKey) { mutableStateOf<String?>(null) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var requestGeneration by remember(accountKey) { mutableLongStateOf(0) }
    LaunchedEffect(accountKey, connectToNetwork) {
        if (!connectToNetwork || account == null || network.isTestnet) return@LaunchedEffect
        while (true) {
            prices = try {
                marketRepository.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            delay(60_000)
        }
    }
    LaunchedEffect(accountKey, connectToNetwork, refreshRequest) {
        if (!connectToNetwork || account == null) return@LaunchedEffect
        val generation = ++requestGeneration
        while (true) {
            networkLoading = true
            networkError = null
            try {
                networkSnapshot = repository.load(account.address, network)
            } catch (_: TimeoutCancellationException) {
                coroutineContext.ensureActive()
                networkError = "A consulta demorou demais. Tente atualizar novamente."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                networkError = "Não foi possível atualizar. Verifique sua conexão e tente novamente."
            } finally {
                if (requestGeneration == generation) networkLoading = false
            }
            delay(30_000)
        }
    }
    var selectionChanged by remember { mutableStateOf(false) }
    val displayedAssets = if (connectToNetwork && account != null) {
        networkSnapshot?.let { WalletAssetsState.Ready(valuePortfolio(it.positions(), network, prices)) }
            ?: if (networkLoading) WalletAssetsState.Loading
            else WalletAssetsState.Failure(networkError ?: "Saldos indisponíveis.")
    } else if (selectionChanged) WalletAssetsState.Unavailable else assetsState
    val positions = (displayedAssets as? WalletAssetsState.Ready)?.positions
    val displayedBalance = if (connectToNetwork && account != null) positions?.let(::totalUsd)
        else if (selectionChanged) null else positions?.let(::totalUsd) ?: balanceUsd
    // A transfer is not profit. Until cash-flow history is available, do not
    // substitute a token's rolling 24h market movement for the account's daily P&L.
    val testBalanceKnown = network.isTestnet && positions != null && displayedBalance != null
    val changeAmount = if (testBalanceKnown) BigDecimal.ZERO else if (!selectionChanged && !connectToNetwork) dailyChangeUsd else null
    val changePercent = if (testBalanceKnown) BigDecimal.ZERO else if (!selectionChanged && !connectToNetwork) dailyChangePercent else null
    val hasChange = changeAmount != null && changePercent != null

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(C.Background).screenEntrance().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.caedralpay), "CaedralPay", Modifier.size(80.dp), colorFilter = ColorFilter.tint(C.Brand))
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Selecionar carteira ${accounts?.selectedWallet?.name ?: walletName}, ${account?.name ?: "Conta 1"}" }
                    .clickable(enabled = enableAccountManagement, role = Role.Button) { sheet = HomeSheet.Accounts },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(accounts?.selectedWallet?.name ?: walletName, color = C.Text, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(account?.name ?: "Conta 1", color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CaedralIcon(CaedralSymbol.Chevron)
            }
            IconButton(onClick = { sheet = HomeSheet.Appearance }, modifier = Modifier.semantics { contentDescription = "Aparência" }) {
                CaedralIcon(CaedralSymbol.Appearance, C.Muted, 22.dp)
            }
        }
        BasicText(
            displayedBalance?.let(::formatUsd) ?: "US$ —",
            style = TextStyle(color = C.Text, fontFamily = com.caedralpay.wallet.ui.theme.CaedralFont,
                fontSize = 48.sp, letterSpacing = (-1.5).sp, fontWeight = FontWeight.Bold),
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 48.sp),
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp).testTag("wallet-total-balance").semantics { heading() }
        )
        val change = if (hasChange) {
            val amount = requireNotNull(changeAmount)
            val rate = requireNotNull(changePercent)
            fun sign(value: BigDecimal) = if (value.signum() > 0) "+" else if (value.signum() < 0) "−" else ""
            "${sign(amount)}${formatUsd(amount.abs())} (${sign(rate)}${percent.format(rate.abs())}%) · Hoje"
        } else "US$ — (—%) · Hoje"
        Text(change, color = when {
            !hasChange -> C.Muted
            requireNotNull(changeAmount).signum() > 0 -> C.Positive
            changeAmount.signum() < 0 -> C.Negative
            else -> C.Muted
        }, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        WalletQuickActions(onSend = { transferAsset = if (network.isTestnet) WalletAsset.BNB else WalletAsset.USDT; transferFromDetails = false; sheet = HomeSheet.Send },
            onReceive = { transferAsset = if (network.isTestnet) WalletAsset.BNB else WalletAsset.USDT; transferFromDetails = false; sheet = HomeSheet.Receive },
            modifier = Modifier.padding(top = 32.dp))
        if (enableAccountManagement) Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={sheet=HomeSheet.Cards},modifier=Modifier.weight(1f)){Text("Cartões")}
            OutlinedButton(onClick={sheet=HomeSheet.Merchant},modifier=Modifier.weight(1f)){Text("Maquininha")}
        }
        HorizontalDivider(Modifier.padding(top = 32.dp, bottom = 24.dp), color = C.Divider)
        WalletAssetSection(displayedAssets,
            onAsset = { assetDetails = it; sheet = HomeSheet.AssetDetails },
            onRetry = if (connectToNetwork) ({ refreshRequest++; Unit }) else if (selectionChanged) null else onRetryAssets,
            network = network,
            onSelectNetwork = if (connectToNetwork) ({ sheet = HomeSheet.Network }) else null,
            onActivity = if (enableAccountManagement) ({ sheet = HomeSheet.Activity }) else null)
    }
    SnackbarHost(alerts, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
    }
    when (sheet) {
        HomeSheet.Cards -> accounts?.let { OriginCardsSheet(it,network,onDismiss={sheet=null}) }
        HomeSheet.Merchant -> accounts?.let { OriginMerchantSheet(it,network,onDismiss={sheet=null}) }
        HomeSheet.Terminal -> PhysicalTerminalSheet(account, onDismiss = { sheet = null })
        HomeSheet.Notifications -> WalletNotificationSettings(onDismiss = { sheet = null })
        HomeSheet.Nfc -> CaedralNfcSheet(account, network, onDismiss = { sheet = null })
        HomeSheet.Activity -> WalletActivitySheet(account, network, onDismiss = { sheet = null })
        HomeSheet.Appearance -> {
            val appearance = LocalThemeController.current
            CaedralSheet("Aparência", onDismiss = { sheet = null }) {
                ThemeMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { appearance?.select(option) }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = appearance?.mode == option, onClick = { appearance?.select(option) })
                        Text(option.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = C.Divider)
                BscExternalLinkButton("Suporte no Discord", "https://discord.com/invite/ujR9huP7qK")
                TextButton(onClick = { sheet = HomeSheet.Notifications }) { Text("Notificações") }
                if (BuildConfig.CAEDRAL_PROTOCOL_PREVIEW) {
                    if (network == BscChain.Testnet) TextButton(onClick = { sheet = HomeSheet.Terminal }) { Text("Maquininha · teste") }
                    TextButton(onClick = { sheet = HomeSheet.Nfc }) { Text("Laboratório NFC Caedral") }
                }
                Text("CaedralPay Wallet ${BuildConfig.VERSION_NAME}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        HomeSheet.Accounts -> accounts?.let { snapshot ->
            WalletAccountsSheet(snapshot, onChanged = {
                if (it.selectedWalletId != snapshot.selectedWalletId || it.selectedAccountIndex != snapshot.selectedAccountIndex) selectionChanged = true
                accounts = it
            }, onDismiss = { sheet = null })
        }
        HomeSheet.Send, HomeSheet.Receive -> WalletTransferSheet(
            receiving = sheet == HomeSheet.Receive, walletName = accounts?.selectedWallet?.name ?: walletName,
            account = account, asset = transferAsset, network = network,
            onDismiss = { sheet = if (transferFromDetails) HomeSheet.AssetDetails else null }
        )
        HomeSheet.AssetDetails -> assetDetails?.let { original ->
            val position = (displayedAssets as? WalletAssetsState.Ready)?.positions?.firstOrNull { it.asset == original.asset } ?: original
            WalletAssetDetails(position, account,
                onSend = { transferAsset = position.asset; transferFromDetails = true; sheet = HomeSheet.Send },
                onReceive = { transferAsset = position.asset; transferFromDetails = true; sheet = HomeSheet.Receive },
                onDismiss = { assetDetails = null; sheet = null }, network = network)
        }
        HomeSheet.Network -> BscNetworkSheet(networkSnapshot, network,
            onSelected = { chosen ->
                if (chosen != network) {
                    NetworkPreference.save(context, chosen)
                    network = chosen
                    selectionChanged = true
                    assetDetails = null
                    transferFromDetails = false
                }
                sheet = null
            }, onDismiss = { sheet = null })
        null -> Unit
    }
}

private enum class HomeSheet { Accounts, Send, Receive, AssetDetails, Network, Appearance, Activity, Nfc, Notifications, Terminal, Cards, Merchant }

@Composable
private fun WalletQuickActions(onSend: () -> Unit, onReceive: () -> Unit, modifier: Modifier = Modifier) {
    val actions = listOf(Triple("Comprar", CaedralSymbol.Buy, false), Triple("Trocar", CaedralSymbol.Swap, false),
        Triple("Enviar", CaedralSymbol.Send, true), Triple("Receber", CaedralSymbol.Receive, true))
    val largeFont = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 300.dp || largeFont) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, symbol, enabled) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (enabled) C.Selected else C.Panel)
                                .clickable(enabled = enabled, role = Role.Button) {
                                    if (symbol == CaedralSymbol.Send) onSend() else onReceive()
                                }.heightIn(min = 96.dp).padding(horizontal = 4.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CaedralIcon(symbol, if (enabled) C.Brand else C.Disabled, 28.dp)
                            Text(label, color = if (enabled) C.Text else C.Disabled, fontSize = 12.sp,
                                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 393, heightDp = 852)
@Composable
private fun WalletHomePreview() { WalletTheme { WalletHomeScreen("Minha carteira") } }

@Preview(widthDp = 320, heightDp = 740, fontScale = 1.5f)
@Composable
private fun WalletHomeLargeFontPreview() { WalletTheme { WalletHomeScreen("Minha carteira com nome longo") } }

@Preview(name = "Consultando ativos", widthDp = 393, heightDp = 852)
@Composable
private fun WalletHomeLoadingPreview() { WalletTheme { WalletHomeScreen("Minha carteira", assetsState = WalletAssetsState.Loading) } }

@Preview(name = "Falha e nova tentativa", widthDp = 393, heightDp = 852)
@Composable
private fun WalletHomeFailurePreview() {
    WalletTheme { WalletHomeScreen("Minha carteira", assetsState = WalletAssetsState.Failure("Verifique sua conexão e tente novamente."), onRetryAssets = {}) }
}

@Preview(name = "Conta sem ativos", widthDp = 393, heightDp = 852)
@Composable
private fun WalletHomeEmptyPreview() { WalletTheme { WalletHomeScreen("Minha carteira", assetsState = WalletAssetsState.Empty) } }
