plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.logback)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.core)
}

application {
    mainClass.set("com.claudedriver.agent.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
