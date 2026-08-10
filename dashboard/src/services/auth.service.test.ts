import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: false,
  mockDelay: <T>(value: T) => Promise.resolve(value),
}))

import { AUTH_METHODS_TIMEOUT_MS, authService, normalizeAuthMethods } from './auth.service'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('authentication capability probe', () => {
  it('normalizes a missing optional OIDC object without crashing the login page', () => {
    expect(normalizeAuthMethods({ passwordEnabled: true })).toEqual({
      passwordEnabled: true,
      oidc: {
        enabled: false,
        label: '企业单点登录',
        startUrl: '/admin/auth/oidc/start',
      },
    })
    expect(() => normalizeAuthMethods({ oidc: {} })).toThrow('passwordEnabled')
  })

  it('aborts a backend that accepts the request but never responds', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) => (
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'))
        })
      })
    )))

    const pending = authService.getMethods()
    const rejection = expect(pending).rejects.toMatchObject({ name: 'TimeoutError' })
    await vi.advanceTimersByTimeAsync(AUTH_METHODS_TIMEOUT_MS)

    await rejection
  })
})
