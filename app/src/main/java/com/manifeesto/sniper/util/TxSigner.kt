package com.manifeesto.sniper.util

import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * EVM 交易签名工具
 * 使用 web3j 进行 RLP 编码 + secp256k1 ECDSA 签名
 */
object TxSigner {

    /**
     * 签名并返回可广播的 hex 交易
     *
     * @param privateKey  64位hex私钥 (无0x前缀)
     * @param chainId     链 ID (BSC=56, ETH=1, Arbitrum=42161 等)
     * @param nonce       账户 nonce
     * @param gasPrice    gas价格 (wei)
     * @param gasLimit    gas上限
     * @param to          目标合约/地址
     * @param value       发送的原生代币 (wei), 无则0
     * @param data        合约调用数据 hex (无0x前缀也可)
     */
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
        val credentials = Credentials.create(privateKey)

        val rawTx = RawTransaction.createTransaction(
            nonce.toBigInteger(),
            gasPrice,
            gasLimit,
            to,
            value,
            data
        )

        val signedBytes = TransactionEncoder.signMessage(rawTx, chainId, credentials)
        return Numeric.toHexString(signedBytes)
    }

    /**
     * 从私钥导出以太坊地址
     */
    fun addressFromPrivateKey(privateKey: String): String {
        return Credentials.create(privateKey).address
    }
}
