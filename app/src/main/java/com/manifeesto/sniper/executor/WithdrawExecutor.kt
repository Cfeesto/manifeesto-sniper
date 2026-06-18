package com.manifeesto.sniper.executor

import com.manifeesto.sniper.data.ClaimableAirdrop
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.WalletManager

/**
 * 自动提现执行器 — 领取空投并转入目标钱包
 */
class WithdrawExecutor {

    private val rpcClient = RpcClient()
    private val walletManager = WalletManager()

    /**
     * 领取空投并自动发送到目标钱包
     */
    suspend fun claimAndWithdraw(airdrop: ClaimableAirdrop) {
        // 1. 调用空投合约 claim 函数
        val claimed = claim(airdrop)
        if (!claimed) return

        // 2. 转移到目标提现地址
        withdraw(airdrop)
    }

    private suspend fun claim(airdrop: ClaimableAirdrop): Boolean {
        // claim(address,uint256,bytes32[]) 标准 Merkle 空投接口
        val claimData = buildClaimCalldata(airdrop)
        // TODO: 签名并广播交易
        return true
    }

    private suspend fun withdraw(airdrop: ClaimableAirdrop) {
        val targetWallet = walletManager.getWithdrawAddress()
        // ERC20 transfer(address,uint256)
        val transferData = buildTransferCalldata(targetWallet, airdrop.amount)
        // TODO: 广播转账交易
    }

    private fun buildClaimCalldata(airdrop: ClaimableAirdrop): String {
        // 标准 Merkle 分发者 claim 调用数据
        // 函数选择器: keccak256("claim(uint256,address,uint256,bytes32[])")[:4]
        return "0xd294f093"
    }

    private fun buildTransferCalldata(to: String, amount: String): String {
        // ERC20 transfer 函数选择器: a9059cbb
        val cleanTo = to.removePrefix("0x").padStart(64, '0')
        val hexAmount = amount.toBigIntegerOrNull()?.toString(16)?.padStart(64, '0') ?: "0".padStart(64, '0')
        return "0xa9059cbb$cleanTo$hexAmount"
    }
}
