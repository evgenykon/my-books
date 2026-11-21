import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    // Desktop (Windows / Linux / macOS) client runs on the JVM and reuses
    // exactly the same parser and document model as the Android app.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    macosArm64()
    macosX64()

    targets.withType<KotlinNativeTarget>().forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // JVM-only vault code (crypto, GitHub client) shared by Android and the desktop app.
        val jvmSharedMain by creating { dependsOn(commonMain) }
        val jvmSharedTest by creating { dependsOn(commonTest) }

        // "androidMain" exists only when the Android plugin is applied; the guarded lookup
        // keeps the desktop-only builds (and the Windows CI job) working without it.
        findByName("androidMain")?.dependsOn(jvmSharedMain)
        val desktopMain by getting { dependsOn(jvmSharedMain) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }
    }
}

android {
    namespace = "io.github.evgenykon.mybooks.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
