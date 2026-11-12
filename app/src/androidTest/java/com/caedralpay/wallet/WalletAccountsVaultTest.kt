package com.caedralpay.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.view.WindowManager
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletAccountsVaultTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    private lateinit var isolated: Context
    private lateinit var directory: File
    private val publicWords = List(11) { "abandon" } + "about"
    private fun testPin() = "123456".toCharArray()

    @Before
    fun prepareIsolatedVault() {
        val testActivity = activity.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            testActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        directory = File(target.cacheDir, "vault-test-$testId").apply { check(mkdirs()) }
        isolated = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("test-$testId-$name", mode)
        }
        WalletVault.lock()
        WalletVault.create(isolated, "Principal", publicWords, testPin())
    }

    @After
    fun clearIsolatedVault() {
        WalletVault.lock()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun sensitiveActionsRequireScopedSingleUseAuthentication() {
        val id = WalletVault.accountsSnapshot().selectedWalletId
        assertThrows(IllegalStateException::class.java) { WalletVault.revealPhrase(isolated, id, VaultAuthorization()) }
        assertThrows(WrongPinException::class.java) { WalletVault.authorizeWithPin(isolated, "999999".toCharArray(), VaultAction.RevealPhrase, id) }
        val wrongAction = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.ChangePin, id)
        assertThrows(IllegalStateException::class.java) { WalletVault.revealPhrase(isolated, id, wrongAction) }
        val token = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.RevealPhrase, id)
        assertEquals(publicWords, WalletVault.revealPhrase(isolated, id, token))
        assertThrows(IllegalStateException::class.java) { WalletVault.revealPhrase(isolated, id, token) }
        val revoked = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.RevealPhrase, id)
        WalletVault.lock()
        assertThrows(IllegalStateException::class.java) { WalletVault.revealPhrase(isolated, id, revoked) }
    }

    @Test
    fun removalAndRenamingPreserveDerivedAddressesAfterReopening() {
        val id = WalletVault.accountsSnapshot().selectedWalletId
        WalletVault.addAccount(isolated, id, "Dois")
        val three = WalletVault.addAccount(isolated, id, "Três")
        val addresses = three.selectedWallet.accounts.associate { it.index to it.address }
        WalletVault.renameWallet(isolated, id, "Pessoal")
        WalletVault.renameAccount(isolated, id, 2, "Reserva")
        WalletVault.selectAccount(isolated, id, 1)
        val token = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.RemoveAccount, id, 1)
        val removed = WalletVault.removeAccount(isolated, id, 1, token)
        assertEquals(listOf(0, 2), removed.selectedWallet.accounts.map { it.index })
        assertEquals(0, removed.selectedAccountIndex)
        assertThrows(IllegalArgumentException::class.java) { WalletVault.selectAccount(isolated, id, 1) }
        WalletVault.lock(); WalletVault.unlockWithPin(isolated, testPin())
        val reopened = WalletVault.accountsSnapshot()
        assertEquals("Pessoal", reopened.selectedWallet.name)
        reopened.selectedWallet.accounts.forEach { assertEquals(addresses[it.index], it.address) }
        assertEquals("Reserva", reopened.selectedWallet.accounts.last().name)
        val added = WalletVault.addAccount(isolated, id, "Nova")
        assertEquals(3, added.selectedAccount.index)
        assertFalse(added.selectedAccount.address in addresses.values)
    }

    @Test
    fun changingPinRejectsOldPinAndPreservesWalletAndRecoveryPhrase() {
        val original = WalletVault.accountsSnapshot()
        val id = original.selectedWalletId
        val token = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.ChangePin, id)
        val newPin = "654321".toCharArray()
        WalletVault.changePin(isolated, id, newPin, token)
        assertTrue(newPin.all { it == '\u0000' })
        WalletVault.lock()
        assertThrows(WrongPinException::class.java) { WalletVault.unlockWithPin(isolated, testPin()) }
        val unlocked = WalletVault.unlockWithPin(isolated, "654321".toCharArray())
        assertEquals(publicWords.joinToString(" "), unlocked.mnemonic)
        assertEquals(original, WalletVault.accountsSnapshot())
    }

    @Test
    fun lastVisibleAccountCannotBeRemoved() {
        val id = WalletVault.accountsSnapshot().selectedWalletId
        val token = WalletVault.authorizeWithPin(isolated, testPin(), VaultAction.RemoveAccount, id, 0)
        assertThrows(WalletAccountException::class.java) { WalletVault.removeAccount(isolated, id, 0, token) }
        assertEquals(1, WalletVault.accountsSnapshot().selectedWallet.accounts.size)
    }

    @Test
    fun persistsAccountsSelectionAndRejectsLockedSession() {
        val first = WalletVault.accountsSnapshot()
        val added = WalletVault.addAccount(isolated, first.selectedWalletId, "Reserva")
        assertEquals(1, added.selectedAccountIndex)
        assertTrue(added.selectedAccount.address != first.selectedAccount.address)
        val selected = WalletVault.selectAccount(isolated, first.selectedWalletId, 0)
        WalletVault.lock()
        assertThrows(IllegalStateException::class.java) { WalletVault.accountsSnapshot() }
        WalletVault.unlockWithPin(isolated, testPin())
        assertEquals(selected, WalletVault.accountsSnapshot())
    }

    @Test
    fun importingAnotherWalletPreservesOriginalAndRestoresSeveralAccounts() {
        val original = WalletVault.accountsSnapshot()
        val imported = WalletVault.importWallet(isolated, "Outra", generateRecoveryWords(), 3)
        assertEquals(2, imported.wallets.size)
        assertEquals(3, imported.selectedWallet.accounts.size)
        assertEquals(original.selectedWallet, imported.wallets.first())
        assertEquals(3, imported.selectedWallet.accounts.map { it.address }.distinct().size)
        WalletVault.selectAccount(isolated, imported.selectedWalletId, 2)
        WalletVault.lock()
        WalletVault.unlockWithPin(isolated, testPin())
        assertEquals(2, WalletVault.accountsSnapshot().selectedAccountIndex)
        assertEquals(2, WalletVault.accountsSnapshot().wallets.size)
    }

    @Test
    fun duplicateImportDoesNotModifyStoredWallet() {
        val before = WalletVault.accountsSnapshot()
        assertThrows(WalletAccountException::class.java) {
            WalletVault.importWallet(isolated, "Duplicada", publicWords, 2)
        }
        assertEquals(before, WalletVault.accountsSnapshot())
        WalletVault.lock()
        WalletVault.unlockWithPin(isolated, testPin())
        assertEquals(before, WalletVault.accountsSnapshot())
    }

    @Test
    fun catalogRemainsEncryptedOnDisk() {
        WalletVault.addAccount(isolated, WalletVault.accountsSnapshot().selectedWalletId, "Reserva secreta")
        val stored = File(directory, "wallet_vault.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(stored.contains(publicWords.joinToString(" ")))
        assertFalse(stored.contains("Reserva secreta"))
        assertFalse(stored.contains("123456"))
    }
}
