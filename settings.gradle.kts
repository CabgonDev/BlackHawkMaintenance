// Opcional: accessors tipados
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false

    // ✅ Kotlin: usa 2.3.20 (misma que tu libs.versions.toml)
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false

    // Google services
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") // por PhotoView
    }
}

rootProject.name = "BlackHawkMaintenance"
include(":app")
