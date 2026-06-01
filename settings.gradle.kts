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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // IjkPlayer 接入时再打开下面之一(maven.bilibili.com 现已不稳定):
        // maven { url = uri("https://maven.bilibili.com/") }
        // maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "test"
include(":app")
include(":core")
include(":media")
include(":player-ui")
