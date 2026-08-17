package com.claudedriver.backend

import com.claudedriver.backend.api.StatusService
import com.claudedriver.backend.audit.AuditRepository
import com.claudedriver.backend.auth.OperatorStore
import com.claudedriver.backend.auth.PasswordAuthService
import com.claudedriver.backend.ca.DeviceCa
import com.claudedriver.backend.config.Config
import com.claudedriver.backend.connection.TrustService
import com.claudedriver.backend.approvals.ApprovalService
import com.claudedriver.backend.control.ControlService
import com.claudedriver.backend.enrollment.EnrollmentService
import com.claudedriver.backend.installer.InstallerService
import com.claudedriver.backend.managed.ManagedService
import com.claudedriver.backend.monitoring.AlertService
import com.claudedriver.backend.monitoring.AttentionClassifier
import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.monitoring.SessionRegistry
import com.claudedriver.backend.persistence.Db
import com.claudedriver.backend.push.DeviceStore
import com.claudedriver.backend.push.LoggingPushSender
import com.claudedriver.backend.push.PushService
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.backend.ws.OperatorHub
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("ClaudeDriver")

/** Everything the routes need, assembled once. */
class AppDeps(
    val config: Config,
    val database: Database,
    val audit: AuditRepository,
    val ca: DeviceCa,
    val passwordAuth: PasswordAuthService,
    val enrollment: EnrollmentService,
    val trust: TrustService,
    val status: StatusService,
    val hub: OperatorHub,
    val operatorStore: OperatorStore,
    val sessions: SessionRegistry,
    val alerts: AlertService,
    val agentHub: AgentHub,
    val approvals: ApprovalService,
    val devices: DeviceStore,
    val control: ControlService,
    val managed: ManagedService,
    val installer: InstallerService,
) {
    companion object {
        fun create(config: Config, database: Database): AppDeps {
            val audit = AuditRepository(database)
            // Prefer a persistent CA (cert path from config, key from a mounted secret) so the ALB
            // mTLS trust store stays valid across restarts. Fall back to a throwaway CA for local dev.
            val ca = when {
                config.caCertPem != null && config.caKeyPem != null -> {
                    log.info("Loading persistent device CA from CA_CERT_PEM/CA_KEY_PEM")
                    DeviceCa.loadFromPem(config.caCertPem, config.caKeyPem)
                }
                config.caCertPath != null && config.caKeyPath != null -> {
                    log.info("Loading persistent device CA from ${config.caCertPath}")
                    DeviceCa.loadFromFiles(config.caCertPath, config.caKeyPath)
                }
                else -> {
                    log.warn("No CA_CERT_PEM/PATH set — generating an ephemeral device CA (dev only)")
                    DeviceCa.generate()
                }
            }
            val operatorStore = OperatorStore(database)
            val hub = OperatorHub()
            val agentHub = AgentHub()
            val publisher = Publisher(hub)
            val classifier = AttentionClassifier()
            val alerts = AlertService(database, audit, publisher)
            val sessions = SessionRegistry(database, classifier, alerts, publisher)
            val devices = DeviceStore(database)
            // Dev uses a logging push sender; prod swaps in SnsPushSender at deploy.
            val push = PushService(devices, LoggingPushSender())
            val approvals = ApprovalService(database, audit, publisher, agentHub, push)
            val control = ControlService(database, audit, publisher, agentHub, approvals)
            val managed = ManagedService(database, audit, publisher, agentHub)
            return AppDeps(
                config = config,
                database = database,
                audit = audit,
                ca = ca,
                passwordAuth = PasswordAuthService(operatorStore),
                enrollment = EnrollmentService(database, ca, audit),
                trust = TrustService(database),
                status = StatusService(database, hub),
                hub = hub,
                operatorStore = operatorStore,
                sessions = sessions,
                alerts = alerts,
                agentHub = agentHub,
                approvals = approvals,
                devices = devices,
                control = control,
                managed = managed,
                installer = InstallerService(config.agentRuntimesBucket),
            )
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    val db = Db.connect(config)
    val deps = AppDeps.create(config, db.database)
    log.info("Starting ClaudeDriver backend on ${config.host}:${config.port} (env=${config.env})")
    embeddedServer(Netty, host = config.host, port = config.port) {
        module(deps)
    }.start(wait = true)
}

fun Application.module(deps: AppDeps) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    install(Sessions) {
        val key = deps.config.sessionSigningKey.toByteArray()
        cookie<OperatorSession>("operator_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            transform(SessionTransportTransformerMessageAuthentication(key))
        }
        cookie<ChallengeSession>("wa_challenge") {
            cookie.path = "/"
            cookie.httpOnly = true
            transform(SessionTransportTransformerMessageAuthentication(key))
        }
    }
    configureRouting(deps)

    // Staleness sweep: mark sessions unknown_stale when their machine goes offline or events stop.
    launch {
        while (true) {
            delay(15.seconds)
            runCatching { deps.sessions.sweepStale(thresholdSeconds = 30) }
        }
    }
}
