package com.caedralpay.wallet

import java.math.BigDecimal
import java.math.BigInteger
import android.content.Context

enum class BscChain(
    val chainId: Int,
    val networkName: String,
    val nativeSymbol: String,
    val explorer: String,
    val rpcEndpoints: List<String>,
    val usdtContract: String? = null,
    val faucetUrl: String? = null
) {
    Mainnet(56, "BNB Smart Chain", "BNB", "https://bscscan.com",
        listOf("https://bsc-dataseed.bnbchain.org", "https://bsc-dataseed-public.bnbchain.org"),
        "0x55d398326f99059fF775485246999027B3197955"),
    Testnet(97, "BNB Smart Chain Testnet", "tBNB", "https://testnet.bscscan.com",
        listOf("https://bsc-testnet-dataseed.bnbchain.org", "https://bsc-testnet.bnbchain.org"),
        "0x337610d27c682E347C9cD60BD4b3b107C9d34dDd",
        faucetUrl = "https://www.bnbchain.org/en/testnet-faucet");

    val isTestnet: Boolean get() = this == Testnet
    val assets: List<WalletAsset> get() = if (usdtContract == null) listOf(WalletAsset.BNB) else listOf(WalletAsset.USDT, WalletAsset.BNB)
}

internal object NetworkPreference {
    fun load(context: Context): BscChain {
        val value = context.getSharedPreferences("network_selection", Context.MODE_PRIVATE).getString("chain", null)
        return BscChain.entries.firstOrNull { it.name == value } ?: BscChain.Testnet
    }
    fun save(context: Context, chain: BscChain) {
        context.getSharedPreferences("network_selection", Context.MODE_PRIVATE).edit().putString("chain", chain.name).apply()
    }
}

internal fun WalletAsset.symbolOn(chain: BscChain): String = if (this == WalletAsset.BNB) chain.nativeSymbol else symbol
internal fun WalletAsset.nameOn(chain: BscChain): String = if (this == WalletAsset.BNB && chain.isTestnet) "BNB de teste" else displayName

/** Configuração explícita: nenhum token é identificado apenas pelo símbolo. */
internal object BscNetwork {
    val name get() = BscChain.Mainnet.networkName
    val chainId get() = BscChain.Mainnet.chainId
    const val nativeDecimals = 18
    const val usdtDecimals = 18
    val usdtContract get() = requireNotNull(BscChain.Mainnet.usdtContract)
    val explorer get() = BscChain.Mainnet.explorer
    val rpcEndpoints get() = BscChain.Mainnet.rpcEndpoints
}

internal object EvmBalanceCodec {
    fun quantity(hex: String): BigInteger {
        require(hex.matches(Regex("0x[0-9a-fA-F]{1,64}"))) { "Quantidade RPC inválida." }
        return BigInteger(hex.drop(2), 16)
    }

    fun abiUint(hex: String): BigInteger {
        require(hex.matches(Regex("0x[0-9a-fA-F]{64}"))) { "Resposta ABI inválida." }
        return BigInteger(hex.drop(2), 16)
    }

    fun units(raw: BigInteger, decimals: Int): BigDecimal {
        require(raw.signum() >= 0 && decimals in 0..36)
        return BigDecimal(raw, decimals)
    }

    fun balanceOfData(address: String): String {
        require(address.matches(Regex("0x[0-9a-fA-F]{40}")))
        return "0x70a08231" + address.drop(2).lowercase(java.util.Locale.ROOT).padStart(64, '0')
    }
}

internal data class BscAccountBalances(
    val address: String,
    val blockNumber: BigInteger,
    val nativeBalance: BigDecimal,
    val usdtBalance: BigDecimal?,
    val updatedAtMillis: Long,
    val rpcEndpoint: String,
    val chain: BscChain = BscChain.Mainnet
) {
    fun positions() = chain.assets.map {
        AssetPosition(it, if (it == WalletAsset.BNB) nativeBalance else usdtBalance, null)
    }
}
