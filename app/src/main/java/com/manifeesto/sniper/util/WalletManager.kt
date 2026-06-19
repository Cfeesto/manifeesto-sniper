package com.manifeesto.sniper.util

import android.content.Context
import android.content.SharedPreferences
import com.manifeesto.sniper.data.Network

/**
 * 钱包管理器 — 使用 SharedPreferences 持久化存储钱包信息
 * 注：runBlocking + DataStore 在主线程会死锁，改用 SharedPreferences 同步读取
 */
class WalletManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WALLET_ADDRESS = "wallet_address"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_WITHDRAW_ADDRESS = "withdraw_address"
    }

    fun getWalletAddress(): String =
        prefs.getString(KEY_WALLET_ADDRESS, "") ?: ""

    fun getPrivateKey(): String =
        prefs.getString(KEY_PRIVATE_KEY, "") ?: ""

    fun getWithdrawAddress(): String {
        val withdraw = prefs.getString(KEY_WITHDRAW_ADDRESS, "") ?: ""
        return withdraw.ifEmpty { getWalletAddress() }
    }

    fun isConfigured(): Boolean =
        getWalletAddress().isNotEmpty() && getPrivateKey().isNotEmpty()

    // suspend 保留以兼容调用方，实际写入是同步的
    suspend fun saveWallet(address: String, privateKey: String, withdrawAddress: String = "") {
        prefs.edit()
            .putString(KEY_WALLET_ADDRESS, address.trim())
            .putString(KEY_PRIVATE_KEY, privateKey.trim())
            .putString(KEY_WITHDRAW_ADDRESS, withdrawAddress.trim().ifEmpty { address.trim() })
            .apply()
    }

    suspend fun clearWallet() {
        prefs.edit()
            .remove(KEY_WALLET_ADDRESS)
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_WITHDRAW_ADDRESS)
            .apply()
    }

    fun getWallet(network: Network): WalletInfo {
        return WalletInfo(
            address = getWalletAddress(),
            privateKey = getPrivateKey(),
            network = network
        )
    }

    data class WalletInfo(
        val address: String,
        val privateKey: String,
        val network: Network
    )
}
