pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Lightsaber"

// Pure-JVM motion pipeline. No Android dependency anywhere in this module — that is what lets a
// recorded sensor trace be replayed through the exact production code path in a unit test.
include(":motion")

// The Wear OS application and the Oboe audio engine it loads.
include(":app")