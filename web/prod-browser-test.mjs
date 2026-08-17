import { chromium } from '@playwright/test';
import fs from 'fs';

const BASE = 'https://claudedriver.resortwise.ai';
const log = (...a) => console.log(...a);

const browser = await chromium.launch({ headless: false }); // visible: you do first-time setup
const context = await browser.newContext({ baseURL: BASE, acceptDownloads: true });
const page = await context.newPage();

try {
  await page.goto('/');
  log('\n=================================================================');
  log(' A browser window is open on https://claudedriver.resortwise.ai');
  log(' 1. Click "First-time setup"');
  log(' 2. Enter a username, a password (8+ chars), and your BOOTSTRAP CODE');
  log('    (from deploy/secrets.env: OPERATOR_BOOTSTRAP_CODE)');
  log(' 3. Click "Create operator"');
  log(' Waiting up to 5 minutes for the dashboard...');
  log('=================================================================\n');

  await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 300000 });
  log('✓ logged in — dashboard is up');

  // Enroll a machine and download its installer.
  await page.getByPlaceholder('e.g. farzin-mac').fill('browser-test-mac');
  await page.getByRole('button', { name: 'Register machine' }).click();
  const link = page.getByRole('link', { name: /Download installer for macOS/i });
  await link.waitFor({ timeout: 20000 });
  log('· machine registered; downloading installer...');

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 180000 }),
    link.click(),
  ]);
  const dest = '/tmp/prod-installer.zip';
  await download.saveAs(dest);
  const size = fs.statSync(dest).size;
  const header = fs.readFileSync(dest).subarray(0, 2).toString('latin1');
  log(`· downloaded "${download.suggestedFilename()}" → ${size} bytes (${(size / 1e6).toFixed(1)} MB), header=${header}`);

  const ok = size > 50_000_000 && header === 'PK';
  log(ok ? '\nRESULT: PASS ✅ — real installer downloaded from PROD' : `\nRESULT: FAIL ❌ (size=${size}, header=${header})`);
  await browser.close();
  process.exit(ok ? 0 : 1);
} catch (e) {
  log('\n✗ EXCEPTION:', e.message);
  await page.screenshot({ path: '/tmp/prod-fail.png' }).catch(() => {});
  await browser.close();
  process.exit(1);
}
