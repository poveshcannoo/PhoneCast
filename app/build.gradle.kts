plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// In the release workflow these come from the release tag (v1.2.3 -> "1.2.3", 10203)
// and from GitHub secrets. Local / CI debug builds fall back to the defaults.
val releaseVersionName: String = System.getenv("VERSION_NAME") ?: "1.0.0"
val releaseVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "dev.phonecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonecast"
        minSdk = 23          // Android 6.0+ (covers most Android-TV style projectors)
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Your own permanent key when built by the release workflow; debug key otherwise.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    lint {
        // Don't let lint warnings block a release build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies: everything uses the Android framework (MediaProjection,
// MediaCodec, AudioRecord/AudioTrack, NsdManager, plain sockets).
