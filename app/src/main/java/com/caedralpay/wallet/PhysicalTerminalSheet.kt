package com.caedralpay.wallet

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.time.Instant

/** Three explicit steps. Read-only card; no signing or automatic spend from proximity. */
@Composable
internal fun PhysicalTerminalSheet(account: WalletAccount?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
    val lifecycle = (activity as androidx.lifecycle.LifecycleOwner).lifecycle
    val reader = remember { PhysicalCardReader(activity) }
    val store = remember { TestCardBindings(context) }
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf<PhysicalCard?>(null) }
    var binding by remember { mutableStateOf<TestCardBinding?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<CaedralProtocol.Split?>(null) }
    fun stop() { reader.stop(); scanning = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(scanning) {
        if (scanning) { delay(60_000); stop(); error = "A leitura expirou. Tente novamente." }
    }
    CaedralSheet("Maquininha · teste", busy = saving, onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when { quote != null -> "3 de 3 · Revisar cobrança"; card != null -> "2 de 3 · Conta do cartão"; else -> "1 de 3 · Aproximar cartão" },
                style = MaterialTheme.typography.labelLarge)
            if (card == null) {
                Text(if (scanning) "Aproxime o cartão branco" else "Vamos reconhecer seu cartão", style = MaterialTheme.typography.headlineSmall)
                Text("Encoste o cartão na traseira do celular e mantenha por alguns segundos.")
                if (scanning) CircularProgressIndicator(Modifier.size(28.dp))
                CaedralButton(onClick = {
                    error = null
                    try {
                        reader.start { result ->
                            scanning = false
                            result.onSuccess { value ->
                                card = value
                                saving = true
                                scope.launch {
                                    try { binding = withContext(Dispatchers.IO) { store.find(value.fingerprint) } }
                                    catch (_: Exception) { error = "Não foi possível consultar o vínculo salvo." }
                                    finally { saving = false }
                                }
                                scope.launch(Dispatchers.IO) {
                                    WalletAlerts.publish(context, WalletAlert(AlertKind.CardDetected, "physical:" + System.nanoTime()))
                                }
                            }.onFailure { error = "Não foi possível ler. Mantenha o cartão encostado e tente novamente." }
                        }
                        scanning = true
                    } catch (_: Exception) { error = "Ative o NFC e tente novamente." }
                }, enabled = reader.supported && !scanning, modifier = Modifier.fillMaxWidth()) { Text("Ler cartão físico") }
            } else {
                Text("Cartão reconhecido", style = MaterialTheme.typography.headlineSmall)
                if (binding == null) {
                    Text("Vincule o cartão à conta ${account?.name ?: "selecionada"} para este teste.")
                    CaedralButton(onClick = {
                        saving = true; error = null
                        scope.launch {
                            try {
                                val selected = WalletVault.accountsSnapshot()
                                val selectedAccount = requireNotNull(account)
                                require(selected.selectedWallet.accounts.any {
                                    it.index == selectedAccount.index && it.address.equals(selectedAccount.address, true)
                                })
                                binding = withContext(Dispatchers.IO) { store.bind(requireNotNull(card), selected.selectedWalletId, selectedAccount) }
                            } catch (_: Exception) { error = "Não foi possível salvar o vínculo. Tente novamente." }
                            finally { saving = false }
                        }
                    }, enabled = account != null && !saving, modifier = Modifier.fillMaxWidth()) {
                        if (saving) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Vincular à conta selecionada")
                    }
                } else {
                    Text("Cartão vinculado", style = MaterialTheme.typography.titleMedium)
                    Text("Conta: ${binding!!.address.take(8)}…${binding!!.address.takeLast(6)}")
                    if (quote == null) {
                        OutlinedTextField(amount, { amount = it.take(40); error = null }, label = { Text("Valor da compra em tBNB") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        CaedralButton(onClick = {
                            quote = runCatching { CaedralProtocol.split(BigDecimal(amount.replace(',', '.')).movePointRight(18).toBigIntegerExact(),
                                18, CaedralProtocol.Tier.Sovereign, Instant.now()) }
                                .onFailure { error = "O valor precisa superar a taxa mínima de 0,10 tBNB." }.getOrNull()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Revisar cobrança") }
                    } else {
                        val split = requireNotNull(quote)
                        TerminalAmount("Total", split.gross)
                        TerminalAmount("Taxa Caedral", split.fee)
                        TerminalAmount("Líquido do lojista", split.merchant)
                        TerminalAmount("Cashback", split.cashback)
                        Text("A cobrança ainda não foi enviada.")
                        TextButton(onClick = { quote = null }) { Text("Editar valor") }
                    }
                }
                TextButton(onClick = { stop(); card = null; binding = null; quote = null; error = null }) { Text("Ler outro cartão") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Aproximar o cartão identifica a conta; não autoriza um pagamento.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TerminalAmount(label: String, value: java.math.BigInteger) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label); Text("${BigDecimal(value, 18).stripTrailingZeros().toPlainString()} tBNB")
    }
}
