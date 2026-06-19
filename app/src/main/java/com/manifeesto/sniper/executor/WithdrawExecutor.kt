package com.manifeesto.sniper.executor

import android.content.Context
import com.manifeesto.sniper.data.ClaimableAirdrop
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.TxSigner
import com.manifeesto.sniper.util.WalletManager
import java.math.BigInteger

/**
 * 自动提现执行器 — 领取空投并转移到目标钱包
 */
class WithdrawExecutor(context: Context) {

    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)

    suspend fun claimAndWithdraw(airdrop: ClaimableAirdrop) {
        val wallet = walletManager.getWallet(airdrop.network)
        if (wallet.address.isEmpty() || wallet.privateKey.isEmpty()) return

        val txHash = claim(airdrop, wallet) ?: return
        // 等待确认后转移
        waitForReceipt(airdrop.network.rpcUrl, txHash)
        withdraw(airdrop, wallet)
    }

    /** 调用 Merkle airdrop 合约 claim 函数 */
    private suspend fun claim(
        airdrop: ClaimableAirdrop,
        wallet: WalletManager.WalletInfo
    ): String? {
        val rpc = airdrop.network.rpcUrl
        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = rpcGasPrice(rpc)

        // 标准 claim(uint256 index, address account, uint256 amount, bytes32[] merkleProof)
        // 函数选择器: 0x2e7ba6ef
        val index = airdrop.claimIndex.toString(16).padStart(64, '0')
        val account = wallet.address.removePrefix("0x").padStart(64, '0')
        val amount = airdrop.amount.toBigIntegerOrNull()?.toString(16)?.padStart(64, '0')
            ?: "0".padStart(64, '0')
        // 空 proof array (offset + length)
        val proofOffset = "80".padStart(64, '0')
        val proofLen = "0".padStart(64, '0')

        val data = "0x2e7ba6ef$index$account$amount$proofOffset$proofLen"

        val signedTx = TxSigner.sign(
            privateKey = wallet.privateKey,
            chainId = airdrop.network.chainId,
            nonce = nonce,
            gasPrice = gasPrice,
            gasLimit = BigInteger.valueOf(300_000),
            to = airdrop.contractAddress,
            value = BigInteger.ZERO,
            data = data
        )
        return rpcClient.sendRawTransaction(rpc, signedTx)
    }

    /** ERC20 transfer — 把领到的代币转到提现地址 */
    private suspend fun withdraw(
        airdrop: ClaimableAirdrop,
        wallet: WalletManager.WalletInfo
    ) {
        val targetAddr = walletManager.getWithdrawAddress()
        if (targetAddr.isEmpty() || targetAddr == wallet.address) return

        val rpc = airdrop.network.rpcUrl
        val nonce = rpcClient.getNonce(rpc, wallet.address)
        val gasPrice = rpcGasPrice(rpc)

        // ERC20 transfer(address to, uint256 amount) — selector: 0xa9059cbb
        val to = targetAddr.removePrefix("0x").padStart(64, '0')
        val amount = airdrop.amount.toBigIntegerOrNull()?.toString(16)?.padStart(64, '0')
            ?: return
        val data = "0xa9059cbb$to$amount"

        val signedTx = TxSigner.sign(
            privateKey = wallet.privateKey,
            chainId = airdrop.network.chainId,
            nonce = nonce,
            gasPrice = gasPrice,
            gasLimit = BigInteger.valueOf(80_000),
            to = airdrop.contractAddress,
            value = BigInteger.ZERO,
            data = data
        )
        rpcClient.sendRawTransaction(rpc, signedTx)
    }

    /** 简单轮询等待交易上链 (最多30秒) */
    private fun waitForReceipt(rpcUrl: String, txHash: String) {
        repeat(10) {
            Thread.sleep(3000)
            val result = rpcClient.call(rpcUrl, "eth_getTransactionReceipt", listOf(txHash))
            if (result?.get("result")?.isJsonNull == false) return
        }
    }

    private fun rpcGasPrice(rpc: String): BigInteger {
        val hex = rpcClient.getGasPrice(rpc).removePrefix("0x").trimStart('0').ifEmpty { "0" }
        return BigInteger(hex, 16).max(BigInteger.valueOf(1_000_000_000L))
    }
}
