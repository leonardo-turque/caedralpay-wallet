package com.caedralpay.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.delay

/** Only reachable in an explicitly enabled, isolated development build. */
@Composable
internal fun CaedralNfcSheet(account: WalletAccount?, network: BscChain, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().first()
    val lifecycle = activity.let { (it as androidx.lifecycle.LifecycleOwner).lifecycle }
    val reader = remember { CaedralNfcReader(activity) }
    val received by CaedralNfcSession.invoice.collectAsState()
    var status by remember { mutableStateOf("Escolha o modo de teste.") }
    var active by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var contract by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var cardLabel by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val adapter = remember { NfcAdapter.getDefaultAdapter(activity) }
    val emulation = remember {
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            adapter?.let(CardEmulation::getInstance) else null
    }
    val component = remember { ComponentName(activity, CaedralHostService::class.java) }
    fun stop() {
        reader.stop(); CaedralNfcSession.clear()
        emulation?.unsetPreferredService(activity)
        active = false; generation++
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(active, generation) {
        if (active) { delay(120_000); stop(); status = "Sessão expirada. Inicie outra aproximação." }
    }
    CaedralSheet("Protocolo Caedral · desenvolvimento", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Sovereign", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(first, { first = it.take(40) }, label = { Text("Nome escolhido") }, singleLine = true)
            OutlinedTextField(last, { last = it.take(40) }, label = { Text("Sobrenome escolhido") }, singleLine = true)
            TextButton(onClick = {
                runCatching { CaedralProtocol.cardName(first, last) }.onSuccess {
                    cardLabel = "$it · validade ${CaedralProtocol.cardExpiry(Instant.now()).toString().take(10)}"
                }.onFailure { status = "Informe um nome e um sobrenome, sem números ou espaços internos." }
            }) { Text("Prévia do cartão") }
            cardLabel?.let { Text(it) }
            HorizontalDivider()
            Text("Teste de entrega NFC. Não emite cartão nem movimenta fundos.")
            Text(status)
            OutlinedTextField(contract, { contract = it.take(42) }, label = { Text("Contrato do ambiente de teste") }, singleLine = true)
            OutlinedTextField(amount, { amount = it.take(40) }, label = { Text("Valor em USDT") }, singleLine = true)
            CaedralButton(onClick = {
                stop()
                try {
                    val gross = BigDecimal(amount.replace(',', '.')).movePointRight(18).toBigIntegerExact()
                    CaedralProtocol.split(gross, 18, CaedralProtocol.Tier.Sovereign, Instant.now())
                    val now = Instant.now().epochSecond
                    val invoice = CaedralProtocol.Invoice(network.chainId.toLong(), contract,
                        requireNotNull(account).address, requireNotNull(network.usdtContract), gross,
                        CaedralProtocol.newId(), now, now + 120)
                    val session = generation
                    reader.start(invoice) { delivered ->
                        if (generation == session && active) {
                            status = if (delivered) "Solicitação entregue. Isso não confirma pagamento." else "Aproxime novamente."
                            if (delivered) { reader.stop(); active = false }
                        }
                    }
                    active = true; status = "Aproxime do celular do pagador com o recebimento NFC aberto."
                } catch (_: Exception) { status = "Confira NFC, contrato, conta e valor superior a 0,10 USDT." }
            }, enabled = reader.supported && account != null && !active) { Text("Lojista: transmitir cobrança") }
            CaedralButton(onClick = {
                stop()
                if (adapter != null && emulation?.setPreferredService(activity, component) == true) {
                    CaedralNfcSession.arm(); active = true; status = "Aproxime do celular do lojista."
                } else status = "NFC/HCE indisponível neste aparelho."
            }, enabled = reader.supported && !active) { Text("Pagador: receber cobrança") }
            received?.let { invoice ->
                val recognized = invoice.chainId == network.chainId.toLong() && invoice.token.equals(network.usdtContract, true)
                Text("Solicitação: ${BigDecimal(invoice.gross, 18).toPlainString()} ${if (recognized) "USDT" else "ativo/rede não reconhecido"}")
                Text("Destino informado: ${invoice.merchant}")
                Text("Cobrança sem assinatura verificada. Liquidação bloqueada neste laboratório.")
            }
            TextButton(onClick = { stop(); status = "Sessão encerrada." }) { Text("Encerrar aproximação") }
        }
    }
}
