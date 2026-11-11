package com.caedralpay.wallet

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
fun ImportWalletScreen(onBack: () -> Unit, onWalletImported: () -> Unit) {
    val entries = remember { mutableStateListOf<String>().apply { repeat(12) { add("") } } }
    val focus = remember { List(12) { FocusRequester() } }
    var stage by remember { mutableStateOf(0) }
    var walletName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showWords by remember { mutableStateOf(false) }

    BackHandler {
        if (stage > 0) stage-- else onBack()
    }

    if (stage == 2) {
        CreatePinScreen(
            walletName = walletName.trim(),
            words = entries.map(::normalizeRecoveryWord),
            onBack = { stage = 1 },
            onWalletCreated = {
                entries.indices.forEach { entries[it] = "" }
                walletName = ""
                onWalletImported()
            }
        )
        return
    }

    val brand = Color(0xFFDDD6CD)
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = brand,
        unfocusedBorderColor = Color(0xFF77736E),
        focusedLabelColor = brand,
        unfocusedLabelColor = Color.LightGray,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
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
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0B))
            .screenEntrance().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            modifier = Modifier.size(140.dp).padding(bottom = 16.dp)
        )
        Text(
            if (stage == 0) "Importe sua carteira" else "Dê um nome à sua carteira",
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            if (stage == 0) "Digite as 12 palavras da sua carteira CaedralPay, na ordem correta."
            else "Escolha um nome para identificar sua carteira neste dispositivo.",
            color = Color.LightGray, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        if (stage == 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { column ->
                            val index = row * 3 + column
                            OutlinedTextField(
                                value = entries[index],
                                onValueChange = { value ->
                                    if (value.length <= 32) {
                                        entries[index] = value
                                        error = ""
                                    }
                                },
                                label = { Text("${index + 1}.") },
                                modifier = Modifier.weight(1f).focusRequester(focus[index]),
                                singleLine = true,
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
            TextButton(onClick = { showWords = !showWords }) {
                Text(if (showWords) "Ocultar palavras" else "Mostrar palavras", color = brand)
            }
        } else {
            OutlinedTextField(
                value = walletName,
                onValueChange = { if (it.length <= 30) walletName = it },
                label = { Text("Nome da carteira") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = colors
            )
        }

        if (error.isNotEmpty()) {
            Text(error, color = Color(0xFFFFB4AB), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = { if (stage == 0) validateAndContinue() else stage = 2 },
            enabled = if (stage == 0) entries.all { it.isNotBlank() } else walletName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = brand, contentColor = Color(0xFF0B0B0B),
                disabledContainerColor = Color(0xFF4A4845), disabledContentColor = Color.LightGray
            )
        ) { Text("Prosseguir") }
    }
}
