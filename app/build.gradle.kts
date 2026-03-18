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
        versionCode = 5000
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.media:media:1.7.0")
}