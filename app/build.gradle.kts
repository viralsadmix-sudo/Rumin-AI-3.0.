plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ruminai.app"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    defaultConfig {
        applicationId = "com.ruminai.app"
        minSdk = 23
        targetSdk = 35
        versionCode = System.getenv("RUMIN_VERSION_CODE")?.toIntOrNull() ?: 30
        versionName = System.getenv("RUMIN_VERSION_NAME") ?: "3.0.30"
    }
    val releaseStorePath = System.getenv("RUMIN_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("RUMIN_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RUMIN_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RUMIN_KEY_PASSWORD")
    signingConfigs {
        create("stableRelease") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            // CI may use the debug key only for non-release development artifacts.
            // The main-branch workflow refuses to publish unless the permanent key is configured.
            signingConfig = if (!releaseStorePath.isNullOrBlank()) signingConfigs.getByName("stableRelease")
                else signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
