import { describe, it, expect } from 'vitest';
import { operatorWsUrl } from './client';

describe('operatorWsUrl', () => {
  it('targets the backend operator websocket route (/ws/operator)', () => {
    // Regression: defaulting to /ws hit the SPA fallback (200) instead of the WS
    // upgrade at /ws/operator, silently disabling all live dashboard updates.
    expect(operatorWsUrl()).toMatch(/\/ws\/operator$/);
  });
});
