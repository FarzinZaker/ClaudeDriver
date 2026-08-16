package com.claudedriver.backend.approvals

import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.Db
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Operators
import com.claudedriver.backend.push.DeviceStore
import com.claudedriver.backend.push.LoggingPushSender
import com.claudedriver.backend.push.PushService
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.ApprovalRequest
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

/** Testcontainers approval-lifecycle tests (skipped without Docker; run in CI). US1/US4. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalIntegrationTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var db: Database
    private lateinit var approvals: ApprovalService
    private lateinit var deviceStore: DeviceStore
    private lateinit var pushSender: LoggingPushSender
    private var operatorId: UUID = UUID.randomUUID()

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
        deviceStore = DeviceStore(db)
        pushSender = LoggingPushSender()
        approvals = ApprovalService(db, AuditRepository(db), publisher, AgentHub(), PushService(deviceStore, pushSender))
        transaction(db) {
            Operators.insert { it[id] = operatorId; it[handle] = "op"; it[createdAt] = Instant.now(); it[status] = "active" }
        }
        deviceStore.register(operatorId, "tok-1", "ios")
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

    private fun req(sid: String, tool: String) =
        ApprovalRequest(UUID.randomUUID().toString(), sid, tool, "$tool: `x`", "{}", "/p", Instant.now().toString())

    @Test
    fun `raise notifies push, decide applies at most once`() = runBlocking {
        val machineId = newMachine()
        val request = req("s-1", "Bash")
        approvals.raise(machineId, request)

        assertTrue(approvals.list().any { it.id.toString() == request.requestId && it.status == "pending" })
        assertTrue(pushSender.sent.any { it.second.kind == "approval" }, "a push must be dispatched")

        val id = UUID.fromString(request.requestId)
        assertEquals(DecideOutcome.OK, approvals.decide(id, approve = true, operator = "op", surface = "web"))
        assertEquals("approved", approvals.list().first { it.id == id }.status)
        // Second decision is a no-op (at-most-once).
        assertEquals(DecideOutcome.ALREADY_RESOLVED, approvals.decide(id, approve = false, operator = "op", surface = "web"))
        // Unknown id.
        assertEquals(DecideOutcome.NOT_FOUND, approvals.decide(UUID.randomUUID(), approve = true, operator = "op", surface = "web"))
    }

    @Test
    fun `a stopped session moots its pending approvals`() = runBlocking {
        val machineId = newMachine()
        val request = req("s-moot", "Write")
        approvals.raise(machineId, request)
        approvals.mootForClaudeSession(machineId, "s-moot")
        assertEquals("moot", approvals.list().first { it.id.toString() == request.requestId }.status)
    }
}
