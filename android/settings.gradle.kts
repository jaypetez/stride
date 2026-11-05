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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "stride"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:common")
include(":core:science")
include(":core:testing")
include(":core:designsystem")
include(":core:ui")
include(":core:navigation")
include(":core:garmin")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:data-testing")

include(":feature:today")
include(":feature:activities")
include(":feature:fitness")
include(":feature:plan")

include(":feature:onboarding")
include(":feature:connect")
include(":feature:settings")
include(":feature:coach")
