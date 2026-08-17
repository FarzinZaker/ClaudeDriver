// Real-browser end-to-end smoke: passkey login (virtual authenticator) → enroll a machine →
// assert the installer download renders and no page errors. Requires a local stack:
//   docker run -d --name cd-pg -e POSTGRES_PASSWORD=claudedriver -e POSTGRES_USER=claudedriver \
//     -e POSTGRES_DB=claudedriver -p 5433:5432 postgres:16
//   (build web: npm run build) and run the backend with WEB_ROOT=../web/dist,
//   DATABASE_URL=jdbc:postgresql://localhost:5433/claudedriver, WEBAUTHN_ORIGIN=http://localhost:8080,
//   OPERATOR_BOOTSTRAP_CODE=dev-bootstrap  — then:  node e2e-test.mjs
// Reset between runs (registration is one-time): TRUNCATE all tables except flyway_schema_history.
import { chromium } from '@playwright/test';

const BASE = 'http://localhost:8080';
const errors = [];
let pass = true;
const log = (...a) => console.log(...a);

const browser = await chromium.launch();
const context = await browser.newContext({ baseURL: BASE });
const page = await context.newPage();

// Virtual WebAuthn authenticator so passkey create/get succeed headlessly.
const client = await context.newCDPSession(page);
await client.send('WebAuthn.enable');
await client.send('WebAuthn.addVirtualAuthenticator', {
  options: {
    protocol: 'ctap2',
    transport: 'internal',
    hasResidentKey: true,
    hasUserVerification: true,
    isUserVerified: true,
    automaticPresenceSimulation: true,
  },
});

// The initial /status before login legitimately returns 401 (→ show login), so ignore that one.
const benign = (t) => /status of 401/.test(t);
page.on('console', (m) => {
  if (m.type() === 'error' && !benign(m.text())) errors.push('console: ' + m.text());
});
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

try {
  await page.goto('/');
  await page.getByRole('heading', { name: 'ClaudeDriver' }).waitFor({ timeout: 10000 });

  // Log in with a passkey if the login panel is showing.
  const setupTab = page.getByRole('tab', { name: 'First-time setup' });
  if (await setupTab.count()) {
    await setupTab.click();
    // Register form must appear (password field for the bootstrap code).
    await page.locator('input[type="password"]').waitFor({ state: 'visible', timeout: 5000 });
    await page.locator('input[type="password"]').fill('dev-bootstrap');
    const btn = page.getByRole('button', { name: 'Register passkey' });
    log('· register button disabled?', await btn.isDisabled());
    await btn.click();
    log('· clicked Register passkey');
    // Surface a login error if the passkey flow fails, instead of a blind timeout.
    await Promise.race([
      page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 20000 }),
      page
        .getByRole('alert')
        .waitFor({ timeout: 20000 })
        .then(async () => {
          throw new Error('login failed: ' + (await page.getByRole('alert').innerText()));
        }),
    ]);
  } else {
    await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 20000 });
  }
  log('✓ dashboard loaded (authenticated)');
  await page.screenshot({ path: '/tmp/e2e-1-dashboard.png' });

  // Enroll a machine — the exact flow that blanked the screen.
  await page.getByPlaceholder('e.g. farzin-mac').fill('e2e-mac');
  await page.getByRole('button', { name: 'Register machine' }).click();
  log('· clicked Register machine');

  // The result must render (not blank) with a working installer download link.
  const link = page.getByRole('link', { name: /Download installer for macOS/i });
  await link.waitFor({ timeout: 20000 });
  const href = await link.getAttribute('href');
  log('✓ installer download link rendered → href =', href);

  // The new machine card must render (this is the null-connection path).
  await page.getByText('e2e-mac').first().waitFor({ timeout: 10000 });
  const bodyLen = (await page.locator('body').innerText()).length;
  log('✓ machine card for "e2e-mac" rendered; body text length =', bodyLen, '(not blank)');
  await page.screenshot({ path: '/tmp/e2e-2-enrolled.png' });

  if (!href || !href.includes('/installer?os=macos')) { pass = false; log('✗ download href wrong'); }
  if (bodyLen < 200) { pass = false; log('✗ page looks blank'); }
} catch (e) {
  pass = false;
  log('✗ EXCEPTION:', e.message);
  await page.screenshot({ path: '/tmp/e2e-fail.png' }).catch(() => {});
}

if (errors.length) { pass = false; log('✗ PAGE ERRORS:', errors.join(' | ')); }
else log('✓ no console/page errors');

log('\nRESULT:', pass ? 'PASS ✅' : 'FAIL ❌');
await browser.close();
process.exit(pass ? 0 : 1);
