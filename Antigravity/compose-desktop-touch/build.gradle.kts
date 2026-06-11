plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                api(compose.ui)
                api(compose.foundation)
                api(compose.runtime)
                api("net.java.dev.jna:jna:5.14.0")
                api("net.java.dev.jna:jna-platform:5.14.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
            }
        }
    }
}

group = "llc.lookatwhataicando"
version = "0.1.0"
