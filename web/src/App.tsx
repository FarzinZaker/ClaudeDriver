import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ackAlert,
  revokeMachine,
  ALERTS_QUERY_KEY,
  answerQuestion,
  ApiError,
  APPROVALS_QUERY_KEY,
  COMMANDS_QUERY_KEY,
  decideApproval,
  dispatchTask,
  fetchAlerts,
  fetchApprovals,
  fetchCommands,
  fetchQuestions,
  fetchSessionDetail,
  fetchSessions,
  fetchStatus,
  fetchTranscript,
  QUESTIONS_QUERY_KEY,
  search,
  searchQueryKey,
  SESSIONS_QUERY_KEY,
  sessionDetailQueryKey,
  startManaged,
  startRun,
  STATUS_QUERY_KEY,
  stopSession,
  transcriptQueryKey,
  type AnswerInput,
  type ControlResult,
} from './api';
import { logout } from './auth/auth';
import { LoginPanel } from './components/LoginPanel';
import { SessionDetailModal } from './components/SessionDetail';
import { StatusView } from './components/StatusView';
import type {
  AlertEventPayload,
  AlertSummary,
  ApprovalEventPayload,
  ApprovalSummary,
  ControlCommandSummary,
  ControlCommandType,
  ControlEventPayload,
  QuestionEventPayload,
  QuestionSummary,
  SampleEventPayload,
  SampleEventRecord,
  SessionSummary,
  SessionUpdatePayload,
  StatusResponse,
  TranscriptEventPayload,
  TranscriptMessage,
} from './types';
import { OperatorWsClient, operatorWsUrl, type WsStatus } from './ws/client';
import { TerminalsPanel } from './components/TerminalsPanel';
import { emitTerminalData } from './terminal/terminalBus';
import {
  fetchTerminals,
  TERMINALS_QUERY_KEY,
} from './api';
import type { TerminalSummary } from './types';

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

const MAX_COMMANDS = 50;

/**
 * Patch a live `control_event` into the cached command strip, correlated by
 * `commandId`. An unseen command is prepended (it may pre-date our seed or come
 * from another surface); a known one is updated in place. The status is applied
 * as-is — the backend already enforces at-most-once/idempotent transitions.
 */
function applyControlEvent(
  prev: ControlCommandSummary[] | undefined,
  ev: ControlEventPayload,
): ControlCommandSummary[] {
  const list = prev ?? [];
  const idx = list.findIndex((c) => c.id === ev.commandId);
  if (idx === -1) {
    const created: ControlCommandSummary = {
      id: ev.commandId,
      machineId: ev.machineId,
      machineName: ev.machineName,
      type: ev.commandType,
      claudeSessionId: ev.claudeSessionId ?? null,
      instruction: null,
      status: ev.status,
      createdAt: ev.at,
      message: ev.message ?? null,
    };
    return [created, ...list].slice(0, MAX_COMMANDS);
  }
  const next = list.slice();
  next[idx] = {
    ...next[idx],
    status: ev.status,
    machineName: ev.machineName || next[idx].machineName,
    claudeSessionId: ev.claudeSessionId ?? next[idx].claudeSessionId ?? null,
    message: ev.message ?? next[idx].message ?? null,
  };
  return next;
}

/** Optimistically seed a just-accepted command into the strip cache. */
function upsertCommand(
  prev: ControlCommandSummary[] | undefined,
  command: ControlCommandSummary,
): ControlCommandSummary[] {
  const list = prev ?? [];
  if (list.some((c) => c.id === command.id)) return list;
  return [command, ...list].slice(0, MAX_COMMANDS);
}

/**
 * Patch a live `question_event` into the cached questions inbox, correlated by
 * `questionId`. A `pending` event upserts the question; a resolved event
 * (`answered`/`cancelled`/`unanswered`) updates its status in place so the inbox
 * drops it from the pending list. The payload carries `machineName` directly.
 */
function applyQuestionEvent(
  prev: QuestionSummary[] | undefined,
  ev: QuestionEventPayload,
): QuestionSummary[] {
  const list = prev ?? [];
  const idx = list.findIndex((q) => q.id === ev.questionId);
  if (idx === -1) {
    const created: QuestionSummary = {
      id: ev.questionId,
      machineId: ev.machineId,
      machineName: ev.machineName,
      claudeSessionId: ev.claudeSessionId,
      text: ev.text,
      status: ev.status,
      createdAt: ev.at,
      answer: null,
      resolvedBy: ev.resolvedBy ?? null,
    };
    return [created, ...list].slice(0, MAX_INBOX);
  }
  const next = list.slice();
  next[idx] = {
    ...next[idx],
    status: ev.status,
    text: ev.text || next[idx].text,
    resolvedBy: ev.resolvedBy ?? next[idx].resolvedBy ?? null,
  };
  return next;
}

/** Append a live `transcript_event` message to a session's cached transcript. */
function applyTranscriptEvent(
  prev: TranscriptMessage[] | undefined,
  ev: TranscriptEventPayload,
): TranscriptMessage[] {
  const message: TranscriptMessage = { role: ev.role, text: ev.text, at: ev.at };
  return [...(prev ?? []), message];
}

export default function App() {
  const queryClient = useQueryClient();
  const [wsStatus, setWsStatus] = useState<WsStatus>('closed');
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [selectedTerminalId, setSelectedTerminalId] = useState<string | null>(null);
  const wsClientRef = useRef<OperatorWsClient | null>(null);
  const [approvalNotes, setApprovalNotes] = useState<Record<string, string>>({});
  const [questionNotes, setQuestionNotes] = useState<Record<string, string>>({});
  const [searchTerm, setSearchTerm] = useState('');

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

  const commandsQuery = useQuery({
    queryKey: COMMANDS_QUERY_KEY,
    queryFn: fetchCommands,
    enabled: authenticated,
  });

  const questionsQuery = useQuery({
    queryKey: QUESTIONS_QUERY_KEY,
    queryFn: fetchQuestions,
    enabled: authenticated,
  });

  const terminalsQuery = useQuery({
    queryKey: TERMINALS_QUERY_KEY,
    queryFn: fetchTerminals,
    enabled: authenticated,
  });

  const sessionDetailQuery = useQuery({
    queryKey: sessionDetailQueryKey(selectedSessionId ?? ''),
    queryFn: () => fetchSessionDetail(selectedSessionId as string),
    enabled: authenticated && selectedSessionId != null,
  });

  const questions = questionsQuery.data ?? [];
  // A session is "managed" if it has a managed control command, a free-form
  // question, or a reconstructed transcript — inferred, since the wire summaries
  // don't carry a `managed` flag.
  const managedSelected =
    selectedSessionId != null &&
    (questions.some((q) => q.claudeSessionId === selectedSessionId) ||
      (commandsQuery.data ?? []).some(
        (c) => c.type === 'start_managed' && c.claudeSessionId === selectedSessionId,
      ) ||
      (queryClient.getQueryData<TranscriptMessage[]>(
        transcriptQueryKey(selectedSessionId),
      )?.length ?? 0) > 0);

  const transcriptQuery = useQuery({
    queryKey: transcriptQueryKey(selectedSessionId ?? ''),
    queryFn: () => fetchTranscript(selectedSessionId as string),
    enabled: authenticated && selectedSessionId != null && managedSelected,
  });

  const searchQuery = useQuery({
    queryKey: searchQueryKey(searchTerm),
    queryFn: () => search(searchTerm),
    enabled: authenticated && searchTerm !== '',
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeMachine(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY });
    },
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

  /**
   * Record a just-accepted control command optimistically so it appears in the
   * strip immediately with `pending` status; the `control_event` for its
   * `commandId` then drives it to a terminal status.
   */
  const seedAcceptedCommand = useCallback(
    (
      result: ControlResult,
      base: {
        type: ControlCommandType;
        machineId: string;
        machineName: string;
        claudeSessionId?: string | null;
        instruction?: string | null;
      },
    ) => {
      if (result.outcome !== 'accepted') return;
      const command: ControlCommandSummary = {
        id: result.commandId,
        machineId: base.machineId,
        machineName: base.machineName,
        type: base.type,
        claudeSessionId: base.claudeSessionId ?? null,
        instruction: base.instruction ?? null,
        status: result.status,
        createdAt: new Date().toISOString(),
        message: null,
      };
      queryClient.setQueryData<ControlCommandSummary[]>(COMMANDS_QUERY_KEY, (prev) =>
        upsertCommand(prev, command),
      );
    },
    [queryClient],
  );

  /** Look up a monitored session's summary from the sessions cache. */
  const findSession = useCallback(
    (sessionId: string): SessionSummary | undefined =>
      queryClient
        .getQueryData<SessionSummary[]>(SESSIONS_QUERY_KEY)
        ?.find((s) => s.id === sessionId),
    [queryClient],
  );

  const dispatchMutation = useMutation({
    mutationFn: ({ sessionId, instruction }: { sessionId: string; instruction: string }) =>
      dispatchTask(sessionId, instruction),
    onSuccess: (result, { sessionId, instruction }) => {
      const session = findSession(sessionId);
      seedAcceptedCommand(result, {
        type: 'dispatch_task',
        machineId: session?.machineId ?? '',
        machineName: session?.machineName ?? 'unknown machine',
        claudeSessionId: sessionId,
        instruction,
      });
    },
  });

  const stopMutation = useMutation({
    mutationFn: ({ sessionId }: { sessionId: string }) => stopSession(sessionId),
    onSuccess: (result, { sessionId }) => {
      const session = findSession(sessionId);
      seedAcceptedCommand(result, {
        type: 'stop_session',
        machineId: session?.machineId ?? '',
        machineName: session?.machineName ?? 'unknown machine',
        claudeSessionId: sessionId,
      });
    },
  });

  const startRunMutation = useMutation({
    mutationFn: ({
      machineId,
      projectPath,
      instruction,
    }: {
      machineId: string;
      projectPath: string;
      instruction: string;
    }) => startRun(machineId, projectPath, instruction),
    onSuccess: (result, { machineId, instruction }) => {
      const status = queryClient.getQueryData<StatusResponse>(STATUS_QUERY_KEY);
      const machineName =
        status?.machines.find((m) => m.id === machineId)?.name ?? machineId;
      seedAcceptedCommand(result, {
        type: 'start_run',
        machineId,
        machineName,
        instruction,
      });
    },
  });

  const startManagedMutation = useMutation({
    mutationFn: ({
      machineId,
      projectPath,
      instruction,
    }: {
      machineId: string;
      projectPath: string;
      instruction: string;
    }) => startManaged(machineId, projectPath, instruction),
    onSuccess: (result, { machineId, instruction }) => {
      const status = queryClient.getQueryData<StatusResponse>(STATUS_QUERY_KEY);
      const machineName =
        status?.machines.find((m) => m.id === machineId)?.name ?? machineId;
      seedAcceptedCommand(result, {
        type: 'start_managed',
        machineId,
        machineName,
        instruction,
      });
    },
  });

  const answerMutation = useMutation({
    mutationFn: ({ id, input }: { id: string; input: AnswerInput }) =>
      answerQuestion(id, input),
    onMutate: ({ id }) => {
      // Clear any stale note from a previous attempt on this question.
      setQuestionNotes((prev) => {
        if (!(id in prev)) return prev;
        const { [id]: _drop, ...rest } = prev;
        return rest;
      });
    },
    onSuccess: (result, { id }) => {
      if (result.outcome === 'already_resolved') {
        setQuestionNotes((prev) => ({ ...prev, [id]: 'Already resolved.' }));
        return;
      }
      // Optimistic local transition; the WS `question_event` will confirm.
      queryClient.setQueryData<QuestionSummary[]>(QUESTIONS_QUERY_KEY, (prev) =>
        (prev ?? []).map((q) =>
          q.id === id ? { ...q, status: result.status, resolvedBy: 'operator' } : q,
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

  const onControlEvent = useCallback(
    (event: ControlEventPayload) => {
      queryClient.setQueryData<ControlCommandSummary[]>(COMMANDS_QUERY_KEY, (prev) =>
        applyControlEvent(prev, event),
      );
    },
    [queryClient],
  );

  const onQuestionEvent = useCallback(
    (event: QuestionEventPayload) => {
      queryClient.setQueryData<QuestionSummary[]>(QUESTIONS_QUERY_KEY, (prev) =>
        applyQuestionEvent(prev, event),
      );
    },
    [queryClient],
  );

  const onTranscriptEvent = useCallback(
    (event: TranscriptEventPayload) => {
      queryClient.setQueryData<TranscriptMessage[]>(
        transcriptQueryKey(event.claudeSessionId),
        (prev) => applyTranscriptEvent(prev, event),
      );
    },
    [queryClient],
  );

  // A terminal opened/closed: upsert it into the live list (open ones first).
  const onTerminalEvent = useCallback(
    (event: TerminalSummary) => {
      queryClient.setQueryData<TerminalSummary[]>(TERMINALS_QUERY_KEY, (prev) => {
        const rest = (prev ?? []).filter((t) => t.terminalId !== event.terminalId);
        return [event, ...rest];
      });
    },
    [queryClient],
  );

  // Live output → route to the attached xterm via the bus (no re-render).
  const onTerminalData = useCallback(
    (event: { terminalId: string; dataB64: string }) => {
      emitTerminalData(event.terminalId, event.dataB64);
    },
    [],
  );

  // Send operator keystrokes to a terminal over the live socket.
  const handleTerminalInput = useCallback((terminalId: string, dataB64: string) => {
    wsClientRef.current?.sendTerminalInput(terminalId, dataB64);
  }, []);

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
      onControlEvent,
      onQuestionEvent,
      onTranscriptEvent,
      onTerminalEvent,
      onTerminalData,
    });
    wsClientRef.current = client;
    client.connect();
    return () => {
      client.close();
      wsClientRef.current = null;
    };
  }, [
    authenticated,
    onSampleEvent,
    onSessionUpdate,
    onAlertEvent,
    onApprovalEvent,
    onControlEvent,
    onQuestionEvent,
    onTranscriptEvent,
    onTerminalEvent,
    onTerminalData,
  ]);

  const handleAuthenticated = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY });
  }, [queryClient]);

  const handleLogout = useCallback(async () => {
    try {
      await logout();
    } finally {
      setSelectedSessionId(null);
      setApprovalNotes({});
      setQuestionNotes({});
      setSearchTerm('');
      queryClient.removeQueries({ queryKey: STATUS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: SESSIONS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: ALERTS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: APPROVALS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: COMMANDS_QUERY_KEY });
      queryClient.removeQueries({ queryKey: QUESTIONS_QUERY_KEY });
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
  const commands = commandsQuery.data ?? [];
  // Latest control command targeting the open session (correlated by claudeSessionId).
  const latestCommandForSelected =
    selectedSessionId == null
      ? undefined
      : commands
          .filter((c) => c.claudeSessionId === selectedSessionId)
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
  // Pending free-form questions raised by the open managed session.
  const pendingQuestionsForSelected =
    selectedSessionId == null
      ? []
      : questions.filter(
          (q) => q.claudeSessionId === selectedSessionId && q.status === 'pending',
        );

  return (
    <main className="app">
      <StatusView
        server={data.server}
        machines={data.machines}
        sessions={sessionsQuery.data ?? []}
        alerts={alertsQuery.data ?? []}
        approvals={approvalsQuery.data ?? []}
        commands={commands}
        recentSampleEvents={data.recentSampleEvents}
        wsStatus={wsStatus}
        onLogout={() => void handleLogout()}
        onAckAlert={(id) => ackMutation.mutate(id)}
        ackPendingId={ackMutation.isPending ? ackMutation.variables : null}
        onDecideApproval={(id, decision) => decideMutation.mutate({ id, decision })}
        decidePendingId={decideMutation.isPending ? decideMutation.variables.id : null}
        approvalNotes={approvalNotes}
        onOpenSession={setSelectedSessionId}
        onStartRun={(machineId, projectPath, instruction) =>
          startRunMutation.mutate({ machineId, projectPath, instruction })
        }
        startRunPending={startRunMutation.isPending}
        onStartManaged={(machineId, projectPath, instruction) =>
          startManagedMutation.mutate({ machineId, projectPath, instruction })
        }
        startManagedPending={startManagedMutation.isPending}
        onEnrolled={() =>
          void queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY })
        }
        onRevokeMachine={(id) => revokeMutation.mutate(id)}
        revokePendingId={revokeMutation.isPending ? revokeMutation.variables : null}
        questions={questions}
        onAnswerQuestion={(id, input) => answerMutation.mutate({ id, input })}
        answerPendingId={answerMutation.isPending ? answerMutation.variables.id : null}
        questionNotes={questionNotes}
        onSearch={setSearchTerm}
        searchResults={searchQuery.data}
        searchTerm={searchTerm}
        searchPending={searchQuery.isFetching}
      />
      <TerminalsPanel
        terminals={terminalsQuery.data ?? []}
        selectedId={selectedTerminalId}
        onSelect={setSelectedTerminalId}
        onInput={handleTerminalInput}
      />
      {selectedSessionId != null && (
        <SessionDetailModal
          detail={sessionDetailQuery.data}
          isLoading={sessionDetailQuery.isPending}
          isError={sessionDetailQuery.isError}
          onClose={() => setSelectedSessionId(null)}
          onDispatch={(sessionId, instruction) =>
            dispatchMutation.mutate({ sessionId, instruction })
          }
          onStop={(sessionId) => stopMutation.mutate({ sessionId })}
          dispatchPending={
            dispatchMutation.isPending &&
            dispatchMutation.variables?.sessionId === selectedSessionId
          }
          stopPending={
            stopMutation.isPending &&
            stopMutation.variables?.sessionId === selectedSessionId
          }
          latestCommand={latestCommandForSelected}
          managed={managedSelected}
          transcript={transcriptQuery.data}
          transcriptLoading={transcriptQuery.isPending && managedSelected}
          pendingQuestions={pendingQuestionsForSelected}
          onAnswerQuestion={(id, input) => answerMutation.mutate({ id, input })}
          answerPendingId={answerMutation.isPending ? answerMutation.variables.id : null}
          questionNotes={questionNotes}
        />
      )}
    </main>
  );
}
