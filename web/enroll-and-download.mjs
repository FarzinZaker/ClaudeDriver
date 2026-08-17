import { chromium } from '@playwright/test';

const BASE = 'https://claudedriver.resortwise.ai';
const log = (...a) => console.log(...a);

const browser = await chromium.launch({ headless: false });
const context = await browser.newContext({ baseURL: BASE, acceptDownloads: true });
const page = await context.newPage();

try {
  await page.goto('/');
  log('\n=== Log in with your username/password in the browser window. Waiting up to 5 min... ===\n');
  await page.getByRole('heading', { name: 'Enroll a machine' }).waitFor({ timeout: 300000 });
  log('✓ logged in');

  await page.getByPlaceholder('e.g. farzin-mac').fill('farzin-mac-live');
  await page.getByLabel('Operating system').selectOption('macos');
  await page.getByRole('button', { name: 'Register machine' }).click();
  const link = page.getByRole('link', { name: /Download installer for macOS/i });
  await link.waitFor({ timeout: 20000 });

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 180000 }),
    link.click(),
  ]);
  await download.saveAs('/tmp/mac-installer.zip');
  log('✓ DOWNLOADED /tmp/mac-installer.zip');
  log('>>> Keep this browser open on the dashboard to watch the machine come online. <<<');
  // Leave the browser open ~12 min so you can watch the machine connect.
  await page.waitForTimeout(720000).catch(() => {});
  await browser.close();
} catch (e) {
  log('✗ EXCEPTION:', e.message);
  await browser.close();
  process.exit(1);
}
