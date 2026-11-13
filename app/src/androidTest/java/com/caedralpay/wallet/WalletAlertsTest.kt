package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalletAlertsTest {
    @get:org.junit.Rule val activity=androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun keepUnlocked(){InstrumentationRegistry.getInstrumentation().runOnMainSync{activity.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    @Test fun notificationKeepsFinancialDetailsOffLockScreen() {
        val context = context()
        org.junit.Assume.assumeTrue(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        try {
            WalletAlerts.setEnabled(context, true)
            val event = WalletAlert(AlertKind.OutgoingConfirmed, "97:private-reference:" + UUID.randomUUID())
            WalletAlerts.publish(context, event)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val deadline=android.os.SystemClock.elapsedRealtime()+3000
            while(manager.activeNotifications.none{it.tag==event.dedupKey} && android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50)
            val notification = manager.activeNotifications.single { it.tag == event.dedupKey }.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("Envio confirmado", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("CaedralPay Wallet", notification.publicVersion.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertFalse(notification.extras.toString().contains("private-reference"))
            assertNotNull(notification.contentIntent)
        } finally { WalletAlerts.setEnabled(context, false); context.filesDir.deleteRecursively() }
    }
    private fun context(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".validation") || target.packageName.endsWith(".nfclab"))
        val name = "alerts-test-" + UUID.randomUUID()
        val directory = File(target.cacheDir, name).apply { mkdirs() }
        return object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences("$name-$key", mode)
        }
    }
    @Test fun duplicateNotificationsDoNotRepeatPopup() = runBlocking {
        val context = context(); val events = mutableListOf<WalletAlert>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { WalletAlerts.popups.collect { events += it } }
        try {
            val pending = WalletAlert(AlertKind.OutgoingPending, "97:" + UUID.randomUUID())
            WalletAlerts.publish(context, pending); WalletAlerts.publish(context, pending)
            WalletAlerts.publish(context, pending.copy(kind = AlertKind.OutgoingConfirmed)); yield()
            assertEquals(listOf(AlertKind.OutgoingPending, AlertKind.OutgoingConfirmed), events.map { it.kind })
        } finally { job.cancelAndJoin(); context.filesDir.deleteRecursively() }
    }
    @Test fun encryptedStoreBindsCiphertextToRecordName() {
        val context = context()
        try {
            val value = "private-test-card-binding".toByteArray()
            PrivateRecordStore(context, "alpha").write(value)
            assertArrayEquals(value, PrivateRecordStore(context, "alpha").read())
            val file = File(context.filesDir, "alpha.bin")
            assertFalse(file.readText().contains("private-test-card-binding"))
            file.copyTo(File(context.filesDir, "beta.bin"))
            try { PrivateRecordStore(context, "beta").read(); fail("Wrong AAD accepted") }
            catch (_: java.security.GeneralSecurityException) { }
        } finally { context.filesDir.deleteRecursively() }
    }
    @Test fun cardBindingIsMetadataOnlyAndPersists() {
        if (!BuildConfig.CAEDRAL_PROTOCOL_PREVIEW) return
        val context = context()
        try {
            val store = TestCardBindings(context)
            val card = PhysicalCard("ab".repeat(32), 1024, 16)
            val binding = store.bind(card, "test-wallet", WalletAccount(0, "Test", "0x" + "1".repeat(40)))
            assertEquals(binding, TestCardBindings(context).find(card.fingerprint))
            assertFalse(File(context.filesDir, "test_card_bindings.bin").readText().contains(binding.address))
            val file = File(context.filesDir, "test_card_bindings.bin")
            val corrupt = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; file.writeBytes(corrupt)
            try { store.bind(card, "test-wallet", WalletAccount(0, "Test", binding.address)); fail("Corrupt file overwritten") }
            catch (_: java.security.GeneralSecurityException) { }
            assertArrayEquals(corrupt, file.readBytes())
        } finally { context.filesDir.deleteRecursively() }
    }
    @Test fun incomingRequiresReceiptAndDoesNotReplayBaseline() = runBlocking {
        val context = context(); val events = mutableListOf<WalletAlert>()
        val from = "0x" + "1".repeat(40); val to = "0x" + "2".repeat(40)
        val txHash = "0x" + "a".repeat(64)
        fun hash(n: Int) = "0x" + n.toString(16).padStart(64, '0')
        var head = 100
        var forged = false
        val transport = EvmTransport { _, method, params ->
            when (method) {
                "eth_chainId" -> "0x61"
                "eth_blockNumber" -> head.toBigInteger().rpcHex()
                "eth_getBlockByNumber" -> {
                    val n = params[0].toString().removePrefix("0x").toInt(16)
                    JSONObject().put("hash", hash(n)).put("number", n.toBigInteger().rpcHex()).put("parentHash", hash(n - 1))
                        .put("transactions", JSONArray().apply {
                            if (n == 87) put(JSONObject().put("hash", txHash).put("from", from).put("to", to).put("value", "0x1"))
                        })
                }
                "eth_getTransactionReceipt" -> JSONObject().put("transactionHash", txHash).put("blockHash", hash(if (forged) 88 else 87))
                    .put("status", "0x1").put("to", to).put("from", from)
                "eth_getLogs" -> JSONArray()
                else -> error("Unexpected method")
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { WalletAlerts.popups.collect { events += it } }
        try {
            val watcher = IncomingActivityWatcher(EvmGateway(transport))
            watcher.poll(context, BscChain.Testnet, to); yield(); assertTrue(events.isEmpty())
            head = 101; forged = true
            try { watcher.poll(context, BscChain.Testnet, to); fail("Forged receipt accepted") }
            catch (_: IllegalStateException) { }
            yield(); assertTrue(events.isEmpty())
            forged = false; watcher.poll(context, BscChain.Testnet, to); yield()
            assertEquals(listOf(AlertKind.IncomingConfirmed), events.map { it.kind })
            watcher.poll(context, BscChain.Testnet, to); yield(); assertEquals(1, events.size)
        } finally { job.cancelAndJoin(); context.filesDir.deleteRecursively() }
    }
    @Test fun incomingTokenRequiresLogInSuccessfulCanonicalReceipt() = runBlocking {
        val context = context(); val events = mutableListOf<WalletAlert>()
        val account = "0x" + "2".repeat(40)
        val txHash = "0x" + "b".repeat(64)
        fun hash(n: Int) = "0x" + n.toString(16).padStart(64, '0')
        var head = 100
        var includeProof = false
        val log = JSONObject().put("address", BscChain.Testnet.usdtContract).put("removed", false)
            .put("blockNumber", "0x57").put("blockHash", hash(87)).put("transactionHash", txHash).put("logIndex", "0x0")
            .put("data", "0x" + "1".padStart(64, '0')).put("topics", JSONArray(listOf(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x" + "1".repeat(40).padStart(64, '0'), "0x" + account.drop(2).padStart(64, '0'))))
        val transport = EvmTransport { _, method, params ->
            when (method) {
                "eth_chainId" -> "0x61"
                "eth_blockNumber" -> head.toBigInteger().rpcHex()
                "eth_getBlockByNumber" -> {
                    val n = params[0].toString().removePrefix("0x").toInt(16)
                    JSONObject().put("number", n.toBigInteger().rpcHex()).put("hash", hash(n))
                        .put("parentHash", hash(n - 1)).put("transactions", JSONArray())
                }
                "eth_getLogs" -> JSONArray().put(log)
                "eth_getTransactionReceipt" -> JSONObject().put("transactionHash", txHash).put("blockHash", hash(87))
                    .put("status", "0x1").put("logs", JSONArray().apply { if (includeProof) put(log) })
                else -> error("Unexpected method")
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { WalletAlerts.popups.collect { events += it } }
        try {
            val watcher = IncomingActivityWatcher(EvmGateway(transport))
            watcher.poll(context, BscChain.Testnet, account); head = 101
            try { watcher.poll(context, BscChain.Testnet, account); fail("Unproven token log accepted") }
            catch (_: IllegalStateException) { }
            yield(); assertTrue(events.isEmpty())
            includeProof = true; watcher.poll(context, BscChain.Testnet, account); yield()
            assertEquals(1, events.size); assertEquals(AlertKind.IncomingConfirmed, events.single().kind)
        } finally { job.cancelAndJoin(); context.filesDir.deleteRecursively() }
    }

}
