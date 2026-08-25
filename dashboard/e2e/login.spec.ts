import { expect, test } from '@playwright/test'
import { requireE2EEnv } from './helpers'

test('auth capability probe failure is explicit, non-blocking, and retryable', async ({ page }) => {
  let attempts = 0
  let recoveryRequested = false
  await page.route('**/admin/auth/methods', async (route) => {
    attempts += 1
    if (!recoveryRequested) {
      await route.abort('connectionfailed')
      return
    }

    await new Promise((resolve) => setTimeout(resolve, 200))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        passwordEnabled: true,
        oidc: {
          enabled: false,
          label: '企业单点登录',
          startUrl: '/admin/auth/oidc/start',
        },
      }),
    })
  })

  await page.goto('/login')

  const probeAlert = page.getByRole('alert').filter({ hasText: '认证方式探测失败' })
  await expect(probeAlert).toBeVisible()
  await expect(probeAlert).toContainText('并不表示服务端已启用密码登录')
  await expect(page.locator('#username')).toBeVisible()

  recoveryRequested = true
  await page.getByRole('button', { name: '重新探测' }).click()
  await expect(page.getByRole('status')).toContainText('正在确认当前实例启用的登录方式')
  await expect(probeAlert).toHaveCount(0)
  await expect(page.locator('#username')).toBeVisible()
  expect(attempts).toBeGreaterThanOrEqual(2)
})

test('expired session explains the redirect and returns to the protected URL', async ({ page }) => {
  const env = requireE2EEnv()
  await page.goto('/login')
  await page.evaluate(() => {
    window.sessionStorage.setItem('polygate_auth_notice', '会话已过期，请重新登录后继续。')
    window.sessionStorage.setItem('polygate_return_to', '/logs?status=error')
  })
  await page.reload()

  await expect(page.getByRole('status').filter({ hasText: '会话已过期' })).toBeVisible()
  await page.locator('#username').fill(env.adminUsername)
  await page.locator('#password').fill(env.adminPassword)
  await page.getByRole('button', { name: /^登录$/ }).click()

  await expect(page).toHaveURL(/\/logs\?status=error$/)
  await expect(page.getByRole('heading', { name: '请求日志' })).toBeVisible()
  await expect(page.getByRole('button', { name: '只看错误' })).toHaveAttribute('aria-pressed', 'true')
})
