package com.claudedriver.backend.api

import com.claudedriver.backend.control.CommandInfo
import kotlinx.serialization.Serializable

/** Phase 3 REST DTOs (contracts/rest-api-additions.md). */

@Serializable
data class DispatchRequest(val instruction: String)

@Serializable
data class StartRunRequest(val projectPath: String, val instruction: String)

@Serializable
data class CommandAcceptedResponse(val commandId: String, val status: String)

@Serializable
data class CommandDto(
    val id: String,
    val machineId: String,
    val machineName: String,
    val type: String,
    val claudeSessionId: String?,
    val instruction: String?,
    val status: String,
    val createdAt: String,
    val message: String?,
)

@Serializable
data class CommandsResponse(val commands: List<CommandDto>)

fun CommandInfo.toDto() = CommandDto(
    id = id.toString(), machineId = machineId.toString(), machineName = machineName, type = type,
    claudeSessionId = claudeSessionId, instruction = instruction, status = status,
    createdAt = createdAt.toString(), message = message,
)
