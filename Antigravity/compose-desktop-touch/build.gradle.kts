plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
    signing
    alias(libs.plugins.dokka)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(libs.bundles.compose.core)
                implementation(libs.bundles.jna)
                implementation(libs.bundles.coroutines)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

group = "com.whataicando"
version = libs.versions.release.get()

val pomName = "Compose Desktop Touch"
val pomDescription = "Native Windows touchscreen and stylus support via JNA WndProc subclassing for Jetpack Compose Desktop."
val pomSiteUrl = "https://github.com/LookAtWhatAiCanDo/ComposeDesktopTouch"
val pomGitUrl = "github.com/whataicando/ComposeDesktopTouch.git"
val pomLicenseName = "The MIT License"
val pomLicenseUrl = "https://raw.githubusercontent.com/LookAtWhatAiCanDo/ComposeDesktopTouch/master/LICENSE"
val pomDeveloperId = "whataicando"
val pomDeveloperName = "whataicando"
val pomDeveloperEmail = "publish@whataicando.com"

val dokkaJavadocJar by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles a JAR containing Dokka HTML docs (satisfies Maven Central javadoc requirement)"
    val dokkaHtml = tasks.named("dokkaGeneratePublicationHtml")
    dependsOn(dokkaHtml)
    from(dokkaHtml.map { it.outputs.files })
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication> {
        artifact(dokkaJavadocJar)

        pom {
            name.set(pomName)
            description.set(pomDescription)
            url.set(pomSiteUrl)
            licenses {
                license {
                    name.set(pomLicenseName)
                    url.set(pomLicenseUrl)
                }
            }
            developers {
                developer {
                    id.set(pomDeveloperId)
                    name.set(pomDeveloperName)
                    email.set(pomDeveloperEmail)
                }
            }
            scm {
                connection.set("scm:git:git://$pomGitUrl")
                developerConnection.set("scm:git:ssh://$pomGitUrl")
                url.set(pomSiteUrl)
            }
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    val skipSigning = signingKey == "SKIP" && signingPassword == "SKIP"
    
    if (!skipSigning) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
