pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "proximity"

// Shared API contracts between the four layers. Changes need review from all four.
include(":contracts")
// Application layer (Om): storage, identity, sync, messaging, calls.
include(":app-core")
