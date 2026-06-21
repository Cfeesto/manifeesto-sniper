package com.manifeesto.sniper.scanner

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.manifeesto.sniper.data.AirdropCampaign
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger

/**
 * 水龙头自动领取器 — 当钱包 gas 不足时自动补充测试网代币
 * 支持免费 HTTP 水龙头，覆盖所有活动网络
 */
class FaucetClaimer(private val walletManager: WalletManager) {

    private val TAG = "FaucetClaimer"
    private val rpcClient = RpcClient()
    private val gson = Gson()

    // 最低 gas 阈值 — 低于此值触发水龙头领取 (0.005 ETH equivalent)
    private val MIN_GAS_WEI = BigInteger.valueOf(5_000_000_000_000_000L)

    // 每条链的免费 HTTP 水龙头列表
    private val faucets = mapOf(
        Network.ETH.name to listOf(
            "https://faucets.chain.link/sepolia"
        ),
        Network.BSC_TESTNET.name to listOf(
            "https://testnet.bnbchain.org/faucet-smart"
        ),
        Network.MONAD_TESTNET.name to listOf(
            "https://testnet.monad.xyz/faucet",
            "https://faucet.monad.xyz/api/claim"
        ),
        Network.MEGAETH_TESTNET.name to listOf(
            "https://faucet.megaeth.com/api/claim",
            "https://megaeth-faucet.vercel.app/api/faucet"
        ),
        Network.BERACHAIN_TESTNET.name to listOf(
            "https://artio.faucet.berachain.com",
            "https://artio.berachain.com/faucet"
        ),
        Network.BASE.name to listOf(
            "https://faucet.base.org/api/claim"
        ),
        Network.ARBITRUM.name to listOf(
            "https://faucet.arbitrum.io/claim"
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
                Log.d(TAG, "${network.name} balance: ${formatBalance(balance, network.nativeSymbol)}")
                if (balance < MIN_GAS_WEI) {
                    Log.w(TAG, "${network.name} gas LOW — attempting faucet claim...")
                    val success = tryClaimFaucet(network, address)
                    if (success) refilled.add(network.name)
                    else Log.e(TAG, "${network.name} faucet claim FAILED — no working faucet found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Balance check failed for ${network.name}: ${e.message}")
            }
        }
        refilled
    }

    /**
     * 基于活动列表检查所有活动网络的 gas 余额
     */
    suspend fun checkAndRefillCampaigns(campaigns: List<AirdropCampaign>): List<String> {
        val networks = campaigns.map { it.network }.distinct()
        return checkAndRefill(networks)
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
     * 尝试通过 HTTP POST 领取水龙头代币 (带多种请求格式回退)
     */
    private fun tryClaimFaucet(network: Network, address: String): Boolean {
        val faucetList = faucets[network.name] ?: return false
        for (faucetUrl in faucetList) {
            // 尝试多种 POST 格式
            val formats = listOf(
                """{"address":"$address"}""",
                """{"wallet":"$address","chain":"${network.name}"}""",
                """{"recipient":"$address"}""",
                """{"to":"$address"}"""
            )
            for (body in formats) {
                try {
                    val response = rpcClient.post(faucetUrl, body)
                    if (response != null && !response.contains("error", ignoreCase = true)
                        && !response.contains("rate limit", ignoreCase = true)) {
                        // 验证是否成功 — 部分 faucet 返回 JSON 含 txHash
                        val json = try { gson.fromJson(response, JsonObject::class.java) } catch (_: Exception) { null }
                        if (json != null && (json.has("txHash") || json.has("success") || json.has("ok"))) {
                            Log.d(TAG, "Faucet claimed: $faucetUrl for ${network.name}")
                            return true
                        }
                        // 部分 faucet 返回纯文本
                        if (response.contains("sent", ignoreCase = true) || response.contains("tx", ignoreCase = true)) {
                            Log.d(TAG, "Faucet claimed: $faucetUrl for ${network.name}")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Faucet attempt failed $faucetUrl: ${e.javaClass.simpleName}")
                }
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
