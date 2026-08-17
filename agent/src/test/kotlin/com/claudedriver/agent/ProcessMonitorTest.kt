package com.claudedriver.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessMonitorTest {

    @Test
    fun `scan enumerates processes on this OS without error`() {
        // Proves OSHI runs on the host platform (Windows/macOS). Result is a possibly-empty list of
        // detected Claude Code processes — we assert it enumerates without throwing.
        val snapshot = ProcessMonitor().scan()
        assertNotNull(snapshot.processes)
        println("ProcessMonitor detected ${snapshot.processes.size} Claude Code process(es)")
    }

    private fun classify(name: String, cmd: String) =
        ProcessMonitor.classifyClaudeCode(name.lowercase(), cmd.lowercase())

    @Test
    fun `detects the Claude Code CLI`() {
        assertTrue(classify("claude", "claude -c"))                                    // standalone CLI
        assertTrue(classify("claude", "/opt/homebrew/bin/claude --resume"))
        assertTrue(classify("node", "node /usr/local/lib/node_modules/@anthropic-ai/claude-code/cli.js"))
        assertTrue(classify("node", "node /Users/x/.bin/claude"))
        assertTrue(classify("claude.exe", "C:\\Users\\x\\AppData\\claude.exe"))
    }

    @Test
    fun `excludes the Claude desktop app and our own agent`() {
        assertFalse(classify("claude", "/applications/claude.app/contents/macos/claude"))
        assertFalse(classify("claude helper", "/applications/claude.app/contents/frameworks/claude helper.app/contents/macos/claude helper --type=gpu-process"))
        assertFalse(classify("shipit", "/applications/claude.app/contents/frameworks/squirrel.framework/resources/shipit com.anthropic.claudefordesktop.shipit"))
        assertFalse(classify("java", "java -cp app/* com.claudedriver.agent.mainkt"))
        assertFalse(classify("node", "node server.js"))                                // unrelated
    }
}
