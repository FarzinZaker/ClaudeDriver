plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.json)

    implementation(libs.coroutines.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)

    implementation(libs.bouncycastle.pkix)
    implementation(libs.webauthn.core)

    // S3 (fetch the per-OS agent runtime to assemble per-machine installers). The URL-connection
    // HTTP client avoids pulling the async Netty client that would clash with Ktor's server engine.
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.url.connection.client)

    implementation(libs.logback)

    testImplementation(project(":agent"))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.coroutines.core)
}

application {
    mainClass.set("com.claudedriver.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Forward the opt-in live-smoke DB config to the forked test JVM (works under --no-daemon).
    listOf("SMOKE_DB_URL", "SMOKE_DB_USER", "SMOKE_DB_PASS").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
