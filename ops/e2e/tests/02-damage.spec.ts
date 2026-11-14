import { expect, test } from '@playwright/test'
import { api, findAvailableTool, poll } from '../lib/api'
import { ADMIN_URL, WEB_URL } from '../lib/env'
import { loginAdmin, loginUser, PNG_1X1, runId, waitUntil } from '../lib/ui'

test('报修 → 核验 → 维修 → 验收', async ({ page }) => {
  test.setTimeout(240_000)

  const id = runId()
  const userToken = (await api.loginUser()).tokenValue
  const adminToken = (await api.loginAdmin()).tokenValue

  // 报修要求租赁处于「使用中」：通过 API 完成下单、支付、取件，把界面留给报修与处理步骤。
  const start = new Date(Date.now() + 10_000)
  start.setMilliseconds(0)
  const end = new Date(start.getTime() + 2 * 60 * 60 * 1000)
  const tool = await findAvailableTool(userToken, start, end)
  const rental = await api.createRental(userToken, {
    toolId: tool.id,
    startTime: start,
    endTime: end,
    pickupPerson: `E2E报修-${id}`,
  })
  await api.payRental(userToken, rental.id)
  await api.adminPickup(adminToken, rental.id)

  await test.step('居民在使用中租赁提交报修并上传 PNG', async () => {
    await loginUser(page)
    await page.goto(`${WEB_URL}/damage?rentalId=${rental.id}`)

    const form = page.locator('.dlg').filter({ hasText: '发起报修' })
    await expect(form).toBeVisible()
    await expect(form.locator('#rentalId')).toHaveValue(String(rental.id))
    await form.locator('#damagePart').fill(`E2E损坏部位-${id}`)
    await form.locator('#description').fill(`E2E 自动化报修描述-${id}`)

    const upload = page.waitForResponse(
      (response) => response.url().includes('/api/user/upload') && response.request().method() === 'POST',
    )
    await form.locator('input[type="file"]').setInputFiles({
      name: 'damage.png',
      mimeType: 'image/png',
      buffer: PNG_1X1,
    })
    expect((await upload).status()).toBe(200)
    await expect(form.locator('.up-item img')).toHaveCount(1)

    await form.getByRole('button', { name: '提交报修' }).click()
    await expect(form).toBeHidden()
  })

  const report = await poll('报修单创建完成', async () => {
    const list = await api.listUserDamage(userToken)
    return list.records.find((item) => item.rentalId === rental.id)
  })

  await test.step('管理端核验、维修完成并验收', async () => {
    await loginAdmin(page)
    await page.goto(`${ADMIN_URL}/damage`)
    await page.getByPlaceholder('报修单号 / 工具 / 报修人').fill(report.reportNo)
    await page.getByRole('button', { name: '查询' }).click()

    const row = page.locator('tr').filter({ hasText: report.reportNo }).first()
    await expect(row).toBeVisible()

    await row.getByRole('button', { name: '核验', exact: true }).click()
    const verify = page.locator('.dlg').filter({ hasText: '报修核验' })
    await verify.locator('select').selectOption('CONFIRMED')
    await verify.getByPlaceholder('选填，如 王师傅').fill(`E2E维修员-${id}`)
    await verify.getByRole('button', { name: '确认核验' }).click()
    await expect(row.getByText('已核验', { exact: true })).toBeVisible()

    await row.getByRole('button', { name: '进度', exact: true }).click()
    const progress = page.locator('.dlg').filter({ hasText: '更新维修进度' })
    await progress.locator('select').selectOption('COMPLETED')
    await progress.getByPlaceholder('请填写本次维修进度说明').fill(`E2E 维修完成说明-${id}`)
    await progress.getByRole('button', { name: '保存进度' }).click()
    await expect(row.getByText('维修完成', { exact: true })).toBeVisible()

    await row.getByRole('button', { name: '验收', exact: true }).click()
    const accept = page.locator('.dlg').filter({ hasText: '维修验收' })
    await accept.locator('select').nth(0).selectOption('QUALIFIED')
    await accept.locator('select').nth(1).selectOption('MAINTENANCE')
    await accept.getByRole('button', { name: '确认验收' }).click()
    await expect(row.getByText('已完成', { exact: true })).toBeVisible()
  })

  await test.step('断言时间线、报修状态与工具状态', async () => {
    const detail = await api.adminDamageDetail(adminToken, report.id)
    expect(detail.status).toBe('COMPLETED')
    expect(detail.progressStatus).toBe('COMPLETED')
    expect(detail.verifyResult).toBe('CONFIRMED')
    expect(detail.acceptResult).toBe('QUALIFIED')
    expect(detail.verifyTime).toBeTruthy()
    expect(detail.acceptTime).toBeTruthy()
    expect(detail.toolStatusAfter).toBe('MAINTENANCE')

    const toolAfter = await api.toolDetail(userToken, tool.id)
    expect(toolAfter.status).toBe('MAINTENANCE')
    expect(toolAfter.damageLevel).toBe('LIGHT')

    await page.goto(`${WEB_URL}/damage`)
    await page.getByPlaceholder('搜索报修单号 / 工具编号…').fill(report.reportNo)
    await page.keyboard.press('Enter')
    const card = page.locator('.rep-card').filter({ hasText: report.reportNo }).first()
    await expect(card).toBeVisible()
    await expect(card).toContainText('已完成')
    await card.click()

    const dialog = page.locator('.dlg').filter({ hasText: '报修详情' })
    await expect(dialog).toContainText('核验处理')
    await expect(dialog).toContainText('维修进度')
    await expect(dialog).toContainText('维修验收')
    await expect(dialog).toContainText('维修完成')
  })

  await test.step('清理：归还租赁单，释放工具', async () => {
    await waitUntil(page, start)
    await api.adminReturn(adminToken, rental.id, {
      actualReturnTime: new Date(),
      damageResult: 'INTACT',
      remark: `E2E 清理归还-${id}`,
    })
    const returned = await poll('租赁单归还', async () => {
      const current = await api.adminRentalDetail(adminToken, rental.id)
      return current.status === 'RETURNED' ? current : undefined
    })
    expect(returned.status).toBe('RETURNED')

    const toolClean = await api.toolDetail(userToken, tool.id)
    expect(toolClean.status).toBe('AVAILABLE')
  })
})
