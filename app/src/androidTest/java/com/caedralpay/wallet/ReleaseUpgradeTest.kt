package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.math.BigInteger

/** Public BIP39 fixture, no funds. Separate UID from the user's app and funded QA. */
class ReleaseUpgradeTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private val words = List(11) { "abandon" } + "about"
    private val fixturePin get() = "123456".toCharArray()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("caedralUpgradeQa") == "true")
        val target = instrumentation.targetContext
        check(target.packageName == "com.caedralpay.wallet.upgradevalidation")
        instrumentation.runOnMainSync { activity.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val dir = File(target.filesDir, "upgrade-fixture").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("upgrade-$name", mode)
        }
        WalletVault.lock()
    }
    @After fun finish() { WalletVault.lock() }

    @Test fun seedPersistentState() {
        check(!WalletVault.exists(context)) { "Fixture já existe; não sobrescrever estado anterior." }
        WalletVault.create(context, "Upgrade fixture", words, fixturePin)
        WalletVault.addAccount(context, WalletVault.accountsSnapshot().selectedWalletId, "Second account")
        context.getSharedPreferences("qa", Context.MODE_PRIVATE).edit().putString("marker", "signed-upgrade-v1").commit()
        val accounts = WalletVault.accountsSnapshot().selectedWallet.accounts
        val quote = PreparedTransfer(chain=BscChain.Testnet, asset=WalletAsset.BNB, from=accounts[0].address,
            recipient=accounts[1].address, units=BigInteger.ONE, nonce=BigInteger.ZERO,
            gasPrice=100_000_000.toBigInteger(), gasLimit=21_000.toBigInteger())
        TransactionJournal(context).put(WalletTransaction(quote, "0x"+"1".repeat(64), null, TransactionStatus.Confirmed, 1, "2100000000000"))
        emit("seed", true)
    }

    @Test fun verifyStateAfterSignedUpdateAndRecoverPhrase() {
        check(WalletVault.exists(context))
        WalletVault.unlockWithPin(context, fixturePin)
        val snapshot=WalletVault.accountsSnapshot()
        check(snapshot.selectedWallet.name=="Upgrade fixture" && snapshot.selectedWallet.accounts.size==2)
        check(snapshot.selectedWallet.accounts[1].name=="Second account")
        check(context.getSharedPreferences("qa", Context.MODE_PRIVATE).getString("marker",null)=="signed-upgrade-v1")
        val record=TransactionJournal(context).all().single()
        check(record.hash=="0x"+"1".repeat(64) && record.status==TransactionStatus.Confirmed && record.raw==null)
        for (index in 0..1) check(snapshot.selectedWallet.accounts[index].address==deriveEvmAddress(words.joinToString(" "),index))
        // Recover into a new vault, with a different local PIN and different data key.
        val recoveryDir=File(context.cacheDir,"upgrade-recovery").apply { mkdirs() }
        val recovered=object : ContextWrapper(context) { override fun getFilesDir()=recoveryDir }
        try {
            check(!WalletVault.exists(recovered))
            WalletVault.create(recovered,"Recovery fixture",words,"654321".toCharArray())
            WalletVault.addAccount(recovered,WalletVault.accountsSnapshot().selectedWalletId,"Recovered second")
            check(WalletVault.accountsSnapshot().selectedWallet.accounts.map { it.address }==snapshot.selectedWallet.accounts.map { it.address })
        } finally { WalletVault.lock(); recoveryDir.deleteRecursively() }
        emit("upgradeAndRecovery",true)
    }

    private fun emit(step:String,passed:Boolean) {
        val result=JSONObject().put("step",step).put("passed",passed)
            .put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
            .put("package",context.packageName)
        instrumentation.sendStatus(0,Bundle().apply { putString("caedralUpgradeResult",result.toString()) })
    }
}
