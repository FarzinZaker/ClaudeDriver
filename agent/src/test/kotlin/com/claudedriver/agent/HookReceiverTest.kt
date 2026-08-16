package com.claudedriver.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HookReceiverTest {
    private val receiver = HookReceiver(port = 0, token = "t", onEvent = { })

    @Test
    fun `maps a Claude Code notification hook to an activity event`() {
        val event = receiver.parse(
            """{"hook_event_name":"Notification","session_id":"s1","cwd":"/proj","notification_type":"permission_prompt"}""",
        )!!
        assertEquals("notification", event.kind)
        assertEquals("permission_prompt", event.notificationType)
        assertEquals("s1", event.claudeSessionId)
        assertEquals("/proj", event.projectPath)
    }

    @Test
    fun `maps stop and rejects unparseable or fieldless payloads`() {
        assertEquals("stop", receiver.parse("""{"hook_event_name":"Stop","session_id":"s1"}""")!!.kind)
        assertEquals("session_start", receiver.parse("""{"hook_event_name":"SessionStart","session_id":"s1"}""")!!.kind)
        assertNull(receiver.parse("not json"))
        assertNull(receiver.parse("""{"no":"relevant fields"}"""))
    }

    @Test
    fun `parses a PreToolUse payload into an approval request with a summary`() {
        val req = receiver.parseApproval(
            """{"hook_event_name":"PreToolUse","session_id":"s1","tool_name":"Bash","cwd":"/p","tool_input":{"command":"git push"}}""",
        )!!
        assertEquals("Bash", req.tool)
        assertEquals("s1", req.claudeSessionId)
        assertEquals("/p", req.projectPath)
        org.junit.jupiter.api.Assertions.assertTrue(req.summary.contains("git push"))
        org.junit.jupiter.api.Assertions.assertTrue(req.requestId.isNotBlank())
        assertNull(receiver.parseApproval("not json"))
    }
}
