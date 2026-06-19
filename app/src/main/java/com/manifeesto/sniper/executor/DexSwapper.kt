package com.manifeesto.sniper.executor

import android.content.Context
import android.util.Log
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.data.SwapResult
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.TxSigner
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigInteger

/**
 * DEX 自动兑换器 — 领到代币后立即兑换成 USDC
 * 支持 Uniswap V2 兼容的所有 DEX (PancakeSwap, Aerodrome, QuickSwap 等)
 *
 * 流程: 授权 ERC20 → swapExactTokensForTokens → 等待确认
 */
class DexSwapper(context: Context) {

    private val TAG = "DexSwapper"
    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)

    /**
     * 将指定代币全部兑换成 USDC
     * @param tokenAddress  待兑换代币合约地址
     * @param tokenSymbol   代币符号 (用于日志)
     * @param rawAmount     兑换数量 (wei)
     * @param network       目标链
     */
    suspend fun swapToUsdc(
        tokenAddress: String,
        tokenSymbol: String,
        rawAmount: BigInteger,
        network: Network
    ): SwapResult = withContext(Dispatchers.IO) {
        if (network.dexRouter.isEmpty() || network.usdcAddress.isEmpty()) {
            return@withContext SwapResult(false, error = "No DEX on ${network.name}")
        }
        if (rawAmount <= BigInteger.ZERO) {
            return@withContext SwapResult(false, error = "Zero amount")
        }

        val wallet = walletManager.getWallet(network)
        if (wallet.address.isEmpty() || wallet.privateKey.isEmpty()) {
            return@withContext SwapResult(false, error = "Wallet not configured")
        }

        try {
            // 步骤1: 授权 DEX router 花费代币
            val approveHash = approveToken(
                tokenAddress = tokenAddress,
                spender = network.dexRouter,
                amount = rawAmount,
                network = network,
                wallet = wallet
            )
            if (approveHash != null) {
                waitForTx(network.rpcUrl, approveHash, maxWaitMs = 30_000)
                Log.d(TAG, "Approved $tokenSymbol for swap")
            }

            // 步骤2: 执行兑换
            val swapHash = executeSwap(
                tokenIn = tokenAddress,
                tokenOut = network.usdcAddress,
                amountIn = rawAmount,
                network = network,
                wallet = wallet
            ) ?: return@withContext SwapResult(false, error = "Swap tx failed")

            // 步骤3: 等待兑换确认
            val confirmed = waitForTx(network.rpcUrl, swapHash, maxWaitMs = 60_000)
            if (!confirmed) {
                return@withContext SwapResult(false, swapHash, error = "Swap tx not confirmed")
            }

            // 步骤4: 查询收到的 USDC 金额
            val usdcBalance = getErc20Balance(network.usdcAddress, wallet.address, network.rpcUrl)
            val usdcAmount = usdcBalance.toBigDecimal()
                .divide(java.math.BigDecimal.TEN.pow(6), 4, java.math.RoundingMode.HALF_UP)
                .toDouble()

            Log.d(TAG, "Swap done: $tokenSymbol → $usdcAmount USDC, tx=$swapHash")
            SwapResult(true, swapHash, usdcAmount)

        } catch (e: Exception) {
            Log.e(TAG, "Swap failed: ${e.message}")
            SwapResult(false, error = e.message ?: "Unknown error")
        }
    }

    // ─── ERC20 approve ─────────────────────────────────────────

    private suspend fun approveToken(
        tokenAddress: String,
        spender: String,
        amount: BigInteger,
        network: Network,
        wallet: WalletManager.WalletInfo
    ): String? {
        val nonce = rpcClient.getNonce(network.rpcUrl, wallet.address)
        val gasPrice = getGasPrice(network.rpcUrl)

        // approve(address spender, uint256 amount) — 0x095ea7b3
        val spenderPadded = spender.removePrefix("0x").padStart(64, '0')
        val amountHex = amount.toString(16).padStart(64, '0')
        val data = "0x095ea7b3$spenderPadded$amountHex"

        return rpcClient.sendRawTransaction(
            network.rpcUrl,
            TxSigner.sign(
                privateKey = wallet.privateKey,
                chainId = network.chainId,
                nonce = nonce,
                gasPrice = gasPrice,
                gasLimit = BigInteger.valueOf(80_000),
                to = tokenAddress,
                value = BigInteger.ZERO,
                data = data
            )
        )
    }

    // ─── Uniswap V2 swapExactTokensForTokens ──────────────────

    private suspend fun executeSwap(
        tokenIn: String,
        tokenOut: String,
        amountIn: BigInteger,
        network: Network,
        wallet: WalletManager.WalletInfo
    ): String? {
        val nonce = rpcClient.getNonce(network.rpcUrl, wallet.address)
        val gasPrice = getGasPrice(network.rpcUrl)
        val deadline = (System.currentTimeMillis() / 1000 + 300).toString(16).padStart(64, '0')

        // 最小输出 = 0 (接受任何滑点 — 测试网/小额)
        val amountOutMin = "0".padStart(64, '0')
        val amountInHex = amountIn.toString(16).padStart(64, '0')
        val to = wallet.address.removePrefix("0x").padStart(64, '0')

        // path = [tokenIn, tokenOut] — 直接对
        val pathOffset = "a0".padStart(64, '0')   // offset to path (5*32=160=0xa0)
        val pathLen = "2".padStart(64, '0')
        val t0 = tokenIn.removePrefix("0x").padStart(64, '0')
        val t1 = tokenOut.removePrefix("0x").padStart(64, '0')

        // swapExactTokensForTokens(amountIn, amountOutMin, path, to, deadline)
        val data = "0x38ed1739" +
                amountInHex + amountOutMin + pathOffset + to + deadline +
                pathLen + t0 + t1

        return rpcClient.sendRawTransaction(
            network.rpcUrl,
            TxSigner.sign(
                privateKey = wallet.privateKey,
                chainId = network.chainId,
                nonce = nonce,
                gasPrice = gasPrice,
                gasLimit = BigInteger.valueOf(300_000),
                to = network.dexRouter,
                value = BigInteger.ZERO,
                data = data
            )
        )
    }

    // ─── 等待交易确认 ──────────────────────────────────────────

    /**
     * 轮询 eth_getTransactionReceipt 直到确认或超时
     * 返回 true = 成功上链
     */
    suspend fun waitForTx(rpcUrl: String, txHash: String, maxWaitMs: Long = 60_000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            delay(3000)
            try {
                val result = rpcClient.call(rpcUrl, "eth_getTransactionReceipt", listOf(txHash))
                val receipt = result?.get("result")
                if (receipt != null && !receipt.isJsonNull) {
                    val status = receipt.asJsonObject.get("status")?.asString ?: "0x1"
                    return status == "0x1"
                }
            } catch (_: Exception) {}
        }
        return false
    }

    // ─── 辅助 ──────────────────────────────────────────────────

    private fun getGasPrice(rpcUrl: String): BigInteger {
        val hex = rpcClient.getGasPrice(rpcUrl).removePrefix("0x").trimStart('0').ifEmpty { "0" }
        return BigInteger(hex, 16).max(BigInteger.valueOf(1_000_000_000L))
    }

    fun getErc20Balance(tokenAddress: String, walletAddress: String, rpcUrl: String): BigInteger {
        return try {
            // balanceOf(address) — 0x70a08231
            val paddedAddr = walletAddress.removePrefix("0x").padStart(64, '0')
            val data = "0x70a08231$paddedAddr"
            val result = rpcClient.call(rpcUrl, "eth_call", listOf(
                mapOf("to" to tokenAddress, "data" to data), "latest"
            ))
            val hex = result?.get("result")?.asString?.removePrefix("0x")?.trimStart('0')?.ifEmpty { "0" } ?: "0"
            BigInteger(hex, 16)
        } catch (_: Exception) { BigInteger.ZERO }
    }
}
