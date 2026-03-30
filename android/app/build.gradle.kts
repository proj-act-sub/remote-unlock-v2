plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.remoteunlock"
    compileSdk = 36          // Android 16 (2026 target)

    defaultConfig {
        applicationId = "com.remoteunlock"
        minSdk = 31           // Android 12 — X25519 AndroidKeyStore + robust BiometricPrompt
        targetSdk = 36
        versionCode = 7
        versionName = "7.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM (Bill of Materials — pins all compose versions)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Biometric — standard BiometricPrompt API
    implementation(libs.androidx.biometric)

    // Camera (for QR scanning)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit barcode scanning
    implementation(libs.mlkit.barcode.scanning)

    // DataStore (peer storage)
    implementation(libs.androidx.datastore.preferences)

    // Kotlin Serialization (JSON)
    implementation(libs.kotlinx.serialization.json)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // BLAKE3 — cryptohash pure-Kotlin implementation
    implementation(libs.appmattus.cryptohash)

    // BouncyCastle for ChaCha20-Poly1305 on older paths
    implementation(libs.bouncycastle.bcprov)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
