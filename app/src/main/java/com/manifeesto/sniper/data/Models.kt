package com.manifeesto.sniper.data

/**
 * 核心数据模型
 */
data class AirdropCampaign(
    val id: String,
    val name: String,
    val network: Network,
    val rpcUrl: String,
    val fallbackRpcUrls: List<String> = emptyList(),
    val requiredActions: List<CampaignAction>,
    val estimatedValueUsd: Double,
    val deadline: Long,         // Unix timestamp
    val isActive: Boolean = true,
    val contractAddress: String = "",   // 合约地址（如有）
    val proofApiUrl: String = "",       // merkle proof API 端点
    val explorerBaseUrl: String = "",   // 区块浏览器基础 URL
    val faucetUrl: String = ""          // 水龙头 URL
)

data class ClaimableAirdrop(
    val campaignId: String,
    val campaignName: String,
    val network: Network,
    val contractAddress: String,
    val tokenAddress: String,   // ERC20 合约地址
    val tokenSymbol: String,
    val amount: String,         // raw token amount (wei)
    val valueUsd: Double,
    val claimIndex: Long = 0,
    val merkleProof: List<String> = emptyList()  // 真实 merkle proof
)

data class TokenBalance(
    val tokenAddress: String,
    val tokenSymbol: String,
    val rawAmount: String,      // wei
    val decimals: Int = 18,
    val network: Network
) {
    fun humanAmount(): Double {
        return try {
            val raw = java.math.BigInteger(rawAmount)
            raw.toBigDecimal().divide(
                java.math.BigDecimal.TEN.pow(decimals),
                6, java.math.RoundingMode.HALF_UP
            ).toDouble()
        } catch (_: Exception) { 0.0 }
    }
}

data class SwapResult(
    val success: Boolean,
    val txHash: String = "",
    val usdcReceived: Double = 0.0,
    val error: String = ""
)

enum class Network(
    val chainId: Long,
    val rpcUrl: String,
    val explorerUrl: String,
    val nativeSymbol: String = "ETH",
    // Uniswap V2 fork router — для auto-swap to USDC
    val dexRouter: String = "",
    val usdcAddress: String = "",
    val wethAddress: String = ""
) {
    ETH(
        chainId = 1,
        rpcUrl = "https://cloudflare-eth.com",
        explorerUrl = "https://etherscan.io",
        nativeSymbol = "ETH",
        dexRouter = "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D",
        usdcAddress = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
        wethAddress = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
    ),
    BSC(
        chainId = 56,
        rpcUrl = "https://bsc-dataseed1.binance.org/",
        explorerUrl = "https://bscscan.com",
        nativeSymbol = "BNB",
        dexRouter = "0x10ED43C718714eb63d5aA57B78B54704E256024E",
        usdcAddress = "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d",
        wethAddress = "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c"
    ),
    POLYGON(
        chainId = 137,
        rpcUrl = "https://polygon-rpc.com",
        explorerUrl = "https://polygonscan.com",
        nativeSymbol = "MATIC",
        dexRouter = "0xa5E0829CaCEd8fFDD4De3c43696c57F7D7A678ff",
        usdcAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174",
        wethAddress = "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270"
    ),
    ARBITRUM(
        chainId = 42161,
        rpcUrl = "https://arb1.arbitrum.io/rpc",
        explorerUrl = "https://arbiscan.io",
        nativeSymbol = "ETH",
        dexRouter = "0x1b02dA8Cb0d097eB8D57A175b88c7D8b47997506",
        usdcAddress = "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8",
        wethAddress = "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1"
    ),
    BASE(
        chainId = 8453,
        rpcUrl = "https://mainnet.base.org",
        explorerUrl = "https://basescan.org",
        nativeSymbol = "ETH",
        dexRouter = "0x4752ba5DBc23f44D87826276BF6Fd6b1C372aD24",
        usdcAddress = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
        wethAddress = "0x4200000000000000000000000000000000000006"
    ),
    OPTIMISM(
        chainId = 10,
        rpcUrl = "https://mainnet.optimism.io",
        explorerUrl = "https://optimistic.etherscan.io",
        nativeSymbol = "ETH",
        dexRouter = "0x9c12939390052919aF3155f41Bf4160Fd3666A6f",
        usdcAddress = "0x7F5c764cBc14f9669B88837ca1490cCa17c31607",
        wethAddress = "0x4200000000000000000000000000000000000006"
    ),
    BSC_TESTNET(
        chainId = 97,
        rpcUrl = "https://data-seed-prebsc-1-s1.binance.org:8545/",
        explorerUrl = "https://testnet.bscscan.com",
        nativeSymbol = "tBNB"
    ),
    MONAD_TESTNET(
        chainId = 10143,
        rpcUrl = "https://testnet-rpc.monad.xyz",
        explorerUrl = "https://testnet.monadexplorer.com",
        nativeSymbol = "MON"
    ),
    MEGAETH_TESTNET(
        chainId = 6342,
        rpcUrl = "https://carrot.megaeth.com/rpc",
        explorerUrl = "https://megaexplorer.xyz",
        nativeSymbol = "ETH"
    ),
    BERACHAIN_TESTNET(
        chainId = 80085,
        rpcUrl = "https://artio.rpc.berachain.com",
        explorerUrl = "https://artio.beratrail.io",
        nativeSymbol = "BERA"
    )
}

enum class CampaignAction {
    BRIDGE,
    SWAP,
    PROVIDE_LP,
    DEPLOY_CONTRACT,
    VOTE,
    STAKE
}
