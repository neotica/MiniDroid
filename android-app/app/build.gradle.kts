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
        versionCode = 10
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "guest"
    productFlavors {
        create("donut") {
            dimension = "guest"
            applicationIdSuffix = ".donut"
            manifestPlaceholders["appLabel"] = "MiniDroid 1.6"
            buildConfigField("String", "GUEST_APP_TITLE", "\"MiniDroid 1.6\"")
            buildConfigField("String", "GUEST_OS_NAME", "\"Android 1.6\"")
            buildConfigField("int", "GUEST_API_LEVEL", "4")
            buildConfigField("int", "GUEST_RAM_MB", "128")
            buildConfigField("int", "GUEST_WIDTH", "320")
            buildConfigField("int", "GUEST_HEIGHT", "480")
            buildConfigField("int", "GUEST_ROM_VERSION", "3")
            buildConfigField(
                "String",
                "GUEST_KERNEL_APPEND",
                "\"qemu=1 console=ttyS0 android.checkjni=1 android.ndns=1 android.qemud=ttyS1 android.ril=ttyS1\""
            )
        }

        /*
         * Template for adding another Android guest flavor (place its ROM images in
         * app/src/gingerbread/assets/images/ and run `./gradlew assembleGingerbreadDebug`):
         *
         * create("gingerbread") {
         *     dimension = "guest"
         *     applicationIdSuffix = ".gingerbread"
         *     manifestPlaceholders["appLabel"] = "MiniDroid 2.3"
         *     buildConfigField("String", "GUEST_APP_TITLE", "\"MiniDroid 2.3\"")
         *     buildConfigField("String", "GUEST_OS_NAME", "\"Android 2.3\"")
         *     buildConfigField("int", "GUEST_API_LEVEL", "10")
         *     buildConfigField("int", "GUEST_RAM_MB", "256")
         *     buildConfigField("int", "GUEST_WIDTH", "320")
         *     buildConfigField("int", "GUEST_HEIGHT", "480")
         *     buildConfigField("int", "GUEST_ROM_VERSION", "1")
         *     buildConfigField(
         *         "String",
         *         "GUEST_KERNEL_APPEND",
         *         "\"qemu=1 console=ttyS0 android.checkjni=1 android.ndns=1 android.qemud=ttyS1 android.ril=ttyS1\""
         *     )
         * }
         */
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
