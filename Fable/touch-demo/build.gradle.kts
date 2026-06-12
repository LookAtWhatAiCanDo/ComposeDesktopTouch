plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":compose-desktop-touch"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose {
    desktop {
        application {
            mainClass = "llc.lookatwhataicando.touchdemo.MainKt"
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    // Forward the library debug flag from the Gradle invocation to the app JVM.
    System.getProperty("composeDesktopTouch.debug")?.let {
        systemProperty("composeDesktopTouch.debug", it)
    }
}
