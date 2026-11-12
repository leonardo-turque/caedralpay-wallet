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
import java.math.BigDecimal

/** Explicit opt-in only. Uses fresh, privately provisioned QA accounts, never a user's wallet. */
class PublicNetworkValidationTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var fixture: JSONObject
    private lateinit var report: File
    private fun pin() = fixture.getString("pin").toCharArray()

    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("caedralPublicEvm") == "true")
        val target = instrumentation.targetContext
        check(target.packageName == "com.caedralpay.wallet.validation")
        instrumentation.runOnMainSync { activity.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        fixture = JSONObject(File(target.filesDir, "public-network-fixture.json").readText())
        val directory = File(target.filesDir, "public-network-vault").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("public-qa-$name", mode)
        }
        report = File(target.filesDir, "public-network-report.json")
        WalletVault.lock()
        if (!WalletVault.exists(context)) WalletVault.create(context, "Public QA", fixture.getString("mnemonic").split(" "), pin())
        else WalletVault.unlockWithPin(context, pin())
        val snapshot = WalletVault.accountsSnapshot()
        if (snapshot.selectedWallet.accounts.size == 1) WalletVault.addAccount(context, snapshot.selectedWalletId, "QA return")
        val accounts = WalletVault.accountsSnapshot().selectedWallet.accounts
        for (index in 0..1) check(accounts[index].address.equals(fixture.getJSONArray("addresses").getString(index), true))
    }

    @After fun finish() { WalletVault.lock() } // Preserve funded QA vault for retries/recovery.

    @Test fun readPublicNetworks() = runBlocking {
        val results = JSONArray()
        for (chain in BscChain.entries) {
            val balance = BscBalanceRepository().load(fixture.getJSONArray("addresses").getString(0), chain)
            results.put(JSONObject().put("chainId", chain.chainId).put("address", balance.address)
                .put("native", balance.nativeBalance.toPlainString()).put("usdt", balance.usdtBalance?.toPlainString())
                .put("block", balance.blockNumber.toString()).put("endpoint", balance.rpcEndpoint))
        }
        writeReport("balances", results)
    }

    @Test fun sendAndReturnNativeAndToken() = runBlocking {
        val chainId = InstrumentationRegistry.getArguments().getString("caedralPublicChain")?.toInt()
        val chain = BscChain.entries.single { it.chainId == chainId }
        val snapshot = WalletVault.accountsSnapshot()
        val walletId = snapshot.selectedWalletId
        val accounts = snapshot.selectedWallet.accounts
        val engine = TransactionEngine(context)
        val allSteps = listOf(
            Triple(0, WalletAsset.BNB, if (chain.isTestnet) "0.002" else "0.0001"),
            Triple(0, WalletAsset.USDT, "0.000001"),
            Triple(1, WalletAsset.USDT, "0.000001"),
            Triple(1, WalletAsset.BNB, "0.000001")
        )
        val steps = if (InstrumentationRegistry.getArguments().getString("caedralPublicAssets") == "native")
            listOf(allSteps.first(), allSteps.last()) else allSteps
        for ((sender, asset, amount) in steps) {
            val from = accounts[sender].address
            val to = accounts[1 - sender].address
            val previous = engine.history().singleOrNull {
                it.quote.chain == chain && it.quote.asset == asset && it.quote.from.equals(from, true) && it.quote.recipient.equals(to, true)
            }
            val before = if (previous == null) BscBalanceRepository().load(to, chain) else null
            var record = previous ?: run {
                val quote = TransferRepository().prepare(chain, asset, from, to, amount)
                // Hard spend bounds apply to these disposable QA accounts only.
                val maxFee = BigDecimal(if (chain.isTestnet) "0.001" else "0.00005").movePointRight(18).toBigIntegerExact()
                check(quote.gasPrice * quote.gasLimit <= maxFee) { "QA fee budget exceeded; no transaction signed." }
                val auth = WalletVault.authorizeWithPin(context, pin(), VaultAction.SignTransfer, walletId, sender, quote.digest)
                engine.send(quote, walletId, sender, auth)
            }
            check(record.quote.units == BigDecimal(amount).movePointRight(18).toBigIntegerExact())
            if (!record.terminal) record = engine.resume(record)
            withTimeout(180_000) {
                while (!record.terminal) { delay(2_000); record = engine.refresh(record) }
            }
            check(record.status == TransactionStatus.Confirmed)
            if (before != null) {
                val after = BscBalanceRepository().load(to, chain)
                val delta = if (asset == WalletAsset.BNB) after.nativeBalance - before.nativeBalance
                    else requireNotNull(after.usdtBalance) - requireNotNull(before.usdtBalance)
                check(delta >= BigDecimal(amount)) { "A consulta de saldo não refletiu o recebimento esperado." }
            }
            val restored = TransactionEngine(context).history().single { it.hash == record.hash }
            check(restored == record && restored.raw == null)
            val gateway = EvmGateway()
            gateway.read(chain) { rpc ->
                val tx = rpc.call("eth_getTransactionByHash", record.hash) as JSONObject
                check(tx.getString("from").equals(from, true) && tx.getString("to").equals(record.quote.to, true))
                check(EvmBalanceCodec.quantity(tx.getString("nonce")) == record.quote.nonce)
                check(EvmBalanceCodec.quantity(tx.getString("value")) == record.quote.value)
                check(tx.getString("input").equals(record.quote.data, true))
            }
            val history = JSONArray(engine.history().filter { it.terminal }.map {
                JSONObject().put("chainId", it.quote.chain.chainId).put("asset", it.quote.asset.name)
                    .put("from", it.quote.from).put("recipient", it.quote.recipient).put("units", it.quote.units.toString())
                    .put("hash", it.hash).put("status", it.status.name).put("block", it.block).put("actualFeeWei", it.actualFee)
            })
            writeReport("transactions", history)
        }
    }

    @Test fun concurrentReadCapacity() = runBlocking {
        val results = JSONArray()
        for (chain in BscChain.entries) {
            val runs = (0 until 3).flatMap {
                coroutineScope {
                    (0 until 5).map { async(Dispatchers.IO) {
                        val start = android.os.SystemClock.elapsedRealtime()
                        val balance = BscBalanceRepository().load(fixture.getJSONArray("addresses").getString(0), chain)
                        check(balance.chain == chain && balance.usdtBalance != null)
                        android.os.SystemClock.elapsedRealtime() - start
                    } }.awaitAll()
                }
            }.sorted()
            results.put(JSONObject().put("chainId", chain.chainId).put("operations", runs.size).put("concurrency", 5)
                .put("failures", 0).put("p50Ms", runs[runs.size / 2]).put("maxMs", runs.last()))
        }
        writeReport("capacity", results)
    }

    @Test fun verifyRecoveryAndExistingVault() {
        val before = WalletVault.accountsSnapshot()
        WalletVault.lock()
        WalletVault.unlockWithPin(context, pin())
        check(before == WalletVault.accountsSnapshot())
        val words = fixture.getString("mnemonic")
        for (index in 0..1) check(deriveEvmAddress(words, index).equals(before.selectedWallet.accounts[index].address, true))
        writeReport("recovery", JSONObject().put("sameAddresses", true).put("vaultReopened", true)
            .put("applicationVersionCode", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode))
    }

    private fun writeReport(key: String, value: Any) {
        val data = if (report.exists()) JSONObject(report.readText()) else JSONObject()
        data.put(key, value)
        report.writeText(data.toString(2)) // Public metadata only; never fixture/PIN/raw signed envelopes.
    }
}
