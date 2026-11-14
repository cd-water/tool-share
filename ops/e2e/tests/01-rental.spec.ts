import { expect, test } from '@playwright/test'
import { api, findAvailableTool, poll, type Rental } from '../lib/api'
import { ADMIN_URL, WEB_URL } from '../lib/env'
import { toInputValue } from '../lib/time'
import { loginAdmin, loginUser, rowByOrder, runId, waitUntil } from '../lib/ui'

test('预约 → 支付 → 取件 → 归还', async ({ page }) => {
  test.setTimeout(240_000)

  const id = runId()
  const pickupPerson = `E2E取件-${id}`
  const userToken = (await api.loginUser()).tokenValue
  const adminToken = (await api.loginAdmin()).tokenValue

  // 开始时间取下一分钟：支付取件后即可满足「实际归还时间不得早于开始时间」。
  const start = new Date(Date.now() + 65_000)
  start.setSeconds(0, 0)
  const end = new Date(start.getTime() + 2 * 60 * 60 * 1000)
  const tool = await findAvailableTool(userToken, start, end)

  await test.step('居民登录并打开工具广场', async () => {
    await loginUser(page)
    await expect(page.getByRole('heading', { name: '工具广场' })).toBeVisible()
  })

  await test.step('选择可用工具并查看可预约时段', async () => {
    await page.getByPlaceholder('搜索工具名称 / 型号 / 编码…').fill(tool.name)
    const card = page.locator('.tool-card').filter({ hasText: tool.name }).first()
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '预约', exact: true }).click()

    const dialog = page.locator('.dlg').filter({ hasText: '预约工具' })
    await expect(dialog).toBeVisible()
    await dialog.locator('#startTime').fill(toInputValue(start))
    await dialog.locator('#endTime').fill(toInputValue(end))
    await expect(dialog.getByText('该时段可预约')).toBeVisible({ timeout: 20_000 })
    await dialog.locator('#pickupPerson').fill(pickupPerson)
    await dialog.getByRole('button', { name: '提交预约' }).click()
    await expect(page).toHaveURL(/\/rentals$/, { timeout: 20_000 })
  })

  let rental: Rental
  await test.step('创建租赁单并在居民端支付押金', async () => {
    rental = await poll('新创建的租赁单', async () => {
      const list = await api.listUserRentals(userToken)
      return list.records.find((item) => item.pickupPerson === pickupPerson)
    })

    const card = page.locator('article.rental').filter({ hasText: rental.orderNo })
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '去支付' }).click()
    const dialog = page.locator('.dlg').filter({ hasText: '支付押金' })
    await dialog.getByRole('button', { name: '确认支付' }).click()
    await expect(card.getByText('已预约', { exact: true })).toBeVisible()
    await expect(card.getByText('押金已支付')).toBeVisible()
  })

  await test.step('管理端确认取件', async () => {
    await loginAdmin(page)
    await page.goto(`${ADMIN_URL}/rentals`)
    await page.getByPlaceholder('单号 / 工具 / 租用人').fill(rental.orderNo)
    await page.getByRole('button', { name: '查询' }).click()

    const row = rowByOrder(page, rental.orderNo)
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '取件', exact: true }).click()

    const dialog = page.locator('.dlg').filter({ hasText: '确认取件' })
    await dialog.getByRole('button', { name: '确认取件' }).click()
    await expect(row.getByText('使用中', { exact: true })).toBeVisible()
  })

  await test.step('管理端办理归还验收', async () => {
    const row = rowByOrder(page, rental.orderNo)
    await waitUntil(page, start)
    await row.getByRole('button', { name: '归还验收' }).click()

    const dialog = page.locator('.dlg').filter({ hasText: '归还验收' })
    await expect(dialog.locator('input[type="datetime-local"]')).not.toHaveValue('')
    await expect(dialog.locator('select').first()).toHaveValue('INTACT')
    await dialog.getByRole('button', { name: '确认归还' }).click()
    await expect(row.getByText('已归还', { exact: true })).toBeVisible()
  })

  await test.step('断言费用、退款与工具状态', async () => {
    const returned = await api.userRentalDetail(userToken, rental.id)
    expect(returned.status).toBe('RETURNED')
    expect(returned.depositStatus).toBe('REFUNDED')
    expect(returned.damageResult).toBe('INTACT')
    expect(returned.overdueFee ?? 0).toBe(0)
    expect(returned.damageFee ?? 0).toBe(0)
    expect(returned.refundAmount).toBe(returned.depositAmount)

    const toolAfter = await api.toolDetail(userToken, tool.id)
    expect(toolAfter.status).toBe('AVAILABLE')

    await page.goto(`${WEB_URL}/rentals`)
    const card = page.locator('article.rental').filter({ hasText: rental.orderNo })
    await expect(card).toContainText('已归还')
    await expect(card).toContainText('押金已退款')
    await expect(card).toContainText('完好归还')
    await expect(card).toContainText(`退款 ¥${rental.depositAmount.toFixed(2)}`)

    await card.getByRole('button', { name: '归还信息' }).click()
    const detail = page.locator('.dlg').filter({ hasText: '租赁详情' })
    await expect(detail).toContainText('实际退款')
    await expect(detail).toContainText(`¥${rental.depositAmount.toFixed(2)}`)
    await expect(detail).toContainText('完好')
  })
})
