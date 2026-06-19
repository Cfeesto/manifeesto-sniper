package com.manifeesto.sniper.scanner

import android.util.Log
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger

/**
 * 水龙头自动领取器 — 当钱包 gas 不足时自动补充测试网代币
 * 支持免费 HTTP 水龙头 (无需 Twitter/Discord)
 */
class FaucetClaimer(private val walletManager: WalletManager) {

    private val TAG = "FaucetClaimer"
    private val rpcClient = RpcClient()

    // 最低 gas 阈值 — 低于此值触发水龙头领取 (0.005 ETH equivalent)
    private val MIN_GAS_WEI = BigInteger.valueOf(5_000_000_000_000_000L)

    // 每条链的免费 HTTP 水龙头列表
    private val faucets = mapOf(
        Network.ETH to listOf(
            "https://faucets.chain.link/sepolia"
        ),
        Network.BSC_TESTNET to listOf(
            "https://testnet.bnbchain.org/faucet-smart"
        )
    )

    /**
     * 检查余额并在低于阈值时尝试领取测试网 gas
     * 返回补充的网络列表
     */
    suspend fun checkAndRefill(networks: List<Network>): List<String> = withContext(Dispatchers.IO) {
        val refilled = mutableListOf<String>()
        val address = walletManager.getWalletAddress()
        if (address.isEmpty()) return@withContext refilled

        networks.forEach { network ->
            try {
                val balance = getNativeBalance(network.rpcUrl, address)
                if (balance < MIN_GAS_WEI) {
                    Log.d(TAG, "${network.name} balance low: $balance wei")
                    val success = tryClaimFaucet(network, address)
                    if (success) refilled.add(network.name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Balance check failed for ${network.name}: ${e.message}")
            }
        }
        refilled
    }

    /**
     * 获取原生代币余额
     */
    fun getNativeBalance(rpcUrl: String, address: String): BigInteger {
        return try {
            val result = rpcClient.call(rpcUrl, "eth_getBalance", listOf(address, "latest"))
            val hex = result?.get("result")?.asString?.removePrefix("0x")?.trimStart('0')?.ifEmpty { "0" } ?: "0"
            BigInteger(hex, 16)
        } catch (_: Exception) { BigInteger.ZERO }
    }

    /**
     * 尝试通过 HTTP POST 领取水龙头代币
     */
    private fun tryClaimFaucet(network: Network, address: String): Boolean {
        val faucetList = faucets[network] ?: return false
        for (faucetUrl in faucetList) {
            try {
                // 标准水龙头 POST 格式
                val body = """{"address":"$address"}"""
                val response = rpcClient.post(faucetUrl, body)
                if (response != null && !response.contains("error", ignoreCase = true)) {
                    Log.d(TAG, "Faucet claimed from $faucetUrl for ${network.name}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Faucet failed $faucetUrl: ${e.message}")
            }
        }
        return false
    }

    /**
     * 以人类可读格式格式化余额
     */
    fun formatBalance(wei: BigInteger, symbol: String): String {
        val eth = wei.toBigDecimal().divide(
            java.math.BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.HALF_UP
        )
        return "$eth $symbol"
    }
}
