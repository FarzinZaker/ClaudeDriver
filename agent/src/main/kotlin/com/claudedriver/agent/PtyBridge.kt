package com.claudedriver.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/** A live terminal session opened by a `claude` shim connection. */
data class TerminalSession(val sid: String, val cwd: String, var cols: Int, var rows: Int)

/**
 * Loopback bridge between the transparent `claude` shim and the agent. The shim runs the real
 * `claude` in a PTY and connects here; the agent mirrors that terminal to the dashboard and injects
 * operator keystrokes back into it. Binds 127.0.0.1 only and gates every connection on a per-boot
 * token written to `~/.claudedriver/pty-endpoint`, so nothing off-box (or lacking the token file's
 * permissions) can attach to a user's terminal.
 *
 * Frame: 1 type byte + 4-byte big-endian length + payload.
 *   shim → agent : H hello{token,cwd,sid,cols,rows} · O output-bytes · R resize{cols,rows} · X exit{code}
 *   agent → shim : I input-bytes
 */
class PtyBridge(
    // Where the `pty-endpoint` handshake file is written. MUST match where the shim reads it
    // (`~/.claudedriver/pty-endpoint`), independent of the agent's own storage dir — which the
    // launchd plist points elsewhere (`~/.claudedriver-agent`). Tests pass a temp dir here.
    private val endpointDir: File,
    private val onOpen: (TerminalSession) -> Unit,
    private val onOutput: (String, ByteArray) -> Unit,
    private val onResize: (String, Int, Int) -> Unit,
    private val onClose: (String, Int) -> Unit,
    private val bindHost: String = "127.0.0.1",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = ByteArray(24).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val conns = ConcurrentHashMap<String, Socket>()
    @Volatile private var server: ServerSocket? = null

    /** Bind an ephemeral loopback port, advertise it in the endpoint file, and accept shims. */
    fun start(): Int {
        val srv = ServerSocket(0, 50, InetAddress.getByName(bindHost))
        server = srv
        writeEndpoint(srv.localPort)
        thread(name = "pty-accept", isDaemon = true) {
            while (!srv.isClosed) {
                val socket = try { srv.accept() } catch (e: Exception) { break }
                thread(name = "pty-conn", isDaemon = true) { handle(socket) }
            }
        }
        return srv.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        endpointFile.delete()
        conns.values.forEach { runCatching { it.close() } }
    }

    /** Write bytes into the terminal of [sid] (an operator keystroke). No-op if it has closed. */
    fun inject(sid: String, data: ByteArray) {
        val socket = conns[sid] ?: return
        runCatching {
            val out = socket.getOutputStream()
            synchronized(socket) {
                out.write('I'.code)
                out.write(intToBytes(data.size))
                out.write(data)
                out.flush()
            }
        }
    }

    fun sessions(): List<String> = conns.keys.toList()

    private fun handle(socket: Socket) {
        var sid: String? = null
        var exitCode = 0
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            // First frame must be a valid, token-bearing hello.
            val (kind0, payload0) = readFrame(input) ?: return
            if (kind0 != 'H') return
            val hello = json.parseToJsonElement(String(payload0)) as? JsonObject ?: return
            if (str(hello, "token") != token) return // reject anything without the per-boot token
            sid = str(hello, "sid") ?: return
            val session = TerminalSession(
                sid = sid,
                cwd = str(hello, "cwd") ?: "",
                cols = intOf(hello, "cols", 80),
                rows = intOf(hello, "rows", 24),
            )
            conns[sid] = socket
            onOpen(session)

            while (true) {
                val (kind, payload) = readFrame(input) ?: break
                when (kind) {
                    'O' -> onOutput(sid, payload)
                    'R' -> {
                        val r = json.parseToJsonElement(String(payload)) as? JsonObject
                        if (r != null) onResize(sid, intOf(r, "cols", session.cols), intOf(r, "rows", session.rows))
                    }
                    'X' -> {
                        val x = json.parseToJsonElement(String(payload)) as? JsonObject
                        exitCode = x?.let { intOf(it, "code", 0) } ?: 0
                        break
                    }
                    else -> {} // ignore unknown frame kinds (forward-compat)
                }
            }
        } catch (_: Exception) {
            // fall through to close
        } finally {
            sid?.let {
                conns.remove(it)
                runCatching { onClose(it, exitCode) }
            }
            runCatching { socket.close() }
        }
    }

    /** Read one frame; null on clean EOF or a length past a sane cap. */
    private fun readFrame(input: DataInputStream): Pair<Char, ByteArray>? {
        val type = input.read()
        if (type < 0) return null
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) return null
        val buf = ByteArray(len)
        input.readFully(buf)
        return type.toChar() to buf
    }

    private fun writeEndpoint(port: Int) {
        endpointDir.mkdirs()
        endpointFile.writeText("""{"port":$port,"token":"$token"}""")
        runCatching { endpointFile.setReadable(false, false); endpointFile.setReadable(true, true) } // owner-only
    }

    private val endpointFile get() = File(endpointDir, "pty-endpoint")

    private fun str(o: JsonObject, k: String): String? = o[k]?.jsonPrimitive?.contentOrNull
    private fun intOf(o: JsonObject, k: String, default: Int): Int = o[k]?.jsonPrimitive?.intOrNull ?: default
    private fun intToBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        private const val MAX_FRAME = 4 * 1024 * 1024 // 4 MiB guard against a runaway/hostile length
    }
}
