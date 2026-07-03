plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.zevcorp.graph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zevcorp.graph"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
