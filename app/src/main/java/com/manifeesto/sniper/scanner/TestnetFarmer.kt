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
 * 测试网农耕器 — 执行真实链上动作建立钱包活动记录
 * 特性:
 * - 每笔交易使用 RPC 回退列表
 * - 随机化 gas price 和执行间隔
 * - 记录成功/失败状态含 explorer 链接
 */
class TestnetFarmer(context: Context) {

    private val TAG = "TestnetFarmer"
    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)

    // Uniswap V2 系列 DEX Router (per chainId)
    private val dexRouters = mapOf(
        1L     to "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D",
        56L    to "0x10ED43C718714eb63d5aA57B78B54704E256024E",
        8453L  to "0x4752ba5DBc23f44D87826276BF6Fd6b1C372aD24",
        42161L to "0x1b02dA8Cb0d097eB8D57A175b88c7D8b47997506",
        10L    to "0x9c12939390052919aF3155f41Bf4160Fd3666A6f"
    )

    // Known bridge contracts per chain (for real bridge-like activity)
    private val bridgeContracts = mapOf(
        42161L to listOf(
            "0x72Ce9c846789fdB6fC1f34aC4AD25Dd9ef7031ef", // Arbitrum Gateway
            "0x4Dbd4fc535Ac27206064B68FfCf827b0A60BAB3f"  // Arbitrum Delayed Inbox
        ),
        8453L to listOf(
            "0x3154Cf16ccdb4C6d922629664174b904d80F2C35"  // Base Bridge
        ),
        10143L to listOf(
            "0x4200000000000000000000000000000000000010"  // Standard L2 bridge
        )
    )

    // Governance/staking contracts per chain
    private val governanceContracts = mapOf(
        80085L to listOf(
            "0x73e04773753F8606c7537360EE3a3D2646b6d8c0"  // Berachain governance
        )
    )

    data class FarmResult(
        val success: Boolean,
        val txHash: String = "",
        val action: String = "",
        val error: String = ""
    )

    suspend fun farm(campaign: AirdropCampaign): List<FarmResult> {
        val wallet = walletManager.getWallet(campaign.network)
        if (wallet.address.isEmpty() || wallet.privateKey.isEmpty())
            return listOf(FarmResult(false, action = "init", error = "Wallet not configured"))

        val rpcUrls = buildRpcList(campaign)
        val results = mutableListOf<FarmResult>()

        campaign.requiredActions.forEach { action ->
            delay(Random.nextLong(2000, 8000))
            try {
                val result = when (action) {
                    CampaignAction.SWAP            -> performSwap(campaign, wallet, rpcUrls)
                    CampaignAction.BRIDGE          -> performBridge(campaign, wallet, rpcUrls)
                    CampaignAction.PROVIDE_LP      -> performSwap(campaign, wallet, rpcUrls)
                    CampaignAction.DEPLOY_CONTRACT -> deploySimpleContract(campaign, wallet, rpcUrls)
                    CampaignAction.VOTE            -> performGovernance(campaign, wallet, rpcUrls)
                    CampaignAction.STAKE           -> performStake(campaign, wallet, rpcUrls)
                }
                results.add(result)
                Log.d(TAG, "${campaign.name} ${action.name}: ${if (result.success) "OK ${result.txHash.take(14)}" else "FAILED ${result.error}"}")
            } catch (e: Exception) {
                Log.w(TAG, "${campaign.name} ${action.name} error: ${e.message}")
                results.add(FarmResult(false, action = action.name, error = e.message?.take(80) ?: "exception"))
            }
        }
        return results
    }

    // ─── 真实 SWAP (Uniswap V2 swapExactETHForTokens) ──────

    private suspend fun performSwap(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>
    ): FarmResult {
        val chainId = campaign.network.chainId
        val router = dexRouters[chainId] ?: return FarmResult(false, action = "swap", error = "No DEX router for chain")

        val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
        if (nonce < 0) return FarmResult(false, action = "swap", error = "Nonce fetch failed")

        val gasPrice = randomizedGasPrice(rpcUrls)
        val weth = getWeth(chainId) ?: return FarmResult(false, action = "swap", error = "No WETH address")
        val usdc = getUsdc(chainId) ?: return FarmResult(false, action = "swap", error = "No USDC address")
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
        val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
            ?: return FarmResult(false, action = "swap", error = "Send tx failed")

        val confirmed = waitForConfirmation(rpcUrls, txHash, 45_000)
        return FarmResult(confirmed, txHash, "swap")
    }

    // ─── 真实合约部署 (最小化 bytecode) ─────────────────────

    private suspend fun deploySimpleContract(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>
    ): FarmResult {
        val chainId = campaign.network.chainId
        val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
        if (nonce < 0) return FarmResult(false, action = "deploy", error = "Nonce fetch failed")

        val gasPrice = randomizedGasPrice(rpcUrls)
        // 最小合约: STOP opcode = 0x00 — 最低成本，仍留下链上部署记录
        val bytecode = "0x00"
        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(53_000), "", BigInteger.ZERO, bytecode)
        val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
            ?: return FarmResult(false, action = "deploy", error = "Send tx failed")

        val confirmed = waitForConfirmation(rpcUrls, txHash, 45_000)
        return FarmResult(confirmed, txHash, "deploy")
    }

    // ─── 真实 BRIDGE 交互 ───────────────────────────────────

    /** 向已知桥合约发送小额 ETH → 模拟真实桥接活动 */
    private suspend fun performBridge(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>
    ): FarmResult {
        val chainId = campaign.network.chainId
        val bridges = bridgeContracts[chainId]
        if (bridges.isNullOrEmpty())
            return performActivityTx(campaign, wallet, rpcUrls, "bridge")

        val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
        if (nonce < 0) return FarmResult(false, action = "bridge", error = "Nonce fetch failed")

        val gasPrice = randomizedGasPrice(rpcUrls)
        val bridgeContract = bridges.shuffled().first()

        // 向桥合约发送微量 ETH + data="0x" → 触发 receive()
        // 部分桥合约的 deposit() 会 emit BridgeInitiated 事件
        val data = if (chainId == 42161L) {
            // Arbitrum: 调用 depositEth() — 0xd0e30db0
            "0xd0e30db0"
        } else {
            "0x"
        }
        val value = BigInteger.valueOf(50_000_000_000_000L) // 0.00005 ETH

        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(120_000), bridgeContract, value, data)
        val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
            ?: return FarmResult(false, action = "bridge", error = "Send tx failed")

        val confirmed = waitForConfirmation(rpcUrls, txHash, 45_000)
        return FarmResult(confirmed, txHash, "bridge")
    }

    // ─── 真实 STAKE 交互 ───────────────────────────────────

    /** 向链的质押/委托合约发送小额 ETH → 模拟质押 */
    private suspend fun performStake(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>
    ): FarmResult {
        val chainId = campaign.network.chainId

        // 支持 WETH deposit() 作为代理 stake 活动
        val weth = getWeth(chainId)
        if (weth != null) {
            val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
            if (nonce < 0) return FarmResult(false, action = "stake", error = "Nonce fetch failed")

            val gasPrice = randomizedGasPrice(rpcUrls)
            // WETH deposit() — 0xd0e30db0 — wraps ETH → WETH (链上质押代理)
            val data = "0xd0e30db0"
            val value = BigInteger.valueOf(200_000_000_000_000L) // 0.0002 ETH

            val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
                BigInteger.valueOf(80_000), weth, value, data)
            val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
                ?: return FarmResult(false, action = "stake", error = "Send tx failed")

            val confirmed = waitForConfirmation(rpcUrls, txHash, 45_000)
            return FarmResult(confirmed, txHash, "stake")
        }

        return performActivityTx(campaign, wallet, rpcUrls, "stake")
    }

    // ─── 真实 GOVERNANCE/VOTE 交互 ────────────────────────

    /** 与已知治理合约交互 → 模拟投票 */
    private suspend fun performGovernance(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>
    ): FarmResult {
        val chainId = campaign.network.chainId
        val govList = governanceContracts[chainId]

        if (!govList.isNullOrEmpty()) {
            val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
            if (nonce < 0) return FarmResult(false, action = "vote", error = "Nonce fetch failed")

            val gasPrice = randomizedGasPrice(rpcUrls)
            val govContract = govList.shuffled().first()

            // 发送 0 ETH + empty data → 触发 fallback() 留下链上交互记录
            val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
                BigInteger.valueOf(30_000), govContract, BigInteger.ZERO, "0x")
            val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
                ?: return FarmResult(false, action = "vote", error = "Send tx failed")

            val confirmed = waitForConfirmation(rpcUrls, txHash, 30_000)
            return FarmResult(confirmed, txHash, "vote")
        }

        return performActivityTx(campaign, wallet, rpcUrls, "vote")
    }

    // ─── Fallback: 通用活动标记交易 ─────────────────────────

    /** 自转账 0 ETH → 最低成本链上记录 (仅用于无合约支持的链) */
    private suspend fun performActivityTx(
        campaign: AirdropCampaign,
        wallet: WalletManager.WalletInfo,
        rpcUrls: List<String>,
        actionName: String
    ): FarmResult {
        val chainId = campaign.network.chainId
        val nonce = rpcClient.getNonceWithFallback(rpcUrls, wallet.address)
        if (nonce < 0) return FarmResult(false, action = actionName, error = "Nonce fetch failed")

        val gasPrice = randomizedGasPrice(rpcUrls)

        val signed = TxSigner.sign(wallet.privateKey, chainId, nonce, gasPrice,
            BigInteger.valueOf(21_000), wallet.address, BigInteger.ZERO, "")
        val txHash = rpcClient.sendRawTransactionWithFallback(rpcUrls, signed)
            ?: return FarmResult(false, action = actionName, error = "Send tx failed")

        val confirmed = waitForConfirmation(rpcUrls, txHash, 30_000)
        return FarmResult(confirmed, txHash, actionName)
    }

    // ─── 等待链上确认 ──────────────────────────────────────

    private suspend fun waitForConfirmation(rpcUrls: List<String>, txHash: String, maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            delay(3000)
            try {
                val receipt = rpcClient.getTransactionReceiptWithFallback(rpcUrls, txHash)
                if (receipt != null && !receipt.isJsonNull) {
                    val status = receipt.asJsonObject.get("status")?.asString
                    return status == "0x1"
                }
            } catch (_: Exception) {}
        }
        return false
    }

    // ─── 辅助 ──────────────────────────────────────────────

    private fun buildRpcList(campaign: AirdropCampaign): List<String> {
        val list = mutableListOf(campaign.rpcUrl)
        list.addAll(campaign.fallbackRpcUrls)
        return list.filter { it.isNotEmpty() }.distinct()
    }

    /** 在基础 gas price 上加 ±15% 随机浮动 */
    private fun randomizedGasPrice(rpcUrls: List<String>): BigInteger {
        val hex = rpcClient.getGasPriceWithFallback(rpcUrls)
            .removePrefix("0x").trimStart('0').ifEmpty { "3B9ACA00" }
        val base = BigInteger(hex, 16).max(BigInteger.valueOf(1_000_000_000L))
        val jitter = Random.nextDouble(0.85, 1.15)
        return base.toBigDecimal().multiply(java.math.BigDecimal(jitter))
            .toBigInteger().max(BigInteger.valueOf(1_000_000_000L))
    }

    private fun getWeth(chainId: Long): String? = when (chainId) {
        1L     -> "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
        56L    -> "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c"
        8453L  -> "0x4200000000000000000000000000000000000006"
        42161L -> "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1"
        10L    -> "0x4200000000000000000000000000000000000006"
        10143L -> "0x760AfE86e5de5fa0Ee542fc7B7B713e1c5425701" // Monad testnet WETH
        80085L -> "0x5806E416dA447b267cEA759358cF22Cc41FAE80F" // Berachain WETH
        else   -> null
    }

    private fun getUsdc(chainId: Long): String? = when (chainId) {
        1L     -> "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        56L    -> "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d"
        8453L  -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        42161L -> "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8"
        10L    -> "0x7F5c764cBc14f9669B88837ca1490cCa17c31607"
        else   -> null
    }
}
