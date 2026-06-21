package com.manifeesto.sniper.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 及 HTTP 客户端 — 与区块链节点和外部 API 通信
 * 增强：详细错误日志、RPC 回退、超时分类
 */
class RpcClient {

    private val TAG = "RpcClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private var requestId = 1

    data class RpcCallResult(
        val success: Boolean,
        val result: JsonObject? = null,
        val error: String = "",
        val httpCode: Int = 0,
        val rpcErrorCode: Int = 0
    )

    data class TxSendResult(
        val txHash: String? = null,
        val error: String = "",
        val httpCode: Int = 0,
        val rpcErrorCode: Int = 0
    )

    /** 发送 JSON-RPC 请求 (带回退列表和详细错误) */
    fun call(rpcUrl: String, method: String, params: List<Any> = emptyList()): JsonObject? {
        return callWithFallback(listOf(rpcUrl), method, params).result
    }

    /** 支持多个 RPC URL 回退的调用 */
    fun callWithFallback(
        rpcUrls: List<String>,
        method: String,
        params: List<Any> = emptyList()
    ): RpcCallResult {
        var lastError = RpcCallResult(false, error = "No RPC URLs provided")
        rpcUrls.forEach { url ->
            if (url.isEmpty()) return@forEach
            val body = gson.toJson(mapOf(
                "jsonrpc" to "2.0", "method" to method,
                "params" to params, "id" to requestId++
            ))
            try {
                val response = client.newCall(
                    Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
                ).execute()

                val responseBody = response.body?.string() ?: ""
                val httpCode = response.code

                if (!response.isSuccessful) {
                    val err = "HTTP $httpCode from $url for $method"
                    if (httpCode in 500..599) {
                        Log.w(TAG, "$err — $responseBody")
                    } else {
                        Log.e(TAG, "$err — $responseBody")
                    }
                    lastError = RpcCallResult(false, error = "HTTP $httpCode", httpCode = httpCode)
                } else {
                    val json = try {
                        gson.fromJson(responseBody, JsonObject::class.java)
                    } catch (e: Exception) {
                        Log.e(TAG, "JSON parse failure from $url: ${e.message}")
                        lastError = RpcCallResult(false, error = "JSON parse: ${e.message}", httpCode = httpCode)
                        return@forEach
                    }
                    if (json.has("error") && !json.get("error").isJsonNull) {
                        val rpcErr = json.getAsJsonObject("error")
                        val rpcCode = rpcErr.get("code")?.asInt ?: 0
                        val rpcMsg = rpcErr.get("message")?.asString ?: "unknown"
                        Log.e(TAG, "RPC error [$rpcCode] from $url ($method): $rpcMsg")
                        lastError = RpcCallResult(false, error = "RPC $rpcCode: $rpcMsg", httpCode = httpCode, rpcErrorCode = rpcCode)
                    } else {
                        return RpcCallResult(true, json, httpCode = httpCode)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error to $url ($method): ${e.javaClass.simpleName} — ${e.message}")
                lastError = RpcCallResult(false, error = "Network: ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error to $url ($method): ${e.message}")
                lastError = RpcCallResult(false, error = "Unexpected: ${e.message?.take(80)}")
            }
        }
        return lastError
    }

    /** 获取账户原生代币余额 (带回退) */
    fun getBalance(rpcUrl: String, address: String): String {
        return getBalanceWithFallback(listOf(rpcUrl), address)
    }

    fun getBalanceWithFallback(rpcUrls: List<String>, address: String): String {
        return callWithFallback(rpcUrls, "eth_getBalance", listOf(address, "latest"))
            .result?.get("result")?.asString ?: "0x0"
    }

    /** 获取 nonce (带回退) */
    fun getNonce(rpcUrl: String, address: String): Long {
        return getNonceWithFallback(listOf(rpcUrl), address)
    }

    fun getNonceWithFallback(rpcUrls: List<String>, address: String): Long {
        val hex = callWithFallback(rpcUrls, "eth_getTransactionCount", listOf(address, "latest"))
            .result?.get("result")?.asString ?: "0x0"
        return hex.removePrefix("0x").toLong(16)
    }

    /** 广播已签名交易 (带回退) */
    fun sendRawTransaction(rpcUrl: String, signedTx: String): String? {
        return sendRawTransactionWithFallback(listOf(rpcUrl), signedTx)
    }

    fun sendRawTransactionWithFallback(rpcUrls: List<String>, signedTx: String): String? {
        return sendRawTransactionDetailed(rpcUrls, signedTx).txHash
    }

    /** 广播已签名交易并返回详细错误信息 */
    fun sendRawTransactionDetailed(rpcUrls: List<String>, signedTx: String): TxSendResult {
        val result = callWithFallback(rpcUrls, "eth_sendRawTransaction", listOf(signedTx))
        if (!result.success) {
            Log.e(TAG, "TX broadcast FAILED: HTTP=${result.httpCode}, RPC=${result.rpcErrorCode}, error=${result.error}")
            return TxSendResult(error = result.error, httpCode = result.httpCode, rpcErrorCode = result.rpcErrorCode)
        }
        val txHash = result.result?.get("result")?.asString
        if (txHash != null) {
            Log.d(TAG, "TX broadcast OK: $txHash")
        }
        return TxSendResult(txHash = txHash, error = if (txHash == null) "No txHash in response" else "")
    }

    /** 获取当前 Gas 价格 (带回退) */
    fun getGasPrice(rpcUrl: String): String {
        return getGasPriceWithFallback(listOf(rpcUrl))
    }

    fun getGasPriceWithFallback(rpcUrls: List<String>): String {
        return callWithFallback(rpcUrls, "eth_gasPrice", emptyList())
            .result?.get("result")?.asString ?: "0x3B9ACA00"
    }

    /** 获取交易回执 (带回退) */
    fun getTransactionReceipt(rpcUrl: String, txHash: String): JsonObject? {
        return getTransactionReceiptWithFallback(listOf(rpcUrl), txHash)
    }

    fun getTransactionReceiptWithFallback(rpcUrls: List<String>, txHash: String): JsonObject? {
        return callWithFallback(rpcUrls, "eth_getTransactionReceipt", listOf(txHash)).result
    }

    /** HTTP GET 请求 */
    fun get(url: String): String? {
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else {
                    Log.w(TAG, "HTTP GET ${response.code} from $url")
                    null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "HTTP GET network error $url: ${e.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET unexpected error $url: ${e.message}")
            null
        }
    }

    /** HTTP POST 请求 */
    fun post(url: String, jsonBody: String): String? {
        return try {
            client.newCall(
                Request.Builder().url(url).post(jsonBody.toRequestBody(JSON)).build()
            ).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else {
                    Log.w(TAG, "HTTP POST ${response.code} from $url")
                    null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "HTTP POST network error $url: ${e.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "HTTP POST unexpected error $url: ${e.message}")
            null
        }
    }

    /** 发送 JSON-RPC 请求 (保留旧接口兼容) */
    fun callDirect(rpcUrl: String, method: String, params: List<Any> = emptyList()): JsonObject? {
        val body = gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params,
            "id" to requestId++
        ))
        return try {
            client.newCall(
                Request.Builder().url(rpcUrl).post(body.toRequestBody(JSON)).build()
            ).execute().use { response ->
                if (response.isSuccessful)
                    gson.fromJson(response.body?.string() ?: return null, JsonObject::class.java)
                else null
            }
        } catch (_: Exception) { null }
    }
}
