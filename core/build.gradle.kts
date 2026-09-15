plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    // El código vive en commonMain: hoy lo consume Android (jvm);
    // js(), macosArm64() y mingwX64() se activan al implementar sus superficies (DOM, AXAccessibility, UIA).
    jvm {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
        }
        // El contrato (docs/specs + commonTest/…/contrato): promesas escritas antes que el código.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // El socket de la voz (docs/specs/002, fase B1a): OkHttp vive solo en jvm, que es lo que consume Android. Se juzga
        // contra un servidor WebSocket local, nunca contra OpenAI.
        jvmMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
    }
}
