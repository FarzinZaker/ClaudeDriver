package com.claudedriver.backend

import com.claudedriver.backend.api.AgentEnrollRequest
import com.claudedriver.backend.api.AgentEnrollResponse
import com.claudedriver.backend.api.CreateMachineRequest
import com.claudedriver.backend.api.CreateMachineResponse
import com.claudedriver.backend.api.EnrollmentApprovedResponse
import com.claudedriver.backend.api.ErrorResponse
import com.claudedriver.backend.api.RegisterOptionsRequest
import com.claudedriver.backend.api.WhoAmIResponse
import com.claudedriver.backend.api.AlertsResponse
import com.claudedriver.backend.api.ApprovalsResponse
import com.claudedriver.backend.api.CommandAcceptedResponse
import com.claudedriver.backend.api.CommandsResponse
import com.claudedriver.backend.api.DecideRequest
import com.claudedriver.backend.api.DecideResponse
import com.claudedriver.backend.api.DeviceRegisterRequest
import com.claudedriver.backend.api.DispatchRequest
import com.claudedriver.backend.api.AnswerRequest
import com.claudedriver.backend.api.AnswerResponse
import com.claudedriver.backend.api.QuestionsResponse
import com.claudedriver.backend.api.RotateCertResponse
import com.claudedriver.backend.api.SearchResponse
import com.claudedriver.backend.api.SessionsResponse
import com.claudedriver.backend.api.StartRunRequest
import com.claudedriver.backend.api.TranscriptResponse
import com.claudedriver.backend.api.toDto
import com.claudedriver.backend.approvals.DecideOutcome
import com.claudedriver.backend.managed.AnswerOutcome
import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.connection.TrustService
import com.claudedriver.backend.enrollment.EnrollmentException
import com.claudedriver.backend.monitoring.AckResult
import com.claudedriver.backend.ws.OutFrame
import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ApprovalRequest
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.ControlResult
import com.claudedriver.protocol.QuestionRaised
import com.claudedriver.protocol.TranscriptMessage
import com.claudedriver.protocol.Envelope
import com.claudedriver.protocol.HelloAck
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.PROTOCOL_VERSION
import com.claudedriver.protocol.Pong
import com.claudedriver.protocol.ProcessSnapshot
import com.claudedriver.protocol.ProtocolVersion
import com.claudedriver.protocol.SampleEvent
import com.claudedriver.protocol.VersionMismatch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentDisposition
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.server.http.content.singlePageApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class OperatorSession(val operatorId: String, val handle: String)

@Serializable
data class ChallengeSession(val token: String)

/** Refuse + audit if there is no authenticated operator; otherwise return the session. */
private suspend fun RoutingContext.requireOperator(deps: AppDeps): OperatorSession? {
    val session = call.sessions.get<OperatorSession>()
    if (session == null) {
        deps.audit.append("anonymous", AuditAction.AUTH_FAILURE, call.request.path())
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))
    }
    return session
}

fun Application.configureRouting(deps: AppDeps) = routing {
    get("/healthz") {
        call.respondText("""{"status":"ok","version":"$PROTOCOL_VERSION"}""", ContentType.Application.Json)
    }

    // Cross-platform agent distribution (bundled into the image at AGENT_DIST_PATH). Not secret —
    // the enrollment code + device-CA mTLS are the security boundary, not the binary.
    get("/download/agent.zip") {
        val dist = java.io.File(
            System.getenv("AGENT_DIST_PATH")?.takeIf { it.isNotBlank() } ?: "downloads/agent.zip",
        )
        if (dist.isFile) {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "claudedriver-agent.zip")
                    .toString(),
            )
            call.respondFile(dist)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_bundled", "Agent distribution is not available"))
        }
    }

    // ---- Operator WebAuthn (self-hosted passkeys) ----
    post("/auth/register/options") {
        val req = call.receive<RegisterOptionsRequest>()
        if (deps.operatorStore.operatorExists()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("already_registered", "An operator already exists"))
            return@post
        }
        if (req.bootstrapCode != deps.config.operatorBootstrapCode) {
            deps.audit.append("anonymous", AuditAction.AUTH_FAILURE, "register")
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bad_bootstrap", "Invalid bootstrap code"))
            return@post
        }
        val challenge = deps.webAuthn.startRegistration(req.handle)
        call.sessions.set(ChallengeSession(challenge.token))
        call.respondText(challenge.json, ContentType.Application.Json)
    }

    post("/auth/register/verify") {
        val token = call.sessions.get<ChallengeSession>()?.token
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("no_challenge", "No pending registration"))
        val body = call.receiveText()
        val auth = deps.webAuthn.finishRegistration(token, body)
        call.sessions.set(OperatorSession(auth.operatorId.toString(), auth.handle))
        call.sessions.clear<ChallengeSession>()
        deps.audit.append(auth.handle, AuditAction.AUTH_SUCCESS, "registration")
        call.respond(HttpStatusCode.Created, WhoAmIResponse(auth.operatorId.toString(), "active"))
    }

    post("/auth/login/options") {
        val challenge = try {
            deps.webAuthn.startAssertion()
        } catch (e: IllegalStateException) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("no_operator", e.message ?: "No operator"))
        }
        call.sessions.set(ChallengeSession(challenge.token))
        call.respondText(challenge.json, ContentType.Application.Json)
    }

    post("/auth/login/verify") {
        val token = call.sessions.get<ChallengeSession>()?.token
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("no_challenge", "No pending login"))
        val body = call.receiveText()
        val auth = try {
            deps.webAuthn.finishAssertion(token, body)
        } catch (e: Exception) {
            deps.audit.append("anonymous", AuditAction.AUTH_FAILURE, "login")
            return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("auth_failed", "Login failed"))
        }
        call.sessions.set(OperatorSession(auth.operatorId.toString(), auth.handle))
        deps.audit.append(auth.handle, AuditAction.AUTH_SUCCESS, "login")
        call.respond(HttpStatusCode.OK, WhoAmIResponse(auth.operatorId.toString(), "active"))
    }

    post("/auth/logout") {
        call.sessions.clear<OperatorSession>()
        call.respond(HttpStatusCode.NoContent)
    }

    // ---- Operator: status, machines, enrollment (session-authenticated) ----
    get("/status") {
        requireOperator(deps) ?: return@get
        call.respond(deps.status.status())
    }

    post("/machines") {
        requireOperator(deps) ?: return@post
        val req = call.receive<CreateMachineRequest>()
        val id = deps.enrollment.createMachine(req.name, req.os)
        call.respond(HttpStatusCode.Created, CreateMachineResponse(id.toString()))
    }

    post("/machines/{id}/enrollment") {
        val op = requireOperator(deps) ?: return@post
        val id = UUID.fromString(call.parameters["id"])
        val approved = deps.enrollment.approveEnrollment(id, op.handle)
        call.respond(
            HttpStatusCode.Created,
            EnrollmentApprovedResponse(approved.code, approved.expiresAt.toString()),
        )
    }

    post("/machines/{id}/revoke") {
        val op = requireOperator(deps) ?: return@post
        val id = UUID.fromString(call.parameters["id"])
        deps.enrollment.revokeMachine(id, op.handle)
        call.respond(HttpStatusCode.NoContent)
    }

    // ---- Operator: monitoring (sessions & alerts) ----
    get("/sessions") {
        requireOperator(deps) ?: return@get
        call.respond(SessionsResponse(deps.sessions.list().map { it.toDto() }))
    }

    get("/sessions/{id}") {
        requireOperator(deps) ?: return@get
        val detail = deps.sessions.detail(UUID.fromString(call.parameters["id"]))
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such session"))
        } else {
            call.respond(detail.toDto())
        }
    }

    get("/alerts") {
        requireOperator(deps) ?: return@get
        call.respond(AlertsResponse(deps.alerts.list().map { it.toDto() }))
    }

    post("/alerts/{id}/ack") {
        requireOperator(deps) ?: return@post
        when (deps.alerts.acknowledge(UUID.fromString(call.parameters["id"]))) {
            AckResult.OK -> call.respond(HttpStatusCode.NoContent)
            AckResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such alert"))
            AckResult.NOT_ACTIVE -> call.respond(HttpStatusCode.Conflict, ErrorResponse("not_active", "Alert is not active"))
        }
    }

    // ---- Operator: approvals ----
    get("/approvals") {
        requireOperator(deps) ?: return@get
        call.respond(ApprovalsResponse(deps.approvals.list().map { it.toDto() }))
    }

    post("/approvals/{id}/decide") {
        val op = requireOperator(deps) ?: return@post
        val decision = call.receive<DecideRequest>().decision
        val approve = when (decision) {
            "approve" -> true
            "deny" -> false
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_decision", "decision must be approve or deny"))
        }
        when (deps.approvals.decide(UUID.fromString(call.parameters["id"]), approve, op.handle, "web")) {
            DecideOutcome.OK -> call.respond(HttpStatusCode.OK, DecideResponse(if (approve) "approved" else "denied"))
            DecideOutcome.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such approval"))
            DecideOutcome.ALREADY_RESOLVED -> call.respond(HttpStatusCode.Conflict, ErrorResponse("already_resolved", "Approval already resolved"))
        }
    }

    // ---- Operator: remote control (dispatch / start-run / stop) ----
    post("/sessions/{id}/dispatch") {
        val op = requireOperator(deps) ?: return@post
        val instruction = call.receive<DispatchRequest>().instruction
        val target = deps.control.sessionTarget(UUID.fromString(call.parameters["id"]))
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such session"))
        if (!deps.control.isConnected(target.machineId)) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("offline", "Machine is offline"))
        }
        val commandId = deps.control.issue(
            type = "dispatch_task", machineId = target.machineId,
            sessionId = UUID.fromString(call.parameters["id"]), claudeSessionId = target.claudeSessionId,
            instruction = instruction, operator = op.handle,
        )
        call.respond(HttpStatusCode.Accepted, CommandAcceptedResponse(commandId.toString(), "pending"))
    }

    post("/machines/{id}/start-run") {
        val op = requireOperator(deps) ?: return@post
        val req = call.receive<StartRunRequest>()
        val machineId = UUID.fromString(call.parameters["id"])
        if (!deps.control.isConnected(machineId)) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("offline", "Machine is offline"))
        }
        val commandId = deps.control.issue(
            type = "start_run", machineId = machineId,
            projectPath = req.projectPath, instruction = req.instruction, operator = op.handle,
        )
        call.respond(HttpStatusCode.Accepted, CommandAcceptedResponse(commandId.toString(), "pending"))
    }

    post("/sessions/{id}/stop") {
        val op = requireOperator(deps) ?: return@post
        val target = deps.control.sessionTarget(UUID.fromString(call.parameters["id"]))
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such session"))
        val commandId = deps.control.issue(
            type = "stop_session", machineId = target.machineId,
            sessionId = UUID.fromString(call.parameters["id"]), claudeSessionId = target.claudeSessionId,
            operator = op.handle,
        )
        call.respond(HttpStatusCode.Accepted, CommandAcceptedResponse(commandId.toString(), "pending"))
    }

    get("/commands") {
        requireOperator(deps) ?: return@get
        call.respond(CommandsResponse(deps.control.list().map { it.toDto() }))
    }

    // ---- Operator: managed sessions (questions / transcript / search) ----
    post("/machines/{id}/start-managed") {
        val op = requireOperator(deps) ?: return@post
        val req = call.receive<StartRunRequest>()
        val machineId = UUID.fromString(call.parameters["id"])
        if (!deps.control.isConnected(machineId)) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("offline", "Machine is offline"))
        }
        val commandId = deps.control.issue(
            type = "start_managed", machineId = machineId,
            projectPath = req.projectPath, instruction = req.instruction, operator = op.handle,
        )
        call.respond(HttpStatusCode.Accepted, CommandAcceptedResponse(commandId.toString(), "pending"))
    }

    get("/questions") {
        requireOperator(deps) ?: return@get
        call.respond(QuestionsResponse(deps.managed.listQuestions().map { it.toDto() }))
    }

    post("/questions/{id}/answer") {
        val op = requireOperator(deps) ?: return@post
        val req = call.receive<AnswerRequest>()
        when (deps.managed.answer(UUID.fromString(call.parameters["id"]), req.answer, req.cancel, op.handle)) {
            AnswerOutcome.ANSWERED -> call.respond(HttpStatusCode.OK, AnswerResponse("answered"))
            AnswerOutcome.CANCELLED -> call.respond(HttpStatusCode.OK, AnswerResponse("cancelled"))
            AnswerOutcome.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such question"))
            AnswerOutcome.ALREADY_RESOLVED -> call.respond(HttpStatusCode.Conflict, ErrorResponse("already_resolved", "Question already resolved"))
        }
    }

    get("/sessions/{id}/transcript") {
        requireOperator(deps) ?: return@get
        call.respond(TranscriptResponse(deps.managed.transcript(UUID.fromString(call.parameters["id"])).map { it.toDto() }))
    }

    get("/search") {
        requireOperator(deps) ?: return@get
        val q = call.request.queryParameters["q"] ?: ""
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        call.respond(SearchResponse(deps.managed.search(q, limit).map { it.toDto() }))
    }

    // ---- Operator: hardening ----
    post("/machines/{id}/rotate-cert") {
        val op = requireOperator(deps) ?: return@post
        val rotated = deps.enrollment.rotateDeviceCert(UUID.fromString(call.parameters["id"]), op.handle)
        call.respond(HttpStatusCode.Created, RotateCertResponse(rotated.code, rotated.expiresAt.toString()))
    }

    // ---- Operator: push devices ----
    post("/devices") {
        val op = requireOperator(deps) ?: return@post
        val req = call.receive<DeviceRegisterRequest>()
        deps.devices.register(UUID.fromString(op.operatorId), req.token, req.platform)
        call.respond(HttpStatusCode.Created)
    }

    delete("/devices/{token}") {
        requireOperator(deps) ?: return@delete
        deps.devices.unregister(call.parameters["token"] ?: "")
        call.respond(HttpStatusCode.NoContent)
    }

    // ---- Agent: enrollment & identity (mutual-TLS listener) ----
    post("/agent/enroll") {
        val req = call.receive<AgentEnrollRequest>()
        try {
            val issued = deps.enrollment.consumeEnrollment(UUID.fromString(req.machineId), req.enrollmentCode, req.csr)
            call.respond(
                HttpStatusCode.Created,
                AgentEnrollResponse(issued.certificatePem, issued.caChainPem, issued.notAfter.toString()),
            )
        } catch (e: EnrollmentException) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("enrollment_denied", e.message ?: "denied"))
        }
    }

    get("/agent/whoami") {
        val fp = TrustService.fingerprintFromHeaders(
            call.request.header("x-amzn-mtls-clientcert"),
            call.request.header("x-client-cert-fingerprint"),
        )
        val identity = fp?.let { deps.trust.resolve(it) }
        if (identity == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "No valid device identity"))
        } else {
            call.respond(WhoAmIResponse(identity.machineId.toString(), "enrolled"))
        }
    }

    // ---- Operator WebSocket: receive live sample events ----
    webSocket("/ws/operator") {
        if (call.sessions.get<OperatorSession>() == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))
            return@webSocket
        }
        deps.hub.addOperator(this)
        try {
            for (frame in incoming) { /* operators are receive-only in Phase 0 */ }
        } finally {
            deps.hub.removeOperator(this)
        }
    }

    // ---- Agent WebSocket: negotiate + relay sample events ----
    webSocket("/agent/connect") {
        handleAgentConnect(deps)
    }

    // ---- Operator dashboard (React SPA) ----
    // Served same-origin so the WebAuthn RP ID / origin match the API host. The built
    // frontend lives at WEB_ROOT (default "web" → /app/web in the container image). The
    // explicit API and WebSocket routes above are more specific and take precedence;
    // any unmatched path falls back to index.html for client-side routing.
    val webRoot = System.getenv("WEB_ROOT")?.takeIf { it.isNotBlank() } ?: "web"
    if (java.io.File(webRoot).isDirectory) {
        singlePageApplication {
            filesPath = webRoot
            defaultPage = "index.html"
        }
    }
}

/** Agent connection lifecycle: mTLS identity → version negotiation → relay (contracts/protocol.md). */
private suspend fun DefaultWebSocketServerSession.handleAgentConnect(deps: AppDeps) {
    val fp = TrustService.fingerprintFromHeaders(
        call.request.header("x-amzn-mtls-clientcert"),
        call.request.header("x-client-cert-fingerprint"),
    )
    val identity = fp?.let { deps.trust.resolve(it) }
    if (identity == null) {
        deps.audit.append("agent", AuditAction.CONNECTION_REFUSED, fp ?: "unknown", """{"reason":"unenrolled_or_forged"}""")
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unenrolled"))
        return
    }

    val firstFrame = incoming.receiveCatching().getOrNull() as? Frame.Text
    val hello = firstFrame?.let { runCatching { Codec.decode(it.readText()) }.getOrNull() }
    if (hello == null || hello.type != MessageType.HELLO) {
        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "expected hello"))
        return
    }

    var seq = 0L
    if (!Codec.isCompatible(hello)) {
        val mismatch = Codec.envelope(
            MessageType.VERSION_MISMATCH,
            ++seq,
            VersionMismatch(PROTOCOL_VERSION, "requires MAJOR ${ProtocolVersion.CURRENT.major}"),
        )
        send(Frame.Text(Codec.encode(mismatch)))
        deps.audit.append("machine:${identity.machineId}", AuditAction.CONNECTION_REFUSED, identity.machineId.toString(), """{"reason":"version_mismatch"}""")
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "version mismatch"))
        return
    }

    val connectionId = deps.trust.openConnection(identity, hello.protocolVersion)
    deps.audit.append("machine:${identity.machineId}", AuditAction.CONNECTION_ACCEPTED, identity.machineId.toString())

    // Single writer: a bounded outbound channel drained by a sender coroutine, so the backend can
    // route frames (e.g. approval decisions) to this specific agent via the AgentHub.
    val outSeq = AtomicLong(seq)
    val outbound = Channel<OutFrame>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    deps.agentHub.register(identity.machineId, outbound)
    val sender = launch {
        for (frame in outbound) {
            send(Frame.Text(Codec.encode(Envelope(PROTOCOL_VERSION, frame.type, outSeq.incrementAndGet(), null, frame.payload))))
        }
    }

    outbound.trySend(OutFrame(MessageType.HELLO_ACK, Codec.json.encodeToJsonElement(HelloAck(identity.machineId.toString(), Instant.now().toString(), 30))))

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val env = runCatching { Codec.decode(frame.readText()) }.getOrNull() ?: continue
            when (env.type) {
                MessageType.PING ->
                    outbound.trySend(OutFrame(MessageType.PONG, Codec.json.encodeToJsonElement(Pong(Instant.now().toString()))))

                MessageType.SAMPLE_EVENT -> {
                    val sample = runCatching { Codec.decodePayload<SampleEvent>(env) }.getOrNull() ?: continue
                    deps.hub.recordAndSnapshotRecent(sample)
                    deps.hub.broadcast(Codec.encode(env)) // relay unchanged to operators
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.PROCESS_SNAPSHOT -> {
                    val snap = runCatching { Codec.decodePayload<ProcessSnapshot>(env) }.getOrNull() ?: continue
                    deps.sessions.applyProcessSnapshot(identity.machineId, snap)
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.ACTIVITY_EVENT -> {
                    val activity = runCatching { Codec.decodePayload<ActivityEvent>(env) }.getOrNull() ?: continue
                    deps.sessions.applyActivityEvent(identity.machineId, activity)
                    if (activity.kind == "stop" || activity.kind == "session_end") {
                        deps.approvals.mootForClaudeSession(identity.machineId, activity.claudeSessionId)
                        deps.managed.markUnansweredForSession(identity.machineId, activity.claudeSessionId)
                    }
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.APPROVAL_REQUEST -> {
                    val request = runCatching { Codec.decodePayload<ApprovalRequest>(env) }.getOrNull() ?: continue
                    deps.approvals.raise(identity.machineId, request)
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.CONTROL_RESULT -> {
                    val result = runCatching { Codec.decodePayload<ControlResult>(env) }.getOrNull() ?: continue
                    deps.control.applyResult(identity.machineId, result)
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.QUESTION_RAISED -> {
                    val raised = runCatching { Codec.decodePayload<QuestionRaised>(env) }.getOrNull() ?: continue
                    deps.managed.raiseQuestion(identity.machineId, raised)
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }

                MessageType.TRANSCRIPT_MESSAGE -> {
                    val message = runCatching { Codec.decodePayload<TranscriptMessage>(env) }.getOrNull() ?: continue
                    deps.managed.storeTranscript(identity.machineId, message)
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }
            }
        }
    } finally {
        deps.agentHub.unregister(identity.machineId, outbound)
        outbound.close()
        sender.cancel()
        deps.trust.closeConnection(connectionId)
    }
}
