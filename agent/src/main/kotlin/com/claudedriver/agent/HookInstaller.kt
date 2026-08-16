package com.claudedriver.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Installs/removes a managed Claude Code hooks block in the user `settings.json`, pointing Claude
 * Code's HTTP hooks at the agent's loopback receiver. Merges (never overwrites the user's other
 * hooks/config), is idempotent (keyed by our loopback `/hook` url), and fully reversible
 * (Constitution FR-017/018/019). Pure transforms are unit-testable without touching disk.
 */
object HookInstaller {
    private val MANAGED_EVENTS = listOf("Notification", "Stop", "SessionStart", "SessionEnd")
    private val RESERVED = setOf("hooks", "allowedHttpHookUrls", "httpHookAllowedEnvVars")
    private const val LOOPBACK_URL_PATTERN = "http://127.0.0.1:*"

    private val pretty = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val parser = Json { ignoreUnknownKeys = true }

    fun defaultSettingsFile(): File = File(System.getProperty("user.home"), ".claude/settings.json")

    fun installToFile(file: File, port: Int, envVar: String) {
        write(file, install(read(file), port, envVar))
    }

    fun teardownFile(file: File, envVar: String) {
        if (file.exists()) write(file, teardown(read(file), envVar))
    }

    /** Pure: return a settings object with our managed hooks installed. */
    fun install(root: JsonObject, port: Int, envVar: String): JsonObject {
        // Activity hooks (non-blocking, → /hook) and the blocking approval hook (→ /approve).
        val activityGroup = group("http://127.0.0.1:$port/hook", envVar, matcher = null, timeoutSeconds = null)
        val approvalGroup = group("http://127.0.0.1:$port/approve", envVar, matcher = "Bash|Write|Edit", timeoutSeconds = 86400)
        val existingHooks = root["hooks"] as? JsonObject ?: JsonObject(emptyMap())
        val newHooks = buildJsonObject {
            for (event in (existingHooks.keys + MANAGED_EVENTS + "PreToolUse").distinct()) {
                val prior = (existingHooks[event] as? JsonArray)?.filterNot { isManaged(it) } ?: emptyList()
                val arr = when {
                    event == "PreToolUse" -> prior + approvalGroup
                    event in MANAGED_EVENTS -> prior + activityGroup
                    else -> prior
                }
                put(event, JsonArray(arr))
            }
        }
        return buildJsonObject {
            for ((k, v) in root) if (k !in RESERVED) put(k, v)
            put("hooks", newHooks)
            put("allowedHttpHookUrls", mergeStrings(root["allowedHttpHookUrls"], LOOPBACK_URL_PATTERN))
            put("httpHookAllowedEnvVars", mergeStrings(root["httpHookAllowedEnvVars"], envVar))
        }
    }

    private fun group(url: String, envVar: String, matcher: String?, timeoutSeconds: Int?) = buildJsonObject {
        if (matcher != null) put("matcher", matcher)
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "http")
                put("url", url)
                putJsonObject("headers") { put("Authorization", "Bearer \$$envVar") }
                putJsonArray("allowedEnvVars") { add(envVar) }
                if (timeoutSeconds != null) put("timeout", timeoutSeconds)
            }
        }
    }

    /** Pure: return a settings object with our managed additions removed, user config preserved. */
    fun teardown(root: JsonObject, envVar: String): JsonObject = buildJsonObject {
        for ((k, v) in root) if (k !in RESERVED) put(k, v)
        (root["hooks"] as? JsonObject)?.let { hooks ->
            val cleaned = buildJsonObject {
                for ((event, groups) in hooks) {
                    val kept = (groups as? JsonArray)?.filterNot { isManaged(it) } ?: emptyList()
                    if (kept.isNotEmpty()) put(event, JsonArray(kept))
                }
            }
            if (cleaned.isNotEmpty()) put("hooks", cleaned)
        }
        removeString(root["allowedHttpHookUrls"], LOOPBACK_URL_PATTERN).takeIf { it.isNotEmpty() }
            ?.let { put("allowedHttpHookUrls", JsonArray(it)) }
        removeString(root["httpHookAllowedEnvVars"], envVar).takeIf { it.isNotEmpty() }
            ?.let { put("httpHookAllowedEnvVars", JsonArray(it)) }
    }

    private fun isManaged(group: kotlinx.serialization.json.JsonElement): Boolean {
        val hooks = (group as? JsonObject)?.get("hooks") as? JsonArray ?: return false
        return hooks.any { h ->
            val u = (h as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: return@any false
            "127.0.0.1" in u // our loopback receiver (/hook or /approve)
        }
    }

    private fun mergeStrings(existing: kotlinx.serialization.json.JsonElement?, value: String): JsonArray {
        val cur = (existing as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return JsonArray((cur + value).distinct().map { JsonPrimitive(it) })
    }

    private fun removeString(existing: kotlinx.serialization.json.JsonElement?, value: String): List<JsonPrimitive> {
        val cur = (existing as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return cur.filterNot { it == value }.map { JsonPrimitive(it) }
    }

    private fun read(file: File): JsonObject {
        if (!file.exists() || file.readText().isBlank()) return JsonObject(emptyMap())
        return parser.parseToJsonElement(file.readText()) as JsonObject
    }

    private fun write(file: File, obj: JsonObject) {
        file.parentFile?.mkdirs()
        file.writeText(pretty.encodeToString(JsonObject.serializer(), obj))
    }
}
