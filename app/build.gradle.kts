plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

android {
    namespace = "com.socks5"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.socks5"
        minSdk = 24
        targetSdk = 34
        versionCode = 202
        versionName = "2.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // F-Droid: disable VCS metadata embedding for reproducible builds
            vcsInfo.include = false
        }
        debug {
            // F-Droid: disable VCS metadata embedding for reproducible builds
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // F-Droid: disable dependency metadata in APK Signing Block
    // F-Droid scanner rejects extra signing blocks, so this must be off
    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // SSH
    implementation(libs.jsch)
    implementation(libs.bouncycastle.prov)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DNS
    implementation(libs.dnsjava)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Security
    implementation(libs.security.crypto)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.fragment.ktx)
}
