/**
 * Self-hosted WebAuthn passkey auth for the operator client.
 *
 * Drives the documented endpoints (rest-api.md, "Operator — WebAuthn passkey
 * authentication (self-hosted)"):
 *
 *   Registration (first operator only, gated by a bootstrap code):
 *     POST /auth/register/options  { bootstrapCode }  -> PublicKeyCredentialCreationOptions (JSON)
 *     POST /auth/register/verify   <attestation>      -> 201
 *
 *   Login (returns a signed session cookie):
 *     POST /auth/login/options     {}                 -> PublicKeyCredentialRequestOptions (JSON)
 *     POST /auth/login/verify      <assertion>        -> 200 + Set-Cookie (session)
 *
 *   POST /auth/logout -> 204
 *
 * The server speaks the standard WebAuthn JSON dialect: binary fields
 * (`challenge`, `user.id`, allow/exclude credential `id`s) are base64url
 * strings. We decode them for `navigator.credentials`, and re-encode the
 * browser's binary response for the verify call.
 */

import { arrayBufferToBase64url, base64urlToArrayBuffer } from './base64url';

// --- server JSON shapes (base64url-encoded binary fields) -------------------

interface ServerCredentialDescriptor {
  type: PublicKeyCredentialType;
  id: string; // base64url
  transports?: AuthenticatorTransport[];
}

interface ServerCreationOptions {
  challenge: string; // base64url
  rp: PublicKeyCredentialRpEntity;
  user: { id: string; name: string; displayName: string }; // id base64url
  pubKeyCredParams: PublicKeyCredentialParameters[];
  timeout?: number;
  attestation?: AttestationConveyancePreference;
  excludeCredentials?: ServerCredentialDescriptor[];
  authenticatorSelection?: AuthenticatorSelectionCriteria;
}

interface ServerRequestOptions {
  challenge: string; // base64url
  timeout?: number;
  rpId?: string;
  userVerification?: UserVerificationRequirement;
  allowCredentials?: ServerCredentialDescriptor[];
}

// --- error type -------------------------------------------------------------

export class AuthError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'AuthError';
  }
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  if (!res.ok) {
    let code: string | undefined;
    let message = `${res.status} ${res.statusText}`;
    try {
      const err = (await res.json()) as { error?: string; message?: string };
      code = err.error;
      if (err.message) message = err.message;
    } catch {
      // non-JSON error body; keep status text
    }
    throw new AuthError(message, res.status, code);
  }
  // 204 has no body.
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

// --- decode helpers ---------------------------------------------------------

function decodeCredentialDescriptors(
  list: ServerCredentialDescriptor[] | undefined,
): PublicKeyCredentialDescriptor[] | undefined {
  return list?.map((d) => ({
    type: d.type,
    id: base64urlToArrayBuffer(d.id),
    transports: d.transports,
  }));
}

function toCreationOptions(
  opts: ServerCreationOptions,
): PublicKeyCredentialCreationOptions {
  return {
    ...opts,
    challenge: base64urlToArrayBuffer(opts.challenge),
    user: {
      id: base64urlToArrayBuffer(opts.user.id),
      name: opts.user.name,
      displayName: opts.user.displayName,
    },
    excludeCredentials: decodeCredentialDescriptors(opts.excludeCredentials),
  };
}

function toRequestOptions(
  opts: ServerRequestOptions,
): PublicKeyCredentialRequestOptions {
  return {
    ...opts,
    challenge: base64urlToArrayBuffer(opts.challenge),
    allowCredentials: decodeCredentialDescriptors(opts.allowCredentials),
  };
}

// --- encode the browser's PublicKeyCredential for the verify calls ----------

function encodeRegistrationCredential(cred: PublicKeyCredential) {
  const att = cred.response as AuthenticatorAttestationResponse;
  return {
    id: cred.id,
    rawId: arrayBufferToBase64url(cred.rawId),
    type: cred.type,
    clientExtensionResults: cred.getClientExtensionResults(),
    response: {
      clientDataJSON: arrayBufferToBase64url(att.clientDataJSON),
      attestationObject: arrayBufferToBase64url(att.attestationObject),
      transports:
        typeof att.getTransports === 'function' ? att.getTransports() : undefined,
    },
  };
}

function encodeAssertionCredential(cred: PublicKeyCredential) {
  const asrt = cred.response as AuthenticatorAssertionResponse;
  return {
    id: cred.id,
    rawId: arrayBufferToBase64url(cred.rawId),
    type: cred.type,
    clientExtensionResults: cred.getClientExtensionResults(),
    response: {
      clientDataJSON: arrayBufferToBase64url(asrt.clientDataJSON),
      authenticatorData: arrayBufferToBase64url(asrt.authenticatorData),
      signature: arrayBufferToBase64url(asrt.signature),
      userHandle: asrt.userHandle ? arrayBufferToBase64url(asrt.userHandle) : null,
    },
  };
}

function assertWebAuthnAvailable(): void {
  if (
    typeof window === 'undefined' ||
    !window.PublicKeyCredential ||
    !navigator.credentials
  ) {
    throw new AuthError('WebAuthn/passkeys are not available in this browser');
  }
}

// --- public API -------------------------------------------------------------

/**
 * First-operator registration, gated by a one-time bootstrap code.
 * Creates a passkey via `navigator.credentials.create` and stores it server-side.
 */
export async function registerPasskey(bootstrapCode: string): Promise<void> {
  assertWebAuthnAvailable();

  const serverOptions = await postJson<ServerCreationOptions>(
    '/auth/register/options',
    { bootstrapCode },
  );

  const credential = (await navigator.credentials.create({
    publicKey: toCreationOptions(serverOptions),
  })) as PublicKeyCredential | null;

  if (!credential) {
    throw new AuthError('Passkey creation was cancelled or failed');
  }

  await postJson<void>('/auth/register/verify', encodeRegistrationCredential(credential));
}

/**
 * Passkey login. On success the server sets a signed session cookie
 * (handled by the browser; `credentials: 'include'` on every call).
 */
export async function loginWithPasskey(): Promise<void> {
  assertWebAuthnAvailable();

  const serverOptions = await postJson<ServerRequestOptions>('/auth/login/options', {});

  const credential = (await navigator.credentials.get({
    publicKey: toRequestOptions(serverOptions),
  })) as PublicKeyCredential | null;

  if (!credential) {
    throw new AuthError('Passkey login was cancelled or failed');
  }

  await postJson<void>('/auth/login/verify', encodeAssertionCredential(credential));
}

/** Clears the operator session server-side. */
export async function logout(): Promise<void> {
  await postJson<void>('/auth/logout', {});
}
