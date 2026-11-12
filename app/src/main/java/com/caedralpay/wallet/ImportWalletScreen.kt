package com.caedralpay.wallet

import com.caedralpay.wallet.ui.theme.CaedralColors as C

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import wallet.core.jni.Mnemonic

internal fun normalizeRecoveryWord(value: String): String = value.trim().lowercase(Locale.ROOT)

internal fun isValidRecoveryPhrase(words: List<String>): Boolean {
    if (words.size != 12 || words.any { !it.matches(Regex("[a-z]+")) }) return false
    System.loadLibrary("TrustWalletCore")
    return Mnemonic.isValid(words.joinToString(" "))
}

@Composable
fun ImportWalletScreen(
    onBack: () -> Unit,
    onWalletImported: (String) -> Unit = {},
    onAdditionalImport: ((String, List<String>, Int) -> Unit)? = null,
    busy: Boolean = false,
    externalError: String = "",
    embedded: Boolean = false
) {
    val entries = remember { mutableStateListOf<String>().apply { repeat(12) { add("") } } }
    val focus = remember { List(12) { FocusRequester() } }
    var stage by remember { mutableStateOf(0) }
    var walletName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showWords by remember { mutableStateOf(false) }
    var accountCount by remember { mutableStateOf("1") }
    var scanning by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val columns = if (LocalDensity.current.fontScale > 1.3f) 2 else 3

    if (scanning) WalletQrScanner("Importar carteira por QR Code", onDismiss = { scanning = false }) { payload ->
        try {
            val words = RecoveryQr.parse(payload)
            if (!isValidRecoveryPhrase(words)) "Frase de recuperação inválida. Confira o QR Code."
            else {
                words.forEachIndexed { index, word -> entries[index] = word }
                showWords = false
                error = ""
                scanning = false
                null
            }
        } catch (_: Exception) {
            "Este QR Code não contém uma frase de recuperação válida de 12 palavras."
        } catch (_: LinkageError) {
            "Não foi possível carregar a validação da carteira."
        }
    }

    BackHandler {
        if (!busy) { if (stage > 0) stage-- else onBack() }
    }

    if (stage == 2) {
        CreatePinScreen(
            walletName = walletName.trim(),
            words = entries.map(::normalizeRecoveryWord),
            onBack = { stage = 1 },
            onWalletCreated = { name ->
                entries.indices.forEach { entries[it] = "" }
                walletName = ""
                onWalletImported(name)
            }
        )
        return
    }

    val brand = C.Brand
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = brand,
        unfocusedBorderColor = C.Muted,
        focusedLabelColor = brand,
        unfocusedLabelColor = C.Muted,
        focusedTextColor = C.Text,
        unfocusedTextColor = C.Text,
        cursorColor = brand
    )

    fun validateAndContinue() {
        try {
            val words = entries.map(::normalizeRecoveryWord)
            if (isValidRecoveryPhrase(words)) {
                words.forEachIndexed { index, word -> entries[index] = word }
                showWords = false
                error = ""
                stage = 1
            } else {
                error = "Frase inválida. Confira as 12 palavras e a ordem em que foram anotadas."
            }
        } catch (_: Exception) {
            error = "Não foi possível validar a frase. Tente novamente."
        } catch (_: LinkageError) {
            error = "Não foi possível carregar a validação da carteira."
        }
    }

    Column(
        modifier = (if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .background(if (embedded) com.caedralpay.wallet.ui.theme.CaedralColors.Panel else C.Background)
            .imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = if (embedded) 0.dp else 24.dp, vertical = if (embedded) 8.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!embedded) Image(
            painter = painterResource(R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier.size(140.dp).padding(bottom = 16.dp)
        )
        if (!embedded || stage != 0) Text(
            if (stage == 0) "Importe sua carteira" else "Dê um nome à sua carteira",
            color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            if (stage == 0) "Digite as 12 palavras da sua carteira CaedralPay, na ordem correta."
            else "Escolha um nome para identificar sua carteira neste dispositivo.",
            color = C.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        if (stage == 0) {
            OutlinedButton(onClick = { keyboard?.hide(); scanning = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)) {
                CaedralIcon(CaedralSymbol.Qr, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Importar por QR Code", color = brand)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(12 / columns) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(columns) { column ->
                            val index = row * columns + column
                            OutlinedTextField(
                                value = entries[index],
                                onValueChange = { value ->
                                    if (value.length <= 32) {
                                        entries[index] = value
                                        error = ""
                                    }
                                },
                                label = { Text("${index + 1}.") },
                                modifier = Modifier.weight(1f).focusRequester(focus[index])
                                    .semantics { contentDescription = "Palavra ${index + 1} da frase de recuperação" },
                                singleLine = true,
                                enabled = !busy,
                                visualTransformation = if (showWords) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = if (index < 11) ImeAction.Next else ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { if (index < 11) focus[index + 1].requestFocus() },
                                    onDone = { validateAndContinue() }
                                ),
                                colors = colors
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { showWords = !showWords }, enabled = !busy) {
                Text(if (showWords) "Ocultar palavras" else "Mostrar palavras", color = brand)
            }
        } else {
            OutlinedTextField(
                value = walletName,
                onValueChange = { if (it.length <= 30) walletName = it },
                label = { Text("Nome da carteira") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = colors, enabled = !busy
            )
            if (onAdditionalImport != null) {
                OutlinedTextField(
                    value = accountCount,
                    onValueChange = { if (it.length <= 3 && it.all { c -> c in '0'..'9' }) accountCount = it },
                    label = { Text("Contas a recuperar") },
                    supportingText = { Text("De 1 a 100, na ordem original. Você pode adicionar mais contas depois.", color = C.Muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !busy, colors = colors,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )
            }
        }

        val visibleError = externalError.ifEmpty { error }
        if (visibleError.isNotEmpty()) {
            Text(visibleError, color = C.Error, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        CaedralButton(
            onClick = {
                if (stage == 0) validateAndContinue()
                else if (onAdditionalImport != null) {
                    onAdditionalImport(walletName.trim(), entries.map(::normalizeRecoveryWord), requireNotNull(accountCount.toIntOrNull()))
                } else stage = 2
            },
            enabled = !busy && if (stage == 0) entries.all { it.isNotBlank() }
                else walletName.isNotBlank() && (onAdditionalImport == null || accountCount.toIntOrNull() in 1..100),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button, contentColor = C.OnButton,
                disabledContainerColor = C.Selected, disabledContentColor = C.Muted
            )
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = brand, strokeWidth = 2.dp)
            else Text(if (stage == 1 && onAdditionalImport != null) "Importar carteira" else "Prosseguir")
        }
    }
}
