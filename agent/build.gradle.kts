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
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.oshi.core)
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

// Cross-compile the transparent Windows ConPTY shim (agent/shim-win) and drop the arch-matched
// binaries into resources so ShimInstaller can extract them on Windows. Skips gracefully when Go
// is not on PATH (dev machines) — the resource is simply absent and ShimInstaller falls open.
val buildWindowsShim by tasks.registering {
    val shimDir = layout.projectDirectory.dir("shim-win").asFile
    val outDir = layout.projectDirectory.dir("src/main/resources").asFile
    val goExe = (System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "go") }
        + File("/opt/homebrew/bin/go") + File("/usr/local/go/bin/go"))
        .firstOrNull { it.canExecute() }
    onlyIf("Go toolchain available") { goExe != null }
    doLast {
        outDir.mkdirs()
        listOf("amd64", "arm64").forEach { arch ->
            exec {
                workingDir = shimDir
                environment("GOOS", "windows"); environment("GOARCH", arch); environment("CGO_ENABLED", "0")
                commandLine(goExe!!.absolutePath, "build", "-trimpath", "-ldflags", "-s -w",
                    "-o", File(outDir, "claude-shim-win-$arch.exe").absolutePath, ".")
            }
        }
    }
}

tasks.named("processResources") { dependsOn(buildWindowsShim) }
