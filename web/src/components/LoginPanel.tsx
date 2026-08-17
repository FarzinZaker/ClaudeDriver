import { useState } from 'react';
import { AuthError, login, registerOperator } from '../auth/auth';

interface Props {
  /** Called after a successful login or registration so the app can re-seed status. */
  onAuthenticated: () => void;
}

type Mode = 'login' | 'register';

/**
 * Operator sign-in. Two modes:
 *  - Login: username + password.
 *  - First-time register: username + password + bootstrap code (first operator only).
 */
export function LoginPanel({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
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
      setError(e instanceof AuthError ? e.message : 'Unexpected error during authentication');
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

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (mode === 'login') {
            void run(() => login(username.trim(), password));
          } else {
            void run(() => registerOperator(username.trim(), password, bootstrapCode.trim()));
          }
        }}
      >
        <label className="field">
          <span>Username</span>
          <input
            type="text"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {mode === 'register' && (
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
        )}
        <button
          type="submit"
          className="btn btn--primary"
          disabled={
            busy ||
            username.trim().length === 0 ||
            password.length === 0 ||
            (mode === 'register' && bootstrapCode.trim().length === 0)
          }
        >
          {busy
            ? mode === 'login'
              ? 'Signing in…'
              : 'Creating account…'
            : mode === 'login'
              ? 'Sign in'
              : 'Create operator'}
        </button>
        {mode === 'register' && (
          <p className="hint">First operator only. Password must be at least 8 characters.</p>
        )}
      </form>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
