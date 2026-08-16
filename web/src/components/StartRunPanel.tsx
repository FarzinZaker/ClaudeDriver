import { useState } from 'react';
import type { Machine } from '../types';

interface Props {
  machines: Machine[];
  onStartRun: (machineId: string, projectPath: string, instruction: string) => void;
  startRunPending?: boolean;
}

/** Only connected, enrolled machines can accept a new run. */
function isStartable(m: Machine): boolean {
  return m.status === 'enrolled' && m.connection.state === 'connected';
}

/**
 * A compact "start a new run" form: pick an enrolled+connected machine, a
 * project path, and an initial instruction, then start a persistent run
 * (`POST /machines/{id}/start-run`). Collapsed by default to stay out of the way.
 */
export function StartRunPanel({ machines, onStartRun, startRunPending }: Props) {
  const startable = machines.filter(isStartable);
  const [open, setOpen] = useState(false);
  const [machineId, setMachineId] = useState('');
  const [projectPath, setProjectPath] = useState('');
  const [instruction, setInstruction] = useState('');

  const effectiveMachine = machineId || startable[0]?.id || '';
  const canSubmit =
    !startRunPending &&
    effectiveMachine !== '' &&
    projectPath.trim() !== '' &&
    instruction.trim() !== '';

  const submit = () => {
    if (!canSubmit) return;
    onStartRun(effectiveMachine, projectPath.trim(), instruction.trim());
    setProjectPath('');
    setInstruction('');
  };

  return (
    <section aria-labelledby="start-run-heading" className="start-run">
      <div className="start-run__head">
        <h2 id="start-run-heading">Start a new run</h2>
        <button
          type="button"
          className="btn btn--sm btn--ghost"
          data-testid="start-run-toggle"
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? 'Cancel' : 'New run'}
        </button>
      </div>

      {open &&
        (startable.length === 0 ? (
          <p className="empty" data-testid="start-run-none">
            No connected, enrolled machine is available to start a run.
          </p>
        ) : (
          <form
            className="start-run__form card"
            data-testid="start-run-form"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <label className="field">
              <span>Machine</span>
              <select
                data-testid="start-run-machine"
                value={effectiveMachine}
                onChange={(e) => setMachineId(e.target.value)}
              >
                {startable.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Project path</span>
              <input
                data-testid="start-run-path"
                value={projectPath}
                placeholder="/Users/dev/my-project"
                onChange={(e) => setProjectPath(e.target.value)}
              />
            </label>
            <label className="field">
              <span>Initial instruction</span>
              <textarea
                data-testid="start-run-instruction"
                value={instruction}
                rows={2}
                placeholder="What should this run start working on?"
                onChange={(e) => setInstruction(e.target.value)}
              />
            </label>
            <button
              type="submit"
              className="btn btn--primary"
              data-testid="start-run-submit"
              disabled={!canSubmit}
            >
              {startRunPending ? 'Starting…' : 'Start run'}
            </button>
          </form>
        ))}
    </section>
  );
}
