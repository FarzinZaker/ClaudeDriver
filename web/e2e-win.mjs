import { chromium } from '@playwright/test';
import fs from 'fs';

const BASE = 'http://localhost:8080';
const log = (...a) => console.log(...a);
let pass = true;

const browser = await chromium.launch();
const context = await browser.newContext({ baseURL: BASE, acceptDownloads: true });
const page = await context.newPage();

try {
  await page.goto('/');
  await page.getByRole('heading', { name: 'ClaudeDriver' }).waitFor({ timeout: 10000 });
  await page.getByRole('tab', { name: 'First-time setup' }).click();
  await page.getByLabel('Username').fill('operator');
  await page.getByLabel('Password').fill('supersecret123');
  await page.getByLabel('Bootstrap code').fill('dev-bootstrap');
  await page.getByRole('button', { name: 'Create operator' }).click();
  await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 20000 });

  // Enroll a WINDOWS machine.
  await page.getByPlaceholder('e.g. farzin-mac').fill('e2e-win');
  await page.getByLabel('Operating system').selectOption('windows');
  await page.getByRole('button', { name: 'Register machine' }).click();
  const link = page.getByRole('link', { name: /Download installer for Windows/i });
  await link.waitFor({ timeout: 20000 });
  log('✓ registered a Windows machine, installer link shown');

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 120000 }),
    link.click(),
  ]);
  const dest = '/tmp/e2e-win.zip';
  await download.saveAs(dest);
  const size = fs.statSync(dest).size;
  const header = fs.readFileSync(dest).subarray(0, 2).toString('latin1');
  log(`· downloaded "${download.suggestedFilename()}" → ${size} bytes (${(size / 1e6).toFixed(1)} MB), header=${header}`);
  if (size < 50_000_000 || header !== 'PK') pass = false;
} catch (e) {
  pass = false;
  log('✗ EXCEPTION:', e.message);
}
log('\nRESULT:', pass ? 'PASS ✅ — real Windows installer downloaded' : 'FAIL ❌');
await browser.close();
process.exit(pass ? 0 : 1);
