package com.claudedriver.backend.installer

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Assembles a per-machine, self-contained agent installer on the fly: it copies the OS-specific
 * self-contained runtime (bundled JRE, no Java required) from S3 and injects that machine's
 * `agent.config` (backend URL, connect URL, machine id, one-time enrollment code) plus an install
 * script that registers an always-on service and lets the agent auto-enroll on first run.
 *
 * The runtime is streamed entry-by-entry so memory stays bounded regardless of package size.
 */
class InstallerService(
    private val runtimesBucket: String?,
    region: String = System.getenv("AWS_REGION") ?: "us-east-1",
) {
    private val regionValue = region
    private val s3: S3Client by lazy {
        S3Client.builder()
            .region(Region.of(regionValue))
            .httpClient(UrlConnectionHttpClient.create())
            .build()
    }

    data class Target(val runtimeKey: String, val scriptName: String, val filename: String)

    fun targetFor(os: String): Target? = when (os.lowercase()) {
        "macos", "mac", "darwin" -> Target("agent-macos-arm64.zip", "install.command", "claudedriver-agent-macos.zip")
        "windows", "win" -> Target("agent-windows-x64.zip", "install.ps1", "claudedriver-agent-windows.zip")
        "linux" -> Target("agent-linux-x64.zip", "install.sh", "claudedriver-agent-linux.zip")
        else -> null
    }

    val configured: Boolean get() = !runtimesBucket.isNullOrBlank()

    /**
     * Open the OS runtime archive stream from S3. Throws (S3Exception, etc.) if it cannot be
     * fetched — call this BEFORE committing the HTTP response so failures surface as a real error
     * status rather than a truncated/empty download.
     */
    fun openRuntime(os: String): InputStream {
        val bucket = requireNotNull(runtimesBucket) { "Agent runtimes bucket is not configured" }
        val target = requireNotNull(targetFor(os)) { "Unsupported OS: $os" }
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(target.runtimeKey).build())
    }

    /**
     * Stream a per-machine installer zip to [out]: the already-opened runtime archive copied through
     * verbatim, plus `agent.config`, the install script, and a short README. [runtimeIn] comes from
     * [openRuntime]; the caller owns closing it.
     */
    fun writeInstaller(os: String, runtimeIn: InputStream, agentConfigJson: String, out: OutputStream) {
        val target = requireNotNull(targetFor(os)) { "Unsupported OS: $os" }
        ZipOutputStream(out).use { zos ->
            // 1) Copy the self-contained runtime archive entry-by-entry (streamed).
            ZipInputStream(runtimeIn).use { zin ->
                val buf = ByteArray(64 * 1024)
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        var n = zin.read(buf)
                        while (n >= 0) {
                            zos.write(buf, 0, n)
                            n = zin.read(buf)
                        }
                        zos.closeEntry()
                    }
                    entry = zin.nextEntry
                }
            }
            // 2) Inject the per-machine config + install script + README.
            putEntry(zos, "agent.config", agentConfigJson)
            putEntry(zos, target.scriptName, installScript(os))
            putEntry(zos, "README.txt", readme(os, target.scriptName))
        }
    }

    private fun putEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun installScript(os: String): String = when (os.lowercase()) {
        "windows", "win" -> WINDOWS_INSTALL_PS1
        "linux" -> LINUX_INSTALL_SH
        else -> MACOS_INSTALL_COMMAND
    }

    private fun readme(os: String, script: String): String = """
        ClaudeDriver agent — self-contained (no Java required).

        macOS/Linux:  unzip, then run  ./$script
        Windows:      unzip, then right-click $script -> Run with PowerShell
                      (or:  powershell -ExecutionPolicy Bypass -File $script)

        The installer registers an always-on background service and the agent auto-enrolls on first
        start using the embedded one-time code in agent.config. To remove it, de-register the machine
        in the dashboard and delete the installed app + service.
    """.trimIndent()

    companion object {
        // launchd LaunchAgent; auto-starts at login, restarts on crash. Clears quarantine + restores
        // exec bits lost when the runtime is re-zipped through java.util.zip.
        private val MACOS_INSTALL_COMMAND = """
            #!/bin/bash
            set -euo pipefail
            HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            APP="${'$'}HOME/Applications/ClaudeDriverAgent.app"
            AGENT_DIR="${'$'}HOME/.claudedriver-agent"
            PLIST="${'$'}HOME/Library/LaunchAgents/com.claudedriver.agent.plist"

            echo "Installing ClaudeDriver agent..."
            mkdir -p "${'$'}HOME/Applications" "${'$'}AGENT_DIR" "${'$'}HOME/Library/LaunchAgents"
            rm -rf "${'$'}APP"
            cp -R "${'$'}HERE/ClaudeDriverAgent.app" "${'$'}APP"
            xattr -dr com.apple.quarantine "${'$'}APP" 2>/dev/null || true
            chmod -R u+x "${'$'}APP/Contents/MacOS" 2>/dev/null || true
            find "${'$'}APP/Contents/runtime" -type f -path '*/bin/*' -exec chmod u+x {} + 2>/dev/null || true
            cp "${'$'}HERE/agent.config" "${'$'}AGENT_DIR/agent.config"

            cat > "${'$'}PLIST" <<PLIST
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>Label</key><string>com.claudedriver.agent</string>
              <key>ProgramArguments</key><array><string>${'$'}APP/Contents/MacOS/ClaudeDriverAgent</string></array>
              <key>EnvironmentVariables</key><dict><key>CLAUDEDRIVER_AGENT_DIR</key><string>${'$'}AGENT_DIR</string></dict>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><true/>
              <key>StandardOutPath</key><string>${'$'}AGENT_DIR/agent.log</string>
              <key>StandardErrorPath</key><string>${'$'}AGENT_DIR/agent.err.log</string>
            </dict></plist>
            PLIST

            launchctl unload "${'$'}PLIST" 2>/dev/null || true
            launchctl load "${'$'}PLIST"
            echo "Installed and started. Logs: ${'$'}AGENT_DIR/agent.log"
        """.trimIndent() + "\n"

        // Scheduled task at logon (no admin needed). Windows app-image layout: ClaudeDriverAgent\.
        private val WINDOWS_INSTALL_PS1 = """
            ${'$'}ErrorActionPreference = "Stop"
            ${'$'}here = Split-Path -Parent ${'$'}MyInvocation.MyCommand.Path
            ${'$'}dest = Join-Path ${'$'}env:LOCALAPPDATA "ClaudeDriverAgent"
            ${'$'}agentDir = Join-Path ${'$'}env:USERPROFILE ".claudedriver-agent"

            Write-Host "Installing ClaudeDriver agent..."
            New-Item -ItemType Directory -Force -Path ${'$'}dest, ${'$'}agentDir | Out-Null
            Copy-Item -Recurse -Force (Join-Path ${'$'}here "ClaudeDriverAgent\*") ${'$'}dest
            Copy-Item -Force (Join-Path ${'$'}here "agent.config") (Join-Path ${'$'}agentDir "agent.config")
            [Environment]::SetEnvironmentVariable("CLAUDEDRIVER_AGENT_DIR", ${'$'}agentDir, "User")

            ${'$'}exe = Join-Path ${'$'}dest "ClaudeDriverAgent.exe"
            schtasks /Create /TN "ClaudeDriverAgent" /TR "`"${'$'}exe`"" /SC ONLOGON /RL LIMITED /F | Out-Null
            schtasks /Run /TN "ClaudeDriverAgent" | Out-Null
            Write-Host "Installed and started (Task Scheduler: ClaudeDriverAgent)."
        """.trimIndent() + "\n"

        private val LINUX_INSTALL_SH = """
            #!/bin/bash
            set -euo pipefail
            HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            DEST="${'$'}HOME/.local/share/ClaudeDriverAgent"
            AGENT_DIR="${'$'}HOME/.claudedriver-agent"
            mkdir -p "${'$'}DEST" "${'$'}AGENT_DIR" "${'$'}HOME/.config/systemd/user"
            cp -R "${'$'}HERE/ClaudeDriverAgent/." "${'$'}DEST/"
            chmod -R u+x "${'$'}DEST/bin" 2>/dev/null || true
            cp "${'$'}HERE/agent.config" "${'$'}AGENT_DIR/agent.config"
            cat > "${'$'}HOME/.config/systemd/user/claudedriver-agent.service" <<UNIT
            [Unit]
            Description=ClaudeDriver agent
            [Service]
            Environment=CLAUDEDRIVER_AGENT_DIR=${'$'}AGENT_DIR
            ExecStart=${'$'}DEST/bin/ClaudeDriverAgent
            Restart=always
            [Install]
            WantedBy=default.target
            UNIT
            systemctl --user daemon-reload
            systemctl --user enable --now claudedriver-agent.service
            echo "Installed and started (systemd --user: claudedriver-agent)."
        """.trimIndent() + "\n"
    }
}
