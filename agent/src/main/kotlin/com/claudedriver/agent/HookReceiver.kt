package com.claudedriver.agent

import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ApprovalRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Loopback-only HTTP receiver for Claude Code hooks:
 *  - `/hook`    (non-blocking) — activity events → [onEvent] (Phase 1).
 *  - `/approve` (BLOCKING)     — a tool-permission prompt; holds the response, calls [onApproval]
 *                                (which awaits the operator's decision over the outbound WSS), and
 *                                returns Claude Code's permission decision. Anything unparseable or a
 *                                failed decision resolves to DENY (fail-safe, Constitution I).
 */
class HookReceiver(
    private val port: Int,
    private val token: String,
    private val onEvent: suspend (ActivityEvent) -> Unit,
    private val onApproval: suspend (ApprovalRequest) -> String = { "deny" },
) {
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                post("/hook") {
                    if (call.request.header("Authorization") != "Bearer $token") {
                        call.respond(HttpStatusCode.Unauthorized); return@post
                    }
                    parse(call.receiveText())?.let { onEvent(it) }
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/approve") {
                    if (call.request.header("Authorization") != "Bearer $token") {
                        call.respond(HttpStatusCode.Unauthorized); return@post
                    }
                    val request = parseApproval(call.receiveText())
                    val decision = if (request == null) "deny" else onApproval(request)
                    val permission = if (decision == "approve") "allow" else "deny"
                    call.respondText(
                        """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"$permission","permissionDecisionReason":"ClaudeDriver: $decision"}}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
    }

    /** Translate a Claude Code activity hook payload into an ActivityEvent (Phase 1). */
    fun parse(body: String): ActivityEvent? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val eventName = obj["hook_event_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
        val notificationType = obj["notification_type"]?.jsonPrimitive?.contentOrNull
        val kind = when (eventName) {
            "Notification" -> "notification"
            "Stop" -> "stop"
            "SessionStart" -> "session_start"
            "SessionEnd" -> "session_end"
            "PreToolUse", "PostToolUse" -> "tool"
            else -> eventName.lowercase()
        }
        val summary = when {
            kind == "notification" -> "waiting: ${notificationType ?: "attention"}"
            kind == "stop" -> "turn finished"
            kind == "session_start" -> "session started"
            kind == "session_end" -> "session ended"
            else -> kind
        }
        return ActivityEvent(sessionId, kind, notificationType, cwd, summary, body, Instant.now().toString())
    }

    /** Build an ApprovalRequest from a PreToolUse hook payload. Returns null if unparseable. */
    fun parseApproval(body: String): ApprovalRequest? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val tool = obj["tool_name"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
        val input = obj["tool_input"]?.jsonObject
        val detailBit = input?.get("command")?.jsonPrimitive?.contentOrNull
            ?: input?.get("file_path")?.jsonPrimitive?.contentOrNull
        val summary = if (detailBit != null) "$tool: `$detailBit`" else tool
        return ApprovalRequest(
            requestId = UUID.randomUUID().toString(),
            claudeSessionId = sessionId,
            tool = tool,
            summary = summary,
            detail = body,
            projectPath = cwd,
            at = Instant.now().toString(),
        )
    }
}
