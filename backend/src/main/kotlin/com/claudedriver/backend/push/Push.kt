package com.claudedriver.backend.push

import com.claudedriver.backend.persistence.PushDevices
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** A push message to deliver to a device. */
data class PushMessage(val title: String, val body: String, val itemId: String, val kind: String)

/** Delivers a push message to one device token. Pluggable so dev/test need no real push infra. */
interface PushSender {
    fun send(token: String, platform: String, message: PushMessage)
}

/** Dev/test sender: records + logs instead of contacting a push network. */
class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger("Push")
    val sent: MutableList<Pair<String, PushMessage>> = CopyOnWriteArrayList()

    override fun send(token: String, platform: String, message: PushMessage) {
        sent.add(token to message)
        log.info("PUSH → {}:{} :: {} — {}", platform, token.take(8), message.title, message.body)
    }
}

/**
 * Prod sender: publishes to Amazon SNS mobile push (→ FCM/APNs). Wired at deploy with the AWS SDK
 * and the SNS platform-application ARNs; intentionally not implemented in this environment.
 */
class SnsPushSender : PushSender {
    override fun send(token: String, platform: String, message: PushMessage) {
        throw NotImplementedError("SnsPushSender is configured at deploy time with AWS SNS credentials")
    }
}

/** Registered operator devices (data-model.md). */
class DeviceStore(private val db: Database) {
    data class DeviceRow(val token: String, val platform: String)

    fun register(operatorId: UUID, token: String, platform: String) = transaction(db) {
        val now = Instant.now()
        val existing = PushDevices.selectAll().where { PushDevices.token eq token }.any()
        if (existing) {
            PushDevices.update({ PushDevices.token eq token }) { it[lastSeenAt] = now }
        } else {
            PushDevices.insert {
                it[id] = UUID.randomUUID()
                it[PushDevices.operatorId] = operatorId
                it[PushDevices.token] = token
                it[PushDevices.platform] = platform
                it[createdAt] = now
                it[lastSeenAt] = now
            }
        }
        Unit
    }

    fun unregister(token: String) = transaction(db) {
        PushDevices.deleteWhere { PushDevices.token eq token }
        Unit
    }

    fun all(): List<DeviceRow> = transaction(db) {
        PushDevices.selectAll().map { DeviceRow(it[PushDevices.token], it[PushDevices.platform]) }
    }
}

/** Fans a message out to all registered devices; prunes tokens the sender rejects. Never throws. */
class PushService(private val devices: DeviceStore, private val sender: PushSender) {
    fun notify(message: PushMessage) {
        for (device in devices.all()) {
            runCatching { sender.send(device.token, device.platform, message) }
                .onFailure { devices.unregister(device.token) }
        }
    }
}
