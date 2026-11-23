plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aydin.sunostudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aydin.sunostudio"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "3.0.0"

        // yt-dlp + Python + FFmpeg are bundled per ABI; keep only the real phone ABIs to limit APK size.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // The bundled yt-dlp/Python/FFmpeg binaries must be extracted to disk to be executable.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/sunostudio-release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jsoup:jsoup:1.18.3")
    // yt-dlp engine (YouTube / Instagram / Facebook) + bundled FFmpeg (MP3 conversion, stream merging)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
