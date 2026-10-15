pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Veyro"

// Module graph and dependency rules: docs/ARCHITECTURE.md
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:timeline")
include(":data:project")
include(":media:import")
include(":media:playback")
include(":media:export")
include(":media:render")
include(":feature:library")
include(":feature:editor")
include(":feature:project")
include(":feature:settings")
include(":feature:export")
