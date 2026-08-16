import { useCallback, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ackAlert,
  ALERTS_QUERY_KEY,
  ApiError,
  APPROVALS_QUERY_KEY,
  decideApproval,
  fetchAlerts,
  fetchApprovals,
  fetchSessionDetail,
  fetchSessions,
  fetchStatus,
  SESSIONS_QUERY_KEY,
  sessionDetailQueryKey,
  STATUS_QUERY_KEY,
} from './api';
import { logout } from './auth/webauthn';
import { LoginPanel } from './components/LoginPanel';
import { SessionDetailModal } from './components/SessionDetail';
import { StatusView } from './components/StatusView';
import type {
  AlertEventPayload,
  AlertSummary,
  ApprovalEventPayload,
  ApprovalSummary,
  SampleEventPayload,
  SampleEventRecord,
  SessionSummary,
  SessionUpdatePayload,
  StatusResponse,
} from './types';
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

/**
 * Patch a live `session_update` into the cached session list. The WS payload
 * lacks `machineName` and uses `sessionId`; resolve the display name from the
 * existing cache entry, falling back to a supplied resolver (the machine fleet).
 */
function applySessionUpdate(
  prev: SessionSummary[] | undefined,
  ev: SessionUpdatePayload,
  resolveMachineName: (machineId: string) => string,
): SessionSummary[] {
  const list = prev ?? [];
  const idx = list.findIndex((s) => s.id === ev.sessionId);
  if (idx === -1) {
    const created: SessionSummary = {
      id: ev.sessionId,
      machineId: ev.machineId,
      machineName: resolveMachineName(ev.machineId),
      projectPath: ev.projectPath,
      state: ev.state,
      lastActivityAt: ev.lastActivityAt,
      processPresent: ev.processPresent,
    };
    return [...list, created];
  }
  const next = list.slice();
  next[idx] = {
    ...next[idx],
    machineId: ev.machineId,
    projectPath: ev.projectPath,
    state: ev.state,
    lastActivityAt: ev.lastActivityAt,
    processPresent: ev.processPresent,
  };
  return next;
}

/**
 * Patch a live `alert_event` into the cached inbox: upsert on active, update on
 * acknowledged, remove on resolved. The payload lacks `machineName`.
 */
function applyAlertEvent(
  prev: AlertSummary[] | undefined,
  ev: AlertEventPayload,
  resolveMachineName: (machineId: string) => string,
): AlertSummary[] {
  const list = prev ?? [];
  if (ev.status === 'resolved') {
    return list.filter((a) => a.id !== ev.alertId);
  }
  const idx = list.findIndex((a) => a.id === ev.alertId);
  if (idx === -1) {
    const created: AlertSummary = {
      id: ev.alertId,
      sessionId: ev.sessionId,
      machineId: ev.machineId,
      machineName: resolveMachineName(ev.machineId),
      status: ev.status,
      urgency: ev.urgency,
      summary: ev.summary,
      raisedAt: ev.raisedAt,
      resolvedReason: ev.resolvedReason,
    };
    return [created, ...list];
  }
  const next = list.slice();
  next[idx] = {
    ...next[idx],
    status: ev.status,
    urgency: ev.urgency,
    summary: ev.summary,
    resolvedReason: ev.resolvedReason,
  };
  return next;
}

/**
 * Patch a live `approval_event` into the cached approvals inbox. `pending`
 * upserts the request; `approved`/`denied`/`moot` update its status in place so
 * the panel drops it from the pending list (keeping it briefly as resolved).
 * The payload carries `machineName` directly (unlike `alert_event`).
 */
function applyApprovalEvent(
  prev: ApprovalSummary[] | undefined,
  ev: ApprovalEventPayload,
): ApprovalSummary[] {
  const list = prev ?? [];
  const idx = list.findIndex((a) => a.id === ev.approvalId);
  if (idx === -1) {
    const created: ApprovalSummary = {
      id: ev.approvalId,
      machineId: ev.machineId,
      machineName: ev.machineName,
      claudeSessionId: ev.claudeSessionId,
      tool: ev.tool,
      summary: ev.summary,
      status: ev.status,
      createdAt: ev.at,
      decidedBy: ev.decidedBy ?? null,
      reason: ev.reason ?? null,
    };
    return [created, ...list];
  }
  const next = list.slice();
  next[idx] = {
    ...next[idx],
    status: ev.status,
    summary: ev.summary,
    decidedBy: ev.decidedBy ?? next[idx].decidedBy ?? null,
    reason: ev.reason ?? next[idx].reason ?? null,
  };
  return next;
}

export default function App() {
  const queryClient = useQueryClient();
  const [wsStatus, setWsStatus] = useState<WsStatus>('closed');
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [approvalNotes, setApprovalNotes] = useState<Record<string, string>>({});

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

  const sessionsQuery = useQuery({
    queryKey: SESSIONS_QUERY_KEY,
    queryFn: fetchSessions,
    enabled: authenticated,
  });

  const alertsQuery = useQuery({
    queryKey: ALERTS_QUERY_KEY,
    queryFn: fetchAlerts,
    enabled: authenticated,
  });

  const approvalsQuery = useQuery({
    queryKey: APPROVALS_QUERY_KEY,
    queryFn: fetchApprovals,
    enabled: authenticated,
  });

  const sessionDetailQuery = useQuery({
    queryKey: sessionDetailQueryKey(selectedSessionId ?? ''),
    queryFn: () => fetchSessionDetail(selectedSessionId as string),
    enabled: authenticated && selectedSessionId != null,
  });

  const ackMutation = useMutation({
    mutationFn: (id: string) => ackAlert(id),
    onSuccess: (_data, id) => {
      // Optimistic local transition; the WS `alert_event` will confirm.
      queryClient.setQueryData<AlertSummary[]>(ALERTS_QUERY_KEY, (prev) =>
        (prev ?? []).map((a) =>
          a.id === id ? { ...a, status: 'acknowledged' as const } : a,
        ),
      );
    },
  });

  const decideMutation = useMutation({
    mutationFn: ({ id, decision }: { id: string; decision: 'approve' | 'deny' }) =>
      decideApproval(id, decision),
    onMutate: ({ id }) => {
      // Clear any stale note from a previous attempt on this request.
      setApprovalNotes((prev) => {
        if (!(id in prev)) return prev;
        const { [id]: _drop, ...rest } = prev;
        return rest;
      });
    },
    onSuccess: (result, { id, decision }) => {
      if (result.outcome === 'already_resolved') {
        setApprovalNotes((prev) => ({ ...prev, [id]: 'Already resolved on another surface.' }));
        return;
      }
      // Optimistic local transition; the WS `approval_event` will confirm.
      const status = decision === 'approve' ? 'approved' : 'denied';
      queryClient.setQueryData<ApprovalSummary[]>(APPROVALS_QUERY_KEY, (prev) =>
        (prev ?? []).map((a) =>
          a.id === id ? { ...a, status, decidedBy: 'operator' } : a,
        ),
      );
    },
  });

  /** Resolve a machine's display name from the cached `/status` snapshot. */
  const resolveMachineName = useCallback(
    (machineId: string): string => {
      const status = queryClient.getQueryData<StatusResponse>(STATUS_QUERY_KEY);
      return status?.machines.find((m) => m.id === machineId)?.name ?? machineId;
    },
    [queryClient],
  );

  const onSampleEvent = useCallback(
    (event: SampleEventPayload) => {
      queryClient.setQueryData<StatusResponse>(STATUS_QUERY_KEY, (prev) =>
        applySampleEvent(prev, event),
      );
    },
    [queryClient],
  );

  const onSessionUpdate = useCallback(
    (event: SessionUpdatePayload) => {
      queryClient.setQueryData<SessionSummary[]>(SESSIONS_QUERY_KEY, (prev) =>
        applySessionUpdate(prev, event, resolveMachineName),
      );
    },
    [queryClient, resolveMachineName],
  );

  const onAlertEvent = useCallback(
    (event: AlertEventPayload) => {
      queryClient.setQueryData<AlertSummary[]>(ALERTS_QUERY_KEY, (prev) =>
        applyAlertEvent(prev, event, resolveMachineName),
      );
    },
    [queryClient, resolveMachineName],
  );

  const onApprovalEvent = useCallback(
    (event: ApprovalEventPayload) => {
      queryClient.setQueryData<ApprovalSummary[]>(APPROVALS_QUERY_KEY, (prev) =>
        applyApprovalEvent(prev, event),
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
      onSessionUpdate,
      onAlertEvent,
      onApprovalEvent,
    });
    client.connect();
    return () => client.close();
  }, [authenticated, onSampleEvent, onSessionUpdate, onAlertEvent, onApprovalEvent]);

  const handleAuthenticated = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY });
  }, [queryClient]);

  const handleLogout = useCallback(async () => {
    try {
      await logout();
    } finally {
      setSelectedSessionId(null);
      setApprovalNotes({});
      queryClient.removeQueries({ queryKey: STATUS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: SESSIONS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: ALERTS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: APPROVALS_QUERY_KEY });
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
        sessions={sessionsQuery.data ?? []}
        alerts={alertsQuery.data ?? []}
        approvals={approvalsQuery.data ?? []}
        recentSampleEvents={data.recentSampleEvents}
        wsStatus={wsStatus}
        onLogout={() => void handleLogout()}
        onAckAlert={(id) => ackMutation.mutate(id)}
        ackPendingId={ackMutation.isPending ? ackMutation.variables : null}
        onDecideApproval={(id, decision) => decideMutation.mutate({ id, decision })}
        decidePendingId={decideMutation.isPending ? decideMutation.variables.id : null}
        approvalNotes={approvalNotes}
        onOpenSession={setSelectedSessionId}
      />
      {selectedSessionId != null && (
        <SessionDetailModal
          detail={sessionDetailQuery.data}
          isLoading={sessionDetailQuery.isPending}
          isError={sessionDetailQuery.isError}
          onClose={() => setSelectedSessionId(null)}
        />
      )}
    </main>
  );
}
