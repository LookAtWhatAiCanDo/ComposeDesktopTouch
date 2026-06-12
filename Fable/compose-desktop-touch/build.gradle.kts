plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

group = "llc.lookatwhataicando"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    // Consumers bring their own Compose version; these are api so the modifier
    // signatures (ScrollableState, Orientation, DecayAnimationSpec) resolve.
    api(compose.ui)
    api(compose.foundation)
    api(compose.animation)

    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "compose-desktop-touch"
            pom {
                name.set("compose-desktop-touch")
                description.set(
                    "Windows touchscreen support (WM_POINTER drag/fling/tap) for Compose for Desktop"
                )
            }
        }
    }
}
