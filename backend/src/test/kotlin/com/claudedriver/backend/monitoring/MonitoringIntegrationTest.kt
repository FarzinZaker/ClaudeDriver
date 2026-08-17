package com.claudedriver.backend.monitoring

import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.persistence.Db
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.DetectedProcess
import com.claudedriver.protocol.ProcessSnapshot
import org.jetbrains.exposed.sql.selectAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/** Testcontainers monitoring tests (skipped without Docker; run in CI). US1/US2/US3 core. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitoringIntegrationTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var db: Database
    private lateinit var registry: SessionRegistry
    private lateinit var alerts: AlertService

    @BeforeAll
    fun setup() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker not available")
        container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val config = Config(
            env = "test", host = "0.0.0.0", port = 8080,
            databaseUrl = container.jdbcUrl, databaseUser = container.username, databasePassword = container.password,
            sessionSigningKey = "k".repeat(40), webAuthnRpId = "localhost", webAuthnRpName = "ClaudeDriver",
            webAuthnOrigin = "http://localhost:5173", operatorBootstrapCode = "boot",
        )
        db = Db.connect(config).database
        val publisher = Publisher(OperatorHub())
        alerts = AlertService(db, AuditRepository(db), publisher)
        registry = SessionRegistry(db, AttentionClassifier(), alerts, publisher)
    }

    @AfterAll
    fun teardown() {
        if (this::container.isInitialized) container.stop()
    }

    private fun newMachine(): UUID {
        val id = UUID.randomUUID()
        transaction(db) {
            Machines.insert {
                it[Machines.id] = id; it[name] = "dev"; it[os] = "macos"; it[status] = "enrolled"
            }
        }
        return id
    }

    private fun event(sid: String, kind: String, notif: String? = null) =
        ActivityEvent(sid, kind, notif, "/proj", "summary", "{}", Instant.now().toString())

    @Test
    fun `a detected process becomes a running session and a later hook reconciles it`() = runBlocking {
        val machineId = newMachine()
        registry.applyProcessSnapshot(
            machineId,
            ProcessSnapshot(listOf(DetectedProcess(1234L, null, "/work/repo", Instant.now().toString()))),
        )
        val afterProc = transaction(db) {
            Sessions.selectAll().where { Sessions.machineId eq machineId }
                .map { Triple(it[Sessions.claudeSessionId], it[Sessions.state], it[Sessions.processPresent]) }
        }
        assertEquals(1, afterProc.size, "process detection should create a session")
        assertTrue(afterProc[0].first.startsWith("proc:"))
        assertTrue(afterProc[0].third, "processPresent")
        assertEquals(SessionState.RUNNING.wire, afterProc[0].second)

        // A hook event for the same project adopts that session instead of duplicating it.
        registry.applyActivityEvent(
            machineId,
            ActivityEvent("real-sid-1", "activity", null, "/work/repo", "did a thing", "{}", Instant.now().toString()),
        )
        val afterHook = transaction(db) {
            Sessions.selectAll().where { Sessions.machineId eq machineId }.map { it[Sessions.claudeSessionId] }
        }
        assertEquals(listOf("real-sid-1"), afterHook, "hook should reconcile, not duplicate")
    }

    @Test
    fun `a needs-attention wait raises exactly one alert and auto-resolves when answered`() = runBlocking {
        val machineId = newMachine()
        registry.applyActivityEvent(machineId, event("s1", "notification", "permission_prompt"))
        assertEquals(1, alerts.list().count { it.status == "active" })

        // A second identical waiting event does not raise a duplicate.
        registry.applyActivityEvent(machineId, event("s1", "notification", "permission_prompt"))
        assertEquals(1, alerts.list().count { it.status == "active" })

        // Answering (a subsequent routine event) auto-resolves the alert.
        registry.applyActivityEvent(machineId, event("s1", "tool"))
        assertEquals(0, alerts.list().count { it.status == "active" })
        assertTrue(alerts.list().any { it.status == "resolved" })
    }

    @Test
    fun `informational events raise no alert`() = runBlocking {
        val machineId = newMachine()
        registry.applyActivityEvent(machineId, event("s2", "session_start"))
        registry.applyActivityEvent(machineId, event("s2", "tool"))
        val sessionAlerts = alerts.list().filter { it.machineId == machineId }
        assertEquals(0, sessionAlerts.count { it.status == "active" })
    }

    @Test
    fun `acknowledging an active alert transitions it`() = runBlocking {
        val machineId = newMachine()
        registry.applyActivityEvent(machineId, event("s3", "notification", "idle_prompt"))
        val active = alerts.list().first { it.status == "active" && it.machineId == machineId }
        assertEquals(AckResult.OK, alerts.acknowledge(active.id))
        assertEquals("acknowledged", alerts.list().first { it.id == active.id }.status)
    }

    @Test
    fun `offline machine sessions become stale`() = runBlocking {
        val machineId = newMachine()
        registry.applyActivityEvent(machineId, event("s4", "session_start")) // running, no agent connection
        registry.sweepStale(thresholdSeconds = 0)
        val session = registry.list().first { it.machineId == machineId }
        assertEquals(SessionState.UNKNOWN_STALE.wire, session.state)
    }
}
