package com.claudedriver.agent

import com.claudedriver.protocol.ActivityEvent
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

/**
 * A loopback-only HTTP receiver for Claude Code hooks. Claude Code POSTs hook events here
 * (127.0.0.1, token-checked); we translate them to [ActivityEvent]s and hand them to [onEvent] for
 * forwarding over the agent's outbound channel. Responds immediately so Claude Code is never blocked
 * (Constitution Principle I / IV).
 */
class HookReceiver(
    private val port: Int,
    private val token: String,
    private val onEvent: suspend (ActivityEvent) -> Unit,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                post("/hook") {
                    if (call.request.header("Authorization") != "Bearer $token") {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                    val body = call.receiveText()
                    parse(body)?.let { onEvent(it) }
                    call.respondText("{}", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
    }

    /** Translate a Claude Code hook payload into an ActivityEvent. Returns null if unparseable. */
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
        return ActivityEvent(
            claudeSessionId = sessionId,
            kind = kind,
            notificationType = notificationType,
            projectPath = cwd,
            summary = summary,
            detail = body,
            at = Instant.now().toString(),
        )
    }
}
