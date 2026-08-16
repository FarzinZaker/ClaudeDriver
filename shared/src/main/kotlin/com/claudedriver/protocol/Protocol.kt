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
const val PROTOCOL_VERSION: String = "0.1.0"

/** Message type discriminators carried in the envelope `type` field. */
object MessageType {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val VERSION_MISMATCH = "version_mismatch"
    const val PING = "ping"
    const val PONG = "pong"
    const val SAMPLE_EVENT = "sample_event"
}

/** Parsed semantic version with the Phase 0 compatibility rule: same MAJOR.MINOR. */
data class ProtocolVersion(val major: Int, val minor: Int, val patch: Int) {
    fun isCompatibleWith(other: ProtocolVersion): Boolean =
        major == other.major && minor == other.minor

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
