package com.claudedriver.backend.monitoring

import com.claudedriver.backend.persistence.ActivityEvents
import com.claudedriver.backend.persistence.AgentConnections
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.persistence.Sessions
import com.claudedriver.protocol.ActivityEvent
import com.claudedriver.protocol.ProcessSnapshot
import com.claudedriver.protocol.SessionUpdate
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class SessionInfo(
    val id: UUID, val machineId: UUID, val machineName: String, val projectPath: String?,
    val state: String, val lastActivityAt: Instant, val processPresent: Boolean,
)

data class SessionEventInfo(val kind: String, val attention: String, val summary: String, val at: Instant)

data class SessionDetailInfo(val session: SessionInfo, val recentEvents: List<SessionEventInfo>)

/** Maintains the session registry and drives alerts from reported events (research D5/D6). */
class SessionRegistry(
    private val db: Database,
    private val classifier: AttentionClassifier,
    private val alerts: AlertService,
    private val publisher: Publisher,
) {
    /** Apply a forwarded Claude Code activity event: update session state, history, and alerts. */
    suspend fun applyActivityEvent(machineId: UUID, event: ActivityEvent) {
        val attention = classifier.classify(event.kind, event.notificationType)
        val newState = when {
            event.kind == "session_end" -> SessionState.STOPPED
            event.kind == "stop" -> SessionState.FINISHED
            attention == Attention.NEEDS_ATTENTION -> SessionState.WAITING_FOR_OPERATOR
            else -> SessionState.RUNNING
        }
        val now = Instant.now()

        data class Applied(val sessionId: UUID, val prevState: SessionState, val projectPath: String?)

        val applied = transaction(db) {
            val existing = Sessions.selectAll().where {
                (Sessions.machineId eq machineId) and (Sessions.claudeSessionId eq event.claudeSessionId)
            }.firstOrNull()
                // Adopt a session that process-detection created for this project (proc:<cwd>) so the
                // detected process and the hook session are one, not two.
                ?: event.projectPath?.takeIf { it.isNotBlank() }?.let { proj ->
                    Sessions.selectAll().where {
                        (Sessions.machineId eq machineId) and (Sessions.projectPath eq proj) and
                            (Sessions.claudeSessionId like "proc:%")
                    }.firstOrNull()?.also { row ->
                        Sessions.update({ Sessions.id eq row[Sessions.id] }) {
                            it[claudeSessionId] = event.claudeSessionId
                        }
                    }
                }

            val sessionId: UUID
            val prevState: SessionState
            val project: String?
            if (existing == null) {
                sessionId = UUID.randomUUID()
                prevState = SessionState.RUNNING
                project = event.projectPath
                Sessions.insert {
                    it[Sessions.id] = sessionId
                    it[Sessions.machineId] = machineId
                    it[claudeSessionId] = event.claudeSessionId
                    it[projectPath] = event.projectPath
                    it[state] = newState.wire
                    it[lastActivityAt] = now
                    it[processPresent] = true
                    it[createdAt] = now
                }
            } else {
                sessionId = existing[Sessions.id]
                prevState = SessionState.fromWire(existing[Sessions.state])
                project = event.projectPath ?: existing[Sessions.projectPath]
                Sessions.update({ Sessions.id eq sessionId }) {
                    it[state] = newState.wire
                    it[lastActivityAt] = now
                    if (event.projectPath != null) it[projectPath] = event.projectPath
                }
            }
            ActivityEvents.insert {
                it[ActivityEvents.sessionId] = sessionId
                it[kind] = event.kind
                it[ActivityEvents.attention] = attention.name.lowercase()
                it[summary] = event.summary
                it[detail] = event.detail
                it[at] = now
            }
            Applied(sessionId, prevState, project)
        }

        publisher.sessionUpdate(
            SessionUpdate(applied.sessionId.toString(), machineId.toString(), applied.projectPath, newState.wire, now.toString(), true),
        )

        if (newState == SessionState.WAITING_FOR_OPERATOR && applied.prevState != SessionState.WAITING_FOR_OPERATOR) {
            alerts.raiseIfNeeded(applied.sessionId, machineId, classifier.urgency(event.notificationType), event.summary)
        } else if (applied.prevState == SessionState.WAITING_FOR_OPERATOR && newState != SessionState.WAITING_FOR_OPERATOR) {
            val reason = if (newState == SessionState.FINISHED || newState == SessionState.STOPPED) "session_stopped" else "answered"
            alerts.resolveActive(applied.sessionId, machineId, reason)
        }
    }

    /**
     * Reconcile detected processes with sessions by working directory (the OS process does not
     * expose Claude Code's session id). Sessions are created from activity events; this only sets
     * `process_present` — true when a matching claude process runs in the session's project.
     */
    suspend fun applyProcessSnapshot(machineId: UUID, snapshot: ProcessSnapshot) {
        val liveCwds = snapshot.processes.mapNotNull { it.projectPath?.takeIf { p -> p.isNotBlank() } }.toSet()
        val now = Instant.now()
        val updates = transaction(db) {
            val out = mutableListOf<SessionUpdate>()
            val known = Sessions.selectAll().where { Sessions.machineId eq machineId }.toList()
            val knownCwds = known.mapNotNull { it[Sessions.projectPath] }.toSet()

            // A detected Claude Code process with no session yet becomes a running session, keyed by
            // its working directory ("proc:<cwd>") until a hook event supplies the real session id.
            for (cwd in liveCwds - knownCwds) {
                val id = UUID.randomUUID()
                Sessions.insert {
                    it[Sessions.id] = id
                    it[Sessions.machineId] = machineId
                    it[claudeSessionId] = "proc:$cwd"
                    it[projectPath] = cwd
                    it[state] = SessionState.RUNNING.wire
                    it[lastActivityAt] = now
                    it[processPresent] = true
                    it[createdAt] = now
                }
                out += SessionUpdate(id.toString(), machineId.toString(), cwd, SessionState.RUNNING.wire, now.toString(), true)
            }

            for (s in known) {
                val proj = s[Sessions.projectPath]
                val present = proj != null && proj in liveCwds
                if (present) {
                    // Keep a present session fresh so the staleness sweep doesn't retire it, and
                    // revive one that already went stale (e.g. after an agent reconnect).
                    val wasStale = s[Sessions.state] == SessionState.UNKNOWN_STALE.wire
                    val nextState = if (wasStale) SessionState.RUNNING.wire else s[Sessions.state]
                    Sessions.update({ Sessions.id eq s[Sessions.id] }) {
                        it[processPresent] = true
                        it[lastActivityAt] = now
                        if (wasStale) it[state] = SessionState.RUNNING.wire
                    }
                    if (!s[Sessions.processPresent] || wasStale) {
                        out += SessionUpdate(s[Sessions.id].toString(), machineId.toString(), proj, nextState, now.toString(), true)
                    }
                } else if (s[Sessions.processPresent]) {
                    Sessions.update({ Sessions.id eq s[Sessions.id] }) { it[processPresent] = false }
                    out += SessionUpdate(s[Sessions.id].toString(), machineId.toString(), proj, s[Sessions.state], s[Sessions.lastActivityAt].toString(), false)
                }
            }
            out
        }
        updates.forEach { publisher.sessionUpdate(it) }
    }

    /** Mark running/waiting sessions stale when their machine is offline or inactivity exceeds the threshold. */
    suspend fun sweepStale(thresholdSeconds: Long) {
        val now = Instant.now()
        val cutoff = now.minusSeconds(thresholdSeconds)
        val staled = transaction(db) {
            val online = AgentConnections.selectAll().where { AgentConnections.state eq "connected" }
                .map { it[AgentConnections.machineId] }.toSet()
            val candidates = Sessions.selectAll().where {
                Sessions.state inList listOf(SessionState.RUNNING.wire, SessionState.WAITING_FOR_OPERATOR.wire)
            }.toList()
            val toStale = candidates.filter { it[Sessions.machineId] !in online || it[Sessions.lastActivityAt] < cutoff }
            toStale.forEach { s -> Sessions.update({ Sessions.id eq s[Sessions.id] }) { it[state] = SessionState.UNKNOWN_STALE.wire } }
            toStale.map {
                SessionUpdate(
                    it[Sessions.id].toString(), it[Sessions.machineId].toString(), it[Sessions.projectPath],
                    SessionState.UNKNOWN_STALE.wire, it[Sessions.lastActivityAt].toString(), it[Sessions.processPresent],
                )
            }
        }
        staled.forEach { publisher.sessionUpdate(it) }
    }

    fun list(): List<SessionInfo> = transaction(db) {
        Sessions.join(Machines, JoinType.INNER, additionalConstraint = { Sessions.machineId eq Machines.id })
            .selectAll().orderBy(Sessions.lastActivityAt, SortOrder.DESC).map { rowToInfo(it) }
    }

    fun detail(sessionId: UUID): SessionDetailInfo? = transaction(db) {
        val row = Sessions.join(Machines, JoinType.INNER, additionalConstraint = { Sessions.machineId eq Machines.id })
            .selectAll().where { Sessions.id eq sessionId }.firstOrNull() ?: return@transaction null
        val events = ActivityEvents.selectAll().where { ActivityEvents.sessionId eq sessionId }
            .orderBy(ActivityEvents.at, SortOrder.DESC).limit(20)
            .map { SessionEventInfo(it[ActivityEvents.kind], it[ActivityEvents.attention], it[ActivityEvents.summary], it[ActivityEvents.at]) }
        SessionDetailInfo(rowToInfo(row), events)
    }

    fun sessionCountByMachine(): Map<UUID, Int> = transaction(db) {
        Sessions.selectAll().groupingBy { it[Sessions.machineId] }.eachCount()
    }

    private fun rowToInfo(it: org.jetbrains.exposed.sql.ResultRow) = SessionInfo(
        id = it[Sessions.id],
        machineId = it[Sessions.machineId],
        machineName = it[Machines.name],
        projectPath = it[Sessions.projectPath],
        state = it[Sessions.state],
        lastActivityAt = it[Sessions.lastActivityAt],
        processPresent = it[Sessions.processPresent],
    )
}
