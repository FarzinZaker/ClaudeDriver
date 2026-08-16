package com.claudedriver.backend.enrollment

import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.ca.DeviceCa
import com.claudedriver.backend.ca.IssuedCertificate
import com.claudedriver.backend.persistence.AgentConnections
import com.claudedriver.backend.persistence.DeviceCertificates
import com.claudedriver.backend.persistence.EnrollmentRequests
import com.claudedriver.backend.persistence.Machines
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class EnrollmentException(message: String) : RuntimeException(message)

/** Operator-approved machine enrollment and device-identity issuance (Principle I/IV). */
class EnrollmentService(
    private val db: Database,
    private val ca: DeviceCa,
    private val audit: AuditRepository,
) {
    private val random = SecureRandom()

    /** Register a machine record in `pending` state. */
    fun createMachine(name: String, os: String): UUID {
        require(os == "windows" || os == "macos") { "os must be windows or macos" }
        val id = UUID.randomUUID()
        transaction(db) {
            Machines.insert {
                it[Machines.id] = id
                it[Machines.name] = name
                it[Machines.os] = os
                it[status] = "pending"
            }
        }
        return id
    }

    data class ApprovedEnrollment(val code: String, val expiresAt: Instant)

    /** Operator approves enrollment: mint a one-time code (only its hash is stored). */
    fun approveEnrollment(machineId: UUID, actor: String): ApprovedEnrollment {
        val code = randomCode()
        val expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)
        transaction(db) {
            val exists = Machines.selectAll().where { Machines.id eq machineId }.any()
            if (!exists) throw EnrollmentException("Unknown machine")
            EnrollmentRequests.insert {
                it[id] = UUID.randomUUID()
                it[EnrollmentRequests.machineId] = machineId
                it[codeHash] = sha256Hex(code)
                it[status] = "approved"
                it[EnrollmentRequests.expiresAt] = expiresAt
                it[createdAt] = Instant.now()
            }
        }
        audit.append(actor, AuditAction.ENROLLMENT_APPROVED, machineId.toString())
        return ApprovedEnrollment(code, expiresAt)
    }

    /** Agent consumes a valid code with a CSR → issues a device certificate; marks machine enrolled. */
    fun consumeEnrollment(machineId: UUID, code: String, csrPem: String): IssuedCertificate {
        val issued = ca.issueFromCsr(csrPem, machineId.toString())
        transaction(db) {
            val req = EnrollmentRequests.selectAll().where {
                (EnrollmentRequests.machineId eq machineId) and (EnrollmentRequests.status eq "approved")
            }.orderBy(EnrollmentRequests.createdAt, SortOrder.DESC).firstOrNull()
                ?: throw EnrollmentException("No approved enrollment")

            if (Instant.now().isAfter(req[EnrollmentRequests.expiresAt])) throw EnrollmentException("Enrollment code expired")
            if (req[EnrollmentRequests.codeHash] != sha256Hex(code)) throw EnrollmentException("Invalid enrollment code")

            DeviceCertificates.insert {
                it[id] = UUID.randomUUID()
                it[DeviceCertificates.machineId] = machineId
                it[serial] = issued.serial
                it[fingerprint] = issued.fingerprint
                it[notBefore] = issued.notBefore
                it[notAfter] = issued.notAfter
                it[status] = "active"
            }
            EnrollmentRequests.update({ EnrollmentRequests.id eq req[EnrollmentRequests.id] }) {
                it[status] = "consumed"
            }
            Machines.update({ Machines.id eq machineId }) {
                it[status] = "enrolled"
                it[enrolledAt] = Instant.now()
            }
        }
        audit.append("machine:$machineId", AuditAction.ENROLLMENT_CONSUMED, machineId.toString())
        return issued
    }

    /** Revoke a machine: mark revoked, revoke its certs, drop live connections. */
    fun revokeMachine(machineId: UUID, actor: String) {
        transaction(db) {
            Machines.update({ Machines.id eq machineId }) {
                it[status] = "revoked"
                it[revokedAt] = Instant.now()
            }
            DeviceCertificates.update({ DeviceCertificates.machineId eq machineId }) {
                it[status] = "revoked"
            }
            AgentConnections.update({ (AgentConnections.machineId eq machineId) and (AgentConnections.state eq "connected") }) {
                it[state] = "disconnected"
                it[disconnectedAt] = Instant.now()
            }
        }
        audit.append(actor, AuditAction.MACHINE_REVOKED, machineId.toString())
    }

    private fun randomCode(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
