package com.caedralpay.wallet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.caedralpay.wallet.ui.theme.CaedralColors as C

@Composable
internal fun BscNetworkSheet(snapshot: BscAccountBalances?, network: BscChain, onSelected: (BscChain) -> Unit, onDismiss: () -> Unit) {
    CaedralSheet("Rede e ativos", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BscChain.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(option == network, role = Role.RadioButton) { onSelected(option) }
                    .padding(vertical = 8.dp)) {
                    RadioButton(selected = option == network, onClick = null)
                    Column(Modifier.weight(1f).padding(top = 6.dp)) {
                        Text(option.networkName, style = MaterialTheme.typography.titleMedium)
                        Text("${if (option.isTestnet) "TESTNET" else "Principal"} · Chain ID ${option.chainId}", color = C.Muted)
                    }
                }
            }
            HorizontalDivider(color = C.Divider)
            Text("Moeda de taxas: ${network.nativeSymbol} · ${BscNetwork.nativeDecimals} casas decimais", color = C.Muted)
            snapshot?.let {
                Text("Bloco consultado: ${it.blockNumber}", color = C.Muted)
                Text("RPC: ${Uri.parse(it.rpcEndpoint).host}", color = C.Muted)
            }
            HorizontalDivider(color = C.Divider)
            if (network.usdtContract != null) {
            Text("USDT · BEP-20", style = MaterialTheme.typography.titleMedium)
            Text("Contrato suportado", color = C.Muted)
            Text(network.usdtContract, fontFamily = FontFamily.Monospace)
            Text("${BscNetwork.usdtDecimals} casas decimais", color = C.Muted)
            BscExplorerButton("Ver contrato no BscScan", "/token/${network.usdtContract}", network)
            } else {
                Text("Teste inicial com tBNB. O contrato de um token de teste será configurado separadamente.", color = C.Muted)
                network.faucetUrl?.let { BscExternalLinkButton("Abrir faucet oficial", it) }
            }
        }
    }
}

@Composable
internal fun BscExplorerButton(label: String, path: String, network: BscChain = BscChain.Mainnet) {
    BscExternalLinkButton(label, network.explorer + path)
}

@Composable
internal fun BscExternalLinkButton(label: String, url: String) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = {
        failed = runCatching {
            require(url.startsWith("https://"))
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isFailure
    }) { Text(label) }
    if (failed) Text("Não há navegador disponível para abrir o explorador.", color = C.Muted)
}
