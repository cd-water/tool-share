import { defineConfig } from '@playwright/test'

const WEB_URL = process.env.WEB_URL ?? 'http://127.0.0.1:5901'

export default defineConfig({
  testDir: './tests',
  // 三条主流程共享同一套后端数据与端口，必须串行执行。
  workers: 1,
  fullyParallel: false,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  outputDir: 'test-results',
  use: {
    baseURL: WEB_URL,
    // 本机没有 Playwright 自带的 Chromium 时复用系统 Chrome，避免额外下载。
    channel: 'chrome',
    headless: true,
    viewport: { width: 1440, height: 960 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    launchOptions: {
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    },
  },
})
