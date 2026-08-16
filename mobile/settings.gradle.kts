pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "claudedriver-mobile"
include(":composeApp")

// NOTE: intentionally standalone. To share the wire contract with the backend/agent, add:
//   includeBuild("..")   and depend on the :shared module (unifies the DTOs — Principle III).
