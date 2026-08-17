package com.claudedriver.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Phase 4 managed-session tables (Flyway V5__managed.sql). */

object Questions : Table("question") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val sessionId = uuid("session_id").nullable()
    val claudeSessionId = text("claude_session_id")
    val text = text("text")
    val status = text("status")
    val answer = text("answer").nullable()
    val createdAt = timestamp("created_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolvedBy = text("resolved_by").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TranscriptMessages : Table("transcript_message") {
    val id = long("id").autoIncrement()
    val machineId = uuid("machine_id")
    val sessionId = uuid("session_id").nullable()
    val claudeSessionId = text("claude_session_id")
    val role = text("role")
    val text = text("text")
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(id)
}
