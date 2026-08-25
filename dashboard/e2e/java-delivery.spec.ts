import { expect, test } from '@playwright/test'
import { login, requireE2EEnv } from './helpers'

test('Java management pages render successfully without failed APIs or caught React errors', async ({ page }) => {
  await login(page, requireE2EEnv())
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  page.on('console', message => { if (message.type() === 'error') failures.push(message.text()) })
  page.on('response', response => {
    if (new URL(response.url()).pathname.startsWith('/admin/') && response.status() >= 400) {
      failures.push(`${response.status()} ${new URL(response.url()).pathname}`)
    }
  })
  await login(page, requireE2EEnv())
  for (const route of ['/dashboard', '/guide', '/api-keys', '/users', '/quotas', '/models', '/logs', '/enterprise', '/governance', '/operations', '/settings']) {
    await page.goto(route, { waitUntil: 'networkidle' })
    await expect(page.locator('main')).toBeVisible()
    await expect(page).not.toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', { name: /页面加载出错|加载失败/ })).toHaveCount(0)
    await expect(page.getByText('Unexpected Application Error!')).toHaveCount(0)
  }
  expect(failures).toEqual([])
})

test('nullable pre-dispatch log renders in the table, filters and detail drawer', async ({ page }) => {
  await login(page, requireE2EEnv())
  const response = await page.request.get('/admin/logs?pageSize=1')
  expect(response.ok()).toBeTruthy()
  const data = await response.json()
  expect(data.logs.length).toBeGreaterThan(0)
  const log = { ...data.logs[0], id: '00000000-0000-0000-0000-000000000400', requestId: '00000000-0000-0000-0000-000000000400', provider: null, resolvedModel: null, model: 'pre-dispatch-fixture', status: 'error', statusCode: 400, errorMessage: 'failed_before_response', pricingEvidence: null, modelPricing: null, costEstimate: null }
  const errors: string[] = []
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/admin/logs?*', route => route.fulfill({ json: { ...data, total: 1, logs: [log] } }))
  await page.route(`**/admin/logs/${log.id}`, route => route.fulfill({ json: log }))
  await page.goto('/logs', { waitUntil: 'networkidle' })
  await expect(page.getByRole('heading', { name: '请求日志', exact: true })).toBeVisible()
  await expect(page.getByText('尚未选择 Provider').filter({ visible: true }).first()).toBeVisible()
  await page.getByRole('button', { name: '查看 pre-dispatch-fixture 请求详情' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  for (const name of ['概览', '协议 Trace', 'Tool Use']) {
    const tab = page.getByRole('tab', { name, exact: true })
    await tab.click()
  }
  await expect(page.getByRole('heading', { name: /页面加载出错|加载失败/ })).toHaveCount(0)
  expect(errors).toEqual([])
})

test('a caught log rendering failure does not persist when navigating to another page', async ({ page }) => {
  await login(page, requireE2EEnv())
  const data = await (await page.request.get('/admin/logs?pageSize=1')).json()
  const invalid = { ...data.logs[0], provider: { unexpected: 'malformed-wire-value' } }
  await page.route('**/admin/logs?*', route => route.fulfill({ json: { ...data, logs: [invalid], total: 1 } }))
  await page.goto('/logs', { waitUntil: 'networkidle' })
  await expect(page.getByRole('heading', { name: '页面加载出错', exact: true })).toBeVisible()
  await page.locator('a[href="/settings"]').filter({ visible: true }).first().click()
  await expect(page).toHaveURL(/\/settings$/)
  await expect(page.getByRole('heading', { name: '运行设置与运维', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '页面加载出错', exact: true })).toHaveCount(0)
})