package com.claudedriver.agent

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * ClaudeDriver agent entrypoint.
 *
 *   enroll --machine-id <uuid> --code <code>   # obtain a device certificate
 *   (no args)                                  # connect and hold the outbound WSS
 *
 * Config via env: CLAUDEDRIVER_BACKEND_URL (default http://localhost:8080),
 *                 CLAUDEDRIVER_AGENT_DIR (default ./agent-data).
 */
fun main(args: Array<String>) = runBlocking {
    val serverUrl = System.getenv("CLAUDEDRIVER_BACKEND_URL") ?: "http://localhost:8080"
    val storageDir = File(System.getenv("CLAUDEDRIVER_AGENT_DIR") ?: "agent-data")
    val client = AgentClient(serverUrl, storageDir)

    if (args.isNotEmpty() && args[0] == "enroll") {
        val machineId = argValue(args, "--machine-id") ?: error("enroll requires --machine-id <uuid>")
        val code = argValue(args, "--code") ?: error("enroll requires --code <code>")
        client.enroll(machineId, code)
    } else {
        println("Connecting to $serverUrl ...")
        client.connectForever()
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val idx = args.indexOf(name)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}
