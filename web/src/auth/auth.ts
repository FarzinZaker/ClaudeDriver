/**
 * Operator username + password auth.
 *
 *   Registration (first operator only, gated by a bootstrap code):
 *     POST /auth/register  { username, password, bootstrapCode }  -> 201 + session cookie
 *   Login:
 *     POST /auth/login     { username, password }                 -> 200 + session cookie
 *   POST /auth/logout -> 204
 */

export class AuthError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'AuthError';
  }
}

async function postJson(url: string, body: unknown): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const err = (await res.json()) as { message?: string };
      if (err.message) message = err.message;
    } catch {
      // keep default
    }
    throw new AuthError(message, res.status);
  }
}

export function registerOperator(
  username: string,
  password: string,
  bootstrapCode: string,
): Promise<void> {
  return postJson('/auth/register', { username, password, bootstrapCode });
}

export function login(username: string, password: string): Promise<void> {
  return postJson('/auth/login', { username, password });
}

export function logout(): Promise<void> {
  return postJson('/auth/logout', {});
}
