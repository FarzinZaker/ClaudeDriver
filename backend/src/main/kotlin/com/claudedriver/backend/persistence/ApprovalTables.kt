package com.claudedriver.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Phase 2 approval + push tables (Flyway V3__approvals.sql). */

object ApprovalRequests : Table("approval_request") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val sessionId = uuid("session_id").nullable()
    val claudeSessionId = text("claude_session_id")
    val tool = text("tool")
    val summary = text("summary")
    val detail = text("detail")
    val status = text("status")
    val createdAt = timestamp("created_at")
    val decidedAt = timestamp("decided_at").nullable()
    val decidedBy = text("decided_by").nullable()
    val surface = text("surface").nullable()
    val decisionReason = text("decision_reason").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PushDevices : Table("push_device") {
    val id = uuid("id")
    val operatorId = uuid("operator_id")
    val token = text("token")
    val platform = text("platform")
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")
    override val primaryKey = PrimaryKey(id)
}
