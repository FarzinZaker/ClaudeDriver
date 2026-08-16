import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import type { Envelope, SampleEventPayload, StatusResponse } from './types';
import { MockWebSocket } from './test/mockWebSocket';

const STATUS: StatusResponse = {
  server: { version: '0.1.0', time: '2026-08-16T12:00:00Z' },
  machines: [
    {
      id: '11111111-1111-1111-1111-111111111111',
      name: 'alpha-macbook',
      os: 'macos',
      status: 'enrolled',
      connection: {
        state: 'connected',
        since: '2026-08-16T11:59:00Z',
        protocolVersion: '0.1.0',
      },
    },
    {
      id: '22222222-2222-2222-2222-222222222222',
      name: 'beta-win',
      os: 'windows',
      status: 'pending',
      connection: { state: 'disconnected', since: null, protocolVersion: null },
    },
  ],
  recentSampleEvents: [],
};

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
  MockWebSocket.reset();
  vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify(STATUS), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    ),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('operator status page', () => {
  it('renders machine cards from a mocked /status response', async () => {
    renderApp();

    const cards = await screen.findAllByTestId('machine-card');
    expect(cards).toHaveLength(2);

    expect(screen.getByText('alpha-macbook')).toBeInTheDocument();
    expect(screen.getByText('beta-win')).toBeInTheDocument();

    // Server identity from the seed.
    expect(screen.getByText(/v0\.1\.0/)).toBeInTheDocument();

    // Connection-health indicator: one of two machines online.
    expect(screen.getByTestId('machines-online')).toHaveTextContent('1/2');

    // Status badges reflect the contract statuses.
    const alpha = within(cards[0]);
    expect(alpha.getByText('enrolled')).toBeInTheDocument();
    expect(alpha.getByText('connected')).toBeInTheDocument();
  });

  it('renders an incoming sample_event envelope in the inbox', async () => {
    renderApp();

    // Wait for seed + WS to be established.
    await screen.findAllByTestId('machine-card');
    await waitFor(() => expect(MockWebSocket.last).toBeDefined());

    const ws = MockWebSocket.last!;
    act(() => ws.emitOpen());

    // Inbox starts empty.
    expect(screen.queryByTestId('inbox-item')).not.toBeInTheDocument();

    const payload: SampleEventPayload = {
      machineId: '11111111-1111-1111-1111-111111111111',
      message: 'hello from alpha',
      at: '2026-08-16T12:01:00Z',
    };
    const envelope: Envelope<SampleEventPayload> = {
      protocolVersion: '0.1.0',
      type: 'sample_event',
      seq: 7,
      payload,
    };

    act(() => ws.emitMessage(envelope));

    const item = await screen.findByTestId('inbox-item');
    expect(within(item).getByText('hello from alpha')).toBeInTheDocument();
    // Live-patched into the seeded snapshot.
    expect(screen.getAllByTestId('inbox-item')).toHaveLength(1);
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
