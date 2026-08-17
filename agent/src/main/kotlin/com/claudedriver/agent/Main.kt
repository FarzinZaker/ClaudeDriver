package com.claudedriver.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ClaudeDriver agent entrypoint.
 *
 *   enroll --machine-id <uuid> --code <code>   # obtain a device certificate explicitly
 *   (no args)                                  # auto-enroll if needed, then hold the outbound WSS
 *
 * Configuration is resolved from (highest precedence first) environment variables, then an
 * `agent.config` JSON file in the agent directory (written by the per-machine installer the
 * dashboard produces), then defaults:
 *
 *   CLAUDEDRIVER_BACKEND_URL          enrollment endpoint          (config: backendUrl)
 *   CLAUDEDRIVER_AGENT_CONNECT_URL    outbound WSS / ALB mTLS      (config: connectUrl)
 *   CLAUDEDRIVER_MACHINE_ID           machine id to auto-enroll    (config: machineId)
 *   CLAUDEDRIVER_ENROLLMENT_CODE      one-time enrollment code     (config: enrollmentCode)
 *   CLAUDEDRIVER_AGENT_DIR            state dir (default ./agent-data)
 *
 * With a pre-configured install the agent auto-enrolls on first run and simply reconnects
 * thereafter (the one-time code is spent, the device certificate persists).
 */
@Serializable
data class AgentConfig(
    val backendUrl: String? = null,
    val connectUrl: String? = null,
    val machineId: String? = null,
    val enrollmentCode: String? = null,
)

private val configJson = Json { ignoreUnknownKeys = true }

private fun loadConfig(dir: File): AgentConfig {
    val f = File(dir, "agent.config")
    return if (f.isFile) {
        runCatching { configJson.decodeFromString(AgentConfig.serializer(), f.readText()) }
            .getOrElse { AgentConfig() }
    } else {
        AgentConfig()
    }
}

fun main(args: Array<String>) = runBlocking {
    val storageDir = File(System.getenv("CLAUDEDRIVER_AGENT_DIR") ?: "agent-data")
    val cfg = loadConfig(storageDir)

    fun setting(env: String, fromCfg: String?, default: String? = null): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: fromCfg?.takeIf { it.isNotBlank() } ?: default

    val serverUrl = setting("CLAUDEDRIVER_BACKEND_URL", cfg.backendUrl, "http://localhost:8080")!!
    val connectUrl = setting("CLAUDEDRIVER_AGENT_CONNECT_URL", cfg.connectUrl, serverUrl)!!
    val client = AgentClient(serverUrl, storageDir, connectBaseUrl = connectUrl)

    if (args.isNotEmpty() && args[0] == "enroll") {
        val machineId = argValue(args, "--machine-id") ?: error("enroll requires --machine-id <uuid>")
        val code = argValue(args, "--code") ?: error("enroll requires --code <code>")
        client.enroll(machineId, code)
        return@runBlocking
    }

    // Auto-enroll on first run when configured and not yet enrolled.
    if (!client.isEnrolled()) {
        val machineId = setting("CLAUDEDRIVER_MACHINE_ID", cfg.machineId)
        val code = setting("CLAUDEDRIVER_ENROLLMENT_CODE", cfg.enrollmentCode)
        if (machineId != null && code != null) {
            println("First run: auto-enrolling machine $machineId with $serverUrl ...")
            client.enroll(machineId, code)
        }
    }

    println("Connecting to $connectUrl ...")
    client.connectForever()
}

private fun argValue(args: Array<String>, name: String): String? {
    val idx = args.indexOf(name)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}
