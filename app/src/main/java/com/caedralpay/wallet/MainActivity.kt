package com.caedralpay.wallet

import com.caedralpay.wallet.ui.theme.CaedralColors as C

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.WalletTheme
import wallet.core.jni.HDWallet
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.KeyboardType
import java.security.SecureRandom
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.LaunchedEffect
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import javax.crypto.Cipher

class MainActivity : ComponentActivity() {
    internal var lockWallet: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE).getString("mode", "System")
        val systemLight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (appearance == "Light" || appearance == "System" && systemLight) setTheme(R.style.Theme_Wallet_Light)
        super.onCreate(savedInstanceState)

        // Bloqueia capturas comuns e exibição em telas não seguras.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        setContent {
            WalletTheme {
                val background = C.Background
                val light = background.red > 0.5f
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = if (light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                            else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    )
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.rgb((background.red * 255).toInt(), (background.green * 255).toInt(), (background.blue * 255).toInt())))
                }
                Surface(Modifier.fillMaxSize(), color = com.caedralpay.wallet.ui.theme.CaedralColors.Background) {
                    androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) { WelcomeScreen() }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        CaedralNfcSession.clear()
        OriginTapSession.clear()
        WalletVault.lock()
        lockWallet?.invoke()
    }
}

@Composable
fun Modifier.screenEntrance(): Modifier {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f,
        tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "screen")
    val offset = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    // O fundo permanece opaco durante toda a transição.
    return graphicsLayer {
        translationY = (1f - progress) * offset
        scaleX = 0.994f + progress * 0.006f
        scaleY = scaleX
    }
}

@Composable
fun WelcomeScreen() {
    val context = LocalContext.current
    var destination by remember {
        mutableStateOf(if (WalletVault.exists(context)) AppDestination.Unlock else AppDestination.Welcome)
    }
    var showingImportScreen by remember { mutableStateOf(false) }
    var activeWalletName by remember { mutableStateOf("") }
    var showingCreateScreen by remember { mutableStateOf(false) }
    val activity = context as? MainActivity

    fun enterHome(name: String) {
        if (activity != null && !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            WalletVault.lock()
            activeWalletName = ""
            destination = AppDestination.Unlock
        } else {
            activeWalletName = name
            destination = AppDestination.Home
        }
    }

    SideEffect {
        activity?.lockWallet = {
            if (destination == AppDestination.Home) {
                WalletVault.lock()
                activeWalletName = ""
                destination = AppDestination.Unlock
            }
        }
    }
    DisposableEffect(activity) {
        onDispose { activity?.lockWallet = null }
    }

    when (destination) {
        AppDestination.Unlock -> {
            UnlockWalletScreen(onUnlocked = { name ->
                enterHome(name)
            })
            return
        }
        AppDestination.Home -> {
            WalletHomeScreen(walletName = activeWalletName, enableAccountManagement = true)
            return
        }
        AppDestination.Welcome -> Unit
    }

    BackHandler(enabled = showingCreateScreen || showingImportScreen) {
        showingCreateScreen = false
        showingImportScreen = false
    }

    if (showingImportScreen) {
        ImportWalletScreen(
            onBack = { showingImportScreen = false },
            onWalletImported = { name ->
                enterHome(name)
            }
        )
        return
    }

    if (showingCreateScreen) {
        CreateWalletIntroScreen(
            onBack = { showingCreateScreen = false },
            onWalletCreated = { name ->
                enterHome(name)
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenEntrance()
            .imePadding()
            .background(C.Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier
                .size(160.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "CaedralPay Wallet",
            color = C.Text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Comece criando ou importando sua carteira.",
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            color = C.Muted,
            textAlign = TextAlign.Center
        )

        CaedralButton(
            onClick = {
                showingCreateScreen = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton
            )
        ) {
            Text(text = "Criar carteira")
        }

        OutlinedButton(
            onClick = {
                showingImportScreen = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(
                text = "Importar carteira",
                color = C.Text
            )
        }

    }
}

private enum class AppDestination { Welcome, Unlock, Home }

// Registra classes e linhas da falha, sem mensagens de exceção ou dados da carteira.
private fun logVaultFailure(operation: String, error: Throwable) {
    val stage = (error as? VaultSaveException)?.stage ?: operation
    val details = generateSequence(error) { it.cause }.take(8).joinToString("\n") { cause ->
        cause.javaClass.name + "\n" + cause.stackTrace.take(12).joinToString("\n") { "    at $it" }
    }
    Log.e("CaedralVault", "Operation=$operation stage=$stage\n$details")
}

@Composable
fun CreateWalletIntroScreen(
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit
) {
    var walletName by remember { mutableStateOf("") }
    var showingRecoveryScreen by remember { mutableStateOf(false) }

    // A frase fica apenas em memória até a conclusão do cofre.
    var recoveryWords by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var generationError by remember { mutableStateOf(false) }

    fun returnToNameScreen() {
        recoveryWords = emptyList()
        showingRecoveryScreen = false
    }

    BackHandler(enabled = showingRecoveryScreen) {
        returnToNameScreen()
    }

    if (showingRecoveryScreen) {
        RecoveryWordsPreviewScreen(
            walletName = walletName.trim(),
            words = recoveryWords,
            onBack = { returnToNameScreen() },
            onWalletCreated = onWalletCreated
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenEntrance()
            .imePadding()
            .background(C.Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier
                .size(140.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "Dê um nome à sua carteira",
            color = C.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Escolha um nome para identificar sua carteira neste dispositivo.",
            modifier = Modifier.padding(
                top = 16.dp,
                bottom = 24.dp
            ),
            color = C.Muted,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = walletName,
            onValueChange = { newName ->
                if (newName.length <= 30) {
                    walletName = newName
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Nome da carteira")
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Brand,
                unfocusedBorderColor = C.Brand,
                focusedLabelColor = C.Brand,
                unfocusedLabelColor = C.Brand,
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                cursorColor = C.Brand
            )
        )

        CaedralButton(
            onClick = {
                try {
                    generationError = false
                    recoveryWords = generateRecoveryWords()
                    showingRecoveryScreen = true
                } catch (_: Exception) {
                    generationError = true
                } catch (_: LinkageError) {
                    generationError = true
                }
            },
            enabled = walletName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton,
                disabledContainerColor = C.Selected,
                disabledContentColor = C.Muted
            )
        ) {
            Text(text = "Prosseguir")
        }

        if (generationError) {
            Text(
                text = "Não foi possível gerar a frase. Tente novamente.",
                modifier = Modifier.padding(top = 16.dp),
                color = C.Error,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RecoveryWordsPreviewScreen(
    walletName: String,
    words: List<String>,
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit
) {

    var showingConfirmation by remember { mutableStateOf(false) }

    BackHandler(enabled = showingConfirmation) {
        showingConfirmation = false
    }

    if (showingConfirmation) {
        ConfirmRecoveryWordsScreen(
            walletName = walletName,
            words = words,
            onBack = { showingConfirmation = false },
            onWalletCreated = onWalletCreated
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenEntrance()
            .imePadding()
            .background(C.Background)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 24.dp,
                vertical = 40.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier
                .size(140.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "Frase de recuperação",
            color = C.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Anote as 12 palavras, na ordem, e guarde-as em um lugar seguro.",
            modifier = Modifier.padding(top = 16.dp),
            color = C.Brand,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Não compartilhe sua frase com ninguém.",
            modifier = Modifier.padding(
                top = 12.dp,
                bottom = 24.dp
            ),
            color = C.Muted,
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = C.Selected,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (column in 0 until 3) {
                        val number = row * 3 + column + 1

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .background(
                                    color = C.Panel,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = C.Divider,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$number.",
                                color = C.Muted,
                                fontSize = 14.sp
                            )

                            Text(
                                text = words[number - 1],
                                modifier = Modifier.padding(start = 6.dp),
                                color = C.Text,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        CaedralButton(
            onClick = {
                showingConfirmation = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton
            )
        ) {
            Text(text = "Prosseguir")
        }
    }
}

fun generateRecoveryWords(): List<String> {
    System.loadLibrary("TrustWalletCore")

    val wallet = HDWallet(128, "")
    val words = wallet.mnemonic().trim().split(Regex("\\s+"))

    check(words.size == 12) {
        "A biblioteca não retornou 12 palavras."
    }

    return words
}

@Composable
fun ConfirmRecoveryWordsScreen(
    walletName: String,
    words: List<String>,
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit
) {
    val selectedPositions = remember {
        val random = SecureRandom()

        List(4) { row ->
            row * 3 + random.nextInt(3)
        }
    }

    val answers = remember {
        mutableStateListOf("", "", "", "")
    }
    val focusRequesters = remember { List(4) { FocusRequester() } }

    var showingPinScreen by remember { mutableStateOf(false) }

    if (showingPinScreen) {
        CreatePinScreen(
            walletName = walletName,
            words = words,
            onBack = { showingPinScreen = false },
            onWalletCreated = onWalletCreated
        )
        return
    }

    val allCorrect = selectedPositions.indices.all { row ->
        answers[row]
            .trim()
            .equals(words[selectedPositions[row]], ignoreCase = true)
    }

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenEntrance()
            .imePadding()
            .background(C.Background)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 24.dp,
                vertical = 40.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier
                .size(140.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "Confirme sua frase",
            color = C.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Complete as quatro palavras que estão faltando.",
            modifier = Modifier.padding(
                top = 16.dp,
                bottom = 24.dp
            ),
            color = C.Muted,
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = C.Selected,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (column in 0 until 3) {
                        val position = row * 3 + column
                        val isMissing = position == selectedPositions[row]

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .background(
                                    color = C.Panel,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isMissing) {
                                        C.Brand
                                    } else {
                                        C.Divider
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${position + 1}.",
                                color = C.Muted,
                                fontSize = 12.sp
                            )

                            if (isMissing) {
                                BasicTextField(
                                    value = answers[row],
                                    onValueChange = { input ->
                                        if (input.length <= 20) {
                                            answers[row] = input
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 4.dp)
                                        .focusRequester(focusRequesters[row]),
                                    singleLine = true,
                                    cursorBrush = SolidColor(C.Brand),
                                    keyboardActions = KeyboardActions(
                                        onNext = { if (row < 3) focusRequesters[row + 1].requestFocus() },
                                        onDone = { if (allCorrect) showingPinScreen = true }
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = C.Text,
                                        fontSize = 12.sp
                                    ),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = if (row < 3) ImeAction.Next else ImeAction.Done,
                                        autoCorrectEnabled = false
                                    )
                                )
                            } else {
                                Text(
                                    text = words[position],
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .blur(6.dp)
                                        .clearAndSetSemantics { contentDescription = "Palavra oculta" },
                                    color = C.Text,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        CaedralButton(
            onClick = {
                showingPinScreen = true
            },
            enabled = allCorrect,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton,
                disabledContainerColor = C.Selected,
                disabledContentColor = C.Muted
            )
        ) {
            Text(text = "Prosseguir")
        }
    }
}

@Composable
fun CreatePinScreen(
    walletName: String,
    words: List<String>,
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val confirmationFocus = remember { FocusRequester() }
    var showingBiometric by remember { mutableStateOf(false) }
    BackHandler { if (showingBiometric) showingBiometric = false else onBack() }

    if (showingBiometric) {
        BiometricSetupScreen(
            walletName = walletName,
            words = words,
            pin = pin,
            onBack = { showingBiometric = false },
            onWalletCreated = { name ->
                pin = ""
                confirmation = ""
                onWalletCreated(name)
            }
        )
        return
    }

    val complete = pin.length == 6 && confirmation.length == 6
    val mismatch = complete && pin != confirmation
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.Brand,
        unfocusedBorderColor = C.Muted,
        focusedLabelColor = C.Brand,
        unfocusedLabelColor = C.Muted,
        focusedTextColor = C.Text,
        unfocusedTextColor = C.Text,
        cursorColor = C.Brand
    )
    val keyboard = KeyboardOptions(
        keyboardType = KeyboardType.NumberPassword,
        autoCorrectEnabled = false
    )

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(C.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenEntrance()
                .background(C.Background)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.caedralpay),
                contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
                modifier = Modifier.size(140.dp).padding(bottom = 16.dp)
            )
            Text(
                text = "Crie seu PIN",
                color = C.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Escolha um PIN de 6 dígitos para este dispositivo.",
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                color = C.Muted,
                textAlign = TextAlign.Center
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { input ->
                    if (input.length <= 6 && input.all { it in '0'..'9' }) {
                        pin = input
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PIN de 6 dígitos") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = keyboard.copy(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { confirmationFocus.requestFocus() }),
                colors = fieldColors
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = { input ->
                    if (input.length <= 6 && input.all { it in '0'..'9' }) {
                        confirmation = input
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(confirmationFocus),
                label = { Text("Confirme seu PIN") },
                singleLine = true,
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text("Os PINs não coincidem.") }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (complete && !mismatch) showingBiometric = true }),
                colors = fieldColors
            )
            CaedralButton(
                onClick = {
                    showingBiometric = true
                },
                enabled = complete && !mismatch,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.Button,
                    contentColor = C.OnButton,
                    disabledContainerColor = C.Selected,
                    disabledContentColor = C.Muted
                )
            ) { Text("Prosseguir") }
        }
    }
}

@Composable
fun BiometricSetupScreen(
    walletName: String,
    words: List<String>,
    pin: String,
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit
) {
    val context = LocalContext.current
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var savingWithoutBiometric by remember { mutableStateOf(false) }
    val manager = remember { context.getSystemService(BiometricManager::class.java) }
    val available = manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun saveWallet(biometricCipher: Cipher?) {
        if (saving) return
        saving = true
        savingWithoutBiometric = biometricCipher == null
        status = ""
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                WalletVault.create(
                    context = appContext,
                    walletName = walletName,
                    words = words,
                    pin = pin.toCharArray(),
                    authenticatedBiometricCipher = biometricCipher
                )
            }
            context.mainExecutor.execute {
                result.onSuccess { onWalletCreated(walletName) }
                    .onFailure { error ->
                        logVaultFailure("save-wallet", error)
                        saving = false
                        status = if (WalletVault.exists(appContext)) {
                            "Já existe uma carteira protegida neste dispositivo."
                        } else {
                            "Não foi possível proteger a carteira. Tente novamente."
                        }
                    }
            }
        }.start()
    }

    DisposableEffect(Unit) { onDispose { signal?.cancel() } }
    BackHandler(enabled = !saving) { signal?.cancel(); onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .screenEntrance()
            .imePadding()
            .background(C.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier.size(140.dp).padding(bottom = 16.dp)
        )
        Text(
            "Acesso com biometria",
            color = C.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            "Use a biometria do aparelho para abrir sua carteira com segurança. O PIN continuará disponível.",
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            color = C.Muted,
            textAlign = TextAlign.Center
        )

        CaedralButton(
            onClick = {
                signal?.cancel()
                val current = CancellationSignal()
                signal = current
                status = ""
                try {
                    val cipher = BiometricCrypto.cipherForEncryption()
                    val prompt = BiometricPrompt.Builder(context)
                        .setTitle("Habilitar biometria")
                        .setSubtitle("Confirme sua identidade para proteger a carteira")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButton("Cancelar", context.mainExecutor) { _, _ -> current.cancel() }
                        .build()
                    prompt.authenticate(
                        BiometricPrompt.CryptoObject(cipher),
                        current,
                        context.mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                val authorizedCipher = result.cryptoObject?.cipher
                                if (authorizedCipher == null) {
                                    status = "Não foi possível habilitar a biometria."
                                } else {
                                    saveWallet(authorizedCipher)
                                }
                            }

                            override fun onAuthenticationFailed() {
                                status = "Biometria não reconhecida. Tente novamente."
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (!current.isCanceled) {
                                    status = "Autenticação interrompida. Tente novamente."
                                }
                            }
                        }
                    )
                } catch (error: Exception) {
                    logVaultFailure("prepare-biometric", error)
                    status = "Não foi possível preparar a biometria neste aparelho."
                }
            },
            enabled = available && !saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton,
                disabledContainerColor = C.Selected,
                disabledContentColor = C.Muted
            )
        ) {
            if (saving && !savingWithoutBiometric) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = C.Brand
                )
            } else {
                Text("Habilitar Biometria")
            }
        }

        if (!available) {
            Text(
                "Biometria forte não está disponível ou não está cadastrada no aparelho.",
                modifier = Modifier.padding(top = 16.dp),
                color = C.Muted,
                textAlign = TextAlign.Center
            )
        }

        OutlinedButton(
            onClick = { saveWallet(null) },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            if (saving && savingWithoutBiometric) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = C.Brand
                )
            } else {
                Text("Agora não", color = if (saving) Color.Gray else C.Text)
            }
        }

        if (status.isNotEmpty()) {
            Text(
                status,
                modifier = Modifier.padding(top = 16.dp),
                color = C.Brand,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun UnlockWalletScreen(onUnlocked: (String) -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val hasBiometric = remember { runCatching { WalletVault.hasBiometric(context) }.getOrDefault(false) }

    fun unlockWithPin() {
        if (working || pin.length != 6) return
        working = true
        status = ""
        val enteredPin = pin.toCharArray()
        pin = ""
        val appContext = context.applicationContext
        Thread {
            val result = runCatching { WalletVault.unlockWithPin(appContext, enteredPin) }
            context.mainExecutor.execute {
                result.onSuccess { wallet -> onUnlocked(wallet.name) }
                    .onFailure { error ->
                        working = false
                        status = when (error) {
                            is WrongPinException -> "PIN incorreto."
                            is PinTemporarilyLockedException ->
                                "Aguarde ${error.remainingSeconds} segundos antes de tentar novamente."
                            else -> "Não foi possível abrir a carteira."
                        }
                    }
            }
        }.start()
    }

    fun unlockWithBiometric() {
        if (working) return
        signal?.cancel()
        val current = CancellationSignal()
        signal = current
        status = ""
        try {
            val cipher = WalletVault.biometricCipherForUnlock(context)
            val prompt = BiometricPrompt.Builder(context)
                .setTitle("Abrir CaedralPay Wallet")
                .setSubtitle("Confirme sua biometria")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Usar PIN", context.mainExecutor) { _, _ -> current.cancel() }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                current,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val authorizedCipher = requireNotNull(result.cryptoObject?.cipher)
                            val wallet = WalletVault.unlockWithBiometric(context, authorizedCipher)
                            onUnlocked(wallet.name)
                        } catch (_: Exception) {
                            status = "Não foi possível abrir com biometria. Use o PIN."
                        }
                    }

                    override fun onAuthenticationFailed() {
                        status = "Biometria não reconhecida."
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!current.isCanceled) status = "Use o PIN para abrir a carteira."
                    }
                }
            )
        } catch (_: Exception) {
            status = "Biometria indisponível. Use o PIN."
        }
    }

    LaunchedEffect(hasBiometric) {
        if (hasBiometric) unlockWithBiometric()
    }

    DisposableEffect(Unit) { onDispose { signal?.cancel() } }
    BackHandler { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(C.Brand),
            modifier = Modifier.size(140.dp).padding(bottom = 16.dp)
        )
        Text(
            "Abra sua carteira",
            color = C.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { input ->
                if (input.length <= 6 && input.all(Char::isDigit)) pin = input
            },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            label = { Text("PIN de 6 dígitos") },
            singleLine = true,
            enabled = !working,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(onDone = { unlockWithPin() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Brand,
                unfocusedBorderColor = C.Muted,
                focusedLabelColor = C.Brand,
                unfocusedLabelColor = C.Muted,
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                cursorColor = C.Brand
            )
        )
        CaedralButton(
            onClick = { unlockWithPin() },
            enabled = pin.length == 6 && !working,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Button,
                contentColor = C.OnButton,
                disabledContainerColor = C.Selected,
                disabledContentColor = C.Muted
            )
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = C.Background
                )
            } else {
                Text("Entrar")
            }
        }
        if (hasBiometric) {
            OutlinedButton(
                onClick = { unlockWithBiometric() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("Usar biometria", color = C.Text)
            }
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                modifier = Modifier.padding(top = 16.dp),
                color = C.Brand,
                textAlign = TextAlign.Center
            )
        }
    }
}
