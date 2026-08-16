/** Operator REST calls the status page depends on. */

import type { StatusResponse } from './types';

/** TanStack Query key for the seeded `/status` snapshot. */
export const STATUS_QUERY_KEY = ['status'] as const;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * Seeds the initial status. A `401` here means the operator is not authenticated
 * (the surface is session-protected — rest-api.md), which the UI turns into a login prompt.
 */
export async function fetchStatus(): Promise<StatusResponse> {
  const res = await fetch('/status', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /status failed: ${res.status}`, res.status);
  }
  return (await res.json()) as StatusResponse;
}
