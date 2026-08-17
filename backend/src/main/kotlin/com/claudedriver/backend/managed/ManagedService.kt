package com.claudedriver.backend.managed

import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Questions
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.persistence.TranscriptMessages
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.QuestionAnswer
import com.claudedriver.protocol.QuestionEvent
import com.claudedriver.protocol.QuestionRaised
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.TranscriptEvent
import com.claudedriver.protocol.TranscriptMessage
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class QuestionInfo(
    val id: UUID, val machineId: UUID, val machineName: String, val claudeSessionId: String,
    val text: String, val status: String, val createdAt: Instant, val answer: String?, val resolvedBy: String?,
)

data class TranscriptLine(val role: String, val text: String, val at: Instant)

data class SearchHit(
    val sessionId: UUID?, val machineName: String, val claudeSessionId: String,
    val role: String, val snippet: String, val at: Instant,
)

enum class AnswerOutcome { ANSWERED, CANCELLED, NOT_FOUND, ALREADY_RESOLVED }

/**
 * Managed-session interactive control (research D3/D4): free-form questions raised by a managed
 * session, the operator's answer routed back (at most once, **never fabricated**), the transcript
 * stored for viewing + cross-session search. On a managed session ending with a pending question, it
 * resolves `unanswered` — never an invented answer (Constitution Principle I).
 */
class ManagedService(
    private val db: org.jetbrains.exposed.sql.Database,
    private val audit: AuditRepository,
    private val publisher: Publisher,
    private val agentHub: AgentHub,
) {
    /** A managed session posed a free-form question. */
    suspend fun raiseQuestion(machineId: UUID, raised: QuestionRaised) {
        val now = Instant.now()
        val questionId = UUID.fromString(raised.questionId)
        transaction(db) {
            Questions.insert {
                it[id] = questionId
                it[Questions.machineId] = machineId
                it[sessionId] = sessionIdFor(machineId, raised.claudeSessionId)
                it[claudeSessionId] = raised.claudeSessionId
                it[text] = raised.text
                it[status] = "pending"
                it[createdAt] = now
            }
        }
        val name = machineName(machineId)
        audit.append("machine:$machineId", AuditAction.QUESTION_RAISED, questionId.toString())
        publisher.questionEvent(QuestionEvent(questionId.toString(), machineId.toString(), name, raised.claudeSessionId, raised.text, "pending", now.toString(), null))
    }

    /** Store a transcript line and push it live. */
    suspend fun storeTranscript(machineId: UUID, message: TranscriptMessage) {
        val now = Instant.now()
        transaction(db) {
            TranscriptMessages.insert {
                it[TranscriptMessages.machineId] = machineId
                it[sessionId] = sessionIdFor(machineId, message.claudeSessionId)
                it[claudeSessionId] = message.claudeSessionId
                it[role] = message.role
                it[text] = message.text
                it[at] = runCatching { Instant.parse(message.at) }.getOrDefault(now)
            }
        }
        publisher.transcriptEvent(TranscriptEvent(message.claudeSessionId, machineId.toString(), message.role, message.text, message.at))
    }

    private data class Resolved(val outcome: AnswerOutcome, val machineId: UUID? = null, val claudeSessionId: String? = null)

    /** Answer or cancel a pending question, at most once; route it to the agent (→ companion). */
    suspend fun answer(questionId: UUID, answerText: String?, cancel: Boolean, operator: String): AnswerOutcome {
        val now = Instant.now()
        val resolved = transaction(db) {
            val row = Questions.selectAll().where { Questions.id eq questionId }.firstOrNull()
                ?: return@transaction Resolved(AnswerOutcome.NOT_FOUND)
            if (row[Questions.status] != "pending") return@transaction Resolved(AnswerOutcome.ALREADY_RESOLVED)
            Questions.update({ Questions.id eq questionId }) {
                it[status] = if (cancel) "cancelled" else "answered"
                it[answer] = if (cancel) null else answerText
                it[resolvedAt] = now
                it[resolvedBy] = operator
            }
            Resolved(if (cancel) AnswerOutcome.CANCELLED else AnswerOutcome.ANSWERED, row[Questions.machineId], row[Questions.claudeSessionId])
        }
        if (resolved.outcome == AnswerOutcome.ANSWERED || resolved.outcome == AnswerOutcome.CANCELLED) {
            agentHub.offer(
                resolved.machineId!!,
                MessageType.QUESTION_ANSWER,
                Codec.json.encodeToJsonElement(QuestionAnswer(questionId.toString(), if (cancel) null else answerText, cancel)),
            )
            audit.append(operator, if (cancel) AuditAction.QUESTION_CANCELLED else AuditAction.QUESTION_ANSWERED, questionId.toString())
            publisher.questionEvent(
                QuestionEvent(
                    questionId.toString(), resolved.machineId.toString(), machineName(resolved.machineId), resolved.claudeSessionId!!,
                    "", if (cancel) "cancelled" else "answered", now.toString(), operator,
                ),
            )
        }
        return resolved.outcome
    }

    /** A managed session ended: any still-pending question resolves `unanswered` (never fabricated). */
    suspend fun markUnansweredForSession(machineId: UUID, claudeSessionId: String) {
        val now = Instant.now()
        val ids = transaction(db) {
            val rows = Questions.selectAll().where {
                (Questions.machineId eq machineId) and (Questions.claudeSessionId eq claudeSessionId) and (Questions.status eq "pending")
            }.map { it[Questions.id] }
            if (rows.isNotEmpty()) {
                Questions.update({
                    (Questions.machineId eq machineId) and (Questions.claudeSessionId eq claudeSessionId) and (Questions.status eq "pending")
                }) { it[status] = "unanswered"; it[resolvedAt] = now }
            }
            rows
        }
        for (id in ids) {
            audit.append("system", AuditAction.QUESTION_UNANSWERED, id.toString())
            publisher.questionEvent(QuestionEvent(id.toString(), machineId.toString(), machineName(machineId), claudeSessionId, "", "unanswered", now.toString(), null))
        }
    }

    fun listQuestions(): List<QuestionInfo> = transaction(db) {
        Questions.join(Machines, JoinType.INNER, additionalConstraint = { Questions.machineId eq Machines.id })
            .selectAll().orderBy(Questions.createdAt, SortOrder.DESC)
            .map {
                QuestionInfo(
                    id = it[Questions.id], machineId = it[Questions.machineId], machineName = it[Machines.name],
                    claudeSessionId = it[Questions.claudeSessionId], text = it[Questions.text], status = it[Questions.status],
                    createdAt = it[Questions.createdAt], answer = it[Questions.answer], resolvedBy = it[Questions.resolvedBy],
                )
            }
    }

    fun transcript(sessionId: UUID): List<TranscriptLine> = transaction(db) {
        TranscriptMessages.selectAll().where { TranscriptMessages.sessionId eq sessionId }
            .orderBy(TranscriptMessages.id, SortOrder.ASC)
            .map { TranscriptLine(it[TranscriptMessages.role], it[TranscriptMessages.text], it[TranscriptMessages.at]) }
    }

    /** Case-insensitive cross-session search over transcript text; bounded/paged. */
    fun search(term: String, limit: Int = 50): List<SearchHit> = transaction(db) {
        val pattern = "%${term.lowercase()}%"
        TranscriptMessages.join(Machines, JoinType.INNER, additionalConstraint = { TranscriptMessages.machineId eq Machines.id })
            .selectAll().where { TranscriptMessages.text.lowerCase() like pattern }
            .orderBy(TranscriptMessages.id, SortOrder.DESC).limit(limit)
            .map {
                SearchHit(
                    sessionId = it[TranscriptMessages.sessionId], machineName = it[Machines.name],
                    claudeSessionId = it[TranscriptMessages.claudeSessionId], role = it[TranscriptMessages.role],
                    snippet = it[TranscriptMessages.text].take(200), at = it[TranscriptMessages.at],
                )
            }
    }

    private fun sessionIdFor(machineId: UUID, claudeSessionId: String): UUID? =
        Sessions.selectAll().where { (Sessions.machineId eq machineId) and (Sessions.claudeSessionId eq claudeSessionId) }
            .firstOrNull()?.get(Sessions.id)

    private fun machineName(machineId: UUID): String = transaction(db) {
        Machines.selectAll().where { Machines.id eq machineId }.firstOrNull()?.get(Machines.name) ?: machineId.toString()
    }
}
