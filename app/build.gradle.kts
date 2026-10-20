plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.echokey.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.echokey.keyboard"
        minSdk = 31 // Android 12+ to align with Material You + modern IME expectations
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // We will enable viewBinding / compose only when the UI layer is actually needed.
        // Keep invisible back-end lean for now.
    }
}

dependencies {
    // Platform Android SDK is enough for the IME + minimal keyboard view.
    // Material You / Compose / emoji libs intentionally deferred.
}
