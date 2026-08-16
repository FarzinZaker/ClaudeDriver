rootProject.name = "claudedriver"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":shared", ":backend", ":agent")
