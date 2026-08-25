import fs from 'node:fs'
import path from 'node:path'
import { expect, type Page } from '@playwright/test'

type EnvMap = Record<string, string>

export interface PolyGateE2EEnv {
  adminUsername: string
  adminPassword: string
  authToken: string
}

export function polyGateEnv(): PolyGateE2EEnv {
  const fileEnv = readEnvFile(process.env.POLYGATE_ENV_FILE || path.resolve(process.cwd(), '..', '.env'))
  const env = { ...fileEnv, ...process.env }
  return {
    adminUsername: env.POLYGATE_ADMIN_USERNAME || 'admin',
    adminPassword: env.POLYGATE_ADMIN_BOOTSTRAP_PASSWORD || env.POLYGATE_ADMIN_PASSWORD || '',
    authToken: env.POLYGATE_CLIENT_TOKEN || env.POLYGATE_AUTH_TOKEN || env.ANTHROPIC_AUTH_TOKEN || '',
  }
}

export function requireE2EEnv(): PolyGateE2EEnv {
  const env = polyGateEnv()
  if (!env.adminPassword) {
    throw new Error('POLYGATE_ADMIN_PASSWORD is required for dashboard E2E tests')
  }
  if (!env.authToken) {
    throw new Error('POLYGATE_AUTH_TOKEN or ANTHROPIC_AUTH_TOKEN is required for dashboard E2E tests')
  }
  return env
}

export async function login(page: Page, env = requireE2EEnv()) {
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.locator('#username').fill(env.adminUsername)
  await page.locator('#password').fill(env.adminPassword)
  await page.getByRole('button', { name: /^登录$/ }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible()
}

export async function seedFailedGatewayRequest(page: Page, env = requireE2EEnv()) {
  // The E2E profile deliberately points the reviewed `custom` Provider at a
  // closed loopback port. This creates a terminal ledger row without contacting
  // a real or billable upstream and without weakening the Provider catalog gate.
  const response = await page.request.post('/v1/chat/completions', {
    headers: {
      Authorization: `Bearer ${env.authToken}`,
    },
    data: {
      model: 'custom:ci-model',
      messages: [{ role: 'user', content: 'PolyGate isolated E2E ledger seed' }],
    },
  })
  expect(response.status()).toBeGreaterThanOrEqual(500)
  expect(response.status()).toBeLessThan(600)
}

export function csrfHeaders() {
  return { 'X-PolyGate-CSRF': '1' }
}

export function dateTimeLocal(timestamp: number): string {
  const date = new Date(timestamp)
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return localDate.toISOString().slice(0, 16)
}

export async function cleanupE2EUsers(page: Page, prefix = 'e2e_') {
  const keysResponse = await page.request.get('/admin/api-keys')
  if (keysResponse.ok()) {
    const keys = await keysResponse.json() as Array<{ id: string; name: string; username?: string }>
    for (const key of keys) {
      if (!key.name.startsWith(prefix) && !key.username?.startsWith(prefix)) continue
      await page.request.delete(`/admin/api-keys/${encodeURIComponent(key.id)}`, {
        headers: csrfHeaders(),
      }).catch(() => undefined)
    }
  }

  const usersResponse = await page.request.get('/admin/users')
  if (!usersResponse.ok()) return
  const users = await usersResponse.json() as Array<{ id: string; username: string }>
  for (const user of users) {
    if (!user.username.startsWith(prefix)) continue
    await page.request.delete(`/admin/users/${encodeURIComponent(user.id)}`, {
      headers: csrfHeaders(),
    }).catch(() => undefined)
  }
}

export async function cleanupE2EProviders(page: Page, prefix = 'e2e_') {
  const providersResponse = await page.request.get('/admin/providers')
  if (!providersResponse.ok()) return
  const providers = await providersResponse.json() as Array<{ id: string }>
  for (const provider of providers) {
    if (!provider.id.startsWith(prefix)) continue
    await page.request.delete(`/admin/providers/${encodeURIComponent(provider.id)}?force=true`, {
      headers: csrfHeaders(),
    }).catch(() => undefined)
  }
}

function readEnvFile(filePath: string): EnvMap {
  if (!fs.existsSync(filePath)) return {}
  const env: EnvMap = {}
  for (const rawLine of fs.readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const index = line.indexOf('=')
    if (index < 0) continue
    const key = line.slice(0, index).trim()
    const value = line.slice(index + 1).trim().replace(/^(['"])(.*)\1$/, '$2')
    if (key) env[key] = value
  }
  return env
}
