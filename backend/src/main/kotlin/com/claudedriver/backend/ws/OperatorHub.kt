package com.claudedriver.backend.ws

import com.claudedriver.protocol.SampleEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fan-out hub for operator clients (web/mobile). Agents' sample events are relayed here and pushed
 * to every connected operator; a small ring of recent events seeds `GET /status`.
 */
class OperatorHub {
    private val operators: MutableSet<DefaultWebSocketServerSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val recent = ArrayDeque<SampleEvent>()
    private val recentLock = Any()
    private val maxRecent = 20

    fun addOperator(session: DefaultWebSocketServerSession) {
        operators.add(session)
    }

    fun removeOperator(session: DefaultWebSocketServerSession) {
        operators.remove(session)
    }

    fun operatorCount(): Int = operators.size

    fun recordAndSnapshotRecent(event: SampleEvent): List<SampleEvent> = synchronized(recentLock) {
        recent.addFirst(event)
        while (recent.size > maxRecent) recent.removeLast()
        recent.toList()
    }

    fun recentEvents(): List<SampleEvent> = synchronized(recentLock) { recent.toList() }

    /** Relay a pre-encoded envelope frame to all connected operators. */
    suspend fun broadcast(frameText: String) {
        for (session in operators) {
            runCatching { session.send(Frame.Text(frameText)) }
        }
    }
}
