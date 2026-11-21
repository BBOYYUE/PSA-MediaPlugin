plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.cusc.media"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cusc.media"
        minSdk = 28
        targetSdk = 34
        versionCode = 6004
        versionName = "1.7.1"
        resConfigs("en")
    }

    signingConfigs {
        create("release") {
            keyAlias = "mediaPlugin"
            keyPassword = "psaMediaPlugin"
            storeFile = file("mediaPlugin.jks")
            storePassword = "psaMediaPlugin"
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media:media:1.7.0")
    implementation(files("libs/mini-dds.aar"))
    implementation(libs.zxing.core)
}
