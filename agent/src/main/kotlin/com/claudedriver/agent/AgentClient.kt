package com.claudedriver.agent

import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.Hello
import com.claudedriver.protocol.HelloAck
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.Pong
import com.claudedriver.protocol.SampleEvent
import com.claudedriver.protocol.VersionMismatch
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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.random.Random

@Serializable
private data class AgentEnrollRequest(val machineId: String, val enrollmentCode: String, val csr: String)

@Serializable
private data class AgentEnrollResponse(val deviceCertificate: String, val caChain: String, val notAfter: String)

/**
 * The per-machine agent. Phase 0 scope: enroll (obtain a device certificate) and hold a resilient
 * OUTBOUND WebSocket to the backend, proving the contract end-to-end with a sample event.
 * (Process monitoring via OSHI is Phase 1.)
 */
class AgentClient(
    private val serverBaseUrl: String,
    private val storageDir: File,
    private val agentVersion: String = "0.1.0",
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient(CIO) { install(WebSockets) }

    private val keyFile get() = File(storageDir, "agent-key.pem")
    private val certFile get() = File(storageDir, "agent-cert.pem")
    private val caFile get() = File(storageDir, "ca-chain.pem")

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

    /** Hold an outbound WSS connection with exponential backoff + jitter (Principle V). */
    suspend fun connectForever() {
        require(certFile.exists()) { "Not enrolled: ${certFile.path} missing. Run `enroll` first." }
        val fingerprint = Crypto.fingerprint(certFile.readText())
        var attempt = 0
        while (true) {
            try {
                runSession(fingerprint)
                attempt = 0 // clean disconnect → reset backoff
            } catch (e: Exception) {
                attempt++
                val backoffMs = minOf(30_000L, (1000L * (1 shl minOf(attempt, 5)))) + Random.nextLong(0, 500)
                println("Disconnected (${e.message}); reconnecting in ${backoffMs}ms (attempt $attempt)")
                delay(backoffMs)
            }
        }
    }

    private suspend fun runSession(fingerprint: String) {
        val wsUrl = serverBaseUrl.replaceFirst("http", "ws") + "/agent/connect"
        var seq = 0L
        http.webSocket(urlString = wsUrl, request = { header("x-client-cert-fingerprint", fingerprint) }) {
            // Handshake: say hello.
            send(Frame.Text(Codec.encode(Codec.envelope(MessageType.HELLO, ++seq, Hello("agent-host", agentVersion)))))

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val env = runCatching { Codec.decode(frame.readText()) }.getOrNull() ?: continue
                when (env.type) {
                    MessageType.HELLO_ACK -> {
                        val ack = Codec.decodePayload<HelloAck>(env)
                        println("Connected as machine ${ack.machineId} (heartbeat ${ack.heartbeatSeconds}s)")
                        // Prove the contract end-to-end (US3): emit one sample event.
                        val sample = SampleEvent(ack.machineId, "hello from the agent", Instant.now().toString())
                        send(Frame.Text(Codec.encode(Codec.envelope(MessageType.SAMPLE_EVENT, ++seq, sample))))
                    }
                    MessageType.VERSION_MISMATCH -> {
                        val vm = Codec.decodePayload<VersionMismatch>(env)
                        println("Version mismatch: ${vm.reason} (server ${vm.serverVersion}); stopping.")
                        close()
                        return@webSocket
                    }
                    MessageType.PING ->
                        send(Frame.Text(Codec.encode(Codec.envelope(MessageType.PONG, ++seq, Pong(Instant.now().toString())))))
                }
            }
        }
    }
}
