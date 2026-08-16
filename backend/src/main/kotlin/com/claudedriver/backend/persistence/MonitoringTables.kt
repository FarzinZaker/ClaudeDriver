package com.claudedriver.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Phase 1 monitoring tables (Flyway V2__monitoring.sql). */

object Sessions : Table("session") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val claudeSessionId = text("claude_session_id")
    val projectPath = text("project_path").nullable()
    val state = text("state")
    val lastActivityAt = timestamp("last_activity_at")
    val processPresent = bool("process_present")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ActivityEvents : Table("activity_event") {
    val id = long("id").autoIncrement()
    val sessionId = uuid("session_id")
    val kind = text("kind")
    val attention = text("attention")
    val summary = text("summary")
    val detail = text("detail")
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(id)
}

object Alerts : Table("alert") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val machineId = uuid("machine_id")
    val status = text("status")
    val urgency = text("urgency")
    val summary = text("summary")
    val raisedAt = timestamp("raised_at")
    val acknowledgedAt = timestamp("acknowledged_at").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolvedReason = text("resolved_reason").nullable()
    override val primaryKey = PrimaryKey(id)
}
