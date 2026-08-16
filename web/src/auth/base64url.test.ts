import { describe, expect, it } from 'vitest';
import { arrayBufferToBase64url, base64urlToArrayBuffer } from './base64url';

describe('base64url WebAuthn codec', () => {
  it('round-trips arbitrary bytes', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253, 254, 255]);
    const encoded = arrayBufferToBase64url(bytes.buffer);
    expect(encoded).not.toContain('+');
    expect(encoded).not.toContain('/');
    expect(encoded).not.toContain('=');
    const decoded = new Uint8Array(base64urlToArrayBuffer(encoded));
    expect(Array.from(decoded)).toEqual(Array.from(bytes));
  });

  it('decodes a known unpadded base64url challenge', () => {
    // "hello" -> base64url "aGVsbG8"
    const decoded = new Uint8Array(base64urlToArrayBuffer('aGVsbG8'));
    expect(new TextDecoder().decode(decoded)).toBe('hello');
  });

  it('handles base64url with - and _ (bytes 0xff 0xfe 0xff)', () => {
    // 0xff 0xff 0xff -> "____"; 0xfb 0xff -> "-_8"
    expect(arrayBufferToBase64url(new Uint8Array([0xff, 0xff, 0xff]).buffer)).toBe('____');
    const decoded = new Uint8Array(base64urlToArrayBuffer('-_8'));
    expect(Array.from(decoded)).toEqual([0xfb, 0xff]);
  });
});
