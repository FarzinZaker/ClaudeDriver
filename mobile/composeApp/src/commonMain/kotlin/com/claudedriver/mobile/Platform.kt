package com.claudedriver.mobile

import io.ktor.client.HttpClient

/** Platform-specific capabilities, implemented per target (androidMain / iosMain). */

expect fun platformName(): String

/** An HTTP client engine for this platform (OkHttp on Android, Darwin on iOS), with cookies. */
expect fun httpClient(): HttpClient

/**
 * Passkey (WebAuthn) sign-in against the backend `/auth/login/options` + `/auth/login/verify`.
 * Android: Credential Manager; iOS: ASAuthorizationPlatformPublicKeyCredentialProvider.
 * Returns true on success (session cookie established).
 */
expect suspend fun signInWithPasskey(baseUrl: String): Boolean

/** The device's push token (FCM on Android, APNs on iOS), or null if unavailable. */
expect suspend fun pushToken(): String?
