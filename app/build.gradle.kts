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
// Keys por defecto que quedan INCRUSTADAS en el APK (leídas de apikey.properties o de variables de
// entorno; nunca viven en git). Se hornean para que la app funcione recién instalada y siguen siendo
// modificables desde la UI (las prefs las sobrescriben).
val defaultApiKey = cfg("apiKey", "GRAPH_API_KEY")
val defaultDeepgramKey = cfg("deepgramKey", "GRAPH_DEEPGRAM_KEY")
// Key de OpenAI (GPT-5.6 computer-use + voz): incrustada para que el switch de proveedor funcione
// recién instalado; se sobrescribe desde el panel de Desarrollador (prefs).
val defaultOpenAiKey = cfg("openaiKey", "GRAPH_OPENAI_KEY")
// Grafo de conocimiento Neo4j Aura: credenciales de CONEXIÓN A LA BD (no las de la API de gestión)
// incrustadas para todos los usuarios. Su alcance es solo leer/escribir esa base — nunca gestionar
// instancias. Se sobrescriben desde la UI si el usuario pone las suyas.
val defaultNeo4jUri = cfg("neo4jUri", "NEO4J_URI")
val defaultNeo4jUser = cfg("neo4jUser", "NEO4J_USER")
val defaultNeo4jPass = cfg("neo4jPass", "NEO4J_PASS")

android {
    namespace = "com.zevcorp.graph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zevcorp.graph"
        minSdk = 30
        targetSdk = 35
        versionCode = 42
        versionName = "0.42"
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
        buildConfigField("String", "DEFAULT_DEEPGRAM_KEY", "\"$defaultDeepgramKey\"")
        buildConfigField("String", "DEFAULT_OPENAI_KEY", "\"$defaultOpenAiKey\"")
        buildConfigField("String", "DEFAULT_NEO4J_URI", "\"$defaultNeo4jUri\"")
        buildConfigField("String", "DEFAULT_NEO4J_USER", "\"$defaultNeo4jUser\"")
        buildConfigField("String", "DEFAULT_NEO4J_PASS", "\"$defaultNeo4jPass\"")
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
        // A los usuarios SIEMPRE se les distribuye el build RELEASE: un APK debuggable sideloaded
        // es lo que Play Protect marca con más agresividad. Sin minify: el APK ya es pequeño y la
        // ofuscación de R8 levanta MÁS sospechas en el análisis, no menos.
        getByName("release") {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
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
