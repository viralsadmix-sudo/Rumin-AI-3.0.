plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruminai.app"
    compileSdk = 35

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
        versionCode = 28
        versionName = "3.0.28"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RUMIN_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RUMIN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RUMIN_KEY_ALIAS")
                keyPassword = System.getenv("RUMIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // CI supplies the existing private release keystore. Local builds retain
            // the previous debug-signing fallback when no release keystore is supplied.
            signingConfig = if (System.getenv("RUMIN_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
