import { chromium } from '@playwright/test';

// Reuse the logged-in persistent profile (same as check-console.mjs).
const ctx = await chromium.launchPersistentContext('/tmp/cd-browser-profile', {
  headless: process.env.HEADED ? false : true,
  baseURL: 'https://claudedriver.resortwise.ai',
});
const page = ctx.pages()[0] || (await ctx.newPage());
const log = (...a) => console.log(...a);
let ok = true;
const check = (name, pass) => { log(`  [${pass ? 'PASS' : 'FAIL'}] ${name}`); if (!pass) ok = false; };

try {
  await page.goto('/');
  await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 20000 });
  log('✓ authenticated');

  // 1. REST: the live terminal registered.
  const terminals = await page.evaluate(() =>
    fetch('/terminals', { credentials: 'include' }).then((r) => r.json()));
  const list = terminals.terminals || [];
  log('TERMINALS=' + JSON.stringify(list.map((t) => ({ dir: t.cwd, status: t.status, machine: t.machineName }))));
  const open = list.find((t) => t.status === 'open');
  check('a live terminal is registered on the backend', !!open);

  if (open) {
    // 2. REST: scrollback carries the mirrored bash output.
    const sb = await page.evaluate((id) =>
      fetch(`/terminals/${encodeURIComponent(id)}/scrollback`, { credentials: 'include' }).then((r) => r.json()),
      open.terminalId);
    const text = atob(sb.dataB64 || '');
    check('scrollback contains the session marker (output mirrored)', text.includes('CD_LIVE_MARKER'));

    // 3. UI: attach in the dashboard and type a keystroke into the live terminal.
    const item = page.locator('.terminals-panel__item').first();
    await item.click();
    await page.locator('.xterm-helper-textarea').waitFor({ timeout: 10000 });
    check('xterm view attached in the dashboard', true);
    const ta = page.locator('.xterm-helper-textarea');
    await ta.click();
    await ta.type('echo INJECTED_FROM_UI_OK\n', { delay: 30 });

    // 4. Round-trip: the injected command runs in bash and its output returns to scrollback.
    let injected = false;
    for (let i = 0; i < 15 && !injected; i += 1) {
      await page.waitForTimeout(700);
      const sb2 = await page.evaluate((id) =>
        fetch(`/terminals/${encodeURIComponent(id)}/scrollback`, { credentials: 'include' }).then((r) => r.json()),
        open.terminalId);
      injected = atob(sb2.dataB64 || '').includes('INJECTED_FROM_UI_OK');
    }
    check('dashboard keystroke executed in the session (full round-trip)', injected);
  }
} catch (e) {
  log('ERROR: ' + e.message);
  ok = false;
} finally {
  await ctx.close();
}
log('RESULT ' + (ok ? 'PASS' : 'FAIL'));
process.exit(ok ? 0 : 1);
