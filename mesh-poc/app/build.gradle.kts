import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offgrid.meshpoc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offgrid.meshpoc"
        minSdk = 26 // Wi-Fi Aware API level
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // Shared debug key (committed on purpose, PoC only) so CI builds install as updates
    // over each other on test phones. Never use this key for a release build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3") // QR codes for offline sharing
}
