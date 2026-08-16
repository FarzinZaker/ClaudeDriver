package com.claudedriver.backend.audit

import java.security.MessageDigest

/**
 * Tamper-evident audit hashing (Constitution Principle VI). Each event's hash chains the previous
 * hash with a canonical serialization of the row, so any retroactive edit/deletion breaks the chain.
 * Pure and side-effect free → unit-testable without a database.
 */
object AuditChain {
    /** Hash of the genesis (empty) chain. */
    val GENESIS: String = "0".repeat(64)

    /** SHA-256 over `prevHash || canonical`, hex-encoded. */
    fun hash(prevHash: String, canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(prevHash.toByteArray(Charsets.UTF_8))
        digest.update(0x1f) // unit separator so fields cannot be ambiguously concatenated
        digest.update(canonical.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic canonical string for a row's material fields. Order is fixed; callers must pass
     * fields in this exact order so verification is reproducible.
     */
    fun canonical(at: String, actor: String, action: String, subject: String, detail: String): String =
        listOf(at, actor, action, subject, detail).joinToString("")

    /** Recompute the chain over ordered (canonical, storedHash) rows; true iff every link matches. */
    fun verify(rows: List<Pair<String, String>>): Boolean {
        var prev = GENESIS
        for ((canonical, storedHash) in rows) {
            val expected = hash(prev, canonical)
            if (expected != storedHash) return false
            prev = expected
        }
        return true
    }
}
