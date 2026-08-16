package com.claudedriver.backend.control

import com.claudedriver.backend.approvals.ApprovalService
import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.ControlCommands
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.ControlCommand
import com.claudedriver.protocol.ControlEvent
import com.claudedriver.protocol.ControlResult
import com.claudedriver.protocol.MessageType
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/** A target session resolved from its backend id. */
data class SessionTarget(val machineId: UUID, val claudeSessionId: String?)

data class CommandInfo(
    val id: UUID, val machineId: UUID, val machineName: String, val type: String,
    val claudeSessionId: String?, val instruction: String?, val status: String,
    val createdAt: Instant, val message: String?,
)

/**
 * Issues remote-control commands over the Phase 2 agent channel and applies their results
 * (research D1/D3/D4). Commands are audited; a result is applied at most once (terminal statuses are
 * not re-applied), and a `stop` result moots the session's pending approvals (Phase 2).
 */
class ControlService(
    private val db: Database,
    private val audit: AuditRepository,
    private val publisher: Publisher,
    private val agentHub: AgentHub,
    private val approvals: ApprovalService,
) {
    fun isConnected(machineId: UUID): Boolean = agentHub.isConnected(machineId)

    fun sessionTarget(sessionId: UUID): SessionTarget? = transaction(db) {
        Sessions.selectAll().where { Sessions.id eq sessionId }.firstOrNull()
            ?.let { SessionTarget(it[Sessions.machineId], it[Sessions.claudeSessionId]) }
    }

    /** Persist + route a control command; returns its id. */
    suspend fun issue(
        type: String,
        machineId: UUID,
        sessionId: UUID? = null,
        claudeSessionId: String? = null,
        projectPath: String? = null,
        instruction: String? = null,
        operator: String,
    ): UUID {
        val commandId = UUID.randomUUID()
        val now = Instant.now()
        transaction(db) {
            ControlCommands.insert {
                it[id] = commandId
                it[ControlCommands.machineId] = machineId
                it[ControlCommands.sessionId] = sessionId
                it[ControlCommands.claudeSessionId] = claudeSessionId
                it[ControlCommands.type] = type
                it[ControlCommands.projectPath] = projectPath
                it[ControlCommands.instruction] = instruction
                it[status] = "pending"
                it[issuedBy] = operator
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        audit.append(operator, AuditAction.CONTROL_ISSUED, commandId.toString(), """{"type":"$type"}""")
        agentHub.offer(
            machineId,
            MessageType.CONTROL_COMMAND,
            Codec.json.encodeToJsonElement(ControlCommand(commandId.toString(), type, claudeSessionId, projectPath, instruction, now.toString())),
        )
        publisher.controlEvent(ControlEvent(commandId.toString(), machineId.toString(), machineName(machineId), type, "pending", claudeSessionId, now.toString(), null))
        return commandId
    }

    private data class Applied(val type: String, val machineId: UUID, val claudeSessionId: String?, val status: String)

    /** Apply an agent-reported result at most once; moot approvals on a stop. */
    suspend fun applyResult(machineId: UUID, result: ControlResult) {
        val now = Instant.now()
        val commandId = runCatching { UUID.fromString(result.commandId) }.getOrNull() ?: return
        val applied = transaction(db) {
            val row = ControlCommands.selectAll().where { ControlCommands.id eq commandId }.firstOrNull()
                ?: return@transaction null
            if (row[ControlCommands.status] != "pending") return@transaction null // at-most-once
            ControlCommands.update({ ControlCommands.id eq commandId }) {
                it[status] = result.status
                it[resultMessage] = result.message
                if (result.claudeSessionId != null && row[ControlCommands.claudeSessionId] == null) {
                    it[ControlCommands.claudeSessionId] = result.claudeSessionId
                }
                it[updatedAt] = now
            }
            Applied(row[ControlCommands.type], row[ControlCommands.machineId], result.claudeSessionId ?: row[ControlCommands.claudeSessionId], result.status)
        } ?: return

        audit.append("machine:$machineId", AuditAction.CONTROL_RESULT, commandId.toString(), """{"status":"${result.status}"}""")
        publisher.controlEvent(
            ControlEvent(commandId.toString(), machineId.toString(), machineName(machineId), applied.type, applied.status, applied.claudeSessionId, now.toString(), result.message),
        )
        if (applied.type == "stop_session" && applied.status == "stopped" && applied.claudeSessionId != null) {
            approvals.mootForClaudeSession(machineId, applied.claudeSessionId)
        }
    }

    fun list(): List<CommandInfo> = transaction(db) {
        ControlCommands.join(Machines, JoinType.INNER, additionalConstraint = { ControlCommands.machineId eq Machines.id })
            .selectAll().orderBy(ControlCommands.createdAt, SortOrder.DESC)
            .map {
                CommandInfo(
                    id = it[ControlCommands.id], machineId = it[ControlCommands.machineId], machineName = it[Machines.name],
                    type = it[ControlCommands.type], claudeSessionId = it[ControlCommands.claudeSessionId],
                    instruction = it[ControlCommands.instruction], status = it[ControlCommands.status],
                    createdAt = it[ControlCommands.createdAt], message = it[ControlCommands.resultMessage],
                )
            }
    }

    private fun machineName(machineId: UUID): String = transaction(db) {
        Machines.selectAll().where { Machines.id eq machineId }.firstOrNull()?.get(Machines.name) ?: machineId.toString()
    }
}
