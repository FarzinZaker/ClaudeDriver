package com.claudedriver.backend.installer

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Opt-in: exercises the real S3 fetch + streaming assembly. Runs only when AGENT_RUNTIMES_BUCKET is
 * set and AWS credentials are available in the environment (e.g. exported from an SSO session).
 */
class InstallerServiceTest {

    @Test
    fun `assembles a macos installer from the S3 runtime`() {
        val bucket = System.getenv("AGENT_RUNTIMES_BUCKET")
        assumeTrue(!bucket.isNullOrBlank(), "AGENT_RUNTIMES_BUCKET not set")

        val svc = InstallerService(bucket)
        val out = ByteArrayOutputStream()
        val config = """{"backendUrl":"https://x","connectUrl":"https://x:8443","machineId":"m","enrollmentCode":"c"}"""
        svc.writeInstaller("macos", config, out)

        val names = mutableSetOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        assertTrue(names.any { it == "agent.config" }, "agent.config present")
        assertTrue(names.any { it == "install.command" }, "install.command present")
        assertTrue(
            names.any { it.endsWith("ClaudeDriverAgent.app/Contents/MacOS/ClaudeDriverAgent") },
            "app launcher present (got ${names.size} entries)",
        )
    }
}
