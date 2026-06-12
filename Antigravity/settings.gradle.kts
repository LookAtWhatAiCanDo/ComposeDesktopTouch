pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.5.0"
}

val mavenCentralUsername = providers.gradleProperty("mavenCentralUsername").getOrNull()
    ?: providers.environmentVariable("MAVEN_CENTRAL_USERNAME").getOrNull()
val mavenCentralPassword = providers.gradleProperty("mavenCentralPassword").getOrNull()
    ?: providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").getOrNull()

nmcpSettings {
    centralPortal {
        username = mavenCentralUsername
        password = mavenCentralPassword
        publishingType = "AUTOMATIC" // "USER_MANAGED" or "AUTOMATIC"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-desktop-touch-project"
include(":compose-desktop-touch")
include(":touch-demo")
