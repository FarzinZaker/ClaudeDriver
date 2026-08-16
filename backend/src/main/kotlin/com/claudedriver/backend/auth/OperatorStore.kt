package com.claudedriver.backend.auth

import com.claudedriver.backend.persistence.Operators
import com.claudedriver.backend.persistence.WebAuthnCredentials
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

/** A stored passkey row. */
data class StoredCredential(
    val credentialId: ByteArray,
    val publicKeyCose: ByteArray,
    val signCount: Long,
    val operatorId: UUID,
)

/** DB access for the operator and their passkeys (self-hosted WebAuthn). */
class OperatorStore(private val db: Database) {

    fun operatorExists(): Boolean = transaction(db) {
        Operators.selectAll().limit(1).any()
    }

    /** The single operator's id, if bootstrapped. */
    fun soleOperator(): Pair<UUID, String>? = transaction(db) {
        Operators.selectAll().limit(1).map { it[Operators.id] to it[Operators.handle] }.firstOrNull()
    }

    fun createOperator(id: UUID, handle: String): UUID = transaction(db) {
        Operators.insert {
            it[Operators.id] = id
            it[Operators.handle] = handle
            it[createdAt] = Instant.now()
            it[status] = "active"
        }
        id
    }

    fun addCredential(operatorId: UUID, credentialId: ByteArray, publicKeyCose: ByteArray, signCount: Long) =
        transaction(db) {
            WebAuthnCredentials.insert {
                it[WebAuthnCredentials.id] = UUID.randomUUID()
                it[WebAuthnCredentials.operatorId] = operatorId
                it[WebAuthnCredentials.credentialId] = credentialId
                it[publicKey] = publicKeyCose
                it[WebAuthnCredentials.signCount] = signCount
                it[createdAt] = Instant.now()
            }
            Unit
        }

    fun credentialsForOperator(operatorId: UUID): List<StoredCredential> = transaction(db) {
        WebAuthnCredentials.selectAll().where { WebAuthnCredentials.operatorId eq operatorId }
            .map { row ->
                StoredCredential(
                    credentialId = row[WebAuthnCredentials.credentialId],
                    publicKeyCose = row[WebAuthnCredentials.publicKey],
                    signCount = row[WebAuthnCredentials.signCount],
                    operatorId = operatorId,
                )
            }
    }

    fun findCredential(credentialId: ByteArray): StoredCredential? = transaction(db) {
        WebAuthnCredentials.selectAll().where { WebAuthnCredentials.credentialId eq credentialId }
            .map { row ->
                StoredCredential(
                    credentialId = row[WebAuthnCredentials.credentialId],
                    publicKeyCose = row[WebAuthnCredentials.publicKey],
                    signCount = row[WebAuthnCredentials.signCount],
                    operatorId = row[WebAuthnCredentials.operatorId],
                )
            }.firstOrNull()
    }

    fun updateSignCount(credentialId: ByteArray, newCount: Long) = transaction(db) {
        WebAuthnCredentials.update({ WebAuthnCredentials.credentialId eq credentialId }) {
            it[signCount] = newCount
        }
        Unit
    }

    companion object {
        fun uuidToBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

        fun bytesToUuid(bytes: ByteArray): UUID =
            ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }
}
