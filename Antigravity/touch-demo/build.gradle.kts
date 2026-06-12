plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":compose-desktop-touch"))
                implementation(libs.bundles.jna)
                implementation(compose.desktop.currentOs)
                implementation(libs.bundles.compose.core)
                implementation(libs.compose.material3)
            }
        }
    }
}

compose {
    desktop {
        application {
            mainClass = "com.whataicando.touch.demo.MainKt"
        }
    }
}
