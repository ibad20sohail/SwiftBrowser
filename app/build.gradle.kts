import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key lives outside version control. Keep keystore/ safe: updates must be
// signed with the same key or Android refuses to install them over the old app.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.swiftbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swiftbrowser.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "2.0"
    }

    flavorDimensions += "abi"
    productFlavors {
        // The Moto E4 runs 32-bit ARM apps whether its userspace is 32- or 64-bit.
        create("phone") {
            dimension = "abi"
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        // Same app for the x86_64 Android emulator on the PC, used for testing.
        create("emu") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    signingConfigs {
        create("release") {
            if (!keystoreProps.isEmpty) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // The default pattern drops folders starting with "_", which removes uBlock Origin's
        // _locales folder and makes GeckoView reject the extension.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging {
        jniLibs {
            // Keep the engine's native libraries uncompressed in the APK so Android loads them
            // straight from it instead of unpacking a second copy: the APK file is larger, but the
            // installed app takes about 60 MB less space on the phone.
            useLegacyPackaging = false
            // Only used to analyse crash dumps for Mozilla's crash reporter, which Swift doesn't use.
            excludes += "**/libminidump_analyzer.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Configurations for one flavour + build type combination must be declared before use.
val emuDebugImplementation by configurations.creating

dependencies {
    // Firefox engine. 143 is the last release that supports Android 7.
    val geckoview = "143.0.20251003115653"
    "phoneImplementation"("org.mozilla.geckoview:geckoview-armeabi-v7a:$geckoview")
    "emuImplementation"("org.mozilla.geckoview:geckoview-x86_64:$geckoview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Reports leaked screens and objects in test builds on the PC emulator only.
    emuDebugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
