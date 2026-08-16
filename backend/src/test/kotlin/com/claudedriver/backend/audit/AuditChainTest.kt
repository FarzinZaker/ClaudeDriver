package com.claudedriver.backend.audit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuditChainTest {

    private fun row(i: Int) = AuditChain.canonical("2026-08-16T00:00:0${i}Z", "op", "action$i", "subj$i", "{}")

    @Test
    fun `an intact chain verifies`() {
        val rows = mutableListOf<Pair<String, String>>()
        var prev = AuditChain.GENESIS
        for (i in 0..3) {
            val c = row(i)
            val h = AuditChain.hash(prev, c)
            rows.add(c to h)
            prev = h
        }
        assertTrue(AuditChain.verify(rows))
    }

    @Test
    fun `tampering with a row breaks the chain`() {
        val rows = mutableListOf<Pair<String, String>>()
        var prev = AuditChain.GENESIS
        for (i in 0..3) {
            val c = row(i)
            val h = AuditChain.hash(prev, c)
            rows.add(c to h)
            prev = h
        }
        // Retroactively edit the second event's content but keep its stored hash.
        rows[1] = AuditChain.canonical("2026-08-16T00:00:01Z", "attacker", "action1", "subj1", "{}") to rows[1].second
        assertFalse(AuditChain.verify(rows))
    }

    @Test
    fun `different previous hash yields a different chain hash`() {
        val c = row(1)
        assertNotEquals(AuditChain.hash(AuditChain.GENESIS, c), AuditChain.hash("f".repeat(64), c))
    }
}
