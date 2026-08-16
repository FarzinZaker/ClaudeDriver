package com.claudedriver.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table mappings for the Flyway-owned schema (data-model.md / V1__init.sql).
 * Plain [Table]s with explicit UUID columns (no DAO/EntityID wrapping) to keep the query DSL simple.
 * Foreign keys are enforced by the database (V1__init.sql), not re-declared here.
 */

object Operators : Table("operator") {
    val id = uuid("id")
    val handle = text("handle")
    val createdAt = timestamp("created_at")
    val status = text("status")
    override val primaryKey = PrimaryKey(id)
}

object WebAuthnCredentials : Table("webauthn_credential") {
    val id = uuid("id")
    val operatorId = uuid("operator_id")
    val credentialId = binary("credential_id")
    val publicKey = binary("public_key")
    val signCount = long("sign_count")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Machines : Table("machine") {
    val id = uuid("id")
    val name = text("name")
    val os = text("os")
    val status = text("status")
    val enrolledAt = timestamp("enrolled_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object EnrollmentRequests : Table("enrollment_request") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val codeHash = text("code_hash")
    val status = text("status")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object DeviceCertificates : Table("device_certificate") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val serial = text("serial")
    val fingerprint = text("fingerprint")
    val notBefore = timestamp("not_before")
    val notAfter = timestamp("not_after")
    val status = text("status")
    override val primaryKey = PrimaryKey(id)
}

object AgentConnections : Table("agent_connection") {
    val id = uuid("id")
    val machineId = uuid("machine_id")
    val deviceCertificateId = uuid("device_certificate_id")
    val protocolVersion = text("protocol_version")
    val connectedAt = timestamp("connected_at")
    val disconnectedAt = timestamp("disconnected_at").nullable()
    val lastSeq = long("last_seq")
    val state = text("state")
    override val primaryKey = PrimaryKey(id)
}

object AuditEvents : Table("audit_event") {
    val id = long("id").autoIncrement()
    val at = timestamp("at")
    val actor = text("actor")
    val action = text("action")
    val subject = text("subject")
    val detail = text("detail")
    val prevHash = text("prev_hash")
    val hash = text("hash")
    override val primaryKey = PrimaryKey(id)
}
