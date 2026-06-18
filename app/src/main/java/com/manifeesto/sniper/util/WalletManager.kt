package com.manifeesto.sniper.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.manifeesto.sniper.data.Network
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wallet_prefs")

/**
 * 钱包管理器 — 使用 DataStore 持久化存储钱包地址和私钥
 */
class WalletManager(private val context: Context) {

    companion object {
        val KEY_WALLET_ADDRESS = stringPreferencesKey("wallet_address")
        val KEY_PRIVATE_KEY = stringPreferencesKey("private_key")
        val KEY_WITHDRAW_ADDRESS = stringPreferencesKey("withdraw_address")
    }

    fun getWalletAddress(): String = runBlocking {
        context.dataStore.data.first()[KEY_WALLET_ADDRESS] ?: ""
    }

    fun getPrivateKey(): String = runBlocking {
        context.dataStore.data.first()[KEY_PRIVATE_KEY] ?: ""
    }

    fun getWithdrawAddress(): String = runBlocking {
        val withdraw = context.dataStore.data.first()[KEY_WITHDRAW_ADDRESS] ?: ""
        withdraw.ifEmpty { getWalletAddress() }
    }

    fun isConfigured(): Boolean = getWalletAddress().isNotEmpty() && getPrivateKey().isNotEmpty()

    suspend fun saveWallet(address: String, privateKey: String, withdrawAddress: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_WALLET_ADDRESS] = address.trim()
            prefs[KEY_PRIVATE_KEY] = privateKey.trim()
            prefs[KEY_WITHDRAW_ADDRESS] = withdrawAddress.trim().ifEmpty { address.trim() }
        }
    }

    suspend fun clearWallet() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_WALLET_ADDRESS)
            prefs.remove(KEY_PRIVATE_KEY)
            prefs.remove(KEY_WITHDRAW_ADDRESS)
        }
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
