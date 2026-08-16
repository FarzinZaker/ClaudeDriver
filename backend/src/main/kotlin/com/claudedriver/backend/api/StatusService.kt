package com.claudedriver.backend.api

import com.claudedriver.backend.persistence.AgentConnections
import com.claudedriver.backend.persistence.Machines
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.PROTOCOL_VERSION
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/** Assembles the operator status view (contracts/rest-api.md GET /status). */
class StatusService(private val db: Database, private val hub: OperatorHub) {

    fun status(): StatusResponse {
        val machines = transaction(db) {
            Machines.selectAll().map { m ->
                val machineId = m[Machines.id]
                val conn = AgentConnections.selectAll().where {
                    (AgentConnections.machineId eq machineId) and (AgentConnections.state eq "connected")
                }.orderBy(AgentConnections.connectedAt, SortOrder.DESC).firstOrNull()

                MachineDto(
                    id = machineId.toString(),
                    name = m[Machines.name],
                    os = m[Machines.os],
                    status = m[Machines.status],
                    connection = conn?.let {
                        ConnectionDto(
                            state = it[AgentConnections.state],
                            since = it[AgentConnections.connectedAt].toString(),
                            protocolVersion = it[AgentConnections.protocolVersion],
                        )
                    },
                )
            }
        }
        return StatusResponse(
            server = ServerInfo(version = PROTOCOL_VERSION, time = Instant.now().toString()),
            machines = machines,
            recentSampleEvents = hub.recentEvents().map { SampleEventDto(it.machineId, it.message, it.at) },
        )
    }
}
