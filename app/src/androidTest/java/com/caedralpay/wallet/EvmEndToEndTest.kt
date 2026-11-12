package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.net.Socket
import java.util.UUID

/** Run explicitly with qa/run-evm-tests.sh; local Anvil chains, never public funds. */
class EvmEndToEndTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var id: String
    private lateinit var from: String
    private val recipient = "0x" + "3".repeat(40)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val enabled get() = InstrumentationRegistry.getArguments().getString("caedralLocalEvm") == "true"

    @Before fun prepare() {
        Assume.assumeTrue("Local EVM suite requires qa/run-evm-tests.sh", enabled)
        val target = instrumentation.targetContext
        check(target.packageName == "com.caedralpay.wallet.validation")
        val testActivity = activity.activity
        instrumentation.runOnMainSync { testActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        directory = File(target.cacheDir, "evm-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${directory.name}-$name", mode)
        }
        WalletVault.lock()
        WalletVault.create(context, "Fixture", List(11) { "abandon" } + "about", "123456".toCharArray())
        val snapshot = WalletVault.accountsSnapshot(); id = snapshot.selectedWalletId; from = snapshot.selectedAccount.address
    }
    @After fun clean() { if (::directory.isInitialized) { WalletVault.lock(); directory.deleteRecursively() } }

    private fun transport(loseResponse: Boolean = false) = EvmTransport { endpoint, method, params ->
        val chain = if ("testnet" in endpoint) BscChain.Testnet else BscChain.Mainnet
        val result = local(chain, method, params)
        if (loseResponse && method == "eth_sendRawTransaction") throw java.io.IOException("simulated response loss")
        result
    }
    private suspend fun local(chain: BscChain, method: String, params: List<Any>): Any? = withContext(Dispatchers.IO) {
        val port = if (chain.isTestnet) 18597 else 18556
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", JSONArray(params)).toString().toByteArray()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body); flush()
            }
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
            val json = JSONObject(response)
            if (json.has("error")) throw java.io.IOException("Local EVM rejected method $method")
            if (json.isNull("result")) null else json.get("result")
        }
    }

    @Test fun sendsNativeAndTokenOnBothChainIdsAndRestoresJournal() = runBlocking {
        for (chain in BscChain.entries) for (asset in WalletAsset.entries) {
            val gateway = EvmGateway(transport())
            val engine = TransactionEngine(context, gateway)
            val quote = TransferRepository(gateway).prepare(chain, asset, from, recipient, "0.0001")
            val token = WalletVault.authorizeWithPin(context, "123456".toCharArray(), VaultAction.SignTransfer, id, 0, quote.digest)
            val submitted = engine.send(quote, id, 0, token)
            Assert.assertTrue(submitted.hash.matches(Regex("0x[0-9a-f]{64}")))
            val tx = local(chain, "eth_getTransactionByHash", listOf(submitted.hash)) as JSONObject
            Assert.assertEquals(from.lowercase(), tx.getString("from").lowercase())
            Assert.assertEquals(quote.to.lowercase(), tx.getString("to").lowercase())
            Assert.assertEquals(quote.nonce, EvmBalanceCodec.quantity(tx.getString("nonce")))
            Assert.assertEquals(quote.data, tx.getString("input"))
            Assert.assertEquals(quote.value, EvmBalanceCodec.quantity(tx.getString("value")))
            local(chain, "anvil_mine", listOf("0x10"))
            val confirmed = engine.refresh(submitted)
            Assert.assertEquals(TransactionStatus.Confirmed, confirmed.status)
            Assert.assertNull(confirmed.raw)
            val restored = TransactionEngine(context, gateway).history().first { it.hash == submitted.hash }
            Assert.assertEquals(confirmed, restored)
        }
        val bytes = File(directory, "wallet_activity.bin").readBytes().toString(Charsets.ISO_8859_1)
        Assert.assertFalse(bytes.contains(from))
    }

    @Test fun lostBroadcastResponseCanNeverCreateASecondPayment() = runBlocking {
        val chain = BscChain.Testnet
        val gateway = EvmGateway(transport(true))
        val engine = TransactionEngine(context, gateway)
        val quote = TransferRepository(gateway).prepare(chain, WalletAsset.BNB, from, recipient, "0.0001")
        val token = WalletVault.authorizeWithPin(context, "123456".toCharArray(), VaultAction.SignTransfer, id, 0, quote.digest)
        val record = engine.send(quote, id, 0, token)
        Assert.assertEquals(TransactionStatus.Signed, record.status)
        Assert.assertEquals(record.hash, engine.resume(record).hash)
        val nonce = EvmBalanceCodec.quantity(local(chain, "eth_getTransactionCount", listOf(from, "latest")) as String)
        Assert.assertEquals(quote.nonce + java.math.BigInteger.ONE, nonce)
        local(chain, "anvil_mine", listOf("0x10"))
        Assert.assertEquals(TransactionStatus.Confirmed, engine.refresh(record).status)
    }

    @Test fun reviewedTransactionCannotBeChangedAfterAuthentication() = runBlocking<Unit> {
        val quote = TransferRepository(EvmGateway(transport())).prepare(BscChain.Testnet, WalletAsset.BNB, from, recipient, "0.0001")
        val token = WalletVault.authorizeWithPin(context, "123456".toCharArray(), VaultAction.SignTransfer, id, 0, quote.digest)
        Assert.assertThrows(IllegalStateException::class.java) {
            WalletVault.signTransfer(context, id, 0, quote.copy(units = java.math.BigInteger.ONE), token)
        }
    }
}
