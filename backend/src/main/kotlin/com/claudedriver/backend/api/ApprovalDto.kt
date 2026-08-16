package com.claudedriver.backend.api

import com.claudedriver.backend.approvals.ApprovalInfo
import kotlinx.serialization.Serializable

/** Phase 2 REST DTOs (contracts/rest-api-additions.md). */

@Serializable
data class ApprovalDto(
    val id: String,
    val machineId: String,
    val machineName: String,
    val claudeSessionId: String,
    val tool: String,
    val summary: String,
    val status: String,
    val createdAt: String,
    val decidedBy: String?,
    val reason: String?,
)

@Serializable
data class ApprovalsResponse(val approvals: List<ApprovalDto>)

@Serializable
data class DecideRequest(val decision: String) // "approve" | "deny"

@Serializable
data class DecideResponse(val status: String)

@Serializable
data class DeviceRegisterRequest(val token: String, val platform: String)

fun ApprovalInfo.toDto() = ApprovalDto(
    id = id.toString(), machineId = machineId.toString(), machineName = machineName,
    claudeSessionId = claudeSessionId, tool = tool, summary = summary, status = status,
    createdAt = createdAt.toString(), decidedBy = decidedBy, reason = reason,
)
