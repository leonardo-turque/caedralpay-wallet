package com.caedralpay.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoke test HTTPS real, somente leitura, com endereço público de teste. Requer internet. */
class BscLiveReadTest {
    @Test fun readsMainnetAndConfiguredTokenWithoutWalletSecrets() = runBlocking {
        val publicAddress = "0x0000000000000000000000000000000000000000"
        val result = BscBalanceRepository().load(publicAddress)
        assertEquals(publicAddress, result.address)
        assertTrue(result.blockNumber.signum() > 0)
        assertTrue(result.nativeBalance.signum() >= 0)
        assertTrue(requireNotNull(result.usdtBalance).signum() >= 0)
    }

    @Test fun readsTestnetWithoutUsingMainnetTokenContract() = runBlocking {
        val result = BscBalanceRepository().load("0x0000000000000000000000000000000000000000", BscChain.Testnet)
        assertEquals(BscChain.Testnet, result.chain)
        assertTrue(result.blockNumber.signum() > 0)
        assertTrue(requireNotNull(result.usdtBalance).signum() >= 0)
        assertTrue(BscChain.Testnet.usdtContract != BscChain.Mainnet.usdtContract)
        assertEquals(listOf(WalletAsset.USDT, WalletAsset.BNB), result.positions().map { it.asset })
    }
}
