package com.claudedriver.backend.monitoring

import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.AlertEvent
import com.claudedriver.protocol.ApprovalEvent
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.ControlEvent
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.QuestionEvent
import com.claudedriver.protocol.SessionUpdate
import com.claudedriver.protocol.TerminalData
import com.claudedriver.protocol.TerminalEvent
import com.claudedriver.protocol.TranscriptEvent
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

    suspend fun approvalEvent(event: ApprovalEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.APPROVAL_EVENT, seq.incrementAndGet(), event)))
    }

    suspend fun controlEvent(event: ControlEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.CONTROL_EVENT, seq.incrementAndGet(), event)))
    }

    suspend fun questionEvent(event: QuestionEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.QUESTION_EVENT, seq.incrementAndGet(), event)))
    }

    suspend fun transcriptEvent(event: TranscriptEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.TRANSCRIPT_EVENT, seq.incrementAndGet(), event)))
    }

    suspend fun terminalEvent(event: TerminalEvent) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.TERMINAL_EVENT, seq.incrementAndGet(), event)))
    }

    suspend fun terminalData(data: TerminalData) {
        hub.broadcast(Codec.encode(Codec.envelope(MessageType.TERMINAL_DATA, seq.incrementAndGet(), data)))
    }
}
