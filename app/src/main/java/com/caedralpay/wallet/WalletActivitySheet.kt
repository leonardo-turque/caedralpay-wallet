package com.caedralpay.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import kotlinx.coroutines.*

@Composable
internal fun WalletActivitySheet(account: WalletAccount?, network: BscChain, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { TransactionEngine(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<WalletTransaction>?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(account?.address, network) {
        while (true) {
            try {
                val saved = engine.history().filter { it.quote.chain == network && it.quote.from.equals(account?.address, true) }
                records = saved
                for (record in saved.filter { !it.terminal }) {
                    try { engine.refresh(record) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "A confirmação está temporariamente indisponível. Seus envios permanecem registrados." }
                }
                records = engine.history().filter { it.quote.chain == network && it.quote.from.equals(account?.address, true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível consultar o histórico local." }
            delay(7_000)
        }
    }
    CaedralSheet("Atividade", busy = busy, onDismiss = onDismiss) {
        Text("Envios deste dispositivo", color = C.Muted, modifier = Modifier.padding(bottom = 16.dp))
        if (records == null) CircularProgressIndicator(Modifier.size(24.dp))
        if (records?.isEmpty() == true) Text("Nenhum envio registrado nesta conta.", color = C.Muted)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(records.orEmpty().sortedByDescending { it.quote.createdAt }, key = { it.hash }) { record ->
                Surface(color = C.Selected, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatAssetQuantity(record.quote.amount, record.quote.asset.symbolOn(network)), style = MaterialTheme.typography.titleMedium)
                        Text(record.status.label, color = when (record.status) {
                            TransactionStatus.Confirmed -> C.Positive; TransactionStatus.Failed -> C.Error; else -> C.Muted
                        })
                        Text("Para: ${record.quote.recipient}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("${record.hash.take(14)}…${record.hash.takeLast(10)}", style = MaterialTheme.typography.bodySmall)
                        record.actualFee?.let { Text("Taxa: ${formatAssetQuantity(java.math.BigDecimal(it.toBigInteger(), 18), network.nativeSymbol)}", color = C.Muted) }
                        BscExplorerButton("Ver transação", "/tx/${record.hash}", network)
                        if (!record.terminal) OutlinedButton(onClick = {
                            if (!busy) { busy = true; scope.launch {
                                try { engine.resume(record); error = "" }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Não foi possível retomar. Tente novamente." }
                                finally { busy = false }
                            } }
                        }, enabled = !busy) { Text("Retomar transmissão") }
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = C.Error, modifier = Modifier.padding(top = 12.dp))
    }
}
