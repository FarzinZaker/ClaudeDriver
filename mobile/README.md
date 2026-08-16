# ClaudeDriver Mobile (Compose Multiplatform)

The operator's phone app for iOS + Android: passkey sign-in, live monitoring parity, and remote
**approve/deny** of Claude Code permission prompts, with push notifications.

> **Status: scaffold.** This module is intentionally **NOT** part of the root Gradle build, so the
> core system's CI stays green without the mobile toolchains. It was authored in an environment
> without the Android SDK, Xcode, or a simulator, so it has **not been compiled or run**. It is a
> real starting point — shared contract, backend client, and Compose UI are wired — that needs to be
> finished and built with the mobile toolchain.

## What's here

```
mobile/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
└── composeApp/
    ├── build.gradle.kts                 # KMP + Compose Multiplatform (android + iosX64/Arm64/Sim)
    └── src/
        ├── commonMain/kotlin/com/claudedriver/mobile/
        │   ├── Contract.kt              # wire DTOs — mirror shared/ (unify via KMP when built)
        │   ├── Api.kt                   # Ktor client: /auth, /status, /sessions, /alerts, /approvals, /devices
        │   ├── Platform.kt              # expect: passkey sign-in + push token registration
        │   └── App.kt                   # Compose UI: login → dashboard + approvals (approve/deny)
        ├── androidMain/…                # actual: FCM token + WebAuthn (Credential Manager); MainActivity
        └── iosMain/…                    # actual: APNs token + ASAuthorization passkey; MainViewController
```

## To finish & build (follow-up, needs the toolchain)

1. Install the **Android SDK** (and set `local.properties` `sdk.dir`) and **Xcode**.
2. **Unify the contract**: replace `Contract.kt` by consuming the root `:shared` module via a Gradle
   composite build (`includeBuild("..")`) or a published artifact — so the wire types are defined
   once (Constitution Principle III).
3. **Passkeys**: Android via the Credential Manager API + WebAuthn; iOS via
   `ASAuthorizationPlatformPublicKeyCredentialProvider`, both against the Phase 0 `/auth/*` endpoints.
4. **Push**: Android via Firebase (FCM) `FirebaseMessagingService`; iOS via APNs
   (`UNUserNotificationCenter`) — commonly routed through FCM. Upload the token via `POST /devices`;
   the backend's `SnsPushSender` (Amazon SNS mobile push) delivers.
5. **Remote-by-default**: the app talks to the AWS-hosted backend over the internet (no LAN
   dependency; no reliance on the iOS Local Network permission), per the Phase 0 decision.
6. Build: `./gradlew :composeApp:assembleDebug` (Android) and open the Xcode project for iOS.

## Screens (in `App.kt`)

- **Login** — passkey sign-in.
- **Dashboard** — machines → sessions with live state (parity with the web).
- **Approvals** — pending tool-permission requests with **Approve** / **Deny**; live via the operator
  WebSocket `approval_event`; the same decision path as the web (`POST /approvals/{id}/decide`).
