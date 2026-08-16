package com.claudedriver.backend.monitoring

import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.AlertEvent
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.SessionUpdate
import java.util.concurrent.atomic.AtomicLong

/** Encodes and broadcasts backend→operator monitoring frames over the operator hub. */
class Publisher(private val hub: OperatorHub) {
    private val seq = AtomicLong(0)

    suspend fun sessionUpdate(update: SessionUpdate) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.SESSION_UPDATE, seq.incrementAndGet(), update)))
    }

    suspend fun alertEvent(event: AlertEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.ALERT_EVENT, seq.incrementAndGet(), event)))
    }
}
