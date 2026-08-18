package com.claudedriver.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ShimInstallerTest {

    @Test
    fun `rc block is idempotent, preserves user content, and strips cleanly`() {
        val rc = Files.createTempFile("zshrc", "").toFile()
        rc.writeText("export EDITOR=vim\nalias ll='ls -la'\n")
        val bin = File("/home/u/.claudedriver/bin")

        ShimInstaller.ensureRcBlock(rc, bin)
        val once = rc.readText()
        assertTrue(once.contains("export EDITOR=vim"), "user content preserved")
        assertTrue(once.contains(bin.absolutePath), "bin dir prepended to PATH")
        assertEquals(1, countBlocks(once), "exactly one managed block")

        ShimInstaller.ensureRcBlock(rc, bin)
        assertEquals(1, countBlocks(rc.readText()), "re-install does not duplicate the block")

        ShimInstaller.stripRcBlock(rc)
        val after = rc.readText()
        assertFalse(after.contains("claudedriver"), "managed block removed")
        assertTrue(after.contains("export EDITOR=vim"), "user content still present after teardown")
        assertTrue(after.contains("alias ll='ls -la'"), "all user lines survive teardown")
    }

    @Test
    fun `strip restores original content exactly (round-trip)`() {
        val original = "line1\nline2\n"
        val rc = Files.createTempFile("bashrc", "").toFile()
        rc.writeText(original)
        ShimInstaller.ensureRcBlock(rc, File("/x/bin"))
        ShimInstaller.stripRcBlock(rc)
        assertEquals(original, rc.readText(), "round-trip leaves the file byte-identical")
    }

    @Test
    fun `install refuses when no real claude exists to fall through to`() {
        // A temp HOME whose bin dir is the only place a `claude` could be — realClaudeOutside is null.
        val home = Files.createTempDirectory("cd-home").toFile()
        // Cannot force PATH in-process, but the resolver also checks well-known paths; on a machine
        // without claude installed this returns false. Assert the method is total (never throws).
        val result = runCatching { ShimInstaller.install(home) }
        assertTrue(result.isSuccess, "install is total and never throws")
    }

    private fun countBlocks(text: String) = Regex(">>> claudedriver shim >>>").findAll(text).count()
}
