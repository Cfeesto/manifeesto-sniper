package com.manifeesto.sniper.util

import android.util.Log
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.Security

object TxSigner {

    private const val TAG = "TxSigner"
    private var bouncyCastleInstalled = false

    // 懒加载 BouncyCastle — 只在第一次签名时初始化
    private fun ensureBouncyCastle() {
        if (bouncyCastleInstalled) return
        try {
            // Android 上需要手动注册 BouncyCastle provider
            val providerClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val provider = providerClass.getDeclaredConstructor().newInstance() as java.security.Provider
            val existing = Security.getProvider("BC")
            if (existing != null) Security.removeProvider("BC")
            Security.insertProviderAt(provider, 1)
            bouncyCastleInstalled = true
            Log.d(TAG, "BouncyCastle installed")
        } catch (e: Exception) {
            Log.w(TAG, "BouncyCastle setup: ${e.message}")
            bouncyCastleInstalled = true // 继续尝试 — Android 可能已内置
        }
    }

    fun sign(
        privateKey: String,
        chainId: Long,
        nonce: Long,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        to: String,
        value: BigInteger = BigInteger.ZERO,
        data: String = ""
    ): String {
        ensureBouncyCastle()
        val credentials = Credentials.create(privateKey)
        val rawTx = RawTransaction.createTransaction(
            nonce.toBigInteger(), gasPrice, gasLimit, to, value, data
        )
        val signedBytes = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        return Numeric.toHexString(signedBytes)
    }

    fun addressFromPrivateKey(privateKey: String): String {
        ensureBouncyCastle()
        return Credentials.create(privateKey).address
    }
}
