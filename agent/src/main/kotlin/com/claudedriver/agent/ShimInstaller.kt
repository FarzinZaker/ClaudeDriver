package com.claudedriver.agent

import java.io.File

/**
 * Installs the transparent `claude` shim ahead of the real binary on PATH, so ordinary
 * `claude` invocations are mirrored to the dashboard with nothing special to run. The shim
 * lives in its own directory (`~/.claudedriver/bin`) that a marker-guarded block prepends to
 * PATH in the user's shell rc files; only NEW shells pick it up, so a session already running
 * is never disturbed. Fully reversible, and fail-safe: it refuses to install unless a real
 * `claude` exists elsewhere on PATH for the shim to fall through to.
 */
object ShimInstaller {
    private const val BEGIN = "# >>> claudedriver shim >>>"
    private const val END = "# <<< claudedriver shim <<<"
    private val RC_FILES = listOf(".zshrc", ".bashrc", ".profile")

    fun binDir(home: File) = File(home, ".claudedriver/bin")
    fun shimFile(home: File) = File(binDir(home), "claude")

    /** The PATH-prepend block, marker-guarded so it is idempotent and cleanly removable. */
    fun rcBlock(binDir: File): String = buildString {
        append(BEGIN).append('\n')
        append("export PATH=\"").append(binDir.absolutePath).append(":\$PATH\"").append('\n')
        append(END).append('\n')
    }

    /**
     * Install into [home]. Returns true if the shim is now in place. No-op-safe: returns false
     * (installing nothing) when no real `claude` is resolvable outside the shim dir, so we never
     * shadow the CLI with a shim that has nothing to run.
     */
    fun install(home: File): Boolean {
        val bin = binDir(home)
        if (realClaudeOutside(bin) == null) return false // nothing to fall through to → don't install
        bin.mkdirs()
        val shim = shimFile(home)
        shim.writeText(readShimResource())
        shim.setExecutable(true, false)
        RC_FILES.map { File(home, it) }.filter { it.exists() || it.name == ".zshrc" }
            .forEach { ensureRcBlock(it, bin) }
        return true
    }

    /** Remove the shim + every marker-guarded PATH block. Idempotent. */
    fun teardown(home: File) {
        shimFile(home).delete()
        RC_FILES.map { File(home, it) }.filter { it.exists() }.forEach { stripRcBlock(it) }
    }

    /** Add the block to [rcFile] once; replace an existing block so the path stays current. */
    fun ensureRcBlock(rcFile: File, bin: File) {
        val existing = if (rcFile.exists()) rcFile.readText() else ""
        val without = stripBlock(existing)
        val joined = if (without.isEmpty() || without.endsWith("\n")) without else without + "\n"
        rcFile.parentFile?.mkdirs()
        rcFile.writeText(joined + rcBlock(bin))
    }

    fun stripRcBlock(rcFile: File) {
        if (!rcFile.exists()) return
        rcFile.writeText(stripBlock(rcFile.readText()))
    }

    /** Drop the marker-delimited block (inclusive) from [text], leaving the rest untouched. */
    internal fun stripBlock(text: String): String {
        val begin = text.indexOf(BEGIN)
        if (begin < 0) return text
        val endMarker = text.indexOf(END, begin)
        if (endMarker < 0) return text.substring(0, begin).trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }
        var after = endMarker + END.length
        if (after < text.length && text[after] == '\n') after += 1
        val result = text.substring(0, begin) + text.substring(after)
        return result
    }

    /** The first `claude` on PATH that is NOT inside [binDir]; null if there is none. */
    fun realClaudeOutside(binDir: File): File? {
        val onPath = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            .map { File(it, "claude") }
            .firstOrNull { it.canExecute() && it.parentFile?.canonicalFile != binDir.canonicalFile }
        if (onPath != null) return onPath
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin/claude", "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "/usr/bin/claude",
        ).map { File(it) }.firstOrNull { it.canExecute() && it.parentFile?.canonicalFile != binDir.canonicalFile }
    }

    private fun readShimResource(): String =
        ShimInstaller::class.java.getResourceAsStream("/claude-shim.py")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("bundled claude-shim.py resource missing")
}
