package com.claudedriver.backend

import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.ca.DeviceCa
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.connection.TrustService
import com.claudedriver.backend.enrollment.EnrollmentException
import com.claudedriver.backend.enrollment.EnrollmentService
import com.claudedriver.backend.persistence.AuditEvents
import com.claudedriver.backend.persistence.Db
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.StringWriter
import java.security.KeyPairGenerator
import javax.security.auth.x500.X500Principal

/**
 * Database-backed integration tests (Testcontainers Postgres). Skipped when Docker is unavailable
 * (local dev); run in CI. Covers Phase 0 US2 (enrollment/trust/revocation), audit integrity
 * (Principle VI), and US1 unauthenticated-refusal + audit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var db: Database
    private lateinit var audit: AuditRepository
    private lateinit var ca: DeviceCa
    private lateinit var enrollment: EnrollmentService
    private lateinit var trust: TrustService
    private lateinit var config: Config

    @BeforeAll
    fun setup() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker not available; skipping integration tests")
        container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        config = testConfig(container.jdbcUrl, container.username, container.password)
        val handle = Db.connect(config)
        db = handle.database
        audit = AuditRepository(db)
        ca = DeviceCa.generate()
        enrollment = EnrollmentService(db, ca, audit)
        trust = TrustService(db)
    }

    @AfterAll
    fun teardown() {
        if (this::container.isInitialized) container.stop()
    }

    @Test
    fun `enroll issues a trusted identity - forged and revoked are refused - audit stays intact`() {
        val machineId = enrollment.createMachine("dev-1", "macos")
        val approved = enrollment.approveEnrollment(machineId, "operator")

        val issued = enrollment.consumeEnrollment(machineId, approved.code, csrPem(machineId.toString()))
        assertNotNull(trust.resolve(issued.fingerprint), "enrolled cert should resolve")
        assertNull(trust.resolve("deadbeef".repeat(8)), "forged fingerprint must be refused")

        enrollment.revokeMachine(machineId, "operator")
        assertNull(trust.resolve(issued.fingerprint), "revoked identity must be refused")

        assertTrue(audit.verifyChain(), "audit chain must remain intact")
    }

    @Test
    fun `an invalid enrollment code is refused`() {
        val machineId = enrollment.createMachine("dev-2", "windows")
        enrollment.approveEnrollment(machineId, "operator")
        assertThrows(EnrollmentException::class.java) {
            enrollment.consumeEnrollment(machineId, "wrong-code", csrPem(machineId.toString()))
        }
    }

    @Test
    fun `unauthenticated status is refused and audited`() {
        testApplication {
            application { module(AppDeps.create(config, db)) }
            val response = client.get("/status")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        val hasAuthFailure = transaction(db) {
            AuditEvents.selectAll().where {
                (AuditEvents.action eq "auth_failure") and (AuditEvents.subject eq "/status")
            }.any()
        }
        assertTrue(hasAuthFailure, "a refused /status must record an auth_failure audit event")
    }

    private fun testConfig(url: String, user: String, password: String) = Config(
        env = "test", host = "0.0.0.0", port = 8080,
        databaseUrl = url, databaseUser = user, databasePassword = password,
        sessionSigningKey = "test-session-signing-key-0000000000000000",
        webAuthnRpId = "localhost", webAuthnRpName = "ClaudeDriver",
        webAuthnOrigin = "http://localhost:5173", operatorBootstrapCode = "test-boot",
    )

    private fun csrPem(cn: String): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val builder = JcaPKCS10CertificationRequestBuilder(X500Principal("CN=$cn"), keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val csr = builder.build(signer)
        return StringWriter().also { sw -> JcaPEMWriter(sw).use { it.writeObject(csr) } }.toString()
    }
}
