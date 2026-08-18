package com.claudedriver.agent

import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ControlCommand
import com.claudedriver.protocol.ControlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // Agent-launched session: write straight to its stdin.
        val managed = sessionId?.let { sessions[it] }
        if (managed != null && managed.process.isAlive) {
            try {
                managed.stdin.appendLine(command.instruction ?: "")
                managed.stdin.flush()
                emitResult(ControlResult(command.commandId, "delivered", sessionId, null))
            } catch (e: Exception) {
                emitResult(ControlResult(command.commandId, "undeliverable", sessionId, e.message))
            }
            return
        }
        // Detected session: drive the real `claude` (runs as this user, so it uses the user's Claude
        // auth) by continuing the session in its project directory. A real claude session id, when
        // known, resumes that exact session; otherwise --continue picks the latest one in the cwd.
        dispatchToRealClaude(command)
    }

    private suspend fun dispatchToRealClaude(command: ControlCommand) {
        val cwd = command.projectPath?.takeIf { File(it).isDirectory }
        val task = command.instruction?.takeIf { it.isNotBlank() }
        if (cwd == null || task == null) {
            emitResult(ControlResult(command.commandId, "undeliverable", command.claudeSessionId, "no project directory or task"))
            return
        }
        val claude = resolveClaude()
            ?: run {
                emitResult(ControlResult(command.commandId, "undeliverable", command.claudeSessionId, "claude CLI not found on PATH"))
                return
            }
        val realSid = command.claudeSessionId?.takeIf { !it.startsWith("proc:") }
        val args = buildList {
            add(claude)
            if (realSid != null) { add("--resume"); add(realSid) } else add("--continue")
            add("--print"); add(task)
        }
        try {
            val result = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(args).directory(File(cwd))
                    .redirectError(ProcessBuilder.Redirect.DISCARD) // keep stdout (the reply) clean
                val proc = pb.start()
                proc.outputStream.close() // EOF on claude's stdin so it uses the arg, not piped input
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(10, TimeUnit.MINUTES)
                if (!finished) proc.destroyForcibly()
                Triple(finished, if (finished) proc.exitValue() else -1, output)
            }
            val (finished, code, output) = result
            if (finished && code == 0) {
                val summary = output.trim().lineSequence().firstOrNull()?.take(160) ?: "task completed"
                emitActivity(ActivityEvent(command.claudeSessionId ?: cwd, "task_response", null, cwd, summary, output.take(8000), now()))
                emitResult(ControlResult(command.commandId, "delivered", command.claudeSessionId, null))
            } else {
                val reason = if (!finished) "claude timed out" else "claude exited $code: ${output.take(400)}"
                emitResult(ControlResult(command.commandId, "undeliverable", command.claudeSessionId, reason))
            }
        } catch (e: Exception) {
            emitResult(ControlResult(command.commandId, "undeliverable", command.claudeSessionId, e.message))
        }
    }

    /** Locate the `claude` CLI; a launchd/service PATH is minimal, so check common install dirs too. */
    private fun resolveClaude(): String? {
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude", "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "/usr/bin/claude",
        )
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }
        // Fall back to PATH lookup.
        return System.getenv("PATH")?.split(File.pathSeparator)
            ?.map { File(it, "claude") }?.firstOrNull { it.canExecute() }?.absolutePath
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
