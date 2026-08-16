package com.claudedriver.backend.ws

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A backend→agent frame queued for a specific connection (type + payload; seq assigned at send). */
data class OutFrame(val type: String, val payload: JsonElement)

/**
 * Tracks connected agents by machine so the backend can route a message (e.g. an approval decision)
 * back down the correct outbound WebSocket. Each connection owns a bounded outbound channel drained
 * by its session's sender coroutine.
 */
class AgentHub {
    private val channels = ConcurrentHashMap<UUID, Channel<OutFrame>>()

    fun register(machineId: UUID, channel: Channel<OutFrame>) {
        channels[machineId] = channel
    }

    fun unregister(machineId: UUID, channel: Channel<OutFrame>) {
        channels.remove(machineId, channel)
    }

    fun isConnected(machineId: UUID): Boolean = channels.containsKey(machineId)

    /** Enqueue a frame for a connected agent; false if that machine is not currently connected. */
    fun offer(machineId: UUID, type: String, payload: JsonElement): Boolean {
        val channel = channels[machineId] ?: return false
        return channel.trySend(OutFrame(type, payload)).isSuccess
    }
}
