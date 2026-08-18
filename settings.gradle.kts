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
        // Shizuku artifacts (dev.rikka.shizuku) live on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "DankYeetersBluetoothDashboard"

include(":app")
include(":core-audio")
include(":core-hearing")
include(":core-system")
include(":core-monitor")
