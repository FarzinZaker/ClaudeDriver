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
    fun shimFile(home: File) = File(binDir(home), if (isWindows()) "claude.exe" else "claude")

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
        return if (isWindows()) installWindows(home, bin) else installPosix(home, bin)
    }

    private fun installPosix(home: File, bin: File): Boolean {
        bin.mkdirs()
        val shim = File(bin, "claude")
        shim.writeText(readShimResource("/claude-shim.py"))
        shim.setExecutable(true, false)
        RC_FILES.map { File(home, it) }.filter { it.exists() || it.name == ".zshrc" }
            .forEach { ensureRcBlock(it, bin) }
        return true
    }

    /**
     * Windows: drop the arch-matched native shim at `%USERPROFILE%\.claudedriver\bin\claude.exe`
     * and prepend that dir to the user PATH (HKCU\Environment) so new consoles pick it up. Returns
     * false if the native shim was not bundled into this build (dev/test jars) — never throws.
     */
    private fun installWindows(home: File, bin: File): Boolean {
        val exeBytes = readBinaryResource("/claude-shim-win-${winArch()}.exe") ?: return false
        bin.mkdirs()
        File(bin, "claude.exe").writeBytes(exeBytes)
        prependWindowsUserPath(bin)
        return true
    }

    /** Prepend [bin] to the persistent user PATH via `reg`, if not already the first entry. */
    private fun prependWindowsUserPath(bin: File) {
        val current = readWindowsUserPath()
        val updated = windowsPrependPath(current, bin.absolutePath)
        if (updated == current) return
        runCatching {
            ProcessBuilder("reg", "add", "HKCU\\Environment", "/v", "Path", "/t", "REG_EXPAND_SZ", "/d", updated, "/f")
                .redirectErrorStream(true).start().waitFor()
        }
    }

    private fun readWindowsUserPath(): String = runCatching {
        val p = ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", "Path").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        // A REG_SZ/REG_EXPAND_SZ line looks like: "    Path    REG_EXPAND_SZ    C:\a;C:\b"
        out.lineSequence().firstOrNull { it.trimStart().startsWith("Path", ignoreCase = true) }
            ?.substringAfter("REG_")?.substringAfter("SZ")?.trim().orEmpty()
    }.getOrDefault("")

    /** Pure: put [binPath] first in a `;`-delimited PATH, removing any existing copy. */
    internal fun windowsPrependPath(current: String, binPath: String): String {
        val parts = current.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.equals(binPath, ignoreCase = true) }
        return (listOf(binPath) + parts).joinToString(";")
    }

    /** Remove the shim + every marker-guarded PATH block. Idempotent. (POSIX rc side; the Windows
     *  user-PATH entry is left for the uninstaller, which owns registry changes.) */
    fun teardown(home: File) {
        File(binDir(home), "claude").delete()
        File(binDir(home), "claude.exe").delete()
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

    /** The first real `claude` launcher on PATH that is NOT inside [binDir]; null if there is none. */
    fun realClaudeOutside(binDir: File): File? {
        val names = if (isWindows()) listOf("claude.exe", "claude.cmd", "claude.bat", "claude") else listOf("claude")
        fun ok(f: File) = f.canExecute() && f.parentFile?.canonicalFile != binDir.canonicalFile
        System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
            names.forEach { n -> File(dir, n).takeIf(::ok)?.let { return it } }
        }
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin/claude", "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "/usr/bin/claude",
        ).map { File(it) }.firstOrNull(::ok)
    }

    fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun winArch(): String =
        if (System.getProperty("os.arch").orEmpty().lowercase().contains("aarch64")) "arm64" else "amd64"

    private fun readShimResource(path: String): String =
        ShimInstaller::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("bundled shim resource missing: $path")

    private fun readBinaryResource(path: String): ByteArray? =
        ShimInstaller::class.java.getResourceAsStream(path)?.use { it.readBytes() }
}
