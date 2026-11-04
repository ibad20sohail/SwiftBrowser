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
        versionCode = 25
        versionName = "3.0.3"
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
        // Newer Java classes (java.time) that the WireGuard library uses and Android 7 lacks.
        isCoreLibraryDesugaringEnabled = true
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
        resources {
            // 2.8: data files of the parts of Apache POI and its libraries that Swift doesn't use
            // (spreadsheet formulas and column widths, shape geometry, Visio, phonetic matching,
            // maths), about 1 MB.
            excludes += listOf("font_metrics.properties", "org/apache/poi/**", "org/apache/commons/codec/**", "assets/org/apache/commons/math3/**")
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

    // The VPN (2.1): the official WireGuard tunnel (Apache-2.0), for Proton VPN's free servers.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // 2.2: scanning QR codes with the camera (ZXing).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // 2.7: the Files screen. Writing to a memory card through Android's document access, and
    // archives in plain Java: ZIP with passwords (zip4j), 7z, tar, gz, bz2 and xz (Commons Compress
    // with XZ for Java), RAR4 (junrar). Commons Compress 1.21+ and junrar 7.5+ need java.nio.file,
    // which Android 7 lacks (they crash there), so these are the last versions without it.
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("org.tukaani:xz:1.9")
    implementation("com.github.junrar:junrar:7.4.1")
    // 2.8: old Word files (.doc) shown as their pages, read with Apache POI's HWPF (old PowerPoint
    // files are read by OldOffice itself: POI's slide code needs java.awt). 5.0.0 is the newest POI
    // that runs on Android 7: from 5.1 on, POI logs through Log4j 2 and reads through Commons IO,
    // which call java.lang.invoke, java.nio.file and java.util.Base64 (Android 8+) on the way in.
    // jcl-over-slf4j only redirects a logging API Swift doesn't use.
    implementation("org.apache.poi:poi-scratchpad:5.0.0") {
        exclude(group = "org.slf4j", module = "jcl-over-slf4j")
    }
    // 2.9: Swift's own player for videos and music (PlayerActivity, MusicService): AndroidX Media3's
    // ExoPlayer, with hardware decoding, and HLS for website videos. 1.11 is the newest release; it
    // runs on Android 6 and later and builds with compileSdk 36. Its notification library
    // (media3-session) isn't used: the notification and lock screen controls are Android's own media
    // session, as for pages (PlaybackService).
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    // 3.0.2: DASH streams (.mpd) too, played and saved: Media3's own DASH part (Apache-2.0, Android 5+).
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    // Dolby (AC-3, E-AC-3, TrueHD) and DTS sound in films (2.9.2), which the phone has no decoders
    // for: Media3's FFmpeg audio decoder as Jellyfin builds it (FFmpeg 6.0, audio decoders only,
    // Android 6+). It's made for Media3 1.9 but only calls what 1.11 still has (checked class by
    // class); it brings no Java of its own beyond the decoder. Gradle uses Media3 1.11 for both.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // 2.9.2: torrents (Torrents) with libtorrent, the C++ engine of qBittorrent and Deluge, through
    // libtorrent4j (MIT). 2.1.0-38 is the newest build whose native library runs on Android 7: from
    // 2.1.0-39 on it is built for Android 9 (API 28). The native part comes per ABI, like the flavors.
    val libtorrent4j = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    "phoneImplementation"("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4j")
    "emuImplementation"("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // 3.0: pictures' EXIF (orientation, details) read the same way on every Android version, with
    // the fixes Android 7's own reader lacks.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // 3.0: tests of the plain logic (addresses, file names, playlists, backups) that run on the PC.
    testImplementation("junit:junit:4.13.2")

    // Reports leaked screens and objects in test builds on the PC emulator only.
    emuDebugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
