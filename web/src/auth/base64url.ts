/**
 * base64url <-> ArrayBuffer helpers for WebAuthn.
 *
 * The WebAuthn JSON that the server sends (PublicKeyCredentialCreationOptions /
 * RequestOptions) encodes binary fields (`challenge`, `user.id`, credential
 * `id`s) as base64url strings. The browser `navigator.credentials` API needs
 * those as `ArrayBuffer`/`BufferSource`, and returns binary results that we must
 * re-encode as base64url to send back. These helpers do exactly that, with no
 * padding (base64url convention).
 */

export function base64urlToArrayBuffer(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const pad = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
  const binary = atob(padded + pad);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

export function arrayBufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
