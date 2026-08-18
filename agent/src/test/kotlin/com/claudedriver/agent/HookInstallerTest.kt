package com.claudedriver.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HookInstallerTest {

    @Test
    fun `install is idempotent, preserves user config, and teardown reverses it`() {
        val tmp = File.createTempFile("settings", ".json")
        tmp.writeText(
            """{"model":"opus","hooks":{"Notification":[{"hooks":[{"type":"command","command":"echo hi"}]}]}}""",
        )
        val token = "secret-hook-token-123"

        HookInstaller.installToFile(tmp, 8765, token)
        val afterInstall = tmp.readText()
        assertTrue(afterInstall.contains("127.0.0.1:8765/hook"), "managed activity hook installed")
        assertFalse(afterInstall.contains("/approve"), "no blocking approval hook installed")
        assertFalse(afterInstall.contains("PreToolUse"), "no PreToolUse hook — must not gate the user's own sessions")
        assertTrue(afterInstall.contains("Bearer $token"), "token baked into the Authorization header")
        assertFalse(afterInstall.contains("httpHookAllowedEnvVars"), "no env-var dependency")
        assertTrue(afterInstall.contains("echo hi"), "user hook preserved")
        assertTrue(afterInstall.contains("opus"), "user config preserved")

        HookInstaller.installToFile(tmp, 8765, token)
        assertEquals(afterInstall, tmp.readText(), "re-install is idempotent")

        HookInstaller.teardownFile(tmp)
        val afterTeardown = tmp.readText()
        assertFalse(afterTeardown.contains("127.0.0.1"), "managed hook removed")
        assertTrue(afterTeardown.contains("echo hi"), "user hook still present")
        assertTrue(afterTeardown.contains("opus"), "user config still present")

        tmp.delete()
    }
}
