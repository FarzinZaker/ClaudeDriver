package com.claudedriver.backend.api

import com.claudedriver.backend.managed.QuestionInfo
import com.claudedriver.backend.managed.SearchHit
import com.claudedriver.backend.managed.TranscriptLine
import kotlinx.serialization.Serializable

/** Phase 4 REST DTOs (contracts/rest-api-additions.md). */

@Serializable
data class QuestionDto(
    val id: String,
    val machineId: String,
    val machineName: String,
    val claudeSessionId: String,
    val text: String,
    val status: String,
    val createdAt: String,
    val answer: String?,
    val resolvedBy: String?,
)

@Serializable
data class QuestionsResponse(val questions: List<QuestionDto>)

@Serializable
data class AnswerRequest(val answer: String? = null, val cancel: Boolean = false)

@Serializable
data class AnswerResponse(val status: String)

@Serializable
data class TranscriptMessageDto(val role: String, val text: String, val at: String)

@Serializable
data class TranscriptResponse(val messages: List<TranscriptMessageDto>)

@Serializable
data class SearchResultDto(
    val sessionId: String?,
    val machineName: String,
    val claudeSessionId: String,
    val role: String,
    val snippet: String,
    val at: String,
)

@Serializable
data class SearchResponse(val results: List<SearchResultDto>)

@Serializable
data class RotateCertResponse(val enrollmentCode: String, val expiresAt: String)

fun QuestionInfo.toDto() = QuestionDto(
    id = id.toString(), machineId = machineId.toString(), machineName = machineName,
    claudeSessionId = claudeSessionId, text = text, status = status, createdAt = createdAt.toString(),
    answer = answer, resolvedBy = resolvedBy,
)

fun TranscriptLine.toDto() = TranscriptMessageDto(role = role, text = text, at = at.toString())

fun SearchHit.toDto() = SearchResultDto(
    sessionId = sessionId?.toString(), machineName = machineName, claudeSessionId = claudeSessionId,
    role = role, snippet = snippet, at = at.toString(),
)
