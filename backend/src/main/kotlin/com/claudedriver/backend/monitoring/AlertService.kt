package com.claudedriver.backend.monitoring

import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.persistence.Alerts
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.protocol.AlertEvent
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class AlertInfo(
    val id: UUID, val sessionId: UUID, val machineId: UUID, val machineName: String,
    val status: String, val urgency: String, val summary: String,
    val raisedAt: Instant, val resolvedReason: String?,
)

enum class AckResult { OK, NOT_FOUND, NOT_ACTIVE }

/** Raises, resolves, and acknowledges attention alerts with de-dup + audit (research D6). */
class AlertService(
    private val db: Database,
    private val audit: AuditRepository,
    private val publisher: Publisher,
) {
    /** Raise one active alert per session (deduped); no-op if a non-resolved alert already exists. */
    suspend fun raiseIfNeeded(sessionId: UUID, machineId: UUID, urgency: Urgency, summary: String) {
        val now = Instant.now()
        val createdId = transaction(db) {
            val existing = Alerts.selectAll().where {
                (Alerts.sessionId eq sessionId) and (Alerts.status neq "resolved")
            }.any()
            if (existing) return@transaction null
            val id = UUID.randomUUID()
            Alerts.insert {
                it[Alerts.id] = id
                it[Alerts.sessionId] = sessionId
                it[Alerts.machineId] = machineId
                it[status] = "active"
                it[Alerts.urgency] = urgency.wire
                it[Alerts.summary] = summary
                it[raisedAt] = now
            }
            id
        }
        if (createdId != null) {
            audit.append("system", AuditAction.ALERT_RAISED, createdId.toString(), """{"session":"$sessionId"}""")
            publisher.alertEvent(
                AlertEvent(createdId.toString(), sessionId.toString(), machineId.toString(), "active", urgency.wire, summary, now.toString(), null),
            )
        }
    }

    /** Auto-resolve any non-resolved alert(s) for a session. */
    suspend fun resolveActive(sessionId: UUID, machineId: UUID, reason: String) {
        val now = Instant.now()
        val resolved = transaction(db) {
            val rows = Alerts.selectAll().where {
                (Alerts.sessionId eq sessionId) and (Alerts.status neq "resolved")
            }.map { Triple(it[Alerts.id], it[Alerts.urgency], it[Alerts.summary]) }
            if (rows.isNotEmpty()) {
                Alerts.update({ (Alerts.sessionId eq sessionId) and (Alerts.status neq "resolved") }) {
                    it[status] = "resolved"
                    it[resolvedAt] = now
                    it[resolvedReason] = reason
                }
            }
            rows
        }
        for ((id, urgency, summary) in resolved) {
            audit.append("system", AuditAction.ALERT_RESOLVED, id.toString(), """{"reason":"$reason"}""")
            publisher.alertEvent(
                AlertEvent(id.toString(), sessionId.toString(), machineId.toString(), "resolved", urgency, summary, now.toString(), reason),
            )
        }
    }

    /** Operator acknowledges an active alert. */
    suspend fun acknowledge(alertId: UUID): AckResult {
        val now = Instant.now()
        var event: AlertEvent? = null
        val result = transaction(db) {
            val row = Alerts.selectAll().where { Alerts.id eq alertId }.firstOrNull()
                ?: return@transaction AckResult.NOT_FOUND
            if (row[Alerts.status] != "active") return@transaction AckResult.NOT_ACTIVE
            Alerts.update({ Alerts.id eq alertId }) {
                it[status] = "acknowledged"
                it[acknowledgedAt] = now
            }
            event = AlertEvent(
                alertId.toString(), row[Alerts.sessionId].toString(), row[Alerts.machineId].toString(),
                "acknowledged", row[Alerts.urgency], row[Alerts.summary], row[Alerts.raisedAt].toString(), null,
            )
            AckResult.OK
        }
        if (result == AckResult.OK) {
            audit.append("operator", AuditAction.ALERT_ACKNOWLEDGED, alertId.toString())
            event?.let { publisher.alertEvent(it) }
        }
        return result
    }

    /** Recent alerts (active first by urgency, then recency) for the inbox. */
    fun list(): List<AlertInfo> = transaction(db) {
        Alerts.join(Machines, JoinType.INNER, additionalConstraint = { Alerts.machineId eq Machines.id })
            .selectAll()
            .orderBy(Alerts.raisedAt, SortOrder.DESC)
            .map {
                AlertInfo(
                    id = it[Alerts.id],
                    sessionId = it[Alerts.sessionId],
                    machineId = it[Alerts.machineId],
                    machineName = it[Machines.name],
                    status = it[Alerts.status],
                    urgency = it[Alerts.urgency],
                    summary = it[Alerts.summary],
                    raisedAt = it[Alerts.raisedAt],
                    resolvedReason = it[Alerts.resolvedReason],
                )
            }
    }
}
