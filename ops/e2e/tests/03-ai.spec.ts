import { expect, test } from '@playwright/test'
import { WEB_URL } from '../lib/env'
import { loginUser } from '../lib/ui'

test('登录 → AI 问答（SSE 流式与来源片段）', async ({ page }) => {
  test.setTimeout(150_000)

  await loginUser(page)
  await page.goto(`${WEB_URL}/ai`)
  await expect(page.getByRole('button', { name: '智能问答' })).toHaveClass(/active/)

  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/user/ai/chat/stream') && response.request().method() === 'POST',
  )
  await page.getByPlaceholder(/问问工具怎么借/).fill('如何预约租赁工具？')
  await page.locator('.ci-send').click()

  const response = await responsePromise
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type'] ?? '').toContain('text/event-stream')

  // 流式协议：先 sources，再逐条 message；外部模型不可用时后端也会用兜底文案发完事件流。
  const stream = await response.text()
  expect(stream).toMatch(/event:\s*sources/)
  expect(stream).toMatch(/event:\s*message/)

  const assistantBubbles = page.locator('.bubble-row.ai')
  await expect(assistantBubbles).toHaveCount(2, { timeout: 30_000 })
  const answer = assistantBubbles.last()

  await expect(answer.locator('.typing, .cursor')).toHaveCount(0, { timeout: 90_000 })
  await expect(answer).not.toHaveText(/^\s*$/, { timeout: 30_000 })

  // 来源片段：向量检索或关键词兜底都应至少命中一条社区知识库文档。
  const source = answer.locator('.src-item').first()
  await expect(source).toBeVisible({ timeout: 30_000 })
  await expect(source.locator('.src-body b')).not.toBeEmpty()
  await expect(source.locator('.src-body p')).not.toBeEmpty()
})
