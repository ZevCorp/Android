plugins {
    id("com.android.application")
    kotlin("android")
}

// API key por defecto embebida en el APK (nunca en git): apikey.properties o env GRAPH_API_KEY.
val defaultApiKey = rootProject.file("apikey.properties")
    .takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("apiKey=") }?.substringAfter("=")?.trim()
    ?: System.getenv("GRAPH_API_KEY") ?: ""

android {
    namespace = "com.zevcorp.graph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zevcorp.graph"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
    }
    buildFeatures { buildConfig = true }
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
