package com.claudedriver.agent

import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ControlCommand
import com.claudedriver.protocol.ControlResult
import com.claudedriver.protocol.QuestionAnswer
import com.claudedriver.protocol.QuestionRaised
import com.claudedriver.protocol.TranscriptMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Launches the Agent-SDK companion for a managed session. Pluggable so the bridge is testable. */
interface CompanionLauncher {
    fun launch(claudeSessionId: String, projectPath: String?, instruction: String?): Process
}

/**
 * Real launcher: runs the Python Agent-SDK companion (`companion.py`). It cannot run in this
 * environment (no SDK/Claude Code/API key), so its true validation is a deploy/CI step; tests inject
 * a fake companion instead.
 */
class PythonCompanionLauncher(
    private val companionPath: String = System.getenv("CLAUDEDRIVER_COMPANION") ?: "companion.py",
) : CompanionLauncher {
    override fun launch(claudeSessionId: String, projectPath: String?, instruction: String?): Process {
        val pb = ProcessBuilder("python3", companionPath)
        pb.environment()["CLAUDEDRIVER_SESSION_ID"] = claudeSessionId
        instruction?.let { pb.environment()["CLAUDEDRIVER_INSTRUCTION"] = it }
        projectPath?.let { File(it).takeIf(File::isDirectory)?.let(pb::directory) }
        return pb.start()
    }
}

@Serializable
private data class CompanionInbound(val kind: String, val questionId: String, val text: String? = null)

/**
 * Bridges a managed session's SDK companion (line-delimited JSON on stdio) to the backend: forwards
 * transcript lines and free-form questions up, routes the operator's answer/cancel down. Only sessions
 * started here are managed; monitoring still observes all sessions.
 */
class ManagedSessionController(
    private val launcher: CompanionLauncher,
    private val scope: CoroutineScope,
    private val emitActivity: suspend (ActivityEvent) -> Unit,
    private val emitResult: suspend (ControlResult) -> Unit,
    private val emitQuestion: suspend (QuestionRaised) -> Unit,
    private val emitTranscript: suspend (TranscriptMessage) -> Unit,
) {
    private data class Managed(val process: Process, val stdin: java.io.BufferedWriter)

    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = ConcurrentHashMap<String, Managed>()
    private val questionToSession = ConcurrentHashMap<String, String>()

    suspend fun startManaged(command: ControlCommand) {
        val sessionId = UUID.randomUUID().toString()
        try {
            val process = launcher.launch(sessionId, command.projectPath, command.instruction)
            sessions[sessionId] = Managed(process, process.outputStream.bufferedWriter())
            emitActivity(ActivityEvent(sessionId, "session_start", null, command.projectPath, "managed session started", "{}", now()))
            emitResult(ControlResult(command.commandId, "started", sessionId, null))
            scope.launch { readCompanion(sessionId, process) }
        } catch (e: Exception) {
            emitResult(ControlResult(command.commandId, "error", null, e.message))
        }
    }

    /** Route the operator's answer/cancel to the companion holding the question. */
    suspend fun answer(answer: QuestionAnswer) {
        val sessionId = questionToSession.remove(answer.questionId) ?: return
        val managed = sessions[sessionId] ?: return
        val line = json.encodeToString(
            CompanionInbound.serializer(),
            CompanionInbound(if (answer.cancel) "cancel" else "answer", answer.questionId, if (answer.cancel) null else answer.answer),
        )
        runCatching { managed.stdin.appendLine(line); managed.stdin.flush() }
    }

    private suspend fun readCompanion(sessionId: String, process: Process) {
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "transcript" -> emitTranscript(
                    TranscriptMessage(sessionId, obj["role"]?.jsonPrimitive?.contentOrNull ?: "assistant", obj["text"]?.jsonPrimitive?.contentOrNull ?: "", now()),
                )
                "question" -> {
                    val raw = obj["questionId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                    // Normalize to a canonical UUID so the key matches the backend's stored id
                    // (which round-trips through UUID.fromString → lowercase); answers come back lowercase.
                    val questionId = runCatching { UUID.fromString(raw).toString() }.getOrNull() ?: raw
                    questionToSession[questionId] = sessionId
                    emitQuestion(QuestionRaised(questionId, sessionId, obj["text"]?.jsonPrimitive?.contentOrNull ?: "", now()))
                }
                "ended" -> break
            }
        }
        sessions.remove(sessionId)
        emitActivity(ActivityEvent(sessionId, "session_end", null, null, "managed session ended", "{}", now()))
    }

    private fun now() = Instant.now().toString()
}
