package com.manifeesto.sniper.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 价格预言机 — 通过 CoinGecko 免费 API 获取实时代币价格
 * 用于判断领取的代币是否值得立即兑换
 */
object PriceOracle {

    private const val TAG = "PriceOracle"
    private val rpcClient = RpcClient()
    private val gson = Gson()

    // 简单内存缓存 — 价格每5分钟过期
    private val cache = mutableMapOf<String, Pair<Double, Long>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    // 常见代币符号 → CoinGecko ID 映射
    private val symbolToId = mapOf(
        "ETH" to "ethereum",
        "BNB" to "binancecoin",
        "MATIC" to "matic-network",
        "ARB" to "arbitrum",
        "OP" to "optimism",
        "BASE" to "ethereum",
        "BERA" to "berachain-bera",
        "MON" to "monad",
        "USDC" to "usd-coin",
        "USDT" to "tether",
        "DAI" to "dai",
        "WETH" to "ethereum",
        "ZK" to "zksync",
        "STRK" to "starknet",
        "SCROLL" to "scroll"
    )

    /**
     * 获取代币 USD 价格，失败时返回 0.0
     */
    suspend fun getPriceUsd(symbol: String): Double = withContext(Dispatchers.IO) {
        val key = symbol.uppercase()

        // 检查缓存
        cache[key]?.let { (price, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext price
        }

        val coinId = symbolToId[key] ?: key.lowercase()
        val price = fetchFromCoinGecko(coinId)

        if (price > 0) cache[key] = Pair(price, System.currentTimeMillis())
        price
    }

    /**
     * 计算代币持仓的 USD 价值
     */
    suspend fun getValueUsd(symbol: String, amount: Double): Double {
        val price = getPriceUsd(symbol)
        return price * amount
    }

    /**
     * 判断是否值得立即兑换 (价值 > $1)
     */
    suspend fun isWorthSwapping(symbol: String, amount: Double): Boolean {
        val value = getValueUsd(symbol, amount)
        Log.d(TAG, "$symbol $amount = \$${"%.2f".format(value)}")
        return value >= 1.0
    }

    private fun fetchFromCoinGecko(coinId: String): Double {
        return try {
            val url = "https://api.coingecko.com/api/v3/simple/price?ids=$coinId&vs_currencies=usd"
            val response = rpcClient.get(url) ?: return 0.0
            val json = gson.fromJson(response, JsonObject::class.java)
            json.getAsJsonObject(coinId)?.get("usd")?.asDouble ?: 0.0
        } catch (e: Exception) {
            Log.w(TAG, "Price fetch failed for $coinId: ${e.message}")
            0.0
        }
    }
}
