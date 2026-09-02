plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jjjk.venueos.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjjk.venueos.player"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Pin every debug build to the keystore committed in the repo root,
    // instead of each machine/CI runner's own auto-generated
    // ~/.android/debug.keystore. Without this, a build from a fresh GitHub
    // Actions runner is signed with a different random key every time,
    // which Android's package manager treats as a different app entirely -
    // any update install then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE
    // and needs a full uninstall (losing pairing/venue state) instead of a
    // normal in-place update.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "launch"
    productFlavors {
        create("standard") {
            dimension = "launch"
            buildConfigField("boolean", "AUTO_LAUNCH_ON_BOOT", "false")
        }
        create("autolaunch") {
            dimension = "launch"
            buildConfigField("boolean", "AUTO_LAUNCH_ON_BOOT", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
