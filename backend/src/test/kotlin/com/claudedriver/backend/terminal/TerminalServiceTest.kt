package com.claudedriver.backend.terminal

import com.claudedriver.backend.monitoring.Publisher
import com.claudedriver.backend.ws.AgentHub
import com.claudedriver.backend.ws.OperatorHub
import com.claudedriver.protocol.Codec
import com.claudedriver.protocol.MessageType
import com.claudedriver.protocol.TerminalClosed
import com.claudedriver.protocol.TerminalInput
import com.claudedriver.protocol.TerminalOpened
import com.claudedriver.protocol.TerminalOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class TerminalServiceTest {

    private fun service(agentHub: AgentHub = AgentHub()) =
        TerminalService(Publisher(OperatorHub()), agentHub, machineNameOf = { "test-machine" })

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `opened registers, output buffers as scrollback, closed marks exit`() = runBlocking {
        val svc = service()
        val m = UUID.randomUUID()
        val id = "$m:pty-1"

        svc.opened(m, TerminalOpened("pty-1", "/tmp/proj", 80, 24, "t0"))
        assertEquals(1, svc.list().size)
        assertEquals("open", svc.list().first().status)
        assertEquals("test-machine", svc.list().first().machineName)

        svc.output(m, TerminalOutput("pty-1", b64("hello "), "t1"))
        svc.output(m, TerminalOutput("pty-1", b64("world"), "t2"))
        val scroll = String(Base64.getDecoder().decode(svc.scrollback(id)!!))
        assertEquals("hello world", scroll)

        svc.closed(m, TerminalClosed("pty-1", 3, "t3"))
        assertEquals("closed", svc.list().first().status)
        assertEquals(3, svc.list().first().exitCode)
    }

    @Test
    fun `scrollback keeps only the most recent tail`() = runBlocking {
        val svc = service()
        val m = UUID.randomUUID()
        svc.opened(m, TerminalOpened("pty-1", "/tmp", 80, 24, "t0"))
        // Push more than the 256 KiB cap; the tail must be preserved, the head dropped.
        val chunk = "X".repeat(100_000)
        repeat(4) { svc.output(m, TerminalOutput("pty-1", b64(chunk), "t")) }
        svc.output(m, TerminalOutput("pty-1", b64("TAILMARK"), "t"))
        val scroll = String(Base64.getDecoder().decode(svc.scrollback("$m:pty-1")!!))
        assertTrue(scroll.length <= 256 * 1024, "buffer capped at the scrollback limit")
        assertTrue(scroll.endsWith("TAILMARK"), "most recent output retained")
    }

    @Test
    fun `input to an open terminal is routed to the owning agent`() = runBlocking {
        val agentHub = AgentHub()
        val m = UUID.randomUUID()
        val channel = Channel<com.claudedriver.backend.ws.OutFrame>(capacity = 8)
        agentHub.register(m, channel)
        val svc = service(agentHub)
        svc.opened(m, TerminalOpened("pty-1", "/tmp", 80, 24, "t0"))

        svc.input(TerminalInput("$m:pty-1", b64("ls\n")), "operator:alice")

        val frame = channel.tryReceive().getOrNull()
        assertTrue(frame != null, "a frame was routed to the agent")
        assertEquals(MessageType.TERMINAL_INPUT, frame!!.type)
        val decoded = Codec.json.decodeFromJsonElement(TerminalInput.serializer(), frame.payload)
        assertEquals("$m:pty-1", decoded.terminalId)
        assertEquals("ls\n", String(Base64.getDecoder().decode(decoded.dataB64)))
    }

    @Test
    fun `input to a closed or unknown terminal is dropped`() = runBlocking {
        val agentHub = AgentHub()
        val m = UUID.randomUUID()
        val channel = Channel<com.claudedriver.backend.ws.OutFrame>(capacity = 8)
        agentHub.register(m, channel)
        val svc = service(agentHub)
        svc.opened(m, TerminalOpened("pty-1", "/tmp", 80, 24, "t0"))
        svc.closed(m, TerminalClosed("pty-1", 0, "t1"))

        svc.input(TerminalInput("$m:pty-1", b64("rm -rf /\n")), "operator:mallory") // closed
        svc.input(TerminalInput("$m:pty-999", b64("x")), "operator:mallory")        // unknown

        assertNull(channel.tryReceive().getOrNull(), "no input routed to a closed/unknown terminal")
    }
}
