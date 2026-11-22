import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":vaultJvm"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // Windows DPAPI for the vault password and the GitHub token (see secret/WindowsSecrets.kt).
    implementation("net.java.dev.jna:jna-platform:5.14.0")

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

compose.desktop {
    application {
        mainClass = "io.github.evgenykon.mybooks.MainKt"

        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MyBooks"
            // MSI/EXE packaging requires a major version of 1 or higher; CI passes a
            // monotonically growing one, otherwise Windows refuses to upgrade in place.
            packageVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
            // WiX (MSI packaging) only accepts ASCII metadata, so keep these two lines Latin.
            description = "FB2 reader for Windows"
            vendor = "Evgeny Konoplev"
            copyright = "(c) 2026 Evgeny Konoplev"

            modules("java.desktop", "java.logging", "java.prefs", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/app.ico"))
                menuGroup = "My Books"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "3f1c2a7e-5b64-4d0e-9a3c-1d7e8b2f4c56"
            }
        }
    }
}

/** Writes screenshots of the library and the reader panels to build/snapshots. */
tasks.register<JavaExec>("snapshot") {
    group = "verification"
    description = "Render UI snapshots to build/snapshots (no window opened)"
    mainClass.set("io.github.evgenykon.mybooks.tools.SnapshotKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.named("build") {
    dependsOn("createDistributable")
}


tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
