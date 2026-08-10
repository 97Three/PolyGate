import type { User } from '@/types'
import { api } from '@/lib/api-client'
import { isMockMode, mockDelay } from '@/lib/mock-mode'
import { mockUsers } from '@/mock'

interface LoginResponse {
  user: User
  expiresAt: string
}

export interface AuthMethods {
  passwordEnabled: boolean
  oidc: {
    enabled: boolean
    label: string
    startUrl: string
  }
}

const MOCK_AUTH_METHODS: AuthMethods = {
  passwordEnabled: true,
  oidc: {
    enabled: false,
    label: '企业单点登录',
    startUrl: '/admin/auth/oidc/start',
  },
}

const MOCK_SESSION_KEY = 'polygate_mock_session'
export const AUTH_METHODS_TIMEOUT_MS = 8_000

function authMethodsTimeoutError(): Error {
  const error = new Error('authentication capability probe timed out')
  error.name = 'TimeoutError'
  return error
}

export function normalizeAuthMethods(value: unknown): AuthMethods {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('authentication capability response is invalid')
  }

  const record = value as Record<string, unknown>
  if (typeof record.passwordEnabled !== 'boolean') {
    throw new Error('authentication capability response is missing passwordEnabled')
  }
  const oidc = record.oidc && typeof record.oidc === 'object' && !Array.isArray(record.oidc)
    ? record.oidc as Record<string, unknown>
    : {}
  const enabled = oidc.enabled === true
  const startUrl = typeof oidc.startUrl === 'string' && oidc.startUrl.trim()
    ? oidc.startUrl
    : '/admin/auth/oidc/start'

  return {
    passwordEnabled: record.passwordEnabled,
    oidc: {
      enabled,
      label: typeof oidc.label === 'string' && oidc.label.trim() ? oidc.label : '企业单点登录',
      startUrl,
    },
  }
}

export const authService = {
  getMethods: async (): Promise<AuthMethods> => {
    if (isMockMode) return mockDelay(MOCK_AUTH_METHODS)
    const controller = new AbortController()
    const timeout = globalThis.setTimeout(() => controller.abort(), AUTH_METHODS_TIMEOUT_MS)
    try {
      return normalizeAuthMethods(await api.get<unknown>('/admin/auth/methods', { signal: controller.signal }))
    } catch (error) {
      if (controller.signal.aborted) throw authMethodsTimeoutError()
      throw error
    } finally {
      globalThis.clearTimeout(timeout)
    }
  },

  login: async (username: string, password: string): Promise<User> => {
    if (!username.trim() || !password) {
      throw new Error('无效的账号或密码')
    }

    if (isMockMode) {
      if (username.trim() !== 'admin' || password !== 'admin') {
        throw new Error('mock 模式账号密码为 admin / admin')
      }
      const user = mockUsers.find((item) => item.username === 'admin') || mockUsers[0]
      window.localStorage.setItem(MOCK_SESSION_KEY, user.id)
      return mockDelay(user)
    }

    const response = await api.post<LoginResponse>('/admin/auth/login', {
      username: username.trim(),
      password,
    })
    return response.user
  },

  logout: (): Promise<{ ok: boolean }> => {
    if (!isMockMode) return api.post('/admin/auth/logout')
    window.localStorage.removeItem(MOCK_SESSION_KEY)
    return mockDelay({ ok: true })
  },

  getCurrentUser: (): Promise<User> => {
    if (!isMockMode) return api.get('/admin/auth/me')
    const userId = window.localStorage.getItem(MOCK_SESSION_KEY)
    const user = mockUsers.find((item) => item.id === userId)
    if (!user) return Promise.reject(new Error('Unauthorized'))
    return mockDelay(user)
  },
}
