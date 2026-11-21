import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// JVM side of the encrypted vaults: PBKDF2 + AES-GCM, the GitHub Contents client and the
// repository that reads and writes vault branches. Kept as a plain JVM module on purpose:
// putting this code in a custom Kotlin Multiplatform source set broke the Apple targets of
// :shared (macosMain disappeared from Shared.framework).
dependencies {
    api(project(":shared"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    testLogging { events("failed") }
}
