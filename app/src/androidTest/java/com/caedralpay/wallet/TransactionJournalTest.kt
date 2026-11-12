package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File
import java.math.BigInteger
import java.util.UUID

class TransactionJournalTest {
    @get:Rule val activity=createAndroidComposeRule<ComponentActivity>()
    private lateinit var directory:File
    private lateinit var context:Context
    @Before fun setup() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        check(target.packageName=="com.caedralpay.wallet.validation")
        instrumentation.runOnMainSync { activity.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        directory=File(target.cacheDir,"journal-${UUID.randomUUID()}").apply { mkdirs() }
        context=object:ContextWrapper(target) { override fun getFilesDir()=directory }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun record(number:Int,terminal:Boolean):WalletTransaction {
        val quote=PreparedTransfer(chain=BscChain.Testnet,asset=WalletAsset.BNB,
            from="0x"+number.toString(16).padStart(40,'0'),recipient="0x"+"f".repeat(40),
            units=BigInteger.ONE,nonce=BigInteger.ZERO,gasPrice=BigInteger.ONE,gasLimit=21_000.toBigInteger())
        return WalletTransaction(quote,"0x"+number.toString(16).padStart(64,'0'),if(terminal)null else "0x01",
            if(terminal)TransactionStatus.Confirmed else TransactionStatus.Signed)
    }
    @Test fun retentionKeepsPendingEnvelopeAndAllowsFurtherPayments() {
        val journal=TransactionJournal(context,2)
        val pending=record(1,false);val oldFinal=record(2,true);val newFinal=record(3,true)
        journal.put(pending);journal.put(oldFinal);journal.put(newFinal)
        Assert.assertEquals(listOf(pending,newFinal),TransactionJournal(context,2).all())
    }
    @Test fun capacityNeverDiscardsPendingPayments() {
        val journal=TransactionJournal(context,2)
        val first=record(1,false);val second=record(2,false)
        journal.put(first);journal.put(second)
        Assert.assertThrows(IllegalArgumentException::class.java) { journal.put(record(3,true)) }
        Assert.assertEquals(listOf(first,second),journal.all())
    }
    @Test fun tamperedJournalCannotBeReadOrOverwritten() {
        val journal=TransactionJournal(context,2);journal.put(record(1,false))
        val file=File(directory,"wallet_activity.bin")
        val tampered=file.readBytes().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        Assert.assertThrows(Exception::class.java) { journal.all() }
        Assert.assertThrows(Exception::class.java) { journal.put(record(2,true)) }
        Assert.assertArrayEquals(tampered,file.readBytes())
    }
}
