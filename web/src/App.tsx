import { useCallback, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, STATUS_QUERY_KEY, fetchStatus } from './api';
import { logout } from './auth/webauthn';
import { LoginPanel } from './components/LoginPanel';
import { StatusView } from './components/StatusView';
import type { SampleEventPayload, SampleEventRecord, StatusResponse } from './types';
import { OperatorWsClient, operatorWsUrl, type WsStatus } from './ws/client';

const MAX_INBOX = 50;

/** Prepend a live sample event onto the seeded `/status` snapshot in the cache. */
function applySampleEvent(
  prev: StatusResponse | undefined,
  event: SampleEventPayload,
): StatusResponse | undefined {
  if (!prev) return prev;
  const record: SampleEventRecord = {
    machineId: event.machineId,
    message: event.message,
    at: event.at,
  };
  return {
    ...prev,
    recentSampleEvents: [record, ...prev.recentSampleEvents].slice(0, MAX_INBOX),
  };
}

export default function App() {
  const queryClient = useQueryClient();
  const [wsStatus, setWsStatus] = useState<WsStatus>('closed');

  const statusQuery = useQuery({
    queryKey: STATUS_QUERY_KEY,
    queryFn: fetchStatus,
    retry: (failureCount, error) => {
      // Don't retry an auth failure — surface the login panel immediately.
      if (error instanceof ApiError && error.status === 401) return false;
      return failureCount < 2;
    },
  });

  const authenticated = statusQuery.isSuccess;

  const onSampleEvent = useCallback(
    (event: SampleEventPayload) => {
      queryClient.setQueryData<StatusResponse>(STATUS_QUERY_KEY, (prev) =>
        applySampleEvent(prev, event),
      );
    },
    [queryClient],
  );

  // Open the operator WS only while authenticated; tear down on logout/unmount.
  useEffect(() => {
    if (!authenticated) {
      setWsStatus('closed');
      return;
    }
    const client = new OperatorWsClient(operatorWsUrl(), {
      onStatusChange: setWsStatus,
      onSampleEvent,
    });
    client.connect();
    return () => client.close();
  }, [authenticated, onSampleEvent]);

  const handleAuthenticated = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY });
  }, [queryClient]);

  const handleLogout = useCallback(async () => {
    try {
      await logout();
    } finally {
      queryClient.removeQueries({ queryKey: STATUS_QUERY_KEY });
      void queryClient.resetQueries({ queryKey: STATUS_QUERY_KEY });
    }
  }, [queryClient]);

  const isAuthFailure =
    statusQuery.isError &&
    statusQuery.error instanceof ApiError &&
    statusQuery.error.status === 401;

  if (isAuthFailure || (statusQuery.isError && !statusQuery.data)) {
    return (
      <main className="app app--centered">
        <LoginPanel onAuthenticated={handleAuthenticated} />
      </main>
    );
  }

  if (statusQuery.isPending) {
    return (
      <main className="app app--centered">
        <p className="empty">Loading status…</p>
      </main>
    );
  }

  const data = statusQuery.data;
  return (
    <main className="app">
      <StatusView
        server={data.server}
        machines={data.machines}
        recentSampleEvents={data.recentSampleEvents}
        wsStatus={wsStatus}
        onLogout={() => void handleLogout()}
      />
    </main>
  );
}
