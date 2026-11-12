package com.caedralpay.wallet

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import kotlinx.coroutines.*

internal enum class ManagementAction(val title: String) {
    RenameAccount("Renomear conta"), RemoveAccount("Remover conta"), RenameWallet("Renomear carteira"),
    RevealPhrase("Frase secreta"), ChangePin("Trocar PIN")
}
internal data class ManagementTarget(val walletId: String, val accountIndex: Int?, val action: ManagementAction)

/**
 * Referência de interação: MetaMask Mobile — ConsenSys Software Inc.
 * https://github.com/MetaMask/metamask-mobile/blob/a216ff1ef6c0af89eafbf8a19aab1a15b4b281af/app/components/Views/AccountActions/AccountActions.tsx
 * Reimplementação visual em Compose; autenticação e cofre próprios CaedralPay.
 */
@Composable
internal fun WalletManagementSheet(target: ManagementTarget, snapshot: WalletAccountsSnapshot,
    onChanged: (WalletAccountsSnapshot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val columns = if (LocalDensity.current.fontScale > 1.3f) 2 else 3
    val wallet = snapshot.wallets.first { it.id == target.walletId }
    val account = wallet.accounts.firstOrNull { it.index == target.accountIndex }
    val rename = target.action == ManagementAction.RenameWallet || target.action == ManagementAction.RenameAccount
    var name by remember { mutableStateOf(account?.name ?: wallet.name) }
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var authorization by remember { mutableStateOf<VaultAuthorization?>(null) }
    var phrase by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val hasBiometric = remember { runCatching { WalletVault.hasBiometric(context) }.getOrDefault(false) }
    val action = when (target.action) {
        ManagementAction.RemoveAccount -> VaultAction.RemoveAccount
        ManagementAction.RevealPhrase -> VaultAction.RevealPhrase
        else -> VaultAction.ChangePin
    }

    fun fail(failure: Throwable) {
        error = when (failure) {
            is WrongPinException -> "PIN incorreto. Tente novamente."
            is PinTemporarilyLockedException -> "Aguarde ${failure.remainingSeconds} segundos para tentar novamente."
            is WalletAccountException -> failure.message.orEmpty()
            else -> "Não foi possível concluir. Autentique-se novamente e tente outra vez."
        }
    }
    fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; keyboard?.hide()
        scope.launch {
            try { operation() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { authorization = null; fail(failure) }
            finally { busy = false }
        }
    }
    suspend fun authenticated(token: VaultAuthorization) {
        when (target.action) {
            ManagementAction.RemoveAccount -> {
                val result = withContext(Dispatchers.IO) { WalletVault.removeAccount(context, wallet.id, requireNotNull(account).index, token) }
                onChanged(result); finished = true
            }
            ManagementAction.RevealPhrase -> phrase = withContext(Dispatchers.IO) { WalletVault.revealPhrase(context, wallet.id, token) }
            ManagementAction.ChangePin -> authorization = token
            else -> Unit
        }
    }
    fun authenticatePin() {
        if (pin.length != 6) return
        val entered = pin.toCharArray(); pin = ""
        runOperation {
            val token = withContext(Dispatchers.IO) { WalletVault.authorizeWithPin(context, entered, action, wallet.id, target.accountIndex) }
            authenticated(token)
        }
    }
    fun authenticateBiometric() {
        if (busy) return
        keyboard?.hide(); error = ""
        try {
            val cipher = WalletVault.biometricCipherForUnlock(context)
            val cancellation = CancellationSignal()
            signal?.cancel(); signal = cancellation
            busy = true
            BiometricPrompt.Builder(context).setTitle(target.action.title)
                .setSubtitle("Confirme sua identidade na CaedralPay")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Usar PIN", context.mainExecutor) { _, _ -> cancellation.cancel(); busy = false }
                .build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            busy = false
                            runOperation {
                                val token = withContext(Dispatchers.IO) {
                                    WalletVault.authorizeWithBiometric(context, requireNotNull(result.cryptoObject?.cipher), action, wallet.id, target.accountIndex)
                                }
                                authenticated(token)
                            }
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            busy = false
                            if (!cancellation.isCanceled) error = "Use seu PIN para continuar."
                        }
                        override fun onAuthenticationFailed() { error = "Biometria não reconhecida. Tente novamente ou use o PIN." }
                    })
        } catch (_: Exception) { busy = false; error = "Biometria indisponível. Use seu PIN." }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            signal?.cancel(); phrase = null; authorization = null; pin = ""; newPin = ""; confirmPin = ""
            WalletVault.revokeAuthorization()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer); signal?.cancel(); WalletVault.revokeAuthorization()
            phrase = null; pin = ""; newPin = ""; confirmPin = ""
        }
    }
    LaunchedEffect(phrase) { if (phrase != null) { delay(30_000); phrase = null } }

    CaedralSheet(target.action.title, busy = busy, closeRequested = finished, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, false).verticalScroll(rememberScrollState()).screenEntrance(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (account != null) "${wallet.name} · ${account.name}" else wallet.name, color = C.Muted)
            when {
                rename -> {
                    OutlinedTextField(name, { if (it.length <= 30) name = it }, label = { Text("Novo nome") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    CaedralButton(onClick = { runOperation {
                        val result = withContext(Dispatchers.IO) {
                            if (account == null) WalletVault.renameWallet(context, wallet.id, name)
                            else WalletVault.renameAccount(context, wallet.id, account.index, name)
                        }
                        onChanged(result); finished = true
                    } }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Salvar nome")
                    }
                }
                phrase != null -> {
                    Text("Frase de recuperação", style = MaterialTheme.typography.titleMedium)
                    requireNotNull(phrase).chunked(columns).forEachIndexed { row, words ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { words.forEachIndexed { column, word ->
                            Surface(Modifier.weight(1f), color = C.Selected, shape = MaterialTheme.shapes.small) {
                                Text("${row * columns + column + 1}. $word", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                    CaedralButton(onClick = { phrase = null; finished = true }, modifier = Modifier.fillMaxWidth()) { Text("Ocultar e fechar") }
                }
                authorization != null -> {
                    Text("Este PIN protege todas as carteiras neste dispositivo.", color = C.Muted)
                    SecurePinField(newPin, { newPin = it }, "Novo PIN de 6 dígitos", !busy)
                    SecurePinField(confirmPin, { confirmPin = it }, "Confirmar novo PIN", !busy)
                    if (confirmPin.length == 6 && newPin != confirmPin) Text("Os PINs não coincidem.", color = C.Error)
                    CaedralButton(onClick = {
                        val entered = newPin.toCharArray(); newPin = ""; confirmPin = ""
                        val token = requireNotNull(authorization)
                        runOperation { withContext(Dispatchers.IO) { WalletVault.changePin(context, wallet.id, entered, token) }; finished = true }
                    }, enabled = !busy && newPin.length == 6 && newPin == confirmPin, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Salvar novo PIN")
                    }
                }
                else -> {
                    Text(if (action == VaultAction.RemoveAccount) "Remover esta conta da lista neste dispositivo? Os endereços das outras contas serão mantidos."
                        else "Confirme sua identidade para continuar.", color = C.Muted)
                    SecurePinField(pin, { pin = it }, "PIN de 6 dígitos", !busy, ::authenticatePin)
                    CaedralButton(onClick = ::authenticatePin, enabled = !busy && pin.length == 6, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(if (action == VaultAction.RemoveAccount) "Confirmar remoção" else "Confirmar PIN")
                    }
                    if (hasBiometric) OutlinedButton(onClick = ::authenticateBiometric, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) { Text("Usar biometria") }
                }
            }
            if (error.isNotEmpty()) Text(error, color = C.Error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun SecurePinField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, onDone: () -> Unit = {}) {
    OutlinedTextField(value, { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) onChange(it) },
        label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }))
}
