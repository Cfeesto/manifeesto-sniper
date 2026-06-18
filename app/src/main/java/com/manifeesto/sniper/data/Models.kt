package com.manifeesto.sniper.data

/**
 * 空投活动数据模型
 */
data class AirdropCampaign(
    val id: String,
    val name: String,
    val network: Network,
    val rpcUrl: String,
    val requiredActions: List<CampaignAction>,
    val estimatedValueUsd: Double,
    val deadline: Long, // Unix timestamp
    val isActive: Boolean = true
)

data class ClaimableAirdrop(
    val campaignId: String,
    val campaignName: String,
    val network: Network,
    val contractAddress: String,
    val tokenSymbol: String,
    val amount: String, // 原始数量字符串
    val valueUsd: Double
)

enum class Network(val chainId: Int, val rpcUrl: String, val explorerUrl: String) {
    BSC(56, "https://bsc-dataseed1.binance.org/", "https://bscscan.com"),
    BSC_TESTNET(97, "https://data-seed-prebsc-1-s1.binance.org:8545/", "https://testnet.bscscan.com"),
    TRON(728126428, "https://api.trongrid.io", "https://tronscan.org"),
    ETH(1, "https://cloudflare-eth.com", "https://etherscan.io"),
    POLYGON(137, "https://polygon-rpc.com", "https://polygonscan.com"),
    ARBITRUM(42161, "https://arb1.arbitrum.io/rpc", "https://arbiscan.io"),
    BASE(8453, "https://mainnet.base.org", "https://basescan.org"),
    OPTIMISM(10, "https://mainnet.optimism.io", "https://optimistic.etherscan.io")
}

enum class CampaignAction {
    BRIDGE,        // 跨链桥接
    SWAP,          // DEX 兑换
    PROVIDE_LP,    // 提供流动性
    DEPLOY_CONTRACT, // 部署合约
    VOTE,          // 治理投票
    STAKE          // 质押
}
