package com.claudedriver.backend.config

/**
 * Runtime configuration. All values come from the environment (env vars / SSM in prod, `.env` in
 * local dev) — never from committed files (Constitution Principle I). Missing REQUIRED values fail
 * fast at startup (FR-003) rather than starting half-configured.
 */
data class Config(
    val env: String,
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val sessionSigningKey: String,
    val webAuthnRpId: String,
    val webAuthnRpName: String,
    val webAuthnOrigin: String,
    val operatorBootstrapCode: String,
    val caCertPath: String? = null,
    val caKeyPath: String? = null,
    val caCertPem: String? = null,
    val caKeyPem: String? = null,
    // Endpoints embedded into per-machine agent installers, and the S3 bucket holding the
    // self-contained per-OS agent runtimes the backend wraps.
    val agentPublicUrl: String = "http://localhost:8080",
    val agentConnectUrl: String = "http://localhost:8080",
    val agentRuntimesBucket: String? = null,
) {
    companion object {
        private fun required(name: String): String {
            val v = System.getenv(name)
            require(!v.isNullOrBlank()) { "Required configuration '$name' is missing or empty" }
            return v
        }

        private fun optional(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        /** Derive the agent mTLS connect URL (…:8443) from the public origin unless overridden. */
        private fun deriveConnectUrl(origin: String): String =
            runCatching {
                val u = java.net.URI(origin)
                val scheme = u.scheme ?: "https"
                "$scheme://${u.host}:8443"
            }.getOrDefault(origin)

        fun fromEnv(): Config {
            val env = optional("CLAUDEDRIVER_ENV", "dev")
            val isProd = env == "prod"
            // In prod every secret is required; local dev gets safe non-secret defaults.
            fun secret(name: String, devDefault: String): String =
                if (isProd) required(name) else optional(name, devDefault)

            return Config(
                env = env,
                host = optional("BACKEND_HOST", "0.0.0.0"),
                port = optional("BACKEND_PORT", "8080").toInt(),
                databaseUrl = optional("DATABASE_URL", "jdbc:postgresql://localhost:5432/claudedriver"),
                databaseUser = optional("DATABASE_USER", "claudedriver"),
                databasePassword = secret("DATABASE_PASSWORD", "claudedriver"),
                sessionSigningKey = secret("SESSION_SIGNING_KEY", "dev-insecure-session-key-change-me-0000000000"),
                webAuthnRpId = optional("WEBAUTHN_RP_ID", "localhost"),
                webAuthnRpName = optional("WEBAUTHN_RP_NAME", "ClaudeDriver"),
                webAuthnOrigin = optional("WEBAUTHN_ORIGIN", "http://localhost:5173"),
                operatorBootstrapCode = secret("OPERATOR_BOOTSTRAP_CODE", "dev-bootstrap"),
                caCertPath = System.getenv("CA_CERT_PATH")?.takeIf { it.isNotBlank() },
                caKeyPath = System.getenv("CA_KEY_PATH")?.takeIf { it.isNotBlank() },
                caCertPem = System.getenv("CA_CERT_PEM")?.takeIf { it.isNotBlank() },
                caKeyPem = System.getenv("CA_KEY_PEM")?.takeIf { it.isNotBlank() },
                agentPublicUrl = optional("AGENT_PUBLIC_URL", optional("WEBAUTHN_ORIGIN", "http://localhost:8080")),
                agentConnectUrl = optional(
                    "AGENT_CONNECT_URL",
                    deriveConnectUrl(optional("WEBAUTHN_ORIGIN", "http://localhost:8080")),
                ),
                agentRuntimesBucket = System.getenv("AGENT_RUNTIMES_BUCKET")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
