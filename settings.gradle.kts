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
        // Test-only: spake2-java is LGPL and never ships - it exists here purely
        // to cross-check our own SPAKE2 against an implementation known to pair
        // with adbd. See Spake2DifferentialTest.
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Test-only. spake2-java is LGPL-3.0 and must never ship in the app -
        // the exchangeability the licence requires cannot be honoured in a
        // statically built APK. As a comparison in a unit test it is not
        // distributed at all, and it is the only implementation available that
        // is known to pair with a real adbd. See Spake2DifferentialTest.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DankYeetersBluetoothDashboard"

include(":app")
include(":core-audio")
include(":core-hearing")
include(":core-system")
include(":core-monitor")
