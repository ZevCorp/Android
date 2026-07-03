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
    }
}
