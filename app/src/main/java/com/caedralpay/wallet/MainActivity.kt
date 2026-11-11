package com.caedralpay.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import android.os.Bundle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.WalletTheme
import androidx.compose.material3.ButtonDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WalletTheme {
                WelcomeScreen()
            }
        }
    }
}

@Composable
fun WelcomeScreen() {
    var message by remember { mutableStateOf("") }
    var showingCreateScreen by remember { mutableStateOf(false) }

    BackHandler(enabled = showingCreateScreen) {
        showingCreateScreen = false
    }

    if (showingCreateScreen) {
        CreateWalletIntroScreen(
            onBack = { showingCreateScreen = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            modifier = Modifier
                .size(160.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "CaedralPay Wallet",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Comece criando ou importando sua carteira.",
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = {
                showingCreateScreen = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDDD6CD),
                contentColor = Color(0xFF0B0B0B)
            )
        ) {
            Text(text = "Criar carteira")
        }

        OutlinedButton(
            onClick = {
                message = "A importação da carteira será adicionada na próxima etapa."
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(text = "Importar carteira", color = Color.White)
        }

        if (message.isNotEmpty()) {
            Text(
                text = message,
                modifier = Modifier.padding(top = 24.dp),
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
        }
    }
}
@Composable
fun CreateWalletIntroScreen(onBack: () -> Unit) {
    var walletName by rememberSaveable { mutableStateOf("") }
    var showingRecoveryScreen by remember { mutableStateOf(false) }

    BackHandler(enabled = showingRecoveryScreen) {
        showingRecoveryScreen = false
    }

    if (showingRecoveryScreen) {
        RecoveryWordsPreviewScreen(
            onBack = { showingRecoveryScreen = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.caedralpay),
            contentDescription = "Logo da CaedralPay",
            modifier = Modifier
                .size(140.dp)
                .padding(bottom = 16.dp)
        )

        Text(
            text = "Dê um nome à sua carteira",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Escolha um nome para identificar sua carteira neste dispositivo.",
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            color = Color.LightGray,
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
                focusedBorderColor = Color(0xFFDDD6CD),
                unfocusedBorderColor = Color(0xFFDDD6CD),
                focusedLabelColor = Color(0xFFDDD6CD),
                unfocusedLabelColor = Color(0xFFDDD6CD),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFDDD6CD)
            )
        )

        Button(
            onClick = {
                showingRecoveryScreen = true
            },
            enabled = walletName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDDD6CD),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF4A4845),
                disabledContentColor = Color.LightGray
            )
        ) {
            Text(text = "Prosseguir")
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(
                text = "Voltar",
                color = Color.White
            )
        }
    }
}
@Composable
fun RecoveryWordsPreviewScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Frase de recuperação",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Prévia visual — nenhuma carteira foi criada.",
            modifier = Modifier.padding(top = 16.dp),
            color = Color(0xFFDDD6CD),
            textAlign = TextAlign.Center
        )

        Text(
            text = "As palavras reais aparecerão aqui quando a geração " +
                    "segura estiver integrada.",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF202124),
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
                                    color = Color(0xFF101113),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF303238),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$number.",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )

                            Text(
                                text = "—",
                                modifier = Modifier.padding(start = 6.dp),
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
        ) {
            Text(
                text = "Voltar",
                color = Color.White
            )
        }
    }
}