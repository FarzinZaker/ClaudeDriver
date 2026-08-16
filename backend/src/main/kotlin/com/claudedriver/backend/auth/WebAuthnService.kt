package com.claudedriver.backend.auth

import com.claudedriver.backend.config.Config
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.UserIdentity
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Result of a successful passkey authentication. */
data class OperatorAuthenticated(val operatorId: UUID, val handle: String)

/**
 * Self-hosted WebAuthn (passkey) operator authentication — no external identity provider
 * (Constitution Principle I). Wraps Yubico's [RelyingParty]; pending challenges are held
 * server-side keyed by a token placed in the operator's session.
 */
class WebAuthnService(private val config: Config, private val store: OperatorStore) {

    private val credentialRepository = object : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> {
            val op = store.soleOperator() ?: return emptySet()
            if (op.second != username) return emptySet()
            return store.credentialsForOperator(op.first)
                .map { PublicKeyCredentialDescriptor.builder().id(ByteArray(it.credentialId)).build() }
                .toSet()
        }

        override fun getUserHandleForUsername(username: String): Optional<ByteArray> {
            val op = store.soleOperator() ?: return Optional.empty()
            if (op.second != username) return Optional.empty()
            return Optional.of(ByteArray(OperatorStore.uuidToBytes(op.first)))
        }

        override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> {
            val op = store.soleOperator() ?: return Optional.empty()
            if (!userHandle.bytes.contentEquals(OperatorStore.uuidToBytes(op.first))) return Optional.empty()
            return Optional.of(op.second)
        }

        override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> {
            val cred = store.findCredential(credentialId.bytes) ?: return Optional.empty()
            return Optional.of(
                RegisteredCredential.builder()
                    .credentialId(ByteArray(cred.credentialId))
                    .userHandle(ByteArray(OperatorStore.uuidToBytes(cred.operatorId)))
                    .publicKeyCose(ByteArray(cred.publicKeyCose))
                    .signatureCount(cred.signCount)
                    .build(),
            )
        }

        override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> =
            lookup(credentialId, ByteArray(kotlin.ByteArray(0))).map { setOf(it) }.orElse(emptySet())
    }

    private val rp: RelyingParty = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(config.webAuthnRpId).name(config.webAuthnRpName).build())
        .credentialRepository(credentialRepository)
        .origins(setOf(config.webAuthnOrigin))
        .build()

    private data class PendingRegistration(val operatorId: UUID, val handle: String, val options: PublicKeyCredentialCreationOptions)

    private val pendingRegistrations = ConcurrentHashMap<String, PendingRegistration>()
    private val pendingAssertions = ConcurrentHashMap<String, AssertionRequest>()

    data class Challenge(val token: String, val json: String)

    /** Begin first-operator passkey registration. */
    fun startRegistration(handle: String): Challenge {
        val operatorId = UUID.randomUUID()
        val user = UserIdentity.builder()
            .name(handle)
            .displayName(handle)
            .id(ByteArray(OperatorStore.uuidToBytes(operatorId)))
            .build()
        val options = rp.startRegistration(StartRegistrationOptions.builder().user(user).build())
        val token = UUID.randomUUID().toString()
        pendingRegistrations[token] = PendingRegistration(operatorId, handle, options)
        return Challenge(token, options.toCredentialsCreateJson())
    }

    /** Complete registration, persisting the operator and their passkey. */
    fun finishRegistration(token: String, responseJson: String): OperatorAuthenticated {
        val pending = pendingRegistrations.remove(token)
            ?: throw IllegalStateException("No pending registration for token")
        val pkc = PublicKeyCredential.parseRegistrationResponseJson(responseJson)
        val result = rp.finishRegistration(
            FinishRegistrationOptions.builder().request(pending.options).response(pkc).build(),
        )
        store.createOperator(pending.operatorId, pending.handle)
        store.addCredential(
            operatorId = pending.operatorId,
            credentialId = result.keyId.id.bytes,
            publicKeyCose = result.publicKeyCose.bytes,
            signCount = result.signatureCount,
        )
        return OperatorAuthenticated(pending.operatorId, pending.handle)
    }

    /** Begin passkey login for the sole operator. */
    fun startAssertion(): Challenge {
        val op = store.soleOperator() ?: throw IllegalStateException("No operator registered")
        val request = rp.startAssertion(StartAssertionOptions.builder().username(op.second).build())
        val token = UUID.randomUUID().toString()
        pendingAssertions[token] = request
        return Challenge(token, request.toCredentialsGetJson())
    }

    /** Complete login; Yubico enforces signature-counter regression (cloned-authenticator guard). */
    fun finishAssertion(token: String, responseJson: String): OperatorAuthenticated {
        val request = pendingAssertions.remove(token)
            ?: throw IllegalStateException("No pending assertion for token")
        val pkc = PublicKeyCredential.parseAssertionResponseJson(responseJson)
        val result = rp.finishAssertion(
            FinishAssertionOptions.builder().request(request).response(pkc).build(),
        )
        check(result.isSuccess) { "Assertion failed" }
        store.updateSignCount(result.credentialId.bytes, result.signatureCount)
        val operatorId = OperatorStore.bytesToUuid(result.userHandle.bytes)
        return OperatorAuthenticated(operatorId, result.username)
    }
}
