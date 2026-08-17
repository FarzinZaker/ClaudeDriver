// Real-browser end-to-end smoke against a local stack: register (username/password) -> enroll a
// machine -> DOWNLOAD the installer and assert it is a real ~76MB package (not a 22-byte empty zip).
// Prereqs: local Postgres + backend (WEB_ROOT=web/dist, OPERATOR_BOOTSTRAP_CODE=dev-bootstrap,
// AGENT_RUNTIMES_BUCKET set with AWS creds so the installer can be assembled). Then: node e2e-test.mjs
import { chromium } from '@playwright/test';
import fs from 'fs';

const BASE = 'http://localhost:8080';
const errors = [];
let pass = true;
const log = (...a) => console.log(...a);

const browser = await chromium.launch();
const context = await browser.newContext({ baseURL: BASE, acceptDownloads: true });
const page = await context.newPage();

const benign = (t) => /status of 401/.test(t); // pre-login /status is expected 401
page.on('console', (m) => { if (m.type() === 'error' && !benign(m.text())) errors.push('console: ' + m.text()); });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

try {
  await page.goto('/');
  await page.getByRole('heading', { name: 'ClaudeDriver' }).waitFor({ timeout: 10000 });

  // Register the first operator (username + password + bootstrap code).
  await page.getByRole('tab', { name: 'First-time setup' }).click();
  await page.getByLabel('Username').fill('operator');
  await page.getByLabel('Password').fill('supersecret123');
  await page.getByLabel('Bootstrap code').fill('dev-bootstrap');
  await page.getByRole('button', { name: 'Create operator' }).click();

  await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 20000 });
  log('✓ registered + logged in (username/password)');

  // Enroll a machine.
  await page.getByPlaceholder('e.g. farzin-mac').fill('e2e-mac');
  await page.getByRole('button', { name: 'Register machine' }).click();
  const link = page.getByRole('link', { name: /Download installer for macOS/i });
  await link.waitFor({ timeout: 20000 });
  log('✓ machine enrolled, installer link shown');

  // Actually download the installer through the browser and measure it.
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 120000 }),
    link.click(),
  ]);
  const dest = '/tmp/e2e-installer.zip';
  await download.saveAs(dest);
  const size = fs.statSync(dest).size;
  const header = fs.readFileSync(dest).subarray(0, 2).toString('latin1');
  log(`· downloaded "${download.suggestedFilename()}" → ${size} bytes (${(size / 1e6).toFixed(1)} MB), header=${header}`);

  if (size < 50_000_000) { pass = false; log('✗ installer too small (empty-zip regression?)'); }
  if (header !== 'PK') { pass = false; log('✗ not a zip'); }
  if (pass) log('✓ real installer package downloaded');
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
