package com.claudedriver.mobile

import kotlinx.serialization.Serializable

/**
 * Wire DTOs the app consumes. These MIRROR the root `shared/` protocol (contracts under
 * specs/003-remote-approvals). When building with the toolchain, replace this file by depending on
 * the `:shared` module via a Gradle composite build so the contract is defined once (Principle III).
 */

@Serializable
data class ApprovalSummary(
    val id: String,
    val machineId: String,
    val machineName: String,
    val claudeSessionId: String,
    val tool: String,
    val summary: String,
    val status: String, // pending | approved | denied | moot
    val createdAt: String,
    val decidedBy: String? = null,
    val reason: String? = null,
)

@Serializable
data class ApprovalsResponse(val approvals: List<ApprovalSummary>)

@Serializable
data class SessionSummary(
    val id: String,
    val machineId: String,
    val machineName: String,
    val projectPath: String? = null,
    val state: String,
    val lastActivityAt: String,
    val processPresent: Boolean,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionSummary>)

@Serializable
data class DecideRequest(val decision: String) // approve | deny

@Serializable
data class DeviceRegisterRequest(val token: String, val platform: String)
