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
        versionCode = 3
        versionName = "3.0"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
