package com.claudedriver.backend

import com.claudedriver.agent.AgentClient
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.persistence.AgentConnections
import com.claudedriver.backend.persistence.Db
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * LIVE end-to-end smoke test: a real backend (Netty + Postgres, real V1+V2 migrations) and a real
 * agent talking over real HTTP + WebSocket, driven by a simulated Claude Code hook posted to the
 * agent's real loopback receiver. Proves: enroll → device-cert identity → connect → activity event →
 * session state → alert raised → auto-resolved.
 *
 * Only runs when SMOKE_DB_URL points at a Postgres (otherwise skipped). Does NOT touch the user's
 * ~/.claude settings (uses a temp settings file). Does NOT exercise WebAuthn or ALB mTLS (browser /
 * AWS only) — enrollment is seeded via the service and the dev fingerprint header path is used.
 */
class SmokeTest {

    @Test
    fun `live end-to-end monitoring smoke`() = runBlocking {
        val dbUrl = System.getenv("SMOKE_DB_URL")
        assumeTrue(dbUrl != null, "Set SMOKE_DB_URL (and optionally SMOKE_DB_USER/PASS) to run the live smoke test")

        val config = Config(
            env = "dev", host = "127.0.0.1", port = 18080,
            databaseUrl = dbUrl!!,
            databaseUser = System.getenv("SMOKE_DB_USER") ?: "claudedriver",
            databasePassword = System.getenv("SMOKE_DB_PASS") ?: "claudedriver",
            sessionSigningKey = "smoke-session-signing-key-00000000000000000",
            webAuthnRpId = "localhost", webAuthnRpName = "ClaudeDriver",
            webAuthnOrigin = "http://localhost:5173", operatorBootstrapCode = "boot",
        )
        val db = Db.connect(config)
        val deps = AppDeps.create(config, db.database)
        val server = embeddedServer(Netty, host = config.host, port = config.port) { module(deps) }.start(wait = false)
        val http = HttpClient(CIO)
        try {
            // 1) Seed an enrolled machine via the service (WebAuthn is browser-only; not under test).
            val machineId = deps.enrollment.createMachine("smoke-mac", "macos")
            val approved = deps.enrollment.approveEnrollment(machineId, "smoke-op")
            println("• seeded machine $machineId + approved enrollment")

            // 2) Real agent: enroll over HTTP, then connect over WSS (receiver + hooks → temp).
            val agentDir = Files.createTempDirectory("smoke-agent").toFile()
            val receiverPort = 18799
            // A fake launcher: a stdin-reading process that records delivered lines to a file, so we
            // can prove start-run → dispatch → stop over the real control channel.
            val dispatchLog = File(agentDir, "dispatch.log")
            val fakeLauncher = object : com.claudedriver.agent.Launcher {
                override fun launch(claudeSessionId: String, projectPath: String?, instruction: String?): Process =
                    ProcessBuilder("/bin/sh", "-c", "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '${dispatchLog.absolutePath}'; done")
                        .redirectErrorStream(true).start()
            }
            val agent = AgentClient(
                serverBaseUrl = "http://127.0.0.1:${config.port}",
                storageDir = agentDir,
                hookReceiverPort = receiverPort,
                settingsFile = File(agentDir, "claude-settings.json"),
                launcher = fakeLauncher,
            )
            agent.enroll(machineId.toString(), approved.code)
            println("• agent enrolled (received device certificate)")
            val agentJob = launch(Dispatchers.IO) { runCatching { agent.connectForever() } }

            // 3) Wait for the mutually-identified WSS connection to be accepted.
            waitUntil(15_000) {
                transaction(db.database) { AgentConnections.selectAll().where { AgentConnections.state eq "connected" }.any() }
            }
            println("• agent connected over WSS and was recognized ✅")

            val token = File(agentDir, "hook-token").readText().trim()
            suspend fun hook(body: String) =
                http.post("http://127.0.0.1:$receiverPort/hook") { header("Authorization", "Bearer $token"); setBody(body) }

            // 4) Simulate Claude Code: session start, then a permission prompt (needs attention).
            hook("""{"hook_event_name":"SessionStart","session_id":"smoke-1","cwd":"/tmp/proj"}""")
            hook("""{"hook_event_name":"Notification","session_id":"smoke-1","cwd":"/tmp/proj","notification_type":"permission_prompt"}""")

            waitUntil(15_000) { deps.alerts.list().any { it.status == "active" } }
            val alert = deps.alerts.list().first { it.status == "active" }
            check(deps.sessions.list().any { it.state == "waiting_for_operator" }) { "session should be waiting_for_operator" }
            println("• attention ALERT raised ✅  urgency=${alert.urgency} summary='${alert.summary}'")

            // 5) Simulate the operator answering (a subsequent tool event) → alert auto-resolves.
            hook("""{"hook_event_name":"PostToolUse","session_id":"smoke-1","cwd":"/tmp/proj"}""")
            waitUntil(15_000) { deps.alerts.list().none { it.status == "active" } }
            println("• alert AUTO-RESOLVED after the wait ended ✅")

            check(deps.alerts.list().any { it.status == "resolved" })

            // 7) Blocking approval — a real held PreToolUse hook → approval_request over WSS →
            //    decide → the held hook returns allow/deny. Prove BOTH.
            val approveHeld = async(Dispatchers.IO) {
                http.post("http://127.0.0.1:$receiverPort/approve") {
                    header("Authorization", "Bearer $token")
                    setBody("""{"hook_event_name":"PreToolUse","session_id":"smoke-appr-1","tool_name":"Bash","cwd":"/tmp/proj","tool_input":{"command":"git push"}}""")
                }.bodyAsText()
            }
            waitUntil(15_000) { deps.approvals.list().any { it.status == "pending" } }
            val pending1 = deps.approvals.list().first { it.status == "pending" }
            deps.approvals.decide(pending1.id, approve = true, operator = "smoke-op", surface = "web")
            val approveResp = approveHeld.await()
            check(approveResp.contains("\"permissionDecision\":\"allow\"")) { "approve should allow: $approveResp" }
            println("• blocking approval APPROVED → allow ✅")

            val denyHeld = async(Dispatchers.IO) {
                http.post("http://127.0.0.1:$receiverPort/approve") {
                    header("Authorization", "Bearer $token")
                    setBody("""{"hook_event_name":"PreToolUse","session_id":"smoke-appr-2","tool_name":"Bash","cwd":"/tmp/proj","tool_input":{"command":"rm -rf x"}}""")
                }.bodyAsText()
            }
            waitUntil(15_000) { deps.approvals.list().any { it.status == "pending" } }
            val pending2 = deps.approvals.list().first { it.status == "pending" }
            deps.approvals.decide(pending2.id, approve = false, operator = "smoke-op", surface = "web")
            val denyResp = denyHeld.await()
            check(denyResp.contains("\"permissionDecision\":\"deny\"")) { "deny should deny: $denyResp" }
            println("• blocking approval DENIED → deny ✅")

            // 8) Remote control — start a persistent run, dispatch a task, then stop. All over the
            //    real control channel; the fake launcher records the dispatched instruction.
            val startId = deps.control.issue("start_run", machineId, projectPath = "/tmp", instruction = "boot", operator = "smoke-op")
            waitUntil(15_000) { deps.control.list().any { it.id == startId && it.status == "started" } }
            val started = deps.control.list().first { it.id == startId }
            val managedSessionId = started.claudeSessionId!!
            println("• started persistent run ✅ session=$managedSessionId")

            val dispatchId = deps.control.issue("dispatch_task", machineId, claudeSessionId = managedSessionId, instruction = "do-the-work", operator = "smoke-op")
            waitUntil(15_000) { deps.control.list().any { it.id == dispatchId && it.status == "delivered" } }
            waitUntil(15_000) { dispatchLog.exists() && dispatchLog.readText().contains("do-the-work") }
            println("• dispatched task DELIVERED (managed process recorded it) ✅")

            val stopId = deps.control.issue("stop_session", machineId, claudeSessionId = managedSessionId, operator = "smoke-op")
            waitUntil(15_000) { deps.control.list().any { it.id == stopId && it.status == "stopped" } }
            println("• stopped the session ✅")

            println("\nSMOKE PASS — monitoring + alerts + approve/deny + start/dispatch/stop verified over real HTTP/WSS + Postgres.")
            agentJob.cancel()
        } finally {
            http.close()
            server.stop(500, 1000)
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            delay(200)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}
