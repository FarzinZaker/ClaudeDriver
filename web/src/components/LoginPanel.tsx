import { useState } from 'react';
import { AuthError, loginWithPasskey, registerPasskey } from '../auth/webauthn';

interface Props {
  /** Called after a successful login or registration so the app can re-seed status. */
  onAuthenticated: () => void;
}

type Mode = 'login' | 'register';

/**
 * Operator sign-in. Two modes:
 *  - Login: passkey assertion -> session cookie.
 *  - First-time register: bootstrap code + passkey creation (first operator only).
 */
export function LoginPanel({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [bootstrapCode, setBootstrapCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onAuthenticated();
    } catch (e) {
      if (e instanceof AuthError) {
        setError(e.message);
      } else if (e instanceof DOMException) {
        // navigator.credentials rejections (cancelled, timeout, etc.)
        setError(e.message || 'Passkey request was cancelled');
      } else {
        setError('Unexpected error during authentication');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-panel card">
      <h1>ClaudeDriver</h1>
      <p className="subtitle">Operator sign-in</p>

      <div className="tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'login'}
          className={`tab ${mode === 'login' ? 'tab--active' : ''}`}
          onClick={() => setMode('login')}
        >
          Sign in
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'register'}
          className={`tab ${mode === 'register' ? 'tab--active' : ''}`}
          onClick={() => setMode('register')}
        >
          First-time setup
        </button>
      </div>

      {mode === 'login' ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void run(loginWithPasskey);
          }}
        >
          <p className="hint">Authenticate with your passkey.</p>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Waiting for passkey…' : 'Sign in with passkey'}
          </button>
        </form>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void run(() => registerPasskey(bootstrapCode.trim()));
          }}
        >
          <p className="hint">
            First operator only. Enter the bootstrap code, then create a passkey.
          </p>
          <label className="field">
            <span>Bootstrap code</span>
            <input
              type="password"
              autoComplete="one-time-code"
              value={bootstrapCode}
              onChange={(e) => setBootstrapCode(e.target.value)}
              required
            />
          </label>
          <button
            type="submit"
            className="btn btn--primary"
            disabled={busy || bootstrapCode.trim().length === 0}
          >
            {busy ? 'Creating passkey…' : 'Register passkey'}
          </button>
        </form>
      )}

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
