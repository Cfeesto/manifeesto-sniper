package com.manifeesto.sniper.executor

import android.content.Context
import android.util.Log
import com.manifeesto.sniper.data.ClaimableAirdrop
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.util.PriceOracle
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.TxSigner
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.delay
import java.math.BigInteger

/**
 * 提现执行器 — 领取空投并立即兑换成 USDC
 *
 * 完整流程:
 * 1. 发送 claim() 交易 (含真实 Merkle proof)
 * 2. 等待链上确认
 * 3. 查询领到的代币余额
 * 4. 价值 > $1 时触发 DexSwapper 兑换成 USDC
 * 5. 将 USDC 转到提现地址
 */
class WithdrawExecutor(context: Context) {

    private val TAG = "WithdrawExecutor"
    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)
    private val dexSwapper = DexSwapper(context)

    data class ClaimResult(
        val success: Boolean,
        val tokenSymbol: String,
        val usdcReceived: Double = 0.0,
        val txHash: String = "",
        val error: String = ""
    )

    suspend fun claimAndWithdraw(airdrop: ClaimableAirdrop): ClaimResult {
        val wallet = walletManager.getWallet(airdrop.network)
        if (wallet.address.isEmpty() || wallet.privateKey.isEmpty()) {
            return ClaimResult(false, airdrop.tokenSymbol, error = "Wallet not configured")
        }

        // 步骤1: 发送 claim 交易
        val claimHash = sendClaimTx(airdrop, wallet)
            ?: return ClaimResult(false, airdrop.tokenSymbol, error = "Claim tx failed to send")

        Log.d(TAG, "Claim tx sent: $claimHash")

        // 步骤2: 等待确认 (最多60秒)
        val confirmed = dexSwapper.waitForTx(airdrop.network.rpcUrl, claimHash, 60_000)
        if (!confirmed) {
            return ClaimResult(false, airdrop.tokenSymbol, txHash = claimHash, error = "Claim not confirmed")
        }
        Log.d(TAG, "Claim confirmed: $claimHash")

        // 步骤3: 查询领到的代币余额
        val tokenBalance = dexSwapper.getErc20Balance(
            airdrop.tokenAddress, wallet.address, airdrop.network.rpcUrl
        )
        if (tokenBalance <= BigInteger.ZERO) {
            return ClaimResult(true, airdrop.tokenSymbol, txHash = claimHash)
        }

        // 步骤4: 检查价值，决定是否立即兑换
        val humanAmount = tokenBalance.toBigDecimal()
            .divide(java.math.BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.HALF_UP)
            .toDouble()

        val worthSwapping = PriceOracle.isWorthSwapping(airdrop.tokenSymbol, humanAmount)
        if (!worthSwapping) {
            Log.d(TAG, "${airdrop.tokenSymbol} value < \$1, skipping swap")
            return ClaimResult(true, airdrop.tokenSymbol, txHash = claimHash)
        }

        // 步骤5: 兑换成 USDC
        Log.d(TAG, "Swapping $humanAmount ${airdrop.tokenSymbol} to USDC...")
        val swapResult = dexSwapper.swapToUsdc(
            tokenAddress = airdrop.tokenAddress,
            tokenSymbol = airdrop.tokenSymbol,
            rawAmount = tokenBalance,
            network = airdrop.network
        )

        if (!swapResult.success) {
            Log.w(TAG, "Swap failed: ${swapResult.error}")
            return ClaimResult(true, airdrop.tokenSymbol, txHash = claimHash)
        }

        // 步骤6: 如果提现地址不同于当前钱包，转 USDC 过去
        val withdrawAddr = walletManager.getWithdrawAddress()
        if (withdrawAddr.isNotEmpty() && !withdrawAddr.equals(wallet.address, ignoreCase = true)) {
            transferUsdcToWithdraw(airdrop.network, wallet, withdrawAddr, swapResult.usdcReceived)
        }

        return ClaimResult(true, airdrop.tokenSymbol, swapResult.usdcReceived, claimHash)
    }

    // ─── Claim 交易 ────────────────────────────────────────────

    private suspend fun sendClaimTx(
        airdrop: ClaimableAirdrop,
        wallet: WalletManager.WalletInfo
    ): String? {
        val rpc = airdrop.network.rpcUrl
        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = getGasPrice(rpc)

        val data = if (airdrop.merkleProof.isNotEmpty()) {
            buildMerkleClaimData(airdrop, wallet.address)
        } else {
            buildSimpleClaimData(airdrop, wallet.address)
        }

        val signed = TxSigner.sign(
            privateKey = wallet.privateKey,
            chainId = airdrop.network.chainId,
            nonce = nonce,
            gasPrice = gasPrice,
            gasLimit = BigInteger.valueOf(350_000),
            to = airdrop.contractAddress,
            value = BigInteger.ZERO,
            data = data
        )
        return rpcClient.sendRawTransaction(rpc, signed)
    }

    /**
     * 构建标准 MerkleDistributor claim 调用数据
     * claim(uint256 index, address account, uint256 amount, bytes32[] merkleProof)
     * selector: 0x2e7ba6ef
     */
    private fun buildMerkleClaimData(airdrop: ClaimableAirdrop, address: String): String {
        val index = airdrop.claimIndex.toString(16).padStart(64, '0')
        val account = address.removePrefix("0x").padStart(64, '0')
        val amount = try {
            java.math.BigInteger(airdrop.amount.removePrefix("0x"), 16).toString(16)
        } catch (_: Exception) { "0" }.padStart(64, '0')

        // proof array ABI encoding
        val proofOffset = "80".padStart(64, '0') // 4 fixed params * 32 = 128 = 0x80
        val proofLen = airdrop.merkleProof.size.toString(16).padStart(64, '0')
        val proofData = airdrop.merkleProof.joinToString("") {
            it.removePrefix("0x").padStart(64, '0')
        }

        return "0x2e7ba6ef$index$account$amount$proofOffset$proofLen$proofData"
    }

    /** 无 proof 的简单 claim — 用于非 Merkle 分发合约 */
    private fun buildSimpleClaimData(airdrop: ClaimableAirdrop, address: String): String {
        val index = airdrop.claimIndex.toString(16).padStart(64, '0')
        val account = address.removePrefix("0x").padStart(64, '0')
        val amount = try {
            java.math.BigInteger(airdrop.amount.removePrefix("0x"), 16).toString(16)
        } catch (_: Exception) { "0" }.padStart(64, '0')
        val proofOffset = "80".padStart(64, '0')
        val proofLen = "0".padStart(64, '0')
        return "0x2e7ba6ef$index$account$amount$proofOffset$proofLen"
    }

    // ─── USDC 转账 ─────────────────────────────────────────────

    private suspend fun transferUsdcToWithdraw(
        network: Network,
        wallet: WalletManager.WalletInfo,
        toAddress: String,
        usdcAmount: Double
    ) {
        if (network.usdcAddress.isEmpty()) return
        try {
            val usdcBalance = dexSwapper.getErc20Balance(
                network.usdcAddress, wallet.address, network.rpcUrl
            )
            if (usdcBalance <= BigInteger.ZERO) return

            val nonce = rpcClient.getNonce(network.rpcUrl, wallet.address)
            val gasPrice = getGasPrice(network.rpcUrl)

            // ERC20 transfer(address to, uint256 amount) — 0xa9059cbb
            val toPadded = toAddress.removePrefix("0x").padStart(64, '0')
            val amountHex = usdcBalance.toString(16).padStart(64, '0')
            val data = "0xa9059cbb$toPadded$amountHex"

            val signed = TxSigner.sign(
                privateKey = wallet.privateKey,
                chainId = network.chainId,
                nonce = nonce,
                gasPrice = gasPrice,
                gasLimit = BigInteger.valueOf(80_000),
                to = network.usdcAddress,
                value = BigInteger.ZERO,
                data = data
            )
            val txHash = rpcClient.sendRawTransaction(network.rpcUrl, signed)
            Log.d(TAG, "USDC transferred to withdraw address: $txHash")
        } catch (e: Exception) {
            Log.w(TAG, "USDC transfer failed: ${e.message}")
        }
    }

    private fun getGasPrice(rpcUrl: String): BigInteger {
        val hex = rpcClient.getGasPrice(rpcUrl).removePrefix("0x").trimStart('0').ifEmpty { "0" }
        return BigInteger(hex, 16).max(BigInteger.valueOf(1_000_000_000L))
    }
}
