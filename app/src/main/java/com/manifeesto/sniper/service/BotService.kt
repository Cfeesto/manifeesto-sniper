package com.manifeesto.sniper.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.manifeesto.sniper.SniperApp
import com.manifeesto.sniper.executor.DexSwapper
import com.manifeesto.sniper.executor.WithdrawExecutor
import com.manifeesto.sniper.scanner.AirdropScanner
import com.manifeesto.sniper.scanner.FaucetClaimer
import com.manifeesto.sniper.scanner.OpportunityHunter
import com.manifeesto.sniper.scanner.TestnetFarmer
import com.manifeesto.sniper.ui.MainActivity
import com.manifeesto.sniper.util.WalletManager
import kotlinx.coroutines.*
import kotlin.math.max

class BotService : Service() {

    private val TAG = "BotService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    // 统计
    private var cycleCount = 0
    private var totalClaimed = 0
    private var totalUsdcEarned = 0.0

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.manifeesto.sniper.STATUS_UPDATE"
        const val EXTRA_STATUS_MSG = "status_msg"
        var isRunning = false

        // 扫描间隔: 默认5分钟，可调整
        const val CYCLE_INTERVAL_MS = 5 * 60 * 1000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Bot initializing..."))
        isRunning = true

        scope.launch {
            try {
                acquireWakeLock()
                runBotLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Fatal bot error", e)
                broadcast("Fatal error: ${e.message}")
                isRunning = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        broadcast("Bot stopped.")
    }

    // ─── 主循环 ────────────────────────────────────────────────

    private suspend fun runBotLoop() {
        val walletManager = WalletManager(applicationContext)
        val airdropScanner = AirdropScanner()
        val opportunityHunter = OpportunityHunter()
        val faucetClaimer = FaucetClaimer(walletManager)
        val farmer = try { TestnetFarmer(applicationContext) }
            catch (e: Exception) { broadcast("Farmer init error: ${e.message}"); null }
        val withdrawExecutor = try { WithdrawExecutor(applicationContext) }
            catch (e: Exception) { broadcast("Withdraw init error: ${e.message}"); null }

        broadcast("Bot started. Wallet: ${walletManager.getWalletAddress().take(10)}...")

        while (scope.isActive) {
            cycleCount++
            val cycleStart = System.currentTimeMillis()
            broadcast("─── Cycle #$cycleCount ───")
            updateNotification("Cycle #$cycleCount running...")

            try {
                runCycle(
                    walletManager, airdropScanner, opportunityHunter,
                    faucetClaimer, farmer, withdrawExecutor
                )
            } catch (e: Exception) {
                broadcast("Cycle error: ${e.message?.take(80)}")
            }

            val elapsed = System.currentTimeMillis() - cycleStart
            val waitMs = max(CYCLE_INTERVAL_MS - elapsed, 10_000L)
            broadcast("Next scan in ${waitMs / 60000}m ${(waitMs % 60000) / 1000}s | Total USDC: ${"%.2f".format(totalUsdcEarned)}")
            updateNotification("Idle — earned \$${"%.2f".format(totalUsdcEarned)} USDC total")
            delay(waitMs)
        }
    }

    // ─── 单次循环 ──────────────────────────────────────────────

    private suspend fun runCycle(
        walletManager: WalletManager,
        airdropScanner: AirdropScanner,
        opportunityHunter: OpportunityHunter,
        faucetClaimer: FaucetClaimer,
        farmer: TestnetFarmer?,
        withdrawExecutor: WithdrawExecutor?
    ) {
        val walletAddress = walletManager.getWalletAddress()

        // ── 阶段1: 检查 gas 余额，按需补充 ──────────────────────
        broadcast("Checking gas balances...")
        try {
            val networksToCheck = listOf(
                com.manifeesto.sniper.data.Network.ETH,
                com.manifeesto.sniper.data.Network.BSC_TESTNET
            )
            val refilled = faucetClaimer.checkAndRefill(networksToCheck)
            if (refilled.isNotEmpty()) broadcast("Gas refilled: ${refilled.joinToString()}")
        } catch (_: Exception) {}

        // ── 阶段2: AI 机会扫描 ────────────────────────────────
        broadcast("Scanning for opportunities...")
        updateNotification("AI scanning...")
        try {
            val opportunities = opportunityHunter.scanAllSources()
            if (opportunities.isNotEmpty()) {
                broadcast("Found ${opportunities.size} opportunities:")
                opportunities.take(5).forEach { opp ->
                    broadcast("  [${opp.type}] ${opp.name} ~\$${opp.estimatedValueUsd.toInt()}")
                }
            }
        } catch (e: Exception) {
            broadcast("Scan error: ${e.message?.take(50)}")
        }

        // ── 阶段3: 测试网农耕 ─────────────────────────────────
        if (farmer != null) {
            val campaigns = try { airdropScanner.fetchActiveCampaigns() } catch (_: Exception) { emptyList() }
            broadcast("Farming ${campaigns.size} campaigns...")
            updateNotification("Farming ${campaigns.size} campaigns...")

            campaigns.forEach { campaign ->
                try {
                    broadcast("Farming ${campaign.name}...")
                    val results = farmer.farm(campaign)
                    val ok = results.count { it.success }
                    val total = results.size
                    broadcast("${campaign.name}: $ok/$total actions confirmed")
                } catch (e: Exception) {
                    broadcast("Farm error ${campaign.name}: ${e.message?.take(40)}")
                }
            }
        }

        // ── 阶段4: 检查可领取空投 ─────────────────────────────
        if (withdrawExecutor != null && walletAddress.isNotEmpty()) {
            broadcast("Checking claimable airdrops...")
            updateNotification("Checking airdrops...")
            try {
                val claimable = airdropScanner.fetchClaimableAirdrops(walletAddress)
                if (claimable.isEmpty()) {
                    broadcast("No claimable airdrops yet.")
                } else {
                    broadcast("${claimable.size} claimable airdrop(s) found!")
                    claimable.forEach { airdrop ->
                        broadcast("Claiming ${airdrop.tokenSymbol} from ${airdrop.campaignName}...")
                        updateNotification("Claiming ${airdrop.tokenSymbol}...")
                        try {
                            val result = withdrawExecutor.claimAndWithdraw(airdrop)
                            if (result.success) {
                                totalClaimed++
                                totalUsdcEarned += result.usdcReceived
                                if (result.usdcReceived > 0) {
                                    broadcast("CLAIMED ${airdrop.tokenSymbol} → ${"%.2f".format(result.usdcReceived)} USDC → sent to wallet")
                                } else {
                                    broadcast("CLAIMED ${airdrop.tokenSymbol} → sent to wallet (swap pending price)")
                                }
                            } else {
                                broadcast("Claim failed: ${result.error}")
                            }
                        } catch (e: Exception) {
                            broadcast("Claim error: ${e.message?.take(50)}")
                        }
                    }
                }
            } catch (e: Exception) {
                broadcast("Airdrop check error: ${e.message?.take(50)}")
            }
        }

        broadcast("Cycle #$cycleCount done. Total claimed: $totalClaimed | USDC earned: ${"%.2f".format(totalUsdcEarned)}")
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ManifeestoSniper::BotWakeLock"
            ).apply { acquire(12 * 60 * 60 * 1000L) } // 12 hours, re-acquired each boot
        } catch (_: Exception) {}
    }

    private fun broadcast(msg: String) {
        Log.d(TAG, msg)
        try {
            sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                putExtra(EXTRA_STATUS_MSG, msg)
                setPackage(packageName)
            })
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SniperApp.CHANNEL_ID)
            .setContentTitle("Manifeesto Sniper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
