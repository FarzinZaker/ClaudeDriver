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

    private fun isClaudeCode(p: OSProcess): Boolean =
        classifyClaudeCode((p.name ?: "").lowercase(), (p.commandLine ?: "").lowercase())

    companion object {
        // A `claude` executable at a path boundary or start of the command line (e.g. `claude -c`,
        // `/opt/homebrew/bin/claude`, `claude.cmd`, `node .../.bin/claude`).
        private val CLI_EXECUTABLE = Regex("""(^|[/\\])claude(\.cmd|\.exe)?(\s|$)""")

        /**
         * Match the Claude Code **CLI** while excluding the Claude **desktop** app (an Electron
         * bundle under `Claude.app` / `Claude Helper`) and our own agent — otherwise the desktop
         * app would show up as a monitored "session". [name] and [cmd] must be lowercased.
         */
        internal fun classifyClaudeCode(name: String, cmd: String): Boolean {
            val isDesktopApp = "claude.app" in cmd || "claude helper" in cmd || "claudefordesktop" in cmd
            val isOurs = "claudedriver" in cmd || "com.claudedriver" in cmd
            if (isDesktopApp || isOurs) return false

            return "claude-code" in cmd ||
                "@anthropic-ai/claude" in cmd ||
                name == "claude" ||
                CLI_EXECUTABLE.containsMatchIn(cmd)
        }
    }
}
