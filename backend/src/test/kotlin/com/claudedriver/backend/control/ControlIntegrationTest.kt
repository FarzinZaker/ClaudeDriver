package com.claudedriver.backend.control

import com.claudedriver.backend.approvals.ApprovalService
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.Db
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.push.DeviceStore
import com.claudedriver.backend.push.LoggingPushSender
import com.claudedriver.backend.push.PushService
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.ApprovalRequest
import com.claudedriver.protocol.ControlResult
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

/** Testcontainers control-command tests (skipped without Docker; run in CI). US1/US3/US4. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlIntegrationTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var db: Database
    private lateinit var control: ControlService
    private lateinit var approvals: ApprovalService

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
        val audit = AuditRepository(db)
        approvals = ApprovalService(db, audit, publisher, AgentHub(), PushService(DeviceStore(db), LoggingPushSender()))
        control = ControlService(db, audit, publisher, AgentHub(), approvals)
    }

    @AfterAll
    fun teardown() {
        if (this::container.isInitialized) container.stop()
    }

    private fun newMachine(): UUID {
        val id = UUID.randomUUID()
        transaction(db) { Machines.insert { it[Machines.id] = id; it[name] = "dev"; it[os] = "macos"; it[status] = "enrolled" } }
        return id
    }

    private fun newSession(machineId: UUID, claudeSessionId: String): UUID {
        val id = UUID.randomUUID()
        transaction(db) {
            Sessions.insert {
                it[Sessions.id] = id; it[Sessions.machineId] = machineId; it[Sessions.claudeSessionId] = claudeSessionId
                it[projectPath] = "/p"; it[state] = "running"; it[lastActivityAt] = Instant.now()
                it[processPresent] = true; it[createdAt] = Instant.now()
            }
        }
        return id
    }

    @Test
    fun `issue records pending, a result is applied at most once`() = runBlocking {
        val machineId = newMachine()
        val commandId = control.issue("start_run", machineId, operator = "op")
        assertTrue(control.list().any { it.id == commandId && it.status == "pending" })

        control.applyResult(machineId, ControlResult(commandId.toString(), "started", "sess-x", null))
        assertEquals("started", control.list().first { it.id == commandId }.status)

        // A late/duplicate result is ignored (at-most-once).
        control.applyResult(machineId, ControlResult(commandId.toString(), "error", null, "late"))
        assertEquals("started", control.list().first { it.id == commandId }.status)
    }

    @Test
    fun `stopping a session moots its pending approvals`() = runBlocking {
        val machineId = newMachine()
        val claudeSid = "s-stop"
        val sessionId = newSession(machineId, claudeSid)
        approvals.raise(machineId, ApprovalRequest(UUID.randomUUID().toString(), claudeSid, "Bash", "x", "{}", "/p", Instant.now().toString()))
        assertTrue(approvals.list().any { it.status == "active" && it.machineId == machineId })

        val commandId = control.issue("stop_session", machineId, sessionId = sessionId, claudeSessionId = claudeSid, operator = "op")
        control.applyResult(machineId, ControlResult(commandId.toString(), "stopped", claudeSid, null))

        assertEquals("stopped", control.list().first { it.id == commandId }.status)
        assertEquals(0, approvals.list().count { it.status == "active" && it.machineId == machineId })
    }

    @Test
    fun `session target resolves a session's machine and claude id`() {
        val machineId = newMachine()
        val sessionId = newSession(machineId, "s-target")
        val target = control.sessionTarget(sessionId)!!
        assertEquals(machineId, target.machineId)
        assertEquals("s-target", target.claudeSessionId)
    }
}
