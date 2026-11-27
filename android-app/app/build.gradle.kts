plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.neotica.minidroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.neotica.minidroid"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
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

    packaging {
        jniLibs {
            // Required on Android 10+ so libqemu.so is extracted into executable nativeLibraryDir
            useLegacyPackaging = true
        }
    }

    androidResources {
        // Keep already-gzipped disk images (.z) untouched (note: AAPT2 strips/decompresses .gz)
        noCompress += listOf("z", "img")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
