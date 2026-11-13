// Included build: pure-Kotlin code shared by the Android app and the office server.
// Consumed via `includeBuild("../shared")` from android/ and server/.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "produce-invoicing-shared"
include(":domain")
