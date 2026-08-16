package com.claudedriver.agent

import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ControlCommand
import com.claudedriver.protocol.ControlResult
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Launches a controllable process for a Claude Code run. Pluggable so the control plane is testable. */
interface Launcher {
    fun launch(claudeSessionId: String, projectPath: String?, instruction: String?): Process
}

/**
 * Default stand-in launcher: a persistent process that reads stdin until EOF. In production this is
 * replaced by a launcher that starts `claude` in the project directory (platform-aware). The
 * stand-in makes start/dispatch/stop fully exercisable without real Claude Code.
 */
class ShellSessionLauncher : Launcher {
    override fun launch(claudeSessionId: String, projectPath: String?, instruction: String?): Process {
        val pb = ProcessBuilder("/bin/sh", "-c", "while IFS= read -r line; do :; done")
        projectPath?.let { File(it).takeIf(File::isDirectory)?.let(pb::directory) }
        pb.redirectErrorStream(true)
        return pb.start()
    }
}

/**
 * Manages the Claude Code runs the agent starts (research D2–D5). Only agent-managed sessions are
 * controllable; monitoring still observes all sessions. Reports outcomes as [ControlResult]s and
 * surfaces a started run to monitoring via a `session_start` [ActivityEvent].
 */
class SessionController(
    private val launcher: Launcher,
    private val emitActivity: suspend (ActivityEvent) -> Unit,
    private val emitResult: suspend (ControlResult) -> Unit,
) {
    private data class Managed(val process: Process, val stdin: java.io.BufferedWriter)

    private val sessions = ConcurrentHashMap<String, Managed>()

    suspend fun handle(command: ControlCommand) {
        when (command.type) {
            "start_run" -> startRun(command)
            "dispatch_task" -> dispatch(command)
            "stop_session" -> stop(command)
            else -> emitResult(ControlResult(command.commandId, "error", null, "unknown command type"))
        }
    }

    private suspend fun startRun(command: ControlCommand) {
        val sessionId = UUID.randomUUID().toString()
        try {
            val process = launcher.launch(sessionId, command.projectPath, command.instruction)
            val stdin = process.outputStream.bufferedWriter()
            sessions[sessionId] = Managed(process, stdin)
            command.instruction?.let { stdin.appendLine(it); stdin.flush() }
            emitActivity(ActivityEvent(sessionId, "session_start", null, command.projectPath, "started by operator", "{}", now()))
            emitResult(ControlResult(command.commandId, "started", sessionId, null))
        } catch (e: Exception) {
            emitResult(ControlResult(command.commandId, "error", null, e.message))
        }
    }

    private suspend fun dispatch(command: ControlCommand) {
        val sessionId = command.claudeSessionId
        val managed = sessionId?.let { sessions[it] }
        if (managed == null || !managed.process.isAlive) {
            emitResult(ControlResult(command.commandId, "undeliverable", sessionId, "no managed session ready"))
            return
        }
        try {
            managed.stdin.appendLine(command.instruction ?: "")
            managed.stdin.flush()
            emitResult(ControlResult(command.commandId, "delivered", sessionId, null))
        } catch (e: Exception) {
            emitResult(ControlResult(command.commandId, "undeliverable", sessionId, e.message))
        }
    }

    private suspend fun stop(command: ControlCommand) {
        val sessionId = command.claudeSessionId
        val managed = sessionId?.let { sessions.remove(it) }
        if (managed == null) {
            emitResult(ControlResult(command.commandId, "stopped", sessionId, "already stopped"))
            return
        }
        // Graceful: close stdin (EOF ends the run). Force if it does not exit promptly.
        runCatching { managed.stdin.close() }
        if (!managed.process.waitFor(3, TimeUnit.SECONDS)) managed.process.destroyForcibly()
        emitActivity(ActivityEvent(sessionId!!, "session_end", null, null, "stopped by operator", "{}", now()))
        emitResult(ControlResult(command.commandId, "stopped", sessionId, null))
    }

    private fun now() = Instant.now().toString()
}
