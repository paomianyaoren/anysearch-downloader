import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.anysearch.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anysearch.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- Jetpack Compose ----
    implementation("androidx.compose.ui:ui:1.8.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.1")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.1")

    // ---- Activity / 核心 ----
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")

    // ---- SAF 文件夹访问 ----
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ---- 网络 ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
