plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ghost.target"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghost.target"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "role"

    productFlavors {
        create("admin") {
            dimension = "role"
            applicationIdSuffix = ".admin"
            versionNameSuffix = "-admin"
            buildConfigField("String", "APP_ROLE", "\"ADMIN\"")
        }

        create("client") {
            dimension = "role"
            applicationIdSuffix = ".client"
            versionNameSuffix = "-client"
            buildConfigField("String", "APP_ROLE", "\"CLIENT\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
