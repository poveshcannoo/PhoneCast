plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.phonecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonecast"
        minSdk = 23          // Android 6.0+ (covers most Android-TV style projectors)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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

// No third-party dependencies: everything uses the Android framework (MediaProjection,
// MediaCodec, AudioRecord/AudioTrack, NsdManager, plain sockets).
