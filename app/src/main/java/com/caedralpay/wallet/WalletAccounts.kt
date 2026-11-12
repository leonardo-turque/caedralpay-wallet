package com.caedralpay.wallet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import wallet.core.jni.AnyAddress
import wallet.core.jni.CoinType
import wallet.core.jni.HDWallet

data class WalletAccount(val index: Int, val name: String, val address: String) {
    val derivationPath: String get() = "m/44'/60'/0'/0/$index"
}

data class WalletGroup(val id: String, val name: String, val accounts: List<WalletAccount>,
    val nextAccountIndex: Int = (accounts.maxOfOrNull { it.index } ?: -1) + 1)

data class WalletAccountsSnapshot(
    val wallets: List<WalletGroup>,
    val selectedWalletId: String,
    val selectedAccountIndex: Int
) {
    val selectedWallet: WalletGroup get() = wallets.first { it.id == selectedWalletId }
    val selectedAccount: WalletAccount get() = selectedWallet.accounts.first { it.index == selectedAccountIndex }
}

internal class StoredWallet(
    val id: String,
    val name: String,
    val mnemonic: String,
    val accountNames: List<String>,
    val hiddenIndices: Set<Int> = emptySet()
) {
    override fun toString() = "StoredWallet([REDACTED])"
}

internal class WalletCatalog(
    val wallets: List<StoredWallet>,
    val selectedWalletId: String,
    val selectedAccountIndex: Int
) {
    val selected: StoredWallet get() = wallets.first { it.id == selectedWalletId }
    override fun toString() = "WalletCatalog([REDACTED])"

    fun snapshot(): WalletAccountsSnapshot = WalletAccountsSnapshot(
        wallets.map { wallet ->
            WalletGroup(wallet.id, wallet.name, wallet.accountNames.mapIndexedNotNull { index, name ->
                if (index in wallet.hiddenIndices) return@mapIndexedNotNull null
                WalletAccount(index, name, deriveEvmAddress(wallet.mnemonic, index))
            }, wallet.accountNames.size)
        }, selectedWalletId, selectedAccountIndex
    )

    companion object {
        fun first(name: String, mnemonic: String, id: String = UUID.randomUUID().toString()): WalletCatalog {
            val wallet = StoredWallet(id, name, mnemonic, listOf("Conta 1"))
            return WalletCatalog(listOf(wallet), wallet.id, 0)
        }
    }
}

internal fun deriveEvmAddress(mnemonic: String, index: Int): String {
    require(index >= 0)
    System.loadLibrary("TrustWalletCore")
    val wallet = HDWallet(mnemonic, "")
    val key = wallet.getKey(CoinType.ETHEREUM, "m/44'/60'/0'/0/$index")
    return AnyAddress(key.getPublicKeySecp256k1(false), CoinType.ETHEREUM).description()
}

/** Leitura do formato anterior sem alteração do arquivo; escrita usa o catálogo v2. */
internal object WalletCatalogCodec {
    private const val LEGACY_MAGIC = 0x43575031
    private const val V2_MAGIC = 0x43575032
    private const val MAGIC = 0x43575033
    const val MAX_WALLETS = 20
    const val MAX_ACCOUNTS = 100

    fun encode(catalog: WalletCatalog): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(catalog.wallets.size)
            catalog.wallets.forEach { wallet ->
                output.writeUTF(wallet.id)
                output.writeUTF(wallet.name)
                output.writeUTF(wallet.mnemonic)
                output.writeInt(wallet.accountNames.size)
                wallet.accountNames.forEach(output::writeUTF)
                output.writeInt(wallet.hiddenIndices.size)
                wallet.hiddenIndices.sorted().forEach(output::writeInt)
            }
            output.writeUTF(catalog.selectedWalletId)
            output.writeInt(catalog.selectedAccountIndex)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): WalletCatalog = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magic = input.readInt()
        val catalog = when (magic) {
            LEGACY_MAGIC -> WalletCatalog.first(input.readUTF(), input.readUTF(), "legacy-wallet")
            MAGIC, V2_MAGIC -> {
                val count = input.readInt().also { require(it in 1..MAX_WALLETS) }
                val wallets = List(count) {
                    val id = input.readUTF()
                    val name = input.readUTF()
                    val mnemonic = input.readUTF()
                    val accounts = input.readInt().also { require(it in 1..MAX_ACCOUNTS) }
                    val names = List(accounts) { input.readUTF() }
                    val hidden = if (magic == MAGIC) {
                        val hiddenCount = input.readInt().also { require(it in 0 until accounts) }
                        List(hiddenCount) { input.readInt().also { index -> require(index in names.indices) } }.also {
                            require(it.distinct().size == it.size)
                        }.toSet()
                    } else emptySet()
                    StoredWallet(id, name, mnemonic, names, hidden)
                }
                WalletCatalog(wallets, input.readUTF(), input.readInt())
            }
            else -> error("Formato de cofre desconhecido.")
        }
        require(catalog.wallets.map { it.id }.distinct().size == catalog.wallets.size)
        require(catalog.wallets.all { it.name.isNotBlank() && it.mnemonic.split(" ").size == 12 })
        require(catalog.selectedAccountIndex in catalog.selected.accountNames.indices)
        require(catalog.selectedAccountIndex !in catalog.selected.hiddenIndices)
        require(input.available() == 0)
        catalog
    }
}
