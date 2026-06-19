package com.manifeesto.sniper.scanner

import android.content.Context
import android.util.Log
import com.manifeesto.sniper.data.AirdropCampaign
import com.manifeesto.sniper.data.CampaignAction
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.TxSigner
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.delay
import java.math.BigInteger
import kotlin.random.Random

/**
 * 测试网农耕器 — 执行链上动作建立钱包活动记录
 * 特性:
 * - 每笔交易等待链上确认再继续
 * - 随机化 gas price 和执行间隔，避免被识别为机器人
 * - 记录成功/失败状态
 */
class TestnetFarmer(context: Context) {

    private val TAG = "TestnetFarmer"
    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)

    private val dexRouters = mapOf(
        1L    to "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D",
        56L   to "0x10ED43C718714eb63d5aA57B78B54704E256024E",
        8453L to "0x4752ba5DBc23f44D87826276BF6Fd6b1C372aD24"
    )

    data class FarmResult(val success: Boolean, val txHash: String = "", val action: String = "")

    suspend fun farm(campaign: AirdropCampaign): List<FarmResult> {
        val wallet = walletManager.getWallet(campaign.network)
        if (wallet.address.isEmpty() || wallet.privateKey.isEmpty()) return emptyList()

        val results = mutableListOf<FarmResult>()
        campaign.requiredActions.forEach { action ->
            // 人类化随机延迟 2-8 秒
            delay(Random.nextLong(2000, 8000))
            try {
                val result = when (action) {
                    CampaignAction.SWAP            -> performSwap(campaign, wallet)
                    CampaignAction.BRIDGE          -> performActivityTx(campaign, wallet, "bridge")
                    CampaignAction.PROVIDE_LP      -> performSwap(campaign, wallet) // LP 需要先 swap
                    CampaignAction.DEPLOY_CONTRACT -> deploySimpleContract(campaign, wallet)
                    CampaignAction.VOTE            -> performActivityTx(campaign, wallet, "vote")
                    CampaignAction.STAKE           -> performActivityTx(campaign, wallet, "stake")
                }
                results.add(result)
                Log.d(TAG, "${campaign.name} ${action.name}: ${if (result.success) "OK ${result.txHash.take(10)}" else "FAILED"}")
            } catch (e: Exception) {
                Log.w(TAG, "${campaign.name} ${action.name} error: ${e.message}")
                results.add(FarmResult(false, action = action.name))
            }
        }
        return results
    }

    // ─── 链上操作 ──────────────────────────────────────────────

    private suspend fun performSwap(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo
    ): FarmResult {
        val rpc = campaign.rpcUrl
        val chainId = campaign.network.chainId
        val router = dexRouters[chainId] ?: return FarmResult(false, action = "swap")

        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = randomizedGasPrice(rpc)
        val weth = getWeth(chainId)
        val usdc = getUsdc(chainId)
        val deadline = (System.currentTimeMillis() / 1000 + 300).toString(16).padStart(64, '0')
        val amountOutMin = "0".padStart(64, '0')
        val to = wallet.address.removePrefix("0x").padStart(64, '0')
        val pathOffset = "80".padStart(64, '0')
        val pathLen = "2".padStart(64, '0')
        val t0 = weth.removePrefix("0x").padStart(64, '0')
        val t1 = usdc.removePrefix("0x").padStart(64, '0')
        val data = "0x7ff36ab5$amountOutMin$pathOffset$to$deadline$pathLen$t0$t1"
        val value = BigInteger.valueOf(100_000_000_000_000L) // 0.0001 ETH

        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(250_000), router, value, data)
        val txHash = rpcClient.sendRawTransaction(rpc, signed) ?: return FarmResult(false, action = "swap")

        // 等待确认 (最多45秒)
        val confirmed = waitForConfirmation(rpc, txHash, 45_000)
        return FarmResult(confirmed, txHash, "swap")
    }

    private suspend fun deploySimpleContract(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo
    ): FarmResult {
        val rpc = campaign.rpcUrl
        val chainId = campaign.network.chainId
        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = randomizedGasPrice(rpc)

        // 最小合约: STOP opcode (1字节) — 最低成本，仍留下链上部署记录
        val bytecode = "0x00"
        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(53_000), "", BigInteger.ZERO, bytecode)
        val txHash = rpcClient.sendRawTransaction(rpc, signed) ?: return FarmResult(false, action = "deploy")

        val confirmed = waitForConfirmation(rpc, txHash, 45_000)
        return FarmResult(confirmed, txHash, "deploy")
    }

    /** 通用活动标记交易 — 自转账 0 ETH，gas 最低，留下链上记录 */
    private suspend fun performActivityTx(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        actionName: String
    ): FarmResult {
        val rpc = campaign.rpcUrl
        val chainId = campaign.network.chainId
        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = randomizedGasPrice(rpc)

        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(21_000), wallet.address, BigInteger.ZERO, "")
        val txHash = rpcClient.sendRawTransaction(rpc, signed) ?: return FarmResult(false, action = actionName)

        val confirmed = waitForConfirmation(rpc, txHash, 30_000)
        return FarmResult(confirmed, txHash, actionName)
    }

    // ─── 等待链上确认 ──────────────────────────────────────────

    private suspend fun waitForConfirmation(rpcUrl: String, txHash: String, maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            delay(3000)
            try {
                val result = rpcClient.call(rpcUrl, "eth_getTransactionReceipt", listOf(txHash))
                val receipt = result?.get("result")
                if (receipt != null && !receipt.isJsonNull) {
                    return receipt.asJsonObject.get("status")?.asString == "0x1"
                }
            } catch (_: Exception) {}
        }
        return false
    }

    // ─── 辅助 ──────────────────────────────────────────────────

    /** 在基础 gas price 上加 ±15% 随机浮动，避免机器人特征 */
    private fun randomizedGasPrice(rpcUrl: String): BigInteger {
        val hex = rpcClient.getGasPrice(rpcUrl).removePrefix("0x").trimStart('0').ifEmpty { "0" }
        val base = BigInteger(hex, 16).max(BigInteger.valueOf(1_000_000_000L))
        val jitter = Random.nextDouble(0.85, 1.15)
        return base.toBigDecimal().multiply(java.math.BigDecimal(jitter))
            .toBigInteger().max(BigInteger.valueOf(1_000_000_000L))
    }

    private fun getWeth(chainId: Long) = when (chainId) {
        56L   -> "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c"
        8453L -> "0x4200000000000000000000000000000000000006"
        else  -> "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
    }

    private fun getUsdc(chainId: Long) = when (chainId) {
        56L   -> "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d"
        8453L -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        else  -> "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    }
}
