import { useState } from 'react';
import { ApiError, enrollMachine, type EnrollmentTicket } from '../api';
import type { MachineOs } from '../types';

interface Props {
  /** Called after a successful enrollment so the caller can refresh the machine list. */
  onEnrolled: () => void;
}

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

  return (
    <section className="enroll card" aria-labelledby="enroll-heading">
      <h2 id="enroll-heading">Enroll a machine</h2>

      {ticket ? (
        <div className="enroll__result">
          <p className="hint">
            <strong>{name || 'Machine'}</strong> registered. Download its pre-configured installer,
            run it on the target machine, and it installs an always-on service and connects
            automatically — no terminal, no code to type (embedded code expires{' '}
            {new Date(ticket.expiresAt).toLocaleString()}).
          </p>

          <a
            className="btn btn--primary enroll__download"
            href={`/machines/${encodeURIComponent(ticket.machineId)}/installer?os=${os}`}
          >
            Download installer for {os === 'windows' ? 'Windows' : 'macOS'}
          </a>

          <ol className="enroll__steps">
            <li>
              Unzip the download and run{' '}
              <code>{os === 'windows' ? 'install.ps1' : 'install.command'}</code>
              {os === 'windows'
                ? ' (right-click → Run with PowerShell).'
                : ' (double-click, or run it in Terminal).'}
            </li>
            <li>
              The agent installs as a background service, auto-enrolls, and this machine appears above
              with live health. Self-contained — <strong>no Java required</strong>.
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
