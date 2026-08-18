import { chromium } from '@playwright/test';

// Persistent profile: log in once, reused headlessly on later runs.
const ctx = await chromium.launchPersistentContext('/tmp/cd-browser-profile', {
  headless: process.env.HEADED ? false : true,
  baseURL: 'https://claudedriver.resortwise.ai',
});
const page = ctx.pages()[0] || (await ctx.newPage());

try {
  await page.goto('/');
  const dash = page.getByRole('heading', { name: 'Enroll a machine' });
  try {
    await dash.waitFor({ timeout: 4000 });
  } catch {
    console.log('>>> Please LOG IN (username/password) in the browser window. Waiting up to 3 min... <<<');
    await dash.waitFor({ timeout: 180000 });
  }
  console.log('✓ authenticated — reading /sessions and /status');

  const sessions = await page.evaluate(() =>
    fetch('/sessions', { credentials: 'include' }).then((r) => r.json()),
  );
  const status = await page.evaluate(() =>
    fetch('/status', { credentials: 'include' }).then((r) => r.json()),
  );
  console.log('SESSIONS=' + JSON.stringify(sessions));
  console.log(
    'MACHINES=' +
      JSON.stringify((status.machines || []).map((m) => ({ name: m.name, status: m.status, conn: m.connection?.state ?? null }))),
  );
} catch (e) {
  console.log('ERROR: ' + e.message);
} finally {
  await ctx.close();
}
