package com.caedralpay.wallet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import java.security.MessageDigest

internal enum class AlertKind(val title: String) {
    OutgoingPending("Envio em andamento"), OutgoingConfirmed("Envio confirmado"),
    OutgoingFailed("Envio não concluído"), IncomingConfirmed("Recebimento confirmado"),
    CardDetected("Cartão reconhecido"), CardPurchaseConfirmed("Compra confirmada")
}
internal data class WalletAlert(val kind: AlertKind, val reference: String) {
    init { require(reference.length in 1..256) }
    val dedupKey: String get() = MessageDigest.getInstance("SHA-256")
        .digest("${kind.name}:$reference".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Generic lock-screen content only. No amounts, counterparties, wallet names or addresses. */
internal object WalletAlerts {
    private val events = MutableSharedFlow<WalletAlert>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val popups = events.asSharedFlow()
    private const val CHANNEL = "caedral_activity_v1"
    private val monitor = Any()
    fun enabled(context: Context) = context.getSharedPreferences("wallet_alerts", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("wallet_alerts", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    fun outgoing(context: Context, record: WalletTransaction) {
        val kind = when (record.status) {
            TransactionStatus.Signed -> return
            TransactionStatus.Pending -> AlertKind.OutgoingPending
            TransactionStatus.Confirmed -> AlertKind.OutgoingConfirmed
            TransactionStatus.Failed -> AlertKind.OutgoingFailed
        }
        publish(context, WalletAlert(kind, "${record.quote.chain.chainId}:${record.hash}"))
    }
    fun publish(context: Context, alert: WalletAlert) = synchronized(monitor) {
        // Notification failures must never change the outcome of a durable financial operation.
        try {
            val store = PrivateRecordStore(context, "wallet_alert_seen")
            val raw = store.read()
            val seen = try { raw?.let { JSONArray(it.toString(Charsets.UTF_8)) } ?: JSONArray() }
                finally { raw?.fill(0) }
            val keys = (0 until seen.length()).map { seen.getString(it) }.toMutableList()
            if (alert.dedupKey in keys) return@synchronized
            keys.add(alert.dedupKey)
            val clear = JSONArray(keys.takeLast(512)).toString().toByteArray()
            try { store.write(clear) } finally { clear.fill(0) }
            events.tryEmit(alert)
            if (!enabled(context) || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return@synchronized
            val manager = context.getSystemService(NotificationManager::class.java)
            if (alert.kind == AlertKind.OutgoingConfirmed || alert.kind == AlertKind.OutgoingFailed)
                manager.cancel(WalletAlert(AlertKind.OutgoingPending, alert.reference).dedupKey, 1)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Atividade da carteira", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Atualizações de pagamentos e recebimentos"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("CaedralPay Wallet").setContentText("Há uma atualização na sua carteira.").build()
            val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.kind.title).setContentText("Abra a Wallet para consultar os detalhes.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(open)
                .setAutoCancel(true).setOnlyAlertOnce(true).setLocalOnly(true).build()
            manager.notify(alert.dedupKey, 1, notification)
        } catch (_: Exception) { /* No financial data or exception messages in diagnostic logs. */ }
    }
}
