import { expect, type Page } from '@playwright/test'
import { ADMIN_URL, ADMIN_USERNAME, PASSWORD, USER_PHONE, WEB_URL } from './env'

export function runId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

/** 居民端登录：走真实登录表单与转场动画。 */
export async function loginUser(page: Page): Promise<void> {
  await page.goto(`${WEB_URL}/login`)
  await page.getByRole('tab', { name: '密码登录' }).click()
  await page.locator('#phone').fill(USER_PHONE)
  await page.locator('#password').fill(PASSWORD)
  await page.locator('#loginBtn').click()
  await expect(page).toHaveURL(/\/tools$/, { timeout: 30_000 })
}

/** 居民端验证码登录：Mock 短信直接回填验证码，走真实登录表单。 */
export async function loginUserByCode(page: Page): Promise<void> {
  await page.goto(`${WEB_URL}/login`)
  await page.getByRole('tab', { name: '验证码登录' }).click()
  await page.locator('#phone').fill(USER_PHONE)
  await page.locator('#sendCode').click()
  await expect(page.locator('#code')).toHaveValue(/^\d{6}$/, { timeout: 15_000 })
  await page.locator('#loginBtn').click()
  await expect(page).toHaveURL(/\/tools$/, { timeout: 30_000 })
}

/** 管理端登录：走真实登录表单与自检转场。 */
export async function loginAdmin(page: Page): Promise<void> {
  await page.goto(`${ADMIN_URL}/login`)
  await page.getByPlaceholder('请输入管理员账号').fill(ADMIN_USERNAME)
  await page.getByPlaceholder('请输入密码').fill(PASSWORD)
  await page.getByRole('button', { name: '启动控制台' }).click()
  await expect(page).toHaveURL(/\/dashboard$/, { timeout: 30_000 })
}

/** 1×1 透明 PNG：真实图片字节，用于报修凭证上传。 */
export const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)

/** 等待到某个时刻之后，用于归还时间不得早于租期开始。 */
export async function waitUntil(page: Page, moment: Date): Promise<void> {
  const remaining = moment.getTime() - Date.now()
  if (remaining > 0) await page.waitForTimeout(remaining + 1_000)
}

/** 按租赁单号定位居民端/管理端的卡片或表格行。 */
export function rowByOrder(page: Page, orderNo: string) {
  return page.locator('article.rental, tr').filter({ hasText: orderNo }).first()
}
