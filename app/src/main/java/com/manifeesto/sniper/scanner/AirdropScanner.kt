package com.manifeesto.sniper.scanner

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.manifeesto.sniper.data.AirdropCampaign
import com.manifeesto.sniper.data.CampaignAction
import com.manifeesto.sniper.data.ClaimableAirdrop
import com.manifeesto.sniper.data.Network
import com.manifeesto.sniper.util.RpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 空投扫描器 — 获取活跃活动并检查真实可领取余额
 * 支持 Merkle Distributor 合约的 isClaimed 查询和 proof 获取
 */
class AirdropScanner {

    private val TAG = "AirdropScanner"
    private val rpcClient = RpcClient()
    private val gson = Gson()

    // ─── 已知活跃活动 ──────────────────────────────────────────
    private val knownCampaigns = listOf(
        AirdropCampaign(
            id = "monad_testnet",
            name = "Monad Testnet",
            network = Network.MONAD_TESTNET,
            rpcUrl = "https://testnet-rpc.monad.xyz",
            fallbackRpcUrls = listOf(
                "https://rpc.testnet.monad.xyz",
                "https://monad-testnet.drpc.org",
                "https://testnet-rpc2.monad.xyz"
            ),
            requiredActions = listOf(CampaignAction.SWAP, CampaignAction.DEPLOY_CONTRACT, CampaignAction.BRIDGE),
            estimatedValueUsd = 500.0,
            deadline = 1900000000L,
            explorerBaseUrl = "https://testnet.monadexplorer.com",
            faucetUrl = "https://testnet.monad.xyz/faucet"
        ),
        AirdropCampaign(
            id = "megaeth_testnet",
            name = "MegaETH Testnet",
            network = Network.MEGAETH_TESTNET,
            rpcUrl = "https://carrot.megaeth.com/rpc",
            fallbackRpcUrls = listOf(
                "https://rpc.megaeth.org",
                "https://megaeth.drpc.org"
            ),
            requiredActions = listOf(CampaignAction.SWAP, CampaignAction.PROVIDE_LP),
            estimatedValueUsd = 300.0,
            deadline = 1900000000L,
            explorerBaseUrl = "https://megaexplorer.xyz",
            faucetUrl = "https://faucet.megaeth.com"
        ),
        AirdropCampaign(
            id = "berachain",
            name = "Berachain Activity",
            network = Network.BERACHAIN_TESTNET,
            rpcUrl = "https://artio.rpc.berachain.com",
            fallbackRpcUrls = listOf(
                "https://berachain-artio.drpc.org",
                "https://rpc.berachain-apis.com"
            ),
            requiredActions = listOf(CampaignAction.STAKE, CampaignAction.VOTE),
            estimatedValueUsd = 200.0,
            deadline = 1900000000L,
            explorerBaseUrl = "https://artio.beratrail.io",
            faucetUrl = "https://artio.faucet.berachain.com"
        ),
        AirdropCampaign(
            id = "base_activity",
            name = "Base Ecosystem Activity",
            network = Network.BASE,
            rpcUrl = Network.BASE.rpcUrl,
            requiredActions = listOf(CampaignAction.SWAP, CampaignAction.PROVIDE_LP),
            estimatedValueUsd = 150.0,
            deadline = 1900000000L,
            explorerBaseUrl = "https://basescan.org"
        ),
        AirdropCampaign(
            id = "arbitrum_activity",
            name = "Arbitrum Ecosystem",
            network = Network.ARBITRUM,
            rpcUrl = Network.ARBITRUM.rpcUrl,
            requiredActions = listOf(CampaignAction.SWAP, CampaignAction.BRIDGE),
            estimatedValueUsd = 100.0,
            deadline = 1900000000L,
            explorerBaseUrl = "https://arbiscan.io"
        )
    )

    /**
     * 获取当前活跃活动
     */
    suspend fun fetchActiveCampaigns(): List<AirdropCampaign> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        knownCampaigns.filter { it.isActive && it.deadline > now }
    }

    /**
     * 检查指定钱包地址的可领取空投
     * 1. 查询已知分发合约的 isClaimed(index)
     * 2. 如未领取，从 API 获取 merkle proof
     */
    suspend fun fetchClaimableAirdrops(walletAddress: String): List<ClaimableAirdrop> =
        withContext(Dispatchers.IO) {
            val claimable = mutableListOf<ClaimableAirdrop>()
            if (walletAddress.isEmpty()) return@withContext claimable

            // 已知分发合约列表 — TGE 后更新合约地址和 proof API
            val distributors = getKnownDistributors()

            distributors.forEach { dist ->
                try {
                    val alreadyClaimed = checkIsClaimed(dist, walletAddress)
                    if (!alreadyClaimed) {
                        val proof = fetchMerkleProof(dist, walletAddress)
                        if (proof != null) {
                            claimable.add(proof)
                            Log.d(TAG, "Claimable found: ${proof.tokenSymbol} on ${proof.network.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Distributor check failed for ${dist.name}: ${e.message}")
                }
            }
            claimable
        }

    // ─── Merkle Distributor 查询 ──────────────────────────────

    /**
     * 查询合约 isClaimed(uint256 index) — 防止重复领取
     */
    private fun checkIsClaimed(dist: Distributor, address: String): Boolean {
        if (dist.contractAddress.isEmpty()) return false
        return try {
            // 先查地址索引
            val index = getAddressIndex(dist, address) ?: return true // 地址不在列表中
            // isClaimed(uint256 index) — 0xe2030d8b
            val indexHex = index.toString(16).padStart(64, '0')
            val result = rpcClient.call(
                dist.network.rpcUrl,
                "eth_call",
                listOf(mapOf("to" to dist.contractAddress, "data" to "0xe2030d8b$indexHex"), "latest")
            )
            val hex = result?.get("result")?.asString?.removePrefix("0x") ?: return false
            hex.trimStart('0').isNotEmpty() && hex.trimStart('0') != "0"
        } catch (_: Exception) { false }
    }

    /**
     * 通过项目 API 获取钱包的 Merkle proof
     */
    private fun fetchMerkleProof(dist: Distributor, address: String): ClaimableAirdrop? {
        if (dist.proofApiUrl.isEmpty()) return null
        return try {
            val url = "${dist.proofApiUrl}${address.lowercase()}"
            val response = rpcClient.get(url) ?: return null
            val json = gson.fromJson(response, JsonObject::class.java)

            // 标准 proof API 格式: {index, amount, proof:[...]}
            val index = json.get("index")?.asLong ?: return null
            val amount = json.get("amount")?.asString ?: return null
            val proofArray = json.getAsJsonArray("proof") ?: return null
            val proof = proofArray.map { it.asString }

            ClaimableAirdrop(
                campaignId = dist.id,
                campaignName = dist.name,
                network = dist.network,
                contractAddress = dist.contractAddress,
                tokenAddress = dist.tokenAddress,
                tokenSymbol = dist.tokenSymbol,
                amount = amount,
                valueUsd = estimateValue(amount, dist.tokenSymbol),
                claimIndex = index,
                merkleProof = proof
            )
        } catch (e: Exception) {
            Log.w(TAG, "Proof fetch failed for ${dist.name}: ${e.message}")
            null
        }
    }

    private fun getAddressIndex(dist: Distributor, address: String): Long? {
        // 某些合约提供 getIndex(address) — 0x0175b1c4
        return try {
            val padded = address.removePrefix("0x").padStart(64, '0')
            val result = rpcClient.call(
                dist.network.rpcUrl,
                "eth_call",
                listOf(mapOf("to" to dist.contractAddress, "data" to "0x0175b1c4$padded"), "latest")
            )
            val hex = result?.get("result")?.asString?.removePrefix("0x")?.trimStart('0')?.ifEmpty { "0" }
                ?: return null
            hex.toLong(16)
        } catch (_: Exception) { null }
    }

    private fun estimateValue(rawAmount: String, symbol: String): Double {
        // 粗略估算 — PriceOracle 在实际领取时会精确计算
        return try {
            val amount = java.math.BigInteger(rawAmount.removePrefix("0x"), 16)
                .toBigDecimal().divide(java.math.BigDecimal.TEN.pow(18), 4, java.math.RoundingMode.HALF_UP)
                .toDouble()
            amount * 1.0 // price unknown until oracle check
        } catch (_: Exception) { 0.0 }
    }

    // ─── 已知分发合约 ──────────────────────────────────────────

    data class Distributor(
        val id: String,
        val name: String,
        val network: Network,
        val contractAddress: String,    // Merkle Distributor 合约
        val tokenAddress: String,       // ERC20 token
        val tokenSymbol: String,
        val proofApiUrl: String         // {baseUrl}{address} → JSON with proof
    )

    /**
     * 已知活跃的 Merkle 分发合约
     * TGE 后在此添加真实合约地址
     */
    private fun getKnownDistributors(): List<Distributor> = listOf(
        // Arbitrum 生态活跃分发 (示例 — 实际地址需 TGE 后更新)
        Distributor(
            id = "arb_stip",
            name = "ARB STIP Round 2",
            network = Network.ARBITRUM,
            contractAddress = "0x67a24CE4321aB3aF51c2D0a4801c3E111D88C9d9",
            tokenAddress = "0x912CE59144191C1204E64559FE8253a0e49E6548",
            tokenSymbol = "ARB",
            proofApiUrl = "https://arbmerkle.arbitrum.io/api/proof/"
        ),
        // OP 生态 — Optimism 定期空投活跃用户
        Distributor(
            id = "op_airdrop4",
            name = "OP Airdrop #4",
            network = Network.OPTIMISM,
            contractAddress = "",   // 等待 TGE 后填入
            tokenAddress = "0x4200000000000000000000000000000000000042",
            tokenSymbol = "OP",
            proofApiUrl = "https://api.optimism.io/airdrop/proof/"
        )
    ).filter { it.contractAddress.isNotEmpty() }
}
