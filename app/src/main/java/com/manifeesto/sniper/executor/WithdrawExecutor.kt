package com.manifeesto.sniper.executor

import android.content.Context
import com.manifeesto.sniper.data.ClaimableAirdrop
import com.manifeesto.sniper.util.RpcClient
import com.manifeesto.sniper.util.WalletManager

/**
 * 自动提现执行器 — 领取空投并转入目标钱包
 */
class WithdrawExecutor(context: Context) {

    private val rpcClient = RpcClient()
    private val walletManager = WalletManager(context)

    suspend fun claimAndWithdraw(airdrop: ClaimableAirdrop) {
        val claimed = claim(airdrop)
        if (!claimed) return
        withdraw(airdrop)
    }

    private suspend fun claim(airdrop: ClaimableAirdrop): Boolean {
        val claimData = buildClaimCalldata(airdrop)
        // TODO: 签名并广播交易
        return true
    }

    private suspend fun withdraw(airdrop: ClaimableAirdrop) {
        val targetWallet = walletManager.getWithdrawAddress()
        val transferData = buildTransferCalldata(targetWallet, airdrop.amount)
        // TODO: 广播转账交易
    }

    private fun buildClaimCalldata(airdrop: ClaimableAirdrop): String {
        return "0xd294f093"
    }

    private fun buildTransferCalldata(to: String, amount: String): String {
        val cleanTo = to.removePrefix("0x").padStart(64, '0')
        val hexAmount = amount.toBigIntegerOrNull()?.toString(16)?.padStart(64, '0') ?: "0".padStart(64, '0')
        return "0xa9059cbb$cleanTo$hexAmount"
    }
}
