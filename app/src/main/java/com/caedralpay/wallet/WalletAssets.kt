package com.caedralpay.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import java.math.BigDecimal

enum class WalletAsset(val symbol: String, val displayName: String) {
    USDT("USDT", "Tether USD"), BNB("BNB", "BNB")
}

data class AssetPosition(val asset: WalletAsset, val quantity: BigDecimal?, val valueUsd: BigDecimal?)

sealed interface WalletAssetsState {
    data object Unavailable : WalletAssetsState
    data object Loading : WalletAssetsState
    data object Empty : WalletAssetsState
    data class Failure(val message: String) : WalletAssetsState
    data class Ready(val positions: List<AssetPosition>) : WalletAssetsState
}

internal fun formatAssetQuantity(quantity: BigDecimal, symbol: String): String =
    "${quantity.stripTrailingZeros().toPlainString().replace('.', ',')} $symbol"

@Composable
internal fun WalletAssetSection(state: WalletAssetsState, onAsset: (AssetPosition) -> Unit, onRetry: (() -> Unit)?, network: BscChain = BscChain.Mainnet, onSelectNetwork: (() -> Unit)? = null, onActivity: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Ativos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (onActivity != null) IconButton(onClick = onActivity, modifier = Modifier.semantics { contentDescription = "Atividade" }) {
            CaedralIcon(CaedralSymbol.History)
        }
        if (onSelectNetwork != null) IconButton(onClick = onSelectNetwork,
            modifier = Modifier.semantics { contentDescription = "Selecionar rede" }) {
            CaedralIcon(CaedralSymbol.Network)
        }
    }
    Spacer(Modifier.height(12.dp))
    when (state) {
        WalletAssetsState.Loading -> WalletAssetsLoading()
        WalletAssetsState.Empty -> CaedralNotice("Nenhum ativo nesta conta", "Nenhum ativo foi retornado pela consulta desta conta.")
        is WalletAssetsState.Failure -> CaedralNotice("Não foi possível atualizar", state.message, onRetry)
        else -> {
            val positions = (state as? WalletAssetsState.Ready)?.positions
                ?: network.assets.map { AssetPosition(it, null, null) }
            if (positions.isEmpty()) CaedralNotice("Nenhum ativo nesta conta", "A consulta não retornou ativos.")
            positions.filter { it.asset in network.assets }.forEach { position ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onAsset(position) }
                        .padding(vertical = 16.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WalletAssetAvatar(position.asset)
                    Column(Modifier.weight(1f)) {
                        Text(position.asset.symbolOn(network), fontWeight = FontWeight.SemiBold)
                        Text(position.asset.displayName, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 160.dp)) {
                        Text(position.valueUsd?.let(::formatUsd) ?: "US$ —",
                            fontWeight = FontWeight.Medium)
                        Text(position.quantity?.let { formatAssetQuantity(it, position.asset.symbolOn(network)) } ?: "— ${position.asset.symbolOn(network)}",
                            color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = C.Divider)
            }
        }
    }
}

@Composable
private fun WalletAssetsLoading() {
    val transition = rememberInfiniteTransition(label = "asset-loading")
    val opacity by transition.animateFloat(0.4f, 0.8f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "placeholder-opacity")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Carregando ativos" }.alpha(opacity)) {
        repeat(2) {
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(C.Selected, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(76.dp, 14.dp).background(C.Selected, RoundedCornerShape(6.dp)))
                    Box(Modifier.size(100.dp, 10.dp).background(C.Selected, RoundedCornerShape(5.dp)))
                }
                Box(Modifier.size(68.dp, 16.dp).background(C.Selected, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun WalletAssetAvatar(asset: WalletAsset) {
    Box(Modifier.size(44.dp).background(if (asset == WalletAsset.USDT) androidx.compose.ui.graphics.Color(0xFF168B70)
        else androidx.compose.ui.graphics.Color(0xFFF0B90B), CircleShape), contentAlignment = Alignment.Center) {
        if (asset == WalletAsset.USDT) Text("₮", color = androidx.compose.ui.graphics.Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        else androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            val unit = size.width / 24f
            listOf(12f to 4f, 4f to 12f, 12f to 12f, 20f to 12f, 12f to 20f).forEach { (x, y) ->
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(x * unit, (y - 3) * unit); lineTo((x + 3) * unit, y * unit)
                    lineTo(x * unit, (y + 3) * unit); lineTo((x - 3) * unit, y * unit); close()
                }, androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
internal fun CaedralNotice(title: String, message: String, onRetry: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth(), color = C.Panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Tentar novamente") }
        }
    }
}

@Composable
internal fun WalletAssetDetails(position: AssetPosition, account: WalletAccount?, onSend: () -> Unit, onReceive: () -> Unit, onDismiss: () -> Unit, network: BscChain = BscChain.Mainnet) {
    CaedralSheet(position.asset.displayName, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(position.asset.symbolOn(network), style = MaterialTheme.typography.headlineLarge)
            Text(position.quantity?.let { formatAssetQuantity(it, position.asset.symbolOn(network)) } ?: "Quantidade não consultada", color = C.Muted)
            Text(position.valueUsd?.let(::formatUsd) ?: "US$ —")
            HorizontalDivider(color = C.Divider)
            Text("Conta: ${account?.name ?: "Não selecionada"}", color = C.Muted)
            if (position.asset == WalletAsset.USDT && network.usdtContract != null) {
                Text("Contrato BEP-20", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text(network.usdtContract, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("${BscNetwork.usdtDecimals} casas decimais", color = C.Muted)
                BscExplorerButton("Ver contrato no BscScan", "/token/${network.usdtContract}", network)
            }
            if (position.asset == WalletAsset.BNB) Text("${network.nativeSymbol} é utilizado para pagar as taxas desta rede.", color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSend, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Enviar") }
                CaedralButton(onClick = onReceive, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Receber") }
            }
        }
    }
}
