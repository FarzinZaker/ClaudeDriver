package com.claudedriver.agent

import com.claudedriver.protocol.DetectedProcess
import com.claudedriver.protocol.ProcessSnapshot
import kotlinx.coroutines.delay
import oshi.SystemInfo
import oshi.software.os.OSProcess
import java.time.Instant

/**
 * Detects Claude Code processes on this machine (OSHI) and emits a snapshot whenever the set
 * changes. Cross-platform (Windows + macOS). The OS process does not expose Claude Code's session
 * id, so only pid / working directory / lifecycle are reported; the backend correlates by project.
 */
class ProcessMonitor(private val intervalMillis: Long = 2000) {
    private val os = SystemInfo().operatingSystem

    suspend fun run(emit: suspend (ProcessSnapshot) -> Unit) {
        var last: ProcessSnapshot? = null
        while (true) {
            val snapshot = scan()
            if (snapshot != last) {
                emit(snapshot)
                last = snapshot
            }
            delay(intervalMillis)
        }
    }

    fun scan(): ProcessSnapshot {
        val processes = os.processes.asSequence()
            .filter { isClaudeCode(it) }
            .map { p ->
                DetectedProcess(
                    pid = p.processID.toLong(),
                    claudeSessionId = null,
                    projectPath = p.currentWorkingDirectory?.ifBlank { null },
                    startedAt = Instant.ofEpochMilli(p.startTime).toString(),
                )
            }
            .toList()
        return ProcessSnapshot(processes)
    }

    private fun isClaudeCode(p: OSProcess): Boolean {
        val name = (p.name ?: "").lowercase()
        val cmd = (p.commandLine ?: "").lowercase()
        val looksLikeClaude = name == "claude" || "claude " in cmd || cmd.endsWith("claude") || "/claude" in cmd
        // Never count our own agent (or the backend) as a monitored Claude Code process.
        val isOurs = "claudedriver" in cmd || "com.claudedriver" in cmd
        return looksLikeClaude && !isOurs
    }
}
