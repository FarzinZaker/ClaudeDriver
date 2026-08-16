package com.claudedriver.backend.api

import com.claudedriver.backend.monitoring.AlertInfo
import com.claudedriver.backend.monitoring.SessionDetailInfo
import com.claudedriver.backend.monitoring.SessionInfo
import kotlinx.serialization.Serializable

/** Phase 1 REST DTOs (contracts/rest-api-additions.md). */

@Serializable
data class SessionSummaryDto(
    val id: String,
    val machineId: String,
    val machineName: String,
    val projectPath: String?,
    val state: String,
    val lastActivityAt: String,
    val processPresent: Boolean,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionSummaryDto>)

@Serializable
data class ActivityEventDto(val kind: String, val attention: String, val summary: String, val at: String)

@Serializable
data class SessionDetailResponse(val session: SessionSummaryDto, val recentEvents: List<ActivityEventDto>)

@Serializable
data class AlertDto(
    val id: String,
    val sessionId: String,
    val machineId: String,
    val machineName: String,
    val status: String,
    val urgency: String,
    val summary: String,
    val raisedAt: String,
    val resolvedReason: String?,
)

@Serializable
data class AlertsResponse(val alerts: List<AlertDto>)

fun SessionInfo.toDto() = SessionSummaryDto(
    id = id.toString(), machineId = machineId.toString(), machineName = machineName,
    projectPath = projectPath, state = state, lastActivityAt = lastActivityAt.toString(),
    processPresent = processPresent,
)

fun SessionDetailInfo.toDto() = SessionDetailResponse(
    session = session.toDto(),
    recentEvents = recentEvents.map { ActivityEventDto(it.kind, it.attention, it.summary, it.at.toString()) },
)

fun AlertInfo.toDto() = AlertDto(
    id = id.toString(), sessionId = sessionId.toString(), machineId = machineId.toString(),
    machineName = machineName, status = status, urgency = urgency, summary = summary,
    raisedAt = raisedAt.toString(), resolvedReason = resolvedReason,
)
