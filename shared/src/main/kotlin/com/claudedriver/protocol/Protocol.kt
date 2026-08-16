package com.claudedriver.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The ClaudeDriver wire protocol — the single source of truth (Constitution Principle III),
 * consumed identically by the backend, the agent, and (Phase 2) the mobile app.
 *
 * See specs/001-phase-0-foundations/contracts/protocol.md.
 */

/** Current contract version. Bump in the same change set as any wire change. */
const val PROTOCOL_VERSION: String = "0.3.0"

/** Message type discriminators carried in the envelope `type` field. */
object MessageType {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val VERSION_MISMATCH = "version_mismatch"
    const val PING = "ping"
    const val PONG = "pong"
    const val SAMPLE_EVENT = "sample_event"

    // Phase 1 (monitoring) — agent → backend
    const val PROCESS_SNAPSHOT = "process_snapshot"
    const val ACTIVITY_EVENT = "activity_event"

    // Phase 1 (monitoring) — backend → operator
    const val SESSION_UPDATE = "session_update"
    const val ALERT_EVENT = "alert_event"

    // Phase 2 (approvals) — agent ↔ backend and backend → operator
    const val APPROVAL_REQUEST = "approval_request"   // agent → backend (held blocking hook)
    const val APPROVAL_DECISION = "approval_decision" // backend → agent (routed decision)
    const val APPROVAL_EVENT = "approval_event"       // backend → operator (live UI)
}

/**
 * Parsed semantic version. Compatibility rule: **same MAJOR** — minor bumps are additive (new
 * message types), and unknown types are ignored by peers (forward-compatible), so a 0.1 agent and a
 * 0.2 backend interoperate. A MAJOR difference is a negotiated break.
 */
data class ProtocolVersion(val major: Int, val minor: Int, val patch: Int) {
    fun isCompatibleWith(other: ProtocolVersion): Boolean =
        major == other.major

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        val CURRENT: ProtocolVersion = parse(PROTOCOL_VERSION)

        fun parse(text: String): ProtocolVersion {
            val parts = text.trim().split(".")
            require(parts.size == 3) { "Invalid protocol version: '$text'" }
            return ProtocolVersion(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        /** Lenient parse for untrusted input; null when unparseable. */
        fun parseOrNull(text: String?): ProtocolVersion? =
            runCatching { parse(text ?: return null) }.getOrNull()
    }
}

/**
 * The frame that wraps every message. `payload` is the type-specific object; decode it with
 * [Codec.decodePayload] using [type] to select the target class.
 */
@kotlinx.serialization.Serializable
data class Envelope(
    val protocolVersion: String = PROTOCOL_VERSION,
    val type: String,
    val seq: Long,
    val commandId: String? = null,
    val payload: JsonElement,
)

// ---- Phase 0 payload types (contracts/protocol.md) -------------------------------------------

@kotlinx.serialization.Serializable
data class Hello(
    val machineName: String,
    val agentVersion: String,
    val resumeFromSeq: Long = 0,
)

@kotlinx.serialization.Serializable
data class HelloAck(
    val machineId: String,
    val serverTime: String,
    val heartbeatSeconds: Int = 30,
)

@kotlinx.serialization.Serializable
data class VersionMismatch(
    val serverVersion: String,
    val reason: String,
)

@kotlinx.serialization.Serializable
data class Ping(val t: String)

@kotlinx.serialization.Serializable
data class Pong(val t: String)

@kotlinx.serialization.Serializable
data class SampleEvent(
    val machineId: String,
    val message: String,
    val at: String,
)

// ---- Phase 1 payload types (contracts/protocol-additions.md) ---------------------------------

@kotlinx.serialization.Serializable
data class DetectedProcess(
    val pid: Long,
    val claudeSessionId: String? = null,
    val projectPath: String? = null,
    val startedAt: String,
)

@kotlinx.serialization.Serializable
data class ProcessSnapshot(
    val processes: List<DetectedProcess>,
)

@kotlinx.serialization.Serializable
data class ActivityEvent(
    val claudeSessionId: String,
    val kind: String,
    val notificationType: String? = null,
    val projectPath: String? = null,
    val summary: String,
    val detail: String = "{}",
    val at: String,
)

@kotlinx.serialization.Serializable
data class SessionUpdate(
    val sessionId: String,
    val machineId: String,
    val projectPath: String? = null,
    val state: String,
    val lastActivityAt: String,
    val processPresent: Boolean,
)

@kotlinx.serialization.Serializable
data class AlertEvent(
    val alertId: String,
    val sessionId: String,
    val machineId: String,
    val status: String,
    val urgency: String,
    val summary: String,
    val raisedAt: String,
    val resolvedReason: String? = null,
)

// ---- Phase 2 payload types (contracts/protocol-additions.md) ---------------------------------

@kotlinx.serialization.Serializable
data class ApprovalRequest(
    val requestId: String,
    val claudeSessionId: String,
    val tool: String,
    val summary: String,
    val detail: String = "{}",
    val projectPath: String? = null,
    val at: String,
)

@kotlinx.serialization.Serializable
data class ApprovalDecision(
    val requestId: String,
    val decision: String, // "approve" | "deny"
    val reason: String = "operator",
)

@kotlinx.serialization.Serializable
data class ApprovalEvent(
    val approvalId: String,
    val machineId: String,
    val machineName: String,
    val claudeSessionId: String,
    val tool: String,
    val summary: String,
    val status: String, // pending | approved | denied | moot
    val at: String,
    val decidedBy: String? = null,
    val reason: String? = null,
)

/** Encode/decode helpers so no consumer re-implements the framing. */
object Codec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> envelope(type: String, seq: Long, payload: T, commandId: String? = null): Envelope =
        Envelope(
            protocolVersion = PROTOCOL_VERSION,
            type = type,
            seq = seq,
            commandId = commandId,
            payload = json.encodeToJsonElement(payload),
        )

    fun encode(envelope: Envelope): String = json.encodeToString(envelope)

    fun decode(text: String): Envelope = json.decodeFromString(Envelope.serializer(), text)

    inline fun <reified T> decodePayload(envelope: Envelope): T =
        json.decodeFromJsonElement(envelope.payload)

    /** True when the frame is a well-formed envelope on a compatible protocol version. */
    fun isCompatible(envelope: Envelope): Boolean {
        val v = ProtocolVersion.parseOrNull(envelope.protocolVersion) ?: return false
        return v.isCompatibleWith(ProtocolVersion.CURRENT)
    }
}
