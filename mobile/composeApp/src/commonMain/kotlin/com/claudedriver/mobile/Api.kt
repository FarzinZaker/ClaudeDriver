package com.claudedriver.mobile

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Backend client for the mobile app. Talks to the AWS-hosted backend over the internet (remote by
 * default — no LAN dependency). The passkey session cookie is established by [signInWithPasskey]
 * (platform-specific) before these calls.
 */
class ClaudeDriverApi(private val baseUrl: String, private val http: HttpClient) {

    suspend fun approvals(): List<ApprovalSummary> =
        http.get("$baseUrl/approvals").body<ApprovalsResponse>().approvals

    suspend fun sessions(): List<SessionSummary> =
        http.get("$baseUrl/sessions").body<SessionsResponse>().sessions

    /** Decide a pending approval. Returns true on success, false on 409 already-resolved. */
    suspend fun decide(approvalId: String, decision: String): Boolean {
        val response: HttpResponse = http.post("$baseUrl/approvals/$approvalId/decide") {
            contentType(ContentType.Application.Json)
            setBody(DecideRequest(decision))
        }
        return response.status.isSuccess()
    }

    suspend fun registerDevice(token: String, platform: String) {
        http.post("$baseUrl/devices") {
            contentType(ContentType.Application.Json)
            setBody(DeviceRegisterRequest(token, platform))
        }
    }

    companion object {
        fun defaultClient(engineClient: HttpClient): HttpClient = engineClient.config {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }
}
