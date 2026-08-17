package com.claudedriver.backend.auth

import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.security.SecureRandom
import java.util.UUID

/** bcrypt password hashing (BouncyCastle). */
object Passwords {
    private val rng = SecureRandom()
    private const val COST = 12

    fun hash(password: String): String {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, COST)
    }

    fun verify(storedHash: String, password: String): Boolean =
        runCatching { OpenBSDBCrypt.checkPassword(storedHash, password.toCharArray()) }.getOrDefault(false)
}

/**
 * Username + password operator authentication. Single-operator: registration is gated by the
 * bootstrap code and allowed only until the first operator exists (enforced at the route).
 */
class PasswordAuthService(private val store: OperatorStore) {

    data class AuthResult(val operatorId: UUID, val handle: String)

    fun register(username: String, password: String): AuthResult {
        val id = store.claimOrCreateOperator(username, Passwords.hash(password))
        return AuthResult(id, username)
    }

    /** Returns the operator on a correct username+password, else null (do not distinguish which was wrong). */
    fun login(username: String, password: String): AuthResult? {
        val rec = store.findByHandle(username) ?: return null
        val hash = rec.passwordHash ?: return null
        return if (Passwords.verify(hash, password)) AuthResult(rec.id, rec.handle) else null
    }
}
