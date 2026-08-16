package com.claudedriver.backend

import com.claudedriver.backend.config.Config
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A no-database route test (runs anywhere). Database-backed flows (WebAuthn, enrollment, mTLS
 * rejection, sample-event relay, 401 + audit) are exercised as Testcontainers integration tests in
 * CI where Docker is available.
 */
class HealthRouteTest {

    private fun testConfig() = Config(
        env = "test",
        host = "0.0.0.0",
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/none",
        databaseUser = "none",
        databasePassword = "none",
        sessionSigningKey = "test-session-signing-key-0000000000000000",
        webAuthnRpId = "localhost",
        webAuthnRpName = "ClaudeDriver",
        webAuthnOrigin = "http://localhost:5173",
        operatorBootstrapCode = "test-boot",
    )

    // Lazy Exposed handle — never queried by /healthz, so no live database is required.
    private fun deps(): AppDeps = AppDeps.create(
        testConfig(),
        Database.connect(
            url = "jdbc:postgresql://localhost:5432/none",
            driver = "org.postgresql.Driver",
            user = "none",
            password = "none",
        ),
    )

    @Test
    fun `healthz returns ok without a database`() = testApplication {
        application { module(deps()) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }
}
