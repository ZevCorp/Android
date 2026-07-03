plugins {
    id("com.android.application")
    kotlin("android")
}

// Config embebida en el APK (nunca en git): apikey.properties o variables de entorno.
val props = rootProject.file("apikey.properties").takeIf { it.exists() }
    ?.readLines()?.mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    ?.toMap() ?: emptyMap()
fun cfg(name: String, env: String) = props[name] ?: System.getenv(env) ?: ""
val defaultApiKey = cfg("apiKey", "GRAPH_API_KEY")
val dashboardUrl = cfg("dashboardUrl", "GRAPH_DASHBOARD_URL")   // https://<proj>.supabase.co
val dashboardKey = cfg("dashboardKey", "GRAPH_DASHBOARD_KEY")   // supabase anon key
val dashboardSite = cfg("dashboardSite", "GRAPH_DASHBOARD_SITE") // https://<app>.vercel.app

android {
    namespace = "com.zevcorp.graph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zevcorp.graph"
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "0.9"
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
        buildConfigField("String", "DASHBOARD_URL", "\"$dashboardUrl\"")
        buildConfigField("String", "DASHBOARD_KEY", "\"$dashboardKey\"")
        buildConfigField("String", "DASHBOARD_SITE", "\"$dashboardSite\"")
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
