plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.extbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.extbrowser"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // GeckoView inclui código nativo para várias CPUs. Cada APK de download
    // leva somente a arquitetura necessária, sem remover o motor Firefox.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Compatível com a infraestrutura atual (compileSdk 36 / AGP 8.9.1).
    // GeckoView 158 passou a exigir SDK 37 e AndroidX Core 1.19 / AGP 9.1.
    implementation("org.mozilla.geckoview:geckoview:140.0.20250707120347")
}
