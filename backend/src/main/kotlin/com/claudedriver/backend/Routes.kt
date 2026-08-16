package com.claudedriver.backend

import com.claudedriver.backend.api.AgentEnrollRequest
import com.claudedriver.backend.api.AgentEnrollResponse
import com.claudedriver.backend.api.CreateMachineRequest
import com.claudedriver.backend.api.CreateMachineResponse
import com.claudedriver.backend.api.EnrollmentApprovedResponse
import com.claudedriver.backend.api.ErrorResponse
import com.claudedriver.backend.api.RegisterOptionsRequest
import com.claudedriver.backend.api.WhoAmIResponse
import com.claudedriver.backend.audit.AuditAction
import com.claudedriver.backend.connection.TrustService
import com.claudedriver.backend.enrollment.EnrollmentException
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.HelloAck
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.PROTOCOL_VERSION
import com.claudedriver.protocol.Pong
import com.claudedriver.protocol.ProtocolVersion
import com.claudedriver.protocol.SampleEvent
import com.claudedriver.protocol.VersionMismatch
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
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
            VersionMismatch(PROTOCOL_VERSION, "requires MAJOR.MINOR ${ProtocolVersion.CURRENT.major}.${ProtocolVersion.CURRENT.minor}"),
        )
        send(Frame.Text(Codec.encode(mismatch)))
        deps.audit.append("machine:${identity.machineId}", AuditAction.CONNECTION_REFUSED, identity.machineId.toString(), """{"reason":"version_mismatch"}""")
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "version mismatch"))
        return
    }

    val connectionId = deps.trust.openConnection(identity, hello.protocolVersion)
    deps.audit.append("machine:${identity.machineId}", AuditAction.CONNECTION_ACCEPTED, identity.machineId.toString())

    val ack = Codec.envelope(
        MessageType.HELLO_ACK,
        ++seq,
        HelloAck(identity.machineId.toString(), Instant.now().toString(), 30),
    )
    send(Frame.Text(Codec.encode(ack)))

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val env = runCatching { Codec.decode(frame.readText()) }.getOrNull() ?: continue
            when (env.type) {
                MessageType.PING ->
                    send(Frame.Text(Codec.encode(Codec.envelope(MessageType.PONG, ++seq, Pong(Instant.now().toString())))))

                MessageType.SAMPLE_EVENT -> {
                    val sample = runCatching { Codec.decodePayload<SampleEvent>(env) }.getOrNull() ?: continue
                    deps.hub.recordAndSnapshotRecent(sample)
                    deps.hub.broadcast(Codec.encode(env)) // relay unchanged to operators
                    deps.trust.updateLastSeq(connectionId, env.seq)
                }
            }
        }
    } finally {
        deps.trust.closeConnection(connectionId)
    }
}
