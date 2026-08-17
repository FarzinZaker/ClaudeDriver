import { useState } from 'react';
import { ApiError, enrollMachine, type EnrollmentTicket } from '../api';
import type { MachineOs } from '../types';

interface Props {
  /** Called after a successful enrollment so the caller can refresh the machine list. */
  onEnrolled: () => void;
}

/** Backend origin (enrollment, :443) and the agent mTLS connect endpoint (:8443). */
const BACKEND_URL = window.location.origin;
const CONNECT_URL = `${window.location.protocol}//${window.location.hostname}:8443`;
const DOWNLOAD_URL = `${window.location.origin}/download/agent.zip`;

/**
 * Register a new machine and hand back a one-time enrollment code plus the exact
 * commands to install and run the agent on that machine (Windows or macOS/Linux).
 * The agent redeems the code for a device certificate, then connects over mutual TLS.
 */
export function EnrollMachinePanel({ onEnrolled }: Props) {
  const [name, setName] = useState('');
  const [os, setOs] = useState<MachineOs>('macos');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ticket, setTicket] = useState<EnrollmentTicket | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const t = await enrollMachine(name.trim(), os);
      setTicket(t);
      onEnrolled();
    } catch (err) {
      setError(
        err instanceof ApiError
          ? `Enrollment failed (${err.status}).`
          : 'Enrollment failed.',
      );
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setTicket(null);
    setName('');
    setError(null);
  }

  const bin = os === 'windows' ? 'bin\\agent.bat' : 'bin/agent';
  const setEnv =
    os === 'windows'
      ? `set CLAUDEDRIVER_BACKEND_URL=${BACKEND_URL}\r\nset CLAUDEDRIVER_AGENT_CONNECT_URL=${CONNECT_URL}`
      : `export CLAUDEDRIVER_BACKEND_URL=${BACKEND_URL}\nexport CLAUDEDRIVER_AGENT_CONNECT_URL=${CONNECT_URL}`;

  return (
    <section className="enroll card" aria-labelledby="enroll-heading">
      <h2 id="enroll-heading">Enroll a machine</h2>

      {ticket ? (
        <div className="enroll__result">
          <p className="hint">
            Machine registered. Install the agent on the target machine and enroll it with the
            one-time code below (expires {new Date(ticket.expiresAt).toLocaleString()}).
          </p>

          <ol className="enroll__steps">
            <li>
              <strong>Download the agent</strong> onto the machine:{' '}
              <a href={DOWNLOAD_URL}>agent.zip</a> — unzip it. Requires <strong>Java 21+</strong>.
            </li>
            <li>
              <strong>Set the server endpoints:</strong>
              <pre className="enroll__cmd">{setEnv}</pre>
            </li>
            <li>
              <strong>Enroll</strong> (redeems the code for a device certificate):
              <pre className="enroll__cmd">
                {`${bin} enroll --machine-id ${ticket.machineId} --code ${ticket.enrollmentCode}`}
              </pre>
            </li>
            <li>
              <strong>Run</strong> the agent (detects your Claude Code processes and connects):
              <pre className="enroll__cmd">{bin}</pre>
            </li>
          </ol>

          <button type="button" className="btn btn--sm btn--ghost" onClick={reset}>
            Enroll another machine
          </button>
        </div>
      ) : (
        <form className="enroll__form" onSubmit={submit}>
          <p className="hint">
            Register a machine to generate a one-time enrollment code and install instructions.
          </p>
          <label className="field">
            <span>Machine name</span>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. farzin-mac"
              required
            />
          </label>
          <label className="field">
            <span>Operating system</span>
            <select value={os} onChange={(e) => setOs(e.target.value as MachineOs)}>
              <option value="macos">macOS / Linux</option>
              <option value="windows">Windows</option>
            </select>
          </label>
          <button type="submit" className="btn btn--primary" disabled={busy || !name.trim()}>
            {busy ? 'Registering…' : 'Register machine'}
          </button>
        </form>
      )}

      {error && <p className="enroll__error">{error}</p>}
    </section>
  );
}
