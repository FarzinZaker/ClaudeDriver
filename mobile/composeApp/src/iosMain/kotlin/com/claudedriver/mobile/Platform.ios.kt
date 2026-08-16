package com.claudedriver.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformName(): String = "iOS"

actual fun httpClient(): HttpClient = HttpClient(Darwin)

actual suspend fun signInWithPasskey(baseUrl: String): Boolean {
    // TODO: ASAuthorizationPlatformPublicKeyCredentialProvider against /auth/login/*.
    return false
}

actual suspend fun pushToken(): String? {
    // TODO: register with APNs (UNUserNotificationCenter) — commonly routed via FCM.
    return null
}
