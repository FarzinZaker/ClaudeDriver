package com.claudedriver.agent

import com.claudedriver.protocol.ApprovalDecision
import com.claudedriver.protocol.ApprovalRequest
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.ControlCommand
import com.claudedriver.protocol.QuestionAnswer
import com.claudedriver.protocol.Envelope
import com.claudedriver.protocol.Hello
import com.claudedriver.protocol.HelloAck
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.PROTOCOL_VERSION
import com.claudedriver.protocol.Pong
import com.claudedriver.protocol.SampleEvent
import com.claudedriver.protocol.TerminalClosed
import com.claudedriver.protocol.TerminalInput
import com.claudedriver.protocol.TerminalOpened
import com.claudedriver.protocol.TerminalOutput
import com.claudedriver.protocol.VersionMismatch
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.network.tls.addKeyStore
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
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
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
    // Where the outbound WSS connects. In prod this is the ALB's mTLS listener (…:8443), separate
    // from serverBaseUrl (enrollment on :443, before any device cert exists). Defaults to the same
    // host for local dev, where there is no ALB and no client-cert requirement.
    private val connectBaseUrl: String = serverBaseUrl,
    private val agentVersion: String = "0.4.0",
    private val hookReceiverPort: Int = 8765,
    private val settingsFile: File = HookInstaller.defaultSettingsFile(),
    private val launcher: Launcher = ShellSessionLauncher(),
    private val companionLauncher: CompanionLauncher = PythonCompanionLauncher(),
) {
    private var sessionController: SessionController? = null
    private var managedController: ManagedSessionController? = null
    private var ptyBridge: PtyBridge? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Enrolled device cert + key, loaded as a client keystore for mutual TLS to the ALB. Null until
    // enrollment has written the PEM files (the enroll request itself runs over the non-mTLS :443).
    private val clientKeyStore: KeyStore? by lazy {
        if (certFile.exists() && keyFile.exists()) {
            runCatching { Crypto.clientKeyStore(keyFile.readText(), certFile.readText()) }.getOrNull()
        } else null
    }

    private val http = HttpClient(CIO) {
        install(WebSockets)
        engine {
            https {
                // Present the device certificate when connecting to the ALB mTLS listener. The ALB
                // server cert is publicly trusted (ACM), so no custom trust manager is needed.
                clientKeyStore?.let { addKeyStore(it, Crypto.KEYSTORE_PASSWORD) }
            }
        }
    }
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

    /** True once a device certificate + key have been persisted (enrollment completed). */
    fun isEnrolled(): Boolean = certFile.exists() && keyFile.exists()

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
        HookInstaller.installToFile(settingsFile, hookReceiverPort, hookToken)
        println("Installed Claude Code monitoring + approval hooks → 127.0.0.1:$hookReceiverPort (auth baked in)")

        HookReceiver(
            port = hookReceiverPort,
            token = hookToken,
            onEvent = { activity -> emit(MessageType.ACTIVITY_EVENT, activity) },
            onApproval = { request -> requestApproval(request) },
        ).start()

        launch {
            var lastSig = ""
            ProcessMonitor().run { snapshot ->
                val sig = snapshot.processes.joinToString { "${it.pid}:${it.projectPath}" }
                if (sig != lastSig) {
                    lastSig = sig
                    println("Detected ${snapshot.processes.size} Claude Code process(es): ${snapshot.processes.map { it.projectPath ?: "<no-cwd>" }}")
                }
                emit(MessageType.PROCESS_SNAPSHOT, snapshot)
            }
        }

        sessionController = SessionController(
            launcher = launcher,
            emitActivity = { activity -> emit(MessageType.ACTIVITY_EVENT, activity) },
            emitResult = { result -> emit(MessageType.CONTROL_RESULT, result) },
        )
        managedController = ManagedSessionController(
            launcher = companionLauncher,
            scope = this,
            emitActivity = { activity -> emit(MessageType.ACTIVITY_EVENT, activity) },
            emitResult = { result -> emit(MessageType.CONTROL_RESULT, result) },
            emitQuestion = { question -> emit(MessageType.QUESTION_RAISED, question) },
            emitTranscript = { message -> emit(MessageType.TRANSCRIPT_MESSAGE, message) },
        )

        // Put the transparent `claude` shim ahead of the real binary so ordinary sessions mirror
        // with nothing special to run. Only new shells pick it up (existing ones are untouched),
        // and it self-skips when no real claude is resolvable. Best-effort — never fatal.
        runCatching {
            val home = File(System.getProperty("user.home"))
            if (ShimInstaller.install(home)) {
                println("Installed transparent claude shim → ${ShimInstaller.shimFile(home).path} (new shells mirror automatically)")
            } else {
                println("Skipped claude shim install (no real claude found on PATH to wrap)")
            }
        }.onFailure { println("claude shim install skipped: ${it.message}") }

        // Live terminal: accept transparent `claude` shim connections and mirror them upstream.
        // The handshake file must land where the shim looks (~/.claudedriver), NOT the agent's
        // storage dir (the launchd plist points that at ~/.claudedriver-agent).
        ptyBridge = PtyBridge(
            endpointDir = File(System.getProperty("user.home"), ".claudedriver"),
            onOpen = { s -> emit(MessageType.TERMINAL_OPENED, TerminalOpened(s.sid, s.cwd, s.cols, s.rows, Instant.now().toString())) },
            onOutput = { sid, bytes -> emit(MessageType.TERMINAL_OUTPUT, TerminalOutput(sid, Base64.getEncoder().encodeToString(bytes), Instant.now().toString())) },
            onResize = { _, _, _ -> },
            onClose = { sid, code -> emit(MessageType.TERMINAL_CLOSED, TerminalClosed(sid, code, Instant.now().toString())) },
        ).also { println("PTY bridge listening for claude shims on 127.0.0.1:${it.start()}") }

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
        val wsUrl = connectBaseUrl.replaceFirst("http", "ws") + "/agent/connect"
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

                        MessageType.CONTROL_COMMAND -> {
                            val command = Codec.decodePayload<ControlCommand>(env)
                            if (command.type == "start_managed") {
                                launch { managedController?.startManaged(command) }
                            } else {
                                launch { sessionController?.handle(command) }
                            }
                        }

                        MessageType.QUESTION_ANSWER -> {
                            val answer = Codec.decodePayload<QuestionAnswer>(env)
                            launch { managedController?.answer(answer) }
                        }

                        MessageType.TERMINAL_INPUT -> {
                            val input = Codec.decodePayload<TerminalInput>(env)
                            val sid = input.terminalId.substringAfter(':')
                            runCatching { ptyBridge?.inject(sid, Base64.getDecoder().decode(input.dataB64)) }
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
