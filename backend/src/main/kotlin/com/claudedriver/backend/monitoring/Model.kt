package com.claudedriver.backend.monitoring

/** Session lifecycle states (data-model.md). Wire form = lowercase name. */
enum class SessionState {
    RUNNING, WAITING_FOR_OPERATOR, FINISHED, STOPPED, UNKNOWN_STALE;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(s: String): SessionState = entries.first { it.wire == s }
    }
}

/** Classification of an activity event. */
enum class Attention { NEEDS_ATTENTION, INFORMATIONAL, COMPLETION }

enum class Urgency {
    HIGH, NORMAL, LOW;

    val wire: String get() = name.lowercase()
}

/**
 * Maps activity events to attention level (Constitution-neutral, configurable — research D4).
 * Default: a Claude Code instance *waiting for the operator* needs attention; `stop` is a
 * low-urgency completion; everything else is informational.
 */
class AttentionClassifier(
    private val needsAttentionNotificationTypes: Set<String> = DEFAULT_NEEDS_ATTENTION,
) {
    fun classify(kind: String, notificationType: String?): Attention = when {
        kind == "notification" && notificationType != null && notificationType in needsAttentionNotificationTypes -> Attention.NEEDS_ATTENTION
        kind == "stop" -> Attention.COMPLETION
        else -> Attention.INFORMATIONAL
    }

    fun urgency(notificationType: String?): Urgency = when (notificationType) {
        "permission_prompt", "agent_needs_input" -> Urgency.HIGH
        "idle_prompt" -> Urgency.NORMAL
        else -> Urgency.NORMAL
    }

    companion object {
        val DEFAULT_NEEDS_ATTENTION = setOf("permission_prompt", "idle_prompt", "agent_needs_input")
    }
}
