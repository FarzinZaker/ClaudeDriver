package com.claudedriver.backend.monitoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttentionClassifierTest {
    private val classifier = AttentionClassifier()

    @Test
    fun `waiting-for-operator notifications need attention with the right urgency`() {
        assertEquals(Attention.NEEDS_ATTENTION, classifier.classify("notification", "permission_prompt"))
        assertEquals(Urgency.HIGH, classifier.urgency("permission_prompt"))
        assertEquals(Attention.NEEDS_ATTENTION, classifier.classify("notification", "agent_needs_input"))
        assertEquals(Urgency.HIGH, classifier.urgency("agent_needs_input"))
        assertEquals(Attention.NEEDS_ATTENTION, classifier.classify("notification", "idle_prompt"))
        assertEquals(Urgency.NORMAL, classifier.urgency("idle_prompt"))
    }

    @Test
    fun `stop is a low-urgency completion, routine events are informational`() {
        assertEquals(Attention.COMPLETION, classifier.classify("stop", null))
        assertEquals(Attention.INFORMATIONAL, classifier.classify("session_start", null))
        assertEquals(Attention.INFORMATIONAL, classifier.classify("tool", null))
        // A notification of an unmapped kind is not attention-worthy (no false alarms).
        assertEquals(Attention.INFORMATIONAL, classifier.classify("notification", "some_other_type"))
    }
}
