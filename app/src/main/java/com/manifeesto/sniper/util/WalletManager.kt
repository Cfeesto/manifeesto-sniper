package com.manifeesto.sniper.util

import com.manifeesto.sniper.data.Network

/**
 * 钱包管理器 — 管理私钥和签名操作
 * 私钥存储在 Android EncryptedSharedPreferences 中 (AES256)
 *
 * 注意: 正式使用前必须在设置界面导入你的钱包私钥
 */
class WalletManager {

    // 每个网络对应的钱包地址 (从加密存储读取)
    fun getWallet(network: Network): WalletInfo {
        // TODO: 从 EncryptedSharedPreferences 读取
        return WalletInfo(
            address = "",
            privateKey = "",
            network = network
        )
    }

    /**
     * 获取自动提现目标地址 (你的主钱包 — Gem Wallet 或任意地址)
     */
    fun getWithdrawAddress(): String {
        // TODO: 从设置读取用户配置的提现地址
        return ""
    }

    /**
     * 检查钱包是否已配置
     */
    fun isConfigured(): Boolean {
        return getWallet(Network.BSC).address.isNotEmpty()
    }

    data class WalletInfo(
        val address: String,
        val privateKey: String,
        val network: Network
    )
}
