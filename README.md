# Manifeesto Sniper — Airdrop Farming Bot

Automated 24/7 airdrop and testnet farming bot for Android.

## Features
- Scans active airdrop campaigns (BSC, ETH, Arbitrum, Base, TRON)
- Auto-executes required on-chain actions (swap, bridge, LP, deploy, vote, stake)
- Detects claimable airdrops and auto-withdraws to your wallet
- Runs 24/7 as an Android Foreground Service (survives app close)
- Auto-launches on device boot
- Dark terminal UI dashboard

## Setup
1. Install the APK (download from [Releases](../../releases))
2. Open the app and go to Settings
3. Import your wallet private key (stored encrypted on-device)
4. Set your withdrawal address (where profits are sent)
5. Tap **START BOT**

## How it works
Every 5 minutes the bot:
1. Fetches active airdrop/testnet campaigns
2. Executes qualifying on-chain actions per campaign
3. Checks for claimable airdrop balances
4. Auto-claims and transfers to your wallet

## Build from source
```bash
git clone https://github.com/Cfeesto/manifeesto-sniper
cd manifeesto-sniper
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Networks supported
- BSC (BNB Smart Chain)
- Ethereum
- Arbitrum
- Base
- Optimism
- Polygon
- TRON (TRC20)

## Disclaimer
This bot performs automated on-chain interactions for airdrop qualification purposes.
Always verify campaign legitimacy before enabling. Use a dedicated wallet, not your main wallet.
