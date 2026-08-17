package com.claudedriver.backend.managed

import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.persistence.Db
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.QuestionRaised
import com.claudedriver.protocol.TranscriptMessage
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

/** Testcontainers managed-session tests (skipped without Docker; run in CI). US1/US3. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedIntegrationTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var db: Database
    private lateinit var managed: ManagedService

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
        managed = ManagedService(db, AuditRepository(db), Publisher(OperatorHub()), AgentHub())
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
    fun `raise then answer a question at most once`() = runBlocking {
        val machineId = newMachine(); newSession(machineId, "s-1")
        val questionId = UUID.randomUUID()
        managed.raiseQuestion(machineId, QuestionRaised(questionId.toString(), "s-1", "Which region?", Instant.now().toString()))
        assertTrue(managed.listQuestions().any { it.id == questionId && it.status == "pending" })

        assertEquals(AnswerOutcome.ANSWERED, managed.answer(questionId, "eu-west", cancel = false, operator = "op"))
        assertEquals("answered", managed.listQuestions().first { it.id == questionId }.status)
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, managed.answer(questionId, "again", cancel = false, operator = "op"))
        assertEquals(AnswerOutcome.NOT_FOUND, managed.answer(UUID.randomUUID(), "x", cancel = false, operator = "op"))
    }

    @Test
    fun `cancel and mark-unanswered never fabricate an answer`() = runBlocking {
        val machineId = newMachine(); newSession(machineId, "s-3")
        val q1 = UUID.randomUUID()
        managed.raiseQuestion(machineId, QuestionRaised(q1.toString(), "s-3", "Proceed?", Instant.now().toString()))
        assertEquals(AnswerOutcome.CANCELLED, managed.answer(q1, null, cancel = true, operator = "op"))
        assertEquals("cancelled", managed.listQuestions().first { it.id == q1 }.status)

        val q2 = UUID.randomUUID()
        managed.raiseQuestion(machineId, QuestionRaised(q2.toString(), "s-3", "Q2?", Instant.now().toString()))
        managed.markUnansweredForSession(machineId, "s-3")
        val unanswered = managed.listQuestions().first { it.id == q2 }
        assertEquals("unanswered", unanswered.status)
        assertEquals(null, unanswered.answer) // never fabricated
    }

    @Test
    fun `transcript is stored and cross-session search finds a term`() = runBlocking {
        val machineId = newMachine(); val sessionId = newSession(machineId, "s-2")
        managed.storeTranscript(machineId, TranscriptMessage("s-2", "assistant", "Deploying to eu-west now", Instant.now().toString()))
        managed.storeTranscript(machineId, TranscriptMessage("s-2", "user", "ok", Instant.now().toString()))

        assertEquals(2, managed.transcript(sessionId).size)
        assertTrue(managed.search("EU-WEST").any { it.snippet.contains("eu-west") }, "search is case-insensitive")
    }
}
