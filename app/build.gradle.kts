plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

// Config embebida en el APK (nunca en git): apikey.properties o variables de entorno.
val props = rootProject.file("apikey.properties").takeIf { it.exists() }
    ?.readLines()?.mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    ?.toMap() ?: emptyMap()
fun cfg(name: String, env: String) = props[name] ?: System.getenv(env) ?: ""
val defaultApiKey = cfg("apiKey", "GRAPH_API_KEY")

android {
    namespace = "com.zevcorp.graph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zevcorp.graph"
        minSdk = 30
        targetSdk = 35
        versionCode = 26
        versionName = "0.26"
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
    }
    // Clave de firma ESTABLE y compartida por todos los builds. Android solo permite ACTUALIZAR una
    // app si la nueva versión está firmada con la misma clave que la instalada; sin esto el updater
    // in-app no podría reemplazar la versión previa. El keystore vive en el repo (privado).
    signingConfigs {
        create("shared") {
            storeFile = file("graph-release.jks")
            storePassword = "graphupdate"
            keyAlias = "graph"
            keyPassword = "graphupdate"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") { signingConfig = signingConfigs.getByName("shared") }
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
