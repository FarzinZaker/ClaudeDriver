package com.claudedriver.backend.api

import com.claudedriver.backend.terminal.TerminalInfo
import kotlinx.serialization.Serializable

/** Phase 5 REST DTOs — live terminals mirrored from the transparent PTY wrapper. */

@Serializable
data class TerminalDto(
    val terminalId: String,
    val machineId: String,
    val machineName: String,
    val sid: String,
    val cwd: String,
    val cols: Int,
    val rows: Int,
    val status: String,
    val openedAt: String,
    val exitCode: Int?,
)

@Serializable
data class TerminalsResponse(val terminals: List<TerminalDto>)

/** Recent output for a single terminal, base64-encoded, for rendering scrollback on attach. */
@Serializable
data class TerminalScrollbackResponse(val terminalId: String, val dataB64: String)

fun TerminalInfo.toDto() = TerminalDto(
    terminalId = terminalId, machineId = machineId, machineName = machineName, sid = sid,
    cwd = cwd, cols = cols, rows = rows, status = status, openedAt = openedAt, exitCode = exitCode,
)
