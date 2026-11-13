package com.caedralpay.wallet

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun WalletNotificationSettings(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WalletAlerts.enabled(context) &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted; WalletAlerts.setEnabled(context, granted)
    }
    CaedralSheet("Notificações", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Atividade da carteira", modifier = Modifier.weight(1f).padding(top = 12.dp))
            Switch(checked = enabled, onCheckedChange = { selected ->
                if (selected && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { enabled = selected; WalletAlerts.setEnabled(context, selected) }
            })
        }
        Text("Valores, endereços e nomes não aparecem nos avisos. Abra a Wallet para consultar os detalhes.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Os avisos são gerados neste aparelho quando a Wallet verifica a atividade. Não há monitoramento com o aplicativo fechado nesta etapa.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}
