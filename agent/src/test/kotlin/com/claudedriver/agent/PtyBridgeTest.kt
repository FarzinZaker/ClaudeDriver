package com.claudedriver.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtyBridgeTest {

    private fun frame(kind: Char, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(kind.code.toByte(), (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) + payload
    }

    private fun endpointToken(dir: java.io.File): Pair<Int, String> {
        val o = Json.parseToJsonElement(java.io.File(dir, "pty-endpoint").readText()) as JsonObject
        return o["port"]!!.jsonPrimitive.content.toInt() to o["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `hello with valid token opens a session, output and inject flow, exit closes`() {
        val dir = Files.createTempDirectory("pty").toFile()
        val opened = ConcurrentLinkedQueue<TerminalSession>()
        val output = ConcurrentLinkedQueue<ByteArray>()
        val resizes = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val closed = ConcurrentLinkedQueue<Pair<String, Int>>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)

        val bridge = PtyBridge(
            storageDir = dir,
            onOpen = { opened.add(it); openLatch.countDown() },
            onOutput = { _, b -> output.add(b) },
            onResize = { _, c, r -> resizes.add(c to r) },
            onClose = { s, c -> closed.add(s to c); closeLatch.countDown() },
        )
        bridge.start()
        val (port, token) = endpointToken(dir)

        Socket("127.0.0.1", port).use { sock ->
            val out = sock.getOutputStream()
            out.write(frame('H', """{"token":"$token","cwd":"/tmp/proj","sid":"pty-1","cols":120,"rows":40}""".toByteArray()))
            out.write(frame('O', "hello-terminal".toByteArray()))
            out.write(frame('R', """{"cols":100,"rows":30}""".toByteArray()))
            out.flush()

            assertTrue(openLatch.await(3, TimeUnit.SECONDS), "onOpen fired")
            val session = opened.first()
            assertEquals("pty-1", session.sid)
            assertEquals("/tmp/proj", session.cwd)
            assertEquals(120, session.cols)

            // Bridge → shim injection arrives as an I frame.
            bridge.inject("pty-1", "whoami\n".toByteArray())
            val input = DataInputStream(sock.getInputStream())
            val type = input.read()
            assertEquals('I'.code, type)
            val len = input.readInt()
            val buf = ByteArray(len).also { input.readFully(it) }
            assertEquals("whoami\n", String(buf))

            out.write(frame('X', """{"code":7}""".toByteArray()))
            out.flush()
            assertTrue(closeLatch.await(3, TimeUnit.SECONDS), "onClose fired")
        }

        // Output + resize were delivered; exit code propagated.
        assertTrue(output.any { String(it) == "hello-terminal" }, "output frame delivered")
        assertEquals(100 to 30, resizes.first())
        assertEquals("pty-1" to 7, closed.first())
        bridge.stop()
    }

    @Test
    fun `hello with wrong token is rejected — no session opens`() {
        val dir = Files.createTempDirectory("pty").toFile()
        val opened = ConcurrentLinkedQueue<TerminalSession>()
        val bridge = PtyBridge(
            storageDir = dir,
            onOpen = { opened.add(it) },
            onOutput = { _, _ -> },
            onResize = { _, _, _ -> },
            onClose = { _, _ -> },
        )
        bridge.start()
        val (port, _) = endpointToken(dir)

        Socket("127.0.0.1", port).use { sock ->
            val out = sock.getOutputStream()
            out.write(frame('H', """{"token":"WRONG","cwd":"/tmp","sid":"pty-x","cols":80,"rows":24}""".toByteArray()))
            out.write(frame('O', "should-be-ignored".toByteArray()))
            out.flush()
            Thread.sleep(300)
        }
        assertTrue(opened.isEmpty(), "no session opened for a bad token")
        assertNull(opened.peek())
        bridge.stop()
    }
}
