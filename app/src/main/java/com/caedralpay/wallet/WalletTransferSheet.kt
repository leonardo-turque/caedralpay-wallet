package com.caedralpay.wallet

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import kotlinx.coroutines.*
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun WalletTransferSheet(
    receiving: Boolean, walletName: String, account: WalletAccount?, onDismiss: () -> Unit,
    asset: WalletAsset = WalletAsset.USDT,
    network: BscChain = BscChain.Mainnet,
    transferRepository: TransferRepository? = null
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var destination by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var addressVisited by remember { mutableStateOf(false) }
    var amountVisited by remember { mutableStateOf(false) }
    var addressBlurred by remember { mutableStateOf(false) }
    var amountBlurred by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var selectedAsset by remember(asset) { mutableStateOf(asset) }
    var requestedAmount by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val repository = remember { transferRepository ?: TransferRepository() }
    val engine = remember { TransactionEngine(context) }
    var quote by remember { mutableStateOf<PreparedTransfer?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var authorizing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WalletTransaction?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val hasBiometric = remember { runCatching { WalletVault.hasBiometric(context) }.getOrDefault(false) }
    fun report(failure: Exception) {
        error = when (failure) {
            is WrongPinException -> "PIN incorreto."
            is PinTemporarilyLockedException -> "Aguarde ${failure.remainingSeconds} segundos para tentar novamente."
            is IllegalArgumentException -> failure.message?.takeUnless { it == "Failed requirement." } ?: "Confira os dados e tente novamente."
            else -> "Não foi possível concluir a consulta. Verifique sua conexão e tente novamente."
        }
    }
    fun prepare() {
        if (busy || account == null) return
        busy = true; error = ""; keyboard?.hide()
        scope.launch {
            try { quote = repository.prepare(network, selectedAsset, account.address, destination, amount); reviewing = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }
    suspend fun submit(token: VaultAuthorization, walletId: String, prepared: PreparedTransfer) {
        result = engine.send(prepared, walletId, requireNotNull(account).index, token)
        authorizing = false; pin = ""
    }
    fun confirmPin() {
        val prepared = quote ?: return
        if (busy || pin.length != 6) return
        val entered = pin.toCharArray(); pin = ""; busy = true; error = ""; keyboard?.hide()
        scope.launch {
            try {
                val id = WalletVault.accountsSnapshot().selectedWalletId
                val token = withContext(Dispatchers.IO) {
                    WalletVault.authorizeWithPin(context, entered, VaultAction.SignTransfer, id, account?.index, prepared.digest)
                }
                submit(token, id, prepared)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { entered.fill('\u0000'); busy = false }
        }
    }
    fun confirmBiometric() {
        val prepared = quote ?: return
        if (busy) return
        error = ""; keyboard?.hide()
        try {
            val id = WalletVault.accountsSnapshot().selectedWalletId
            val cipher = WalletVault.biometricCipherForUnlock(context)
            val cancellation = CancellationSignal(); signal?.cancel(); signal = cancellation
            busy = true
            BiometricPrompt.Builder(context).setTitle("Confirmar pagamento")
                .setSubtitle("${formatAssetQuantity(prepared.amount, selectedAsset.symbolOn(network))}")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Usar PIN", context.mainExecutor) { _, _ -> cancellation.cancel(); busy = false }
                .build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                            scope.launch {
                                try {
                                    val token = withContext(Dispatchers.IO) {
                                        WalletVault.authorizeWithBiometric(context, requireNotNull(authentication.cryptoObject?.cipher), VaultAction.SignTransfer,
                                            id, account?.index, prepared.digest)
                                    }
                                    submit(token, id, prepared)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { report(failure) }
                                finally { busy = false }
                            }
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) { busy = false }
                    })
        } catch (_: Exception) { busy = false; error = "Biometria indisponível. Use seu PIN." }
    }
    DisposableEffect(Unit) { onDispose { signal?.cancel(); WalletVault.revokeAuthorization() } }
    val addressError = addressBlurred && destination.isNotEmpty() && !TransferDraft.isAddressShapeValid(destination)
    val amountError = amountBlurred && amount.isNotEmpty() && TransferDraft.amount(amount) == null
    val valid = account != null && TransferDraft.canReview(destination, amount)

    val symbol = selectedAsset.symbolOn(network)
    if (scanning) WalletQrScanner("Ler QR Code para pagar", onDismiss = { scanning = false }) { payload ->
        try {
            val request = PaymentQr.parse(payload, network)
            destination = request.address
            selectedAsset = request.asset ?: selectedAsset
            // Never retain an amount entered for an earlier recipient.
            amount = request.amount?.stripTrailingZeros()?.toPlainString().orEmpty()
            addressBlurred = false
            amountBlurred = false
            scanning = false
            null
        } catch (failure: IllegalArgumentException) {
            failure.message ?: "QR Code de pagamento inválido."
        }
    }
    CaedralSheet(if (receiving) "Receber $symbol" else if (result != null) "Pagamento registrado" else if (reviewing) "Revise os dados" else "Enviar $symbol", busy = busy, onDismiss = onDismiss) {
        BackHandler(enabled = reviewing && !busy && result == null) { reviewing = false; authorizing = false; quote = null }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("$walletName · ${account?.name ?: "Conta 1"}", color = C.Muted)
            if (result != null) {
                val transaction = requireNotNull(result)
                Text(transaction.status.label, style = MaterialTheme.typography.titleLarge)
                Text(formatAssetQuantity(transaction.quote.amount, symbol))
                Text(transaction.hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Acompanhe a confirmação em Atividade. Se a conexão cair, retome por lá sem criar outro pagamento.", color = C.Muted)
                BscExplorerButton("Ver no explorador", "/tx/${transaction.hash}", network)
                CaedralButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
            } else if (receiving) {
                val parsedAmount = TransferDraft.amount(requestedAmount)
                val amountValid = requestedAmount.isEmpty() || (parsedAmount != null &&
                    parsedAmount.stripTrailingZeros().scale() <= 18 && parsedAmount.movePointRight(18).toBigInteger().bitLength() <= 256)
                val receivePayload = if (account != null && amountValid)
                    PaymentQr.encode(account.address, network, selectedAsset, parsedAmount) else null
                receivePayload?.let { ReceiveQr(it) }
                OutlinedTextField(requestedAmount, onValueChange = { value ->
                    if (value.length <= 40 && value.all { it in '0'..'9' || it == ',' || it == '.' }) requestedAmount = value
                }, label = { Text("Valor a receber (opcional)") }, singleLine = true,
                    suffix = { Text(symbol) }, isError = !amountValid,
                    supportingText = if (!amountValid) ({ Text("Informe um valor válido com até 18 casas decimais.") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
                if (asset == WalletAsset.USDT && network.usdtContract != null) {
                    Text("USDT · BEP-20", color = C.Brand)
                    Text("Contrato: ${network.usdtContract}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("Endereço da conta", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                Surface(color = C.Selected, shape = MaterialTheme.shapes.medium) {
                    Text(account?.address ?: "Endereço indisponível", color = C.Text,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                CaedralButton(onClick = {
                    account?.let {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Endereço ${network.networkName}", it.address))
                        copied = true
                    }
                }, enabled = account != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    CaedralIcon(CaedralSymbol.Copy, C.Background, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Endereço copiado" else "Copiar endereço")
                }
                if (copied) Text("Endereço copiado", color = C.Muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                network.faucetUrl?.let { BscExternalLinkButton("Obter tBNB no faucet oficial", it) }
                account?.let { BscExplorerButton("Ver conta no explorador", "/address/${it.address}", network) }
            } else if (reviewing) {
                val prepared = requireNotNull(quote)
                TransferReviewRow("Ativo", prepared.asset.symbolOn(prepared.chain))
                TransferReviewRow("Quantidade", formatAssetQuantity(prepared.amount, prepared.asset.symbolOn(prepared.chain)))
                TransferReviewRow("De", prepared.from)
                TransferReviewRow("Para", prepared.recipient)
                HorizontalDivider(color = C.Divider)
                TransferReviewRow("Taxa máxima de rede", quote?.let { formatAssetQuantity(java.math.BigDecimal(it.fee, 18), network.nativeSymbol) } ?: "—")
                TransferReviewRow("Rede do pagamento", network.networkName)
                if (authorizing) {
                    OutlinedTextField(pin, { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) pin = it },
                        label = { Text("PIN de 6 dígitos") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirmPin() }))
                    CaedralButton(onClick = ::confirmPin, enabled = !busy && pin.length == 6, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Autorizar pagamento")
                    }
                    if (hasBiometric) OutlinedButton(onClick = ::confirmBiometric, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Usar biometria") }
                } else CaedralButton(onClick = { authorizing = true }, enabled = quote != null && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Confirmar envio") }
                TextButton(onClick = { reviewing = false; authorizing = false; quote = null; error = "" }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Editar dados") }
                TextButton(onClick = { authorizing = false; pin = ""; prepare() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("Recalcular taxa") }
            } else {
                OutlinedButton(onClick = { keyboard?.hide(); scanning = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    CaedralIcon(CaedralSymbol.Qr, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Ler QR Code")
                }
                OutlinedTextField(destination, onValueChange = { if (it.length <= 42) destination = it.trim() },
                    label = { Text("Endereço de destino") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (it.isFocused) addressVisited = true else if (addressVisited) addressBlurred = true
                    },
                    isError = addressError,
                    supportingText = if (addressError) ({ Text("Informe um endereço EVM válido, iniciado por 0x.") }) else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
                OutlinedTextField(amount, onValueChange = { value ->
                    if (value.length <= 40 && value.all { it in '0'..'9' || it == ',' || it == '.' }) amount = value
                }, label = { Text("Quantidade em $symbol") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (it.isFocused) amountVisited = true else if (amountVisited) amountBlurred = true
                    },
                    isError = amountError,
                    supportingText = {
                        Text(if (amountError) "Informe uma quantidade maior que zero, sem separador de milhar."
                            else "Use vírgula ou ponto para separar os decimais.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) prepare() }))
                Text("Confira o endereço e a rede antes de revisar.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                CaedralButton(onClick = ::prepare, enabled = valid && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Revisar dados")
                }
            }
            if (error.isNotEmpty()) Text(error, color = C.Error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun TransferReviewRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = C.Text)
    }
}
