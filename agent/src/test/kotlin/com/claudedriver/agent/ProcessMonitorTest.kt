package com.claudedriver.agent

import org.junit.jupiter.api.Assertions.assertNotNull
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
}
