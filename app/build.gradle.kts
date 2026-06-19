plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manifeesto.sniper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.manifeesto.sniper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DISCLAIMER",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                // BouncyCastle conflict exclusions
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF"
            )
        }
    }

    // Force the full BouncyCastle over Android's stripped-down version
    configurations.all {
        resolutionStrategy.force("org.bouncycastle:bcprov-jdk15on:1.70")
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking — JSON-RPC calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager — background scheduling
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle service support
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // SharedPreferences used instead of DataStore to avoid main-thread deadlock

    // web3j crypto — EVM tx signing, keccak256, secp256k1 (Android-compatible build)
    implementation("org.web3j:crypto:4.12.3-android")
    implementation("org.web3j:utils:4.12.3-android")

    // Full BouncyCastle — Android ships a stripped version missing CustomNamedCurves
    // which web3j needs for secp256k1 signing. This bundles the complete BC library.
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
