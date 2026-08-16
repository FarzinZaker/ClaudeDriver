package com.claudedriver.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformName(): String = "Android"

actual fun httpClient(): HttpClient = HttpClient(OkHttp)

actual suspend fun signInWithPasskey(baseUrl: String): Boolean {
    // TODO: Credential Manager + WebAuthn against /auth/login/options + /auth/login/verify.
    return false
}

actual suspend fun pushToken(): String? {
    // TODO: FirebaseMessaging.getInstance().token
    return null
}
