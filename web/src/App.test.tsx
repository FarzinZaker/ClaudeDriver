import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import type {
  AlertEventPayload,
  AlertSummary,
  ApprovalEventPayload,
  ApprovalSummary,
  ControlCommandSummary,
  ControlEventPayload,
  Envelope,
  SampleEventPayload,
  SessionSummary,
  SessionUpdatePayload,
  StatusResponse,
} from './types';
import { MockWebSocket } from './test/mockWebSocket';

const MACHINE_ALPHA = '11111111-1111-1111-1111-111111111111';
const MACHINE_BETA = '22222222-2222-2222-2222-222222222222';

const STATUS: StatusResponse = {
  server: { version: '0.1.0', time: '2026-08-16T12:00:00Z' },
  machines: [
    {
      id: MACHINE_ALPHA,
      name: 'alpha-macbook',
      os: 'macos',
      status: 'enrolled',
      connection: {
        state: 'connected',
        since: '2026-08-16T11:59:00Z',
        protocolVersion: '0.2.0',
      },
    },
    {
      id: MACHINE_BETA,
      name: 'beta-win',
      os: 'windows',
      status: 'pending',
      connection: { state: 'disconnected', since: null, protocolVersion: null },
    },
  ],
  recentSampleEvents: [],
};

const SESSION_RUNNING = 'aaaaaaaa-0000-0000-0000-000000000001';
const SESSION_WAITING = 'aaaaaaaa-0000-0000-0000-000000000002';

const APPROVAL_ID = 'dddddddd-0000-0000-0000-000000000001';
const COMMAND_ID = 'eeeeeeee-0000-0000-0000-000000000001';

let SESSIONS: SessionSummary[];
let ALERTS: AlertSummary[];
let APPROVALS: ApprovalSummary[];
let COMMANDS: ControlCommandSummary[];

function makeApprovals(): ApprovalSummary[] {
  return [
    {
      id: APPROVAL_ID,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      claudeSessionId: 'abc-123-session',
      tool: 'Bash',
      summary: 'Bash: `git push origin main`',
      status: 'pending',
      createdAt: '2026-08-16T12:00:30Z',
      decidedBy: null,
      reason: null,
    },
  ];
}

function makeCommands(): ControlCommandSummary[] {
  return [
    {
      id: COMMAND_ID,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      type: 'dispatch_task',
      claudeSessionId: SESSION_RUNNING,
      instruction: 'run the smoke tests',
      status: 'pending',
      createdAt: '2026-08-16T12:00:35Z',
      message: null,
    },
  ];
}

function makeSessions(): SessionSummary[] {
  return [
    {
      id: SESSION_RUNNING,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      projectPath: '/Users/dev/proj-web',
      state: 'running',
      lastActivityAt: '2026-08-16T12:00:10Z',
      processPresent: true,
    },
    {
      id: SESSION_WAITING,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      projectPath: '/Users/dev/proj-api',
      state: 'waiting_for_operator',
      lastActivityAt: '2026-08-16T12:00:20Z',
      processPresent: true,
    },
  ];
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

let fetchMock: ReturnType<typeof vi.fn>;

function routeFetch(input: RequestInfo | URL): Response {
  const url =
    typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  const path = url.replace(/^https?:\/\/[^/]+/, '');

  if (path === '/status') return jsonResponse(STATUS);
  if (path === '/sessions') return jsonResponse({ sessions: SESSIONS });
  if (path === '/alerts') return jsonResponse({ alerts: ALERTS });
  if (path === '/approvals') return jsonResponse({ approvals: APPROVALS });
  if (path === '/commands') return jsonResponse({ commands: COMMANDS });
  if (/^\/sessions\/[^/]+\/dispatch$/.test(path)) {
    return jsonResponse({ commandId: 'cmd-dispatch-1', status: 'pending' }, 202);
  }
  if (/^\/sessions\/[^/]+\/stop$/.test(path)) {
    return jsonResponse({ commandId: 'cmd-stop-1', status: 'pending' }, 202);
  }
  if (/^\/machines\/[^/]+\/start-run$/.test(path)) {
    return jsonResponse({ commandId: 'cmd-start-1', status: 'pending' }, 202);
  }
  if (/^\/sessions\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split('/')[2]);
    const session = SESSIONS.find((s) => s.id === id) ?? SESSIONS[0];
    return jsonResponse({
      session,
      recentEvents: [
        {
          kind: 'notification',
          attention: 'needs_attention',
          summary: 'permission: Bash `git push`',
          at: '2026-08-16T12:00:20Z',
        },
      ],
    });
  }
  if (/^\/alerts\/[^/]+\/ack$/.test(path)) {
    return new Response(null, { status: 204 });
  }
  if (/^\/approvals\/[^/]+\/decide$/.test(path)) {
    return jsonResponse({ status: 'approved' });
  }
  return jsonResponse({}, 404);
}

function renderApp() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  SESSIONS = makeSessions();
  ALERTS = [];
  APPROVALS = [];
  COMMANDS = [];
  MockWebSocket.reset();
  vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
  fetchMock = vi.fn(async (input: RequestInfo | URL) => routeFetch(input));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('operator dashboard', () => {
  it('renders machine cards from a mocked /status response', async () => {
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    expect(cards).toHaveLength(2);

    expect(screen.getByText('alpha-macbook')).toBeInTheDocument();
    expect(screen.getByText('beta-win')).toBeInTheDocument();
    expect(screen.getByText(/v0\.1\.0/)).toBeInTheDocument();

    // Connection-health indicator: one of two machines online.
    expect(screen.getByTestId('machines-online')).toHaveTextContent('1/2');

    const alpha = within(cards[0]);
    expect(alpha.getByText('enrolled')).toBeInTheDocument();
  });

  it('lists a machine’s sessions with correct state badges from /sessions', async () => {
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    const alpha = within(cards[0]);

    // Two sessions render under the connected machine, with mapped badges.
    await waitFor(() =>
      expect(alpha.getAllByTestId('session-row')).toHaveLength(2),
    );
    expect(alpha.getByText('Running')).toBeInTheDocument();
    expect(alpha.getByText('Waiting for you')).toBeInTheDocument();

    // The offline machine has no sessions.
    const beta = within(cards[1]);
    expect(beta.getByTestId('no-sessions')).toBeInTheDocument();
  });

  it('renders an incoming alert_event (active) and acknowledging calls the ack endpoint', async () => {
    renderApp();

    await screen.findAllByTestId('machine-card');
    // Wait for the alerts query to settle empty before we patch it live.
    await screen.findByText(/No active alerts/i);
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    const ALERT_ID = 'cccccccc-0000-0000-0000-000000000001';
    const payload: AlertEventPayload = {
      alertId: ALERT_ID,
      sessionId: SESSION_WAITING,
      machineId: MACHINE_ALPHA,
      status: 'active',
      urgency: 'high',
      summary: 'permission: Bash `git push`',
      raisedAt: '2026-08-16T12:00:21Z',
      resolvedReason: null,
    };
    const envelope: Envelope<AlertEventPayload> = {
      protocolVersion: '0.2.0',
      type: 'alert_event',
      seq: 3,
      payload,
    };
    act(() => ws.emitMessage(envelope));

    const item = await screen.findByTestId('alert-item');
    expect(within(item).getByText('permission: Bash `git push`')).toBeInTheDocument();
    expect(within(item).getByText('alpha-macbook')).toBeInTheDocument();

    // Acknowledge → POST /alerts/{id}/ack.
    act(() => within(item).getByTestId('alert-ack').click());

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input, init]) => {
          const url = typeof input === 'string' ? input : (input as URL).href ?? '';
          return (
            String(url).includes(`/alerts/${ALERT_ID}/ack`) &&
            (init as RequestInit | undefined)?.method === 'POST'
          );
        }),
      ).toBe(true),
    );
  });

  it('applies a session_update moving a session to unknown_stale', async () => {
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    const alpha = within(cards[0]);
    await waitFor(() => expect(alpha.getByText('Running')).toBeInTheDocument());
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    const payload: SessionUpdatePayload = {
      sessionId: SESSION_RUNNING,
      machineId: MACHINE_ALPHA,
      projectPath: '/Users/dev/proj-web',
      state: 'unknown_stale',
      lastActivityAt: '2026-08-16T12:05:00Z',
      processPresent: false,
    };
    const envelope: Envelope<SessionUpdatePayload> = {
      protocolVersion: '0.2.0',
      type: 'session_update',
      seq: 9,
      payload,
    };
    act(() => ws.emitMessage(envelope));

    await waitFor(() =>
      expect(within(cards[0]).getByText('Unknown / stale')).toBeInTheDocument(),
    );
    // The previously-running badge is gone.
    expect(within(cards[0]).queryByText('Running')).not.toBeInTheDocument();
  });

  it('renders an incoming sample_event envelope in the inbox', async () => {
    renderApp();

    await screen.findAllByTestId('machine-card');
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    expect(screen.queryByTestId('inbox-item')).not.toBeInTheDocument();

    const payload: SampleEventPayload = {
      machineId: MACHINE_ALPHA,
      message: 'hello from alpha',
      at: '2026-08-16T12:01:00Z',
    };
    const envelope: Envelope<SampleEventPayload> = {
      protocolVersion: '0.2.0',
      type: 'sample_event',
      seq: 7,
      payload,
    };
    act(() => ws.emitMessage(envelope));

    const item = await screen.findByTestId('inbox-item');
    expect(within(item).getByText('hello from alpha')).toBeInTheDocument();
    expect(screen.getAllByTestId('inbox-item')).toHaveLength(1);
  });

  it('renders a pending approval from /approvals with Approve/Deny', async () => {
    APPROVALS = makeApprovals();
    renderApp();

    const item = await screen.findByTestId('approval-item');
    expect(within(item).getByText('Bash: `git push origin main`')).toBeInTheDocument();
    expect(within(item).getByText('alpha-macbook')).toBeInTheDocument();
    expect(within(item).getByTestId('approval-approve')).toBeInTheDocument();
    expect(within(item).getByTestId('approval-deny')).toBeInTheDocument();
  });

  it('clicking Approve calls the decide endpoint', async () => {
    APPROVALS = makeApprovals();
    renderApp();

    const item = await screen.findByTestId('approval-item');
    act(() => within(item).getByTestId('approval-approve').click());

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([input, init]) => {
          const url = typeof input === 'string' ? input : (input as URL).href ?? '';
          return (
            String(url).includes(`/approvals/${APPROVAL_ID}/decide`) &&
            (init as RequestInit | undefined)?.method === 'POST' &&
            String((init as RequestInit | undefined)?.body ?? '').includes('approve')
          );
        }),
      ).toBe(true),
    );
  });

  it('removes an approval from the pending list on an approved approval_event', async () => {
    APPROVALS = makeApprovals();
    renderApp();

    await screen.findByTestId('approval-item');
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    const payload: ApprovalEventPayload = {
      approvalId: APPROVAL_ID,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      claudeSessionId: 'abc-123-session',
      tool: 'Bash',
      summary: 'Bash: `git push origin main`',
      status: 'approved',
      at: '2026-08-16T12:00:40Z',
      decidedBy: 'operator',
      reason: null,
    };
    const envelope: Envelope<ApprovalEventPayload> = {
      protocolVersion: '0.3.0',
      type: 'approval_event',
      seq: 5,
      payload,
    };
    act(() => ws.emitMessage(envelope));

    await waitFor(() =>
      expect(screen.queryByTestId('approval-item')).not.toBeInTheDocument(),
    );
    // The pending heading drops to zero; it now shows resolved for context.
    expect(screen.getByText(/Approvals \(0\)/)).toBeInTheDocument();
    expect(screen.getByTestId('approval-resolved-item')).toBeInTheDocument();
  });

  it('typing an instruction and clicking Send task dispatches it to the session', async () => {
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    const rows = within(cards[0]).getAllByTestId('session-row');
    // Open the running session's detail modal (SESSIONS[0] === SESSION_RUNNING).
    act(() => within(rows[0]).getByRole('button').click());

    const input = await screen.findByTestId('dispatch-input');
    act(() => {
      fireEvent.change(input, { target: { value: 'run the smoke tests' } });
    });
    act(() => screen.getByTestId('dispatch-send').click());

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([reqInput, init]) => {
          const url =
            typeof reqInput === 'string' ? reqInput : (reqInput as URL).href ?? '';
          return (
            String(url).includes(`/sessions/${SESSION_RUNNING}/dispatch`) &&
            (init as RequestInit | undefined)?.method === 'POST' &&
            String((init as RequestInit | undefined)?.body ?? '').includes(
              'run the smoke tests',
            )
          );
        }),
      ).toBe(true),
    );
  });

  it('applies an incoming control_event to a command in the strip', async () => {
    COMMANDS = makeCommands();
    renderApp();

    const item = await screen.findByTestId('command-item');
    expect(within(item).getByTestId('control-status')).toHaveTextContent('Pending');
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    const payload: ControlEventPayload = {
      commandId: COMMAND_ID,
      machineId: MACHINE_ALPHA,
      machineName: 'alpha-macbook',
      commandType: 'dispatch_task',
      status: 'delivered',
      claudeSessionId: SESSION_RUNNING,
      at: '2026-08-16T12:00:45Z',
      message: 'delivered to session',
    };
    const envelope: Envelope<ControlEventPayload> = {
      protocolVersion: '0.4.0',
      type: 'control_event',
      seq: 11,
      payload,
    };
    act(() => ws.emitMessage(envelope));

    await waitFor(() =>
      expect(
        within(screen.getByTestId('command-item')).getByTestId('control-status'),
      ).toHaveTextContent('Delivered'),
    );
  });

  it('clicking Stop session calls the stop endpoint after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    const rows = within(cards[0]).getAllByTestId('session-row');
    act(() => within(rows[0]).getByRole('button').click());

    const stopBtn = await screen.findByTestId('stop-session');
    act(() => stopBtn.click());

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([reqInput, init]) => {
          const url =
            typeof reqInput === 'string' ? reqInput : (reqInput as URL).href ?? '';
          return (
            String(url).includes(`/sessions/${SESSION_RUNNING}/stop`) &&
            (init as RequestInit | undefined)?.method === 'POST'
          );
        }),
      ).toBe(true),
    );
  });

  it('shows connection stream status once the socket opens', async () => {
    renderApp();
    await screen.findAllByTestId('machine-card');
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    act(() => MockWebSocket.last!.emitOpen());

    await waitFor(() =>
      expect(screen.getByTestId('ws-status')).toHaveTextContent('connected'),
    );
  });
});
