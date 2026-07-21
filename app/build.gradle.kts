plugins {
    // AGP 9+ has built-in Kotlin support — do NOT also apply org.jetbrains.kotlin.android
    // (it fails with "extension 'kotlin' already registered").
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.wksaurey.mosaic"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.wksaurey.mosaic"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"   // 0.2.0 = the Mosaic rename
    }

    buildTypes {
        release {
            // No minification: tiny app, and R8 non-determinism hurts reproducible builds.
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Zero dependencies, deliberately: platform APIs cover everything at minSdk 31,
// and an empty dependency graph is the strongest F-Droid/no-exfiltration posture.
dependencies {
}
