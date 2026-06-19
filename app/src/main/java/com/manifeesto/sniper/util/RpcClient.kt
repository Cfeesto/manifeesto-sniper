package com.manifeesto.sniper.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 及 HTTP 客户端 — 与区块链节点和外部 API 通信
 */
class RpcClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private var requestId = 1

    /** 发送 JSON-RPC 请求 */
    fun call(rpcUrl: String, method: String, params: List<Any> = emptyList()): JsonObject? {
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

    /** 获取账户原生代币余额 */
    fun getBalance(rpcUrl: String, address: String): String {
        return call(rpcUrl, "eth_getBalance", listOf(address, "latest"))
            ?.get("result")?.asString ?: "0x0"
    }

    /** 获取 nonce */
    fun getNonce(rpcUrl: String, address: String): Long {
        val hex = call(rpcUrl, "eth_getTransactionCount", listOf(address, "latest"))
            ?.get("result")?.asString ?: "0x0"
        return hex.removePrefix("0x").toLong(16)
    }

    /** 广播已签名交易 */
    fun sendRawTransaction(rpcUrl: String, signedTx: String): String? {
        return call(rpcUrl, "eth_sendRawTransaction", listOf(signedTx))
            ?.get("result")?.asString
    }

    /** 获取当前 Gas 价格 */
    fun getGasPrice(rpcUrl: String): String {
        return call(rpcUrl, "eth_gasPrice", emptyList())
            ?.get("result")?.asString ?: "0x3B9ACA00" // 1 gwei fallback
    }

    /** HTTP GET 请求 */
    fun get(url: String): String? {
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    /** HTTP POST 请求 (用于水龙头等 REST API) */
    fun post(url: String, jsonBody: String): String? {
        return try {
            client.newCall(
                Request.Builder().url(url).post(jsonBody.toRequestBody(JSON)).build()
            ).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) { null }
    }
}
