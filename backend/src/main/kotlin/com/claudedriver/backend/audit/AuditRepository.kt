package com.claudedriver.backend.audit

import com.claudedriver.backend.persistence.AuditEvents
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/** Actions recorded in the append-only audit trail (data-model.md). */
enum class AuditAction {
    ENROLLMENT_APPROVED, ENROLLMENT_CONSUMED, MACHINE_REVOKED,
    CONNECTION_ACCEPTED, CONNECTION_REFUSED, AUTH_SUCCESS, AUTH_FAILURE,
    ALERT_RAISED, ALERT_ACKNOWLEDGED, ALERT_RESOLVED,
    APPROVAL_RAISED, APPROVAL_DECIDED, APPROVAL_MOOT,
    CONTROL_ISSUED, CONTROL_RESULT,
}

/** Persists hash-chained audit events (Principle VI). */
class AuditRepository(private val db: Database) {

    /** Append one event, chaining it to the previous hash. Returns the new head hash. */
    fun append(actor: String, action: AuditAction, subject: String, detail: String = "{}"): String = transaction(db) {
        val prev = AuditEvents.selectAll()
            .orderBy(AuditEvents.id, SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.get(AuditEvents.hash) ?: AuditChain.GENESIS
        val at = Instant.now()
        val actionName = action.name.lowercase()
        val canonical = AuditChain.canonical(at.toString(), actor, actionName, subject, detail)
        val newHash = AuditChain.hash(prev, canonical)
        AuditEvents.insert {
            it[AuditEvents.at] = at
            it[AuditEvents.actor] = actor
            it[AuditEvents.action] = actionName
            it[AuditEvents.subject] = subject
            it[AuditEvents.detail] = detail
            it[prevHash] = prev
            it[hash] = newHash
        }
        newHash
    }

    /** Recompute the full chain; true iff intact (used by tests / integrity checks). */
    fun verifyChain(): Boolean = transaction(db) {
        val rows = AuditEvents.selectAll().orderBy(AuditEvents.id, SortOrder.ASC).map {
            AuditChain.canonical(
                it[AuditEvents.at].toString(),
                it[AuditEvents.actor],
                it[AuditEvents.action],
                it[AuditEvents.subject],
                it[AuditEvents.detail],
            ) to it[AuditEvents.hash]
        }
        AuditChain.verify(rows)
    }
}
