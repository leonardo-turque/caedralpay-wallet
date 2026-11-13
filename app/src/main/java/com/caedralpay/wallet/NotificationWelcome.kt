package com.caedralpay.wallet

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun NotificationWelcome() {
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("wallet_alerts",Context.MODE_PRIVATE)}
    LaunchedEffect(Unit){if(WalletAlerts.enabled(context))prefs.edit().putBoolean("welcome_asked",true).apply()}
    var show by remember{mutableStateOf(!prefs.getBoolean("welcome_asked",false) && !WalletAlerts.enabled(context))}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){WalletAlerts.setEnabled(context,it)}
    fun answered(){prefs.edit().putBoolean("welcome_asked",true).apply();show=false}
    if(show)AlertDialog(onDismissRequest={answered()},title={Text("Quer receber notificações?")},text={Text("Acompanhe entradas, saídas e pagamentos do cartão. Você pode mudar essa escolha nas configurações.")},
        confirmButton={TextButton(onClick={answered();permission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Ativar notificações")}},
        dismissButton={TextButton(onClick={answered()}){Text("Agora não")}})
}
