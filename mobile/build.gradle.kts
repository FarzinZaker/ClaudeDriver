// Root of the standalone Compose Multiplatform app. Plugin versions declared here, applied per-module.
plugins {
    // Versions are indicative; align with the installed toolchain when building.
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
