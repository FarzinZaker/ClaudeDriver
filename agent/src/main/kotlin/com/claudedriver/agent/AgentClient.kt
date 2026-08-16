package com.claudedriver.agent

import com.claudedriver.protocol.ApprovalDecision
import com.claudedriver.protocol.ApprovalRequest
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.Envelope
import com.claudedriver.protocol.Hello
import com.claudedriver.protocol.HelloAck
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.PROTOCOL_VERSION
import com.claudedriver.protocol.Pong
import com.claudedriver.protocol.SampleEvent
import com.claudedriver.protocol.VersionMismatch
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.time.Instant
import kotlin.random.Random

@Serializable
private data class AgentEnrollRequest(val machineId: String, val enrollmentCode: String, val csr: String)

@Serializable
private data class AgentEnrollResponse(val deviceCertificate: String, val caChain: String, val notAfter: String)

private data class OutFrame(val type: String, val payload: JsonElement)

/**
 * The per-machine agent (Phase 0 + Phase 1). Enrolls, holds a resilient OUTBOUND WebSocket, and —
 * for monitoring — detects Claude Code processes (OSHI), runs a loopback hook receiver, installs the
 * managed Claude Code hooks, and forwards process snapshots + activity events over the one socket.
 */
class AgentClient(
    private val serverBaseUrl: String,
    private val storageDir: File,
    private val agentVersion: String = "0.2.0",
    private val hookReceiverPort: Int = 8765,
    private val settingsFile: File = HookInstaller.defaultSettingsFile(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val hookTokenEnvVar = "CLAUDEDRIVER_HOOK_TOKEN"

    // Outbound frames queued by the monitor/receiver, drained by the active session (single writer).
    private val outbound = Channel<OutFrame>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var seq = 0L

    // Approvals held on the machine, awaiting the operator's decision over the WSS.
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var connected = false

    /** Called by the loopback receiver for a blocking permission prompt; suspends until decided.
     *  Fail-safe: returns "deny" if disconnected or the wait is interrupted (Constitution I). */
    private suspend fun requestApproval(request: ApprovalRequest): String {
        if (!connected) return "deny" // can't reach the operator → deny, never auto-approve
        val deferred = CompletableDeferred<String>()
        pendingApprovals[request.requestId] = deferred
        emit(MessageType.APPROVAL_REQUEST, request)
        return try {
            deferred.await()
        } catch (e: Exception) {
            "deny"
        } finally {
            pendingApprovals.remove(request.requestId)
        }
    }

    private val keyFile get() = File(storageDir, "agent-key.pem")
    private val certFile get() = File(storageDir, "agent-cert.pem")
    private val caFile get() = File(storageDir, "ca-chain.pem")
    private val hookTokenFile get() = File(storageDir, "hook-token")

    private inline fun <reified T> emit(type: String, payload: T) {
        outbound.trySend(OutFrame(type, Codec.json.encodeToJsonElement(payload)))
    }

    /** Enroll with a one-time code + a fresh CSR; persist the issued certificate + key. */
    suspend fun enroll(machineId: String, code: String) {
        storageDir.mkdirs()
        val keyAndCsr = Crypto.generateKeyPairAndCsr(machineId)
        val response: HttpResponse = http.post("$serverBaseUrl/agent/enroll") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AgentEnrollRequest.serializer(), AgentEnrollRequest(machineId, code, keyAndCsr.csrPem)))
        }
        require(response.status.isSuccess()) { "Enrollment failed: ${response.status} ${response.bodyAsText()}" }
        val enrolled = json.decodeFromString(AgentEnrollResponse.serializer(), response.bodyAsText())
        keyFile.writeText(keyAndCsr.privateKeyPem)
        certFile.writeText(enrolled.deviceCertificate)
        caFile.writeText(enrolled.caChain)
        println("Enrolled machine $machineId. Certificate valid until ${enrolled.notAfter}.")
    }

    /** Set up monitoring, then hold the outbound WSS forever with exponential backoff + jitter. */
    suspend fun connectForever() = coroutineScope {
        require(certFile.exists()) { "Not enrolled: ${certFile.path} missing. Run `enroll` first." }
        val fingerprint = Crypto.fingerprint(certFile.readText())

        // Monitoring setup: token, managed Claude Code hooks, loopback receiver, process monitor.
        val hookToken = loadOrCreateHookToken()
        HookInstaller.installToFile(settingsFile, hookReceiverPort, hookTokenEnvVar)
        println("Installed Claude Code monitoring hooks → 127.0.0.1:$hookReceiverPort")
        println("Export this in the environment Claude Code runs in: export $hookTokenEnvVar=$hookToken")

        HookReceiver(
            port = hookReceiverPort,
            token = hookToken,
            onEvent = { activity -> emit(MessageType.ACTIVITY_EVENT, activity) },
            onApproval = { request -> requestApproval(request) },
        ).start()

        launch {
            ProcessMonitor().run { snapshot -> emit(MessageType.PROCESS_SNAPSHOT, snapshot) }
        }

        var attempt = 0
        while (true) {
            try {
                runSession(fingerprint)
                attempt = 0
            } catch (e: Exception) {
                attempt++
                val backoffMs = minOf(30_000L, 1000L * (1 shl minOf(attempt, 5))) + Random.nextLong(0, 500)
                println("Disconnected (${e.message}); reconnecting in ${backoffMs}ms (attempt $attempt)")
                delay(backoffMs)
            }
        }
    }

    private suspend fun runSession(fingerprint: String) = coroutineScope {
        val wsUrl = serverBaseUrl.replaceFirst("http", "ws") + "/agent/connect"
        http.webSocket(urlString = wsUrl, request = { header("x-client-cert-fingerprint", fingerprint) }) {
            // Handshake first (guarantees hello is frame #1), then start the single writer.
            send(Frame.Text(Codec.encode(Codec.envelope(MessageType.HELLO, ++seq, Hello("agent-host", agentVersion)))))
            val sender = launch {
                for (frame in outbound) {
                    send(Frame.Text(Codec.encode(Envelope(PROTOCOL_VERSION, frame.type, ++seq, null, frame.payload))))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val env = runCatching { Codec.decode(frame.readText()) }.getOrNull() ?: continue
                    when (env.type) {
                        MessageType.HELLO_ACK -> {
                            val ack = Codec.decodePayload<HelloAck>(env)
                            println("Connected as machine ${ack.machineId} (heartbeat ${ack.heartbeatSeconds}s)")
                            connected = true
                            emit(MessageType.SAMPLE_EVENT, SampleEvent(ack.machineId, "agent online", Instant.now().toString()))
                        }
                        MessageType.VERSION_MISMATCH -> {
                            val vm = Codec.decodePayload<VersionMismatch>(env)
                            println("Version mismatch: ${vm.reason} (server ${vm.serverVersion}); stopping.")
                            close()
                            return@webSocket
                        }
                        MessageType.PING -> emit(MessageType.PONG, Pong(Instant.now().toString()))

                        MessageType.APPROVAL_DECISION -> {
                            val decision = Codec.decodePayload<ApprovalDecision>(env)
                            pendingApprovals[decision.requestId]?.complete(decision.decision)
                        }
                    }
                }
            } finally {
                connected = false
                // Fail-safe: any prompt still waiting when the link drops is denied (never left open).
                pendingApprovals.values.forEach { it.complete("deny") }
                sender.cancel()
            }
        }
    }

    private fun loadOrCreateHookToken(): String {
        if (hookTokenFile.exists()) return hookTokenFile.readText().trim()
        val token = ByteArray(24).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        storageDir.mkdirs()
        hookTokenFile.writeText(token)
        return token
    }
}
