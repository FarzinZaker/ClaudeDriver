plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// NOTE: Phase 0 keeps `shared` a Kotlin/JVM library for a simple, reliable build.
// Phase 2 converts it to a Kotlin Multiplatform module (sources move to commonMain,
// adding iOS/Android targets) so the Compose Multiplatform app reuses this exact
// contract without changing its shape. Consumers (backend, agent) are unaffected.
dependencies {
    api(libs.serialization.json)
}

kotlin {
    jvmToolchain(21)
}
