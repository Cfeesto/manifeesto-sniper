package com.manifeesto.sniper.util

import android.util.Log
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.KeccakDigest
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * EVM 交易签名器 — 纯 BouncyCastle 实现，不依赖 web3j
 * 避免 web3j 多个模块缺少 Android 构建产物导致的 NoClassDefFoundError
 *
 * 实现：RLP 编码 + Keccak256 + secp256k1 ECDSA
 */
object TxSigner {

    private const val TAG = "TxSigner"

    // secp256k1 曲线参数
    private val CURVE_PARAMS = SECNamedCurves.getByName("secp256k1")
    private val CURVE = ECDomainParameters(
        CURVE_PARAMS.curve, CURVE_PARAMS.g, CURVE_PARAMS.n, CURVE_PARAMS.h
    )
    private val HALF_CURVE_ORDER = CURVE_PARAMS.n.shiftRight(1)

    /**
     * 签名 EVM Legacy 交易 (EIP-155)
     * 返回 hex 编码的已签名交易，可直接传给 eth_sendRawTransaction
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
        val privKeyBytes = hexToBytes(privateKey.removePrefix("0x"))

        // 1. RLP 编码待签名内容 (EIP-155: 末尾附 chainId, 0, 0)
        val toBytes = if (to.isEmpty()) ByteArray(0) else hexToBytes(to.removePrefix("0x"))
        val dataBytes = if (data.isEmpty() || data == "0x") ByteArray(0)
                        else hexToBytes(data.removePrefix("0x"))

        val rlpForSigning = rlpList(
            rlpBigInt(BigInteger.valueOf(nonce)),
            rlpBigInt(gasPrice),
            rlpBigInt(gasLimit),
            rlpBytes(toBytes),
            rlpBigInt(value),
            rlpBytes(dataBytes),
            rlpBigInt(BigInteger.valueOf(chainId)),   // EIP-155
            rlpBigInt(BigInteger.ZERO),
            rlpBigInt(BigInteger.ZERO)
        )

        // 2. Keccak256 哈希
        val hash = keccak256(rlpForSigning)

        // 3. secp256k1 ECDSA 签名
        val sig = ecSign(privKeyBytes, hash)
        val r = sig[0]
        val s = sig[1]
        val recId = sig[2].toInt()

        // EIP-155 v 值
        val v = BigInteger.valueOf(chainId * 2 + 35 + recId)

        // 4. RLP 编码完整签名交易
        val signedRlp = rlpList(
            rlpBigInt(BigInteger.valueOf(nonce)),
            rlpBigInt(gasPrice),
            rlpBigInt(gasLimit),
            rlpBytes(toBytes),
            rlpBigInt(value),
            rlpBytes(dataBytes),
            rlpBigInt(v),
            rlpBigInt(r),
            rlpBigInt(s)
        )

        return "0x" + bytesToHex(signedRlp)
    }

    /**
     * 从私钥计算以太坊地址
     */
    fun addressFromPrivateKey(privateKey: String): String {
        val privBytes = hexToBytes(privateKey.removePrefix("0x"))
        val privKey = BigInteger(1, privBytes)
        val pubKeyPoint = CURVE.g.multiply(privKey).normalize()
        val x = pubKeyPoint.xCoord.encoded
        val y = pubKeyPoint.yCoord.encoded
        val pubKeyBytes = ByteArray(64)
        // 填充到 32 字节
        System.arraycopy(x, x.size - 32, pubKeyBytes, 0, 32)
        System.arraycopy(y, y.size - 32, pubKeyBytes, 32, 32)
        val hash = keccak256(pubKeyBytes)
        return "0x" + bytesToHex(hash).takeLast(40)
    }

    // ─── secp256k1 ECDSA ───────────────────────────────────────

    /**
     * 返回 [r, s, recoveryId]
     */
    private fun ecSign(privateKeyBytes: ByteArray, hash: ByteArray): Array<BigInteger> {
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, privateKeyBytes), CURVE))
        val components = signer.generateSignature(hash)
        var r = components[0]
        var s = components[1]

        // 规范化 s (低 s)
        if (s > HALF_CURVE_ORDER) s = CURVE_PARAMS.n.subtract(s)

        // 计算 recovery id
        val privKey = BigInteger(1, privateKeyBytes)
        val pubPoint = CURVE.g.multiply(privKey).normalize()
        val recId = findRecoveryId(hash, r, s, pubPoint)

        return arrayOf(r, s, BigInteger.valueOf(recId.toLong()))
    }

    private fun findRecoveryId(hash: ByteArray, r: BigInteger, s: BigInteger, expectedPub: org.bouncycastle.math.ec.ECPoint): Int {
        for (i in 0..1) {
            val recovered = recoverPubKey(hash, r, s, i) ?: continue
            if (recovered.normalize() == expectedPub.normalize()) return i
        }
        return 0
    }

    private fun recoverPubKey(hash: ByteArray, r: BigInteger, s: BigInteger, recId: Int): org.bouncycastle.math.ec.ECPoint? {
        return try {
            val n = CURVE_PARAMS.n
            val x = r.add(BigInteger.valueOf(recId.toLong() / 2).multiply(n))
            if (x >= CURVE_PARAMS.curve.field.characteristic) return null
            val rPoint = decompressKey(x, recId and 1 == 1) ?: return null
            if (!rPoint.multiply(n).isInfinity) return null
            val eInv = BigInteger(1, hash).negate().mod(n)
            val rInv = r.modInverse(n)
            rPoint.multiply(rInv.multiply(s).mod(n)).add(
                CURVE_PARAMS.g.multiply(rInv.multiply(eInv).mod(n))
            )
        } catch (e: Exception) { null }
    }

    private fun decompressKey(xBN: BigInteger, yBit: Boolean): org.bouncycastle.math.ec.ECPoint? {
        return try {
            val x9 = org.bouncycastle.asn1.x9.X9IntegerConverter()
            val compEnc = x9.integerToBytes(xBN, 1 + x9.getByteLength(CURVE_PARAMS.curve))
            compEnc[0] = if (yBit) 0x03 else 0x02
            CURVE_PARAMS.curve.decodePoint(compEnc)
        } catch (e: Exception) { null }
    }

    // ─── Keccak256 ─────────────────────────────────────────────

    private fun keccak256(input: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(input, 0, input.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    // ─── RLP 编码 ──────────────────────────────────────────────

    private fun rlpBigInt(value: BigInteger): ByteArray {
        if (value == BigInteger.ZERO) return rlpBytes(ByteArray(0))
        return rlpBytes(stripLeadingZeroes(value.toByteArray()))
    }

    private fun rlpBytes(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80 -> bytes
            bytes.size <= 55 -> byteArrayOf((0x80 + bytes.size).toByte()) + bytes
            else -> {
                val lenBytes = bigEndianBytes(bytes.size)
                byteArrayOf((0xB7 + lenBytes.size).toByte()) + lenBytes + bytes
            }
        }
    }

    private fun rlpList(vararg items: ByteArray): ByteArray {
        val payload = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return when {
            payload.size <= 55 -> byteArrayOf((0xC0 + payload.size).toByte()) + payload
            else -> {
                val lenBytes = bigEndianBytes(payload.size)
                byteArrayOf((0xF7 + lenBytes.size).toByte()) + lenBytes + payload
            }
        }
    }

    // ─── 辅助 ──────────────────────────────────────────────────

    private fun stripLeadingZeroes(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun bigEndianBytes(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(4).putInt(value).array()
        var start = 0
        while (start < buf.size - 1 && buf[start] == 0.toByte()) start++
        return buf.copyOfRange(start, buf.size)
    }

    fun hexToBytes(hex: String): ByteArray {
        val h = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
