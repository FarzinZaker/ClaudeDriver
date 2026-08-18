package com.claudedriver.backend.terminal

import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.TerminalClosed
import com.claudedriver.protocol.TerminalData
import com.claudedriver.protocol.TerminalEvent
import com.claudedriver.protocol.TerminalInput
import com.claudedriver.protocol.TerminalOpened
import com.claudedriver.protocol.TerminalOutput
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A DTO describing a live (or just-closed) terminal for the dashboard list. */
data class TerminalInfo(
    val terminalId: String, val machineId: String, val machineName: String,
    val sid: String, val cwd: String, val cols: Int, val rows: Int,
    val status: String, val openedAt: String, val exitCode: Int?,
)

/**
 * Tracks live terminals mirrored from the transparent `claude` shim (Phase 5). Buffers a bounded
 * scrollback tail per terminal so a dashboard that attaches mid-session sees recent context, streams
 * output to operators, and routes operator keystrokes back down to the owning agent. Fail-safe: input
 * to a machine that is not connected is simply dropped (never queued to a stranger).
 */
class TerminalService(
    private val publisher: Publisher,
    private val agentHub: AgentHub,
    private val machineNameOf: (UUID) -> String,
    /** Audit sink for operator keystrokes: (operator, terminalId). */
    private val auditInput: (String, String) -> Unit = { _, _ -> },
) {
    private class Live(
        val info: TerminalInfoMutable,
        val buffer: ScrollbackBuffer = ScrollbackBuffer(MAX_SCROLLBACK),
    )
    private class TerminalInfoMutable(
        val terminalId: String, val machineId: String, val machineName: String,
        val sid: String, val cwd: String, var cols: Int, var rows: Int,
        var status: String, val openedAt: String, var exitCode: Int?,
    )

    private val terminals = ConcurrentHashMap<String, Live>()

    private fun terminalId(machineId: UUID, sid: String) = "$machineId:$sid"

    suspend fun opened(machineId: UUID, opened: TerminalOpened) {
        val id = terminalId(machineId, opened.sid)
        val name = machineNameOf(machineId)
        val info = TerminalInfoMutable(
            id, machineId.toString(), name, opened.sid, opened.cwd,
            opened.cols, opened.rows, "open", opened.at, null,
        )
        terminals[id] = Live(info)
        evictIfCrowded()
        publisher.terminalEvent(event(info))
    }

    suspend fun output(machineId: UUID, output: TerminalOutput) {
        val id = terminalId(machineId, output.sid)
        val live = terminals[id] ?: return
        live.buffer.append(Base64.getDecoder().decode(output.dataB64))
        publisher.terminalData(TerminalData(id, output.dataB64, output.at))
    }

    suspend fun closed(machineId: UUID, closed: TerminalClosed) {
        val id = terminalId(machineId, closed.sid)
        val live = terminals[id] ?: return
        live.info.status = "closed"
        live.info.exitCode = closed.exitCode
        publisher.terminalEvent(event(live.info))
    }

    /** Route an operator keystroke to the agent that owns the terminal; audited. Drops if offline. */
    fun input(input: TerminalInput, operator: String) {
        val machineId = runCatching { UUID.fromString(input.terminalId.substringBefore(':')) }.getOrNull() ?: return
        val live = terminals[input.terminalId]
        if (live == null || live.info.status != "open") return
        runCatching { auditInput(operator, input.terminalId) }
        agentHub.offer(machineId, MessageType.TERMINAL_INPUT, Codec.json.encodeToJsonElement(input))
    }

    fun list(): List<TerminalInfo> = terminals.values
        .sortedByDescending { it.info.openedAt }
        .map { it.info.let { i -> TerminalInfo(i.terminalId, i.machineId, i.machineName, i.sid, i.cwd, i.cols, i.rows, i.status, i.openedAt, i.exitCode) } }

    /** Recent output for a terminal as base64, so a newly-attached dashboard renders scrollback. */
    fun scrollback(terminalId: String): String? =
        terminals[terminalId]?.let { Base64.getEncoder().encodeToString(it.buffer.snapshot()) }

    private fun event(i: TerminalInfoMutable) = TerminalEvent(
        i.terminalId, i.machineId, i.machineName, i.sid, i.cwd, i.cols, i.rows, i.status, i.openedAt, i.exitCode,
    )

    private fun evictIfCrowded() {
        if (terminals.size <= MAX_TERMINALS) return
        terminals.values.filter { it.info.status == "closed" }
            .sortedBy { it.info.openedAt }
            .take(terminals.size - MAX_TERMINALS)
            .forEach { terminals.remove(it.info.terminalId) }
    }

    companion object {
        private const val MAX_SCROLLBACK = 256 * 1024 // bytes of tail kept per terminal
        private const val MAX_TERMINALS = 64
    }
}

/** A fixed-capacity byte tail: appends trim the front so only the last [capacity] bytes remain. */
class ScrollbackBuffer(private val capacity: Int) {
    private val buf = java.io.ByteArrayOutputStream()

    @Synchronized fun append(data: ByteArray) {
        buf.write(data)
        if (buf.size() > capacity) {
            val all = buf.toByteArray()
            buf.reset()
            buf.write(all, all.size - capacity, capacity)
        }
    }

    @Synchronized fun snapshot(): ByteArray = buf.toByteArray()
}
