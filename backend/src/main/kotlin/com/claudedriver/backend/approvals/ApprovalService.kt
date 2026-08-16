package com.claudedriver.backend.approvals

import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.ApprovalRequests
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.push.PushMessage
import com.claudedriver.backend.push.PushService
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.protocol.ApprovalDecision
import com.claudedriver.protocol.ApprovalEvent
import com.claudedriver.protocol.ApprovalRequest
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.MessageType
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class ApprovalInfo(
    val id: UUID, val machineId: UUID, val machineName: String, val claudeSessionId: String,
    val tool: String, val summary: String, val status: String, val createdAt: Instant,
    val decidedBy: String?, val reason: String?,
)

enum class DecideOutcome { OK, NOT_FOUND, ALREADY_RESOLVED }

/**
 * The remote-approval lifecycle (research D1/D2/D5). Records a held tool-permission request, routes
 * the operator's decision back to the owning agent, applies decisions at most once, and moots +
 * denies pending requests when a session stops. Never auto-approves.
 */
class ApprovalService(
    private val db: Database,
    private val audit: AuditRepository,
    private val publisher: Publisher,
    private val agentHub: AgentHub,
    private val push: PushService,
) {
    /** Record a held approval request; notify operators (live + push). */
    suspend fun raise(machineId: UUID, req: ApprovalRequest) {
        val now = Instant.now()
        val approvalId = UUID.fromString(req.requestId)
        transaction(db) {
            val sessionId = Sessions.selectAll().where {
                (Sessions.machineId eq machineId) and (Sessions.claudeSessionId eq req.claudeSessionId)
            }.firstOrNull()?.get(Sessions.id)
            ApprovalRequests.insert {
                it[id] = approvalId
                it[ApprovalRequests.machineId] = machineId
                it[ApprovalRequests.sessionId] = sessionId
                it[claudeSessionId] = req.claudeSessionId
                it[tool] = req.tool
                it[summary] = req.summary
                it[detail] = req.detail
                it[status] = "pending"
                it[createdAt] = now
            }
        }
        val name = machineName(machineId)
        audit.append("machine:$machineId", AuditAction.APPROVAL_RAISED, approvalId.toString(), """{"tool":"${req.tool}"}""")
        publisher.approvalEvent(
            ApprovalEvent(approvalId.toString(), machineId.toString(), name, req.claudeSessionId, req.tool, req.summary, "pending", now.toString(), null, null),
        )
        push.notify(PushMessage("Approval needed — $name", req.summary, approvalId.toString(), "approval"))
    }

    private data class Captured(
        val outcome: DecideOutcome, val machineId: UUID? = null, val claudeSessionId: String? = null,
        val tool: String? = null, val summary: String? = null,
    )

    /** Apply the operator's decision at most once; route it to the owning agent. */
    suspend fun decide(approvalId: UUID, approve: Boolean, operator: String, surface: String): DecideOutcome {
        val now = Instant.now()
        val cap = transaction(db) {
            val row = ApprovalRequests.selectAll().where { ApprovalRequests.id eq approvalId }.firstOrNull()
                ?: return@transaction Captured(DecideOutcome.NOT_FOUND)
            if (row[ApprovalRequests.status] != "pending") return@transaction Captured(DecideOutcome.ALREADY_RESOLVED)
            ApprovalRequests.update({ ApprovalRequests.id eq approvalId }) {
                it[status] = if (approve) "approved" else "denied"
                it[decidedAt] = now
                it[decidedBy] = operator
                it[ApprovalRequests.surface] = surface
                it[decisionReason] = "operator"
            }
            Captured(DecideOutcome.OK, row[ApprovalRequests.machineId], row[ApprovalRequests.claudeSessionId], row[ApprovalRequests.tool], row[ApprovalRequests.summary])
        }
        if (cap.outcome == DecideOutcome.OK) {
            val decision = if (approve) "approve" else "deny"
            // Route to the agent holding the blocking hook (idempotent by requestId; no-op if gone).
            agentHub.offer(
                cap.machineId!!,
                MessageType.APPROVAL_DECISION,
                Codec.json.encodeToJsonElement(ApprovalDecision(approvalId.toString(), decision, "operator")),
            )
            audit.append(operator, AuditAction.APPROVAL_DECIDED, approvalId.toString(), """{"decision":"$decision"}""")
            publisher.approvalEvent(
                ApprovalEvent(
                    approvalId.toString(), cap.machineId.toString(), machineName(cap.machineId), cap.claudeSessionId!!,
                    cap.tool!!, cap.summary!!, if (approve) "approved" else "denied", now.toString(), operator, "operator",
                ),
            )
        }
        return cap.outcome
    }

    /** A session stopped: moot its pending approvals and deny them on the agent (nothing runs). */
    suspend fun mootForClaudeSession(machineId: UUID, claudeSessionId: String) {
        val now = Instant.now()
        val mooted = transaction(db) {
            val rows = ApprovalRequests.selectAll().where {
                (ApprovalRequests.machineId eq machineId) and (ApprovalRequests.claudeSessionId eq claudeSessionId) and (ApprovalRequests.status eq "pending")
            }.map { Triple(it[ApprovalRequests.id], it[ApprovalRequests.tool], it[ApprovalRequests.summary]) }
            if (rows.isNotEmpty()) {
                ApprovalRequests.update({
                    (ApprovalRequests.machineId eq machineId) and (ApprovalRequests.claudeSessionId eq claudeSessionId) and (ApprovalRequests.status eq "pending")
                }) {
                    it[status] = "moot"; it[decidedAt] = now; it[decisionReason] = "session_stopped"
                }
            }
            rows
        }
        if (mooted.isEmpty()) return
        val name = machineName(machineId)
        for ((id, tool, summary) in mooted) {
            agentHub.offer(machineId, MessageType.APPROVAL_DECISION, Codec.json.encodeToJsonElement(ApprovalDecision(id.toString(), "deny", "session_stopped")))
            audit.append("system", AuditAction.APPROVAL_MOOT, id.toString(), """{"reason":"session_stopped"}""")
            publisher.approvalEvent(ApprovalEvent(id.toString(), machineId.toString(), name, claudeSessionId, tool, summary, "moot", now.toString(), null, "session_stopped"))
        }
    }

    fun list(): List<ApprovalInfo> = transaction(db) {
        ApprovalRequests.join(Machines, JoinType.INNER, additionalConstraint = { ApprovalRequests.machineId eq Machines.id })
            .selectAll().orderBy(ApprovalRequests.createdAt, SortOrder.DESC)
            .map {
                ApprovalInfo(
                    id = it[ApprovalRequests.id], machineId = it[ApprovalRequests.machineId], machineName = it[Machines.name],
                    claudeSessionId = it[ApprovalRequests.claudeSessionId], tool = it[ApprovalRequests.tool],
                    summary = it[ApprovalRequests.summary], status = it[ApprovalRequests.status],
                    createdAt = it[ApprovalRequests.createdAt], decidedBy = it[ApprovalRequests.decidedBy],
                    reason = it[ApprovalRequests.decisionReason],
                )
            }
    }

    private fun machineName(machineId: UUID): String = transaction(db) {
        Machines.selectAll().where { Machines.id eq machineId }.firstOrNull()?.get(Machines.name) ?: machineId.toString()
    }
}
