package com.claudedriver.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Phase 3 control-command table (Flyway V4__control.sql). */
object ControlCommands : Table("control_command") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val sessionId = uuid("session_id").nullable()
    val claudeSessionId = text("claude_session_id").nullable()
    val type = text("type")
    val projectPath = text("project_path").nullable()
    val instruction = text("instruction").nullable()
    val status = text("status")
    val resultMessage = text("result_message").nullable()
    val issuedBy = text("issued_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
