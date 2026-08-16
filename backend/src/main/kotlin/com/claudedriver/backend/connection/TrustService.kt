package com.claudedriver.backend.connection

import com.claudedriver.backend.ca.DeviceCa
import com.claudedriver.backend.persistence.AgentConnections
import com.claudedriver.backend.persistence.DeviceCertificates
import com.claudedriver.backend.persistence.Machines
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

/** A verified agent identity resolved from a device-certificate fingerprint. */
data class ResolvedIdentity(val machineId: UUID, val deviceCertificateId: UUID)

/**
 * Maps a presented device-certificate fingerprint to an enrolled machine and manages connection
 * records. Fail-safe: anything not clearly `active` + `enrolled` resolves to null → refuse
 * (Constitution Principle I).
 */
class TrustService(private val db: Database) {

    /** Resolve an active cert for a currently-enrolled machine, or null (unenrolled/forged/expired/revoked). */
    fun resolve(fingerprint: String): ResolvedIdentity? = transaction(db) {
        val cert = DeviceCertificates.selectAll().where {
            (DeviceCertificates.fingerprint eq fingerprint) and (DeviceCertificates.status eq "active")
        }.firstOrNull() ?: return@transaction null

        if (Instant.now().isAfter(cert[DeviceCertificates.notAfter])) return@transaction null

        val machineId = cert[DeviceCertificates.machineId]
        val enrolled = Machines.selectAll().where {
            (Machines.id eq machineId) and (Machines.status eq "enrolled")
        }.any()
        if (!enrolled) return@transaction null

        ResolvedIdentity(machineId, cert[DeviceCertificates.id])
    }

    fun openConnection(identity: ResolvedIdentity, protocolVersion: String): UUID = transaction(db) {
        val id = UUID.randomUUID()
        AgentConnections.insert {
            it[AgentConnections.id] = id
            it[machineId] = identity.machineId
            it[deviceCertificateId] = identity.deviceCertificateId
            it[AgentConnections.protocolVersion] = protocolVersion
            it[connectedAt] = Instant.now()
            it[lastSeq] = 0
            it[state] = "connected"
        }
        id
    }

    fun closeConnection(connectionId: UUID) = transaction(db) {
        AgentConnections.update({ AgentConnections.id eq connectionId }) {
            it[state] = "disconnected"
            it[disconnectedAt] = Instant.now()
        }
        Unit
    }

    fun updateLastSeq(connectionId: UUID, seq: Long) = transaction(db) {
        AgentConnections.update({ AgentConnections.id eq connectionId }) {
            it[lastSeq] = seq
        }
        Unit
    }

    companion object {
        /**
         * Extract the client-certificate fingerprint from request headers.
         * Prod: the ALB verifies mTLS and forwards the client cert PEM in `x-amzn-mtls-clientcert`.
         * Local/test: an `x-client-cert-fingerprint` header carries the fingerprint directly.
         */
        fun fingerprintFromHeaders(albClientCertPem: String?, devFingerprint: String?): String? {
            if (!albClientCertPem.isNullOrBlank()) {
                return runCatching {
                    val pem = URLDecoder.decode(albClientCertPem, Charsets.UTF_8)
                    DeviceCa.fingerprintOf(DeviceCa.parseCertificate(pem))
                }.getOrNull()
            }
            return devFingerprint?.takeIf { it.isNotBlank() }
        }
    }
}
