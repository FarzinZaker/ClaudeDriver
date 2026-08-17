package com.claudedriver.backend.api

import kotlinx.serialization.Serializable

/** REST request/response DTOs (contracts/rest-api.md). */

@Serializable
data class RegisterOptionsRequest(val bootstrapCode: String, val handle: String = "operator")

@Serializable
data class RegisterRequest(val username: String, val password: String, val bootstrapCode: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class CreateMachineRequest(val name: String, val os: String)

@Serializable
data class CreateMachineResponse(val machineId: String)

@Serializable
data class EnrollmentApprovedResponse(val enrollmentCode: String, val expiresAt: String)

@Serializable
data class AgentEnrollRequest(val machineId: String, val enrollmentCode: String, val csr: String)

@Serializable
data class AgentEnrollResponse(val deviceCertificate: String, val caChain: String, val notAfter: String)

@Serializable
data class WhoAmIResponse(val machineId: String, val status: String)

@Serializable
data class ErrorResponse(val error: String, val message: String)

@Serializable
data class ServerInfo(val version: String, val time: String)

@Serializable
data class ConnectionDto(val state: String, val since: String?, val protocolVersion: String?)

@Serializable
data class MachineDto(
    val id: String,
    val name: String,
    val os: String,
    val status: String,
    val connection: ConnectionDto?,
)

@Serializable
data class SampleEventDto(val machineId: String, val message: String, val at: String)

@Serializable
data class StatusResponse(
    val server: ServerInfo,
    val machines: List<MachineDto>,
    val recentSampleEvents: List<SampleEventDto>,
)
