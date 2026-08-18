import { describe, expect, it } from 'vitest'

import {
  authCapabilityErrorMessage,
  buildOidcStartUrl,
  loginErrorMessage,
  oidcErrorMessage,
  safeReturnPath,
  withoutOidcError,
} from './login-auth'
import { ApiError } from '@/lib/api-client'

describe('login auth helpers', () => {
  it('adds a safely encoded return path without discarding existing OIDC query parameters', () => {
    expect(buildOidcStartUrl(
      '/admin/auth/oidc/start?connection=corporate#authorize',
      '/logs?status=error#request',
      'https://polygate.example',
    )).toBe(
      '/admin/auth/oidc/start?connection=corporate&returnTo=%2Flogs%3Fstatus%3Derror%23request#authorize',
    )
  })

  it('replaces an existing returnTo value and preserves a same-origin absolute start URL', () => {
    const result = buildOidcStartUrl(
      'https://polygate.example/admin/auth/oidc/start?returnTo=%2Funtrusted&connection=corporate',
      '/dashboard',
      'https://polygate.example',
    )
    const url = new URL(result)

    expect(url.origin).toBe('https://polygate.example')
    expect(url.searchParams.getAll('returnTo')).toEqual(['/dashboard'])
    expect(url.searchParams.get('connection')).toBe('corporate')
  })

  it('rejects executable start URL protocols', () => {
    expect(() => buildOidcStartUrl(
      'javascript:alert(1)',
      '/dashboard',
      'https://polygate.example',
    )).toThrow('Unsupported OIDC start URL protocol')
  })

  it('rejects cross-origin, protocol-relative, and unexpected start endpoints', () => {
    expect(() => buildOidcStartUrl(
      'https://auth.example/admin/auth/oidc/start',
      '/dashboard',
      'https://polygate.example',
    )).toThrow('OIDC start URL must be same-origin')
    expect(() => buildOidcStartUrl(
      '//polygate.example/admin/auth/oidc/start',
      '/dashboard',
      'https://polygate.example',
    )).toThrow('OIDC start URL must be same-origin')
    expect(() => buildOidcStartUrl(
      '/admin/auth/another-start',
      '/dashboard',
      'https://polygate.example',
    )).toThrow('Unexpected OIDC start URL path')
  })

  it('accepts only internal return paths', () => {
    expect(safeReturnPath('/logs?status=error#request')).toBe('/logs?status=error#request')
    expect(safeReturnPath('//attacker.example/path')).toBe('')
    expect(safeReturnPath('/\\attacker.example/path')).toBe('')
    expect(safeReturnPath('https://attacker.example/path')).toBe('')
    expect(safeReturnPath('/login?next=/logs')).toBe('')
  })

  it('maps known errors to Chinese messages and never reflects an unknown query value', () => {
    expect(oidcErrorMessage('?oidc_error=invalid_state')).toContain('会话已失效')

    const maliciousValue = '<img src=x onerror=alert(1)>'
    const message = oidcErrorMessage(`?oidc_error=${encodeURIComponent(maliciousValue)}`)
    expect(message).toBe('企业单点登录失败，请重试或联系管理员。')
    expect(message).not.toContain(maliciousValue)
    expect(message).not.toContain('<')
  })

  it('removes every oidc_error query value while preserving unrelated parameters', () => {
    expect(withoutOidcError(
      '?view=compact&oidc_error=invalid_state&next=%2Flogs&oidc_error=provider_error',
    )).toBe('?view=compact&next=%2Flogs')
    expect(withoutOidcError('?oidc_error=invalid_state')).toBe('')
  })

  it('distinguishes credentials, authorization, backend readiness, and network failures', () => {
    expect(loginErrorMessage(new ApiError('unauthorized', 401, null))).toContain('用户名或密码错误')
    expect(loginErrorMessage(new ApiError('forbidden', 403, null))).toContain('无权访问')
    expect(loginErrorMessage(new ApiError('unavailable', 503, null))).toContain('/readyz')
    expect(loginErrorMessage(new ApiError('crashed', 500, null))).toContain('HTTP 500')
    expect(loginErrorMessage(new TypeError('Failed to fetch'))).toContain('无法连接')
  })

  it('reports capability probe failures separately from credential failures', () => {
    expect(authCapabilityErrorMessage(new ApiError('unavailable', 503, null))).toContain('尚未就绪')
    expect(authCapabilityErrorMessage(new ApiError('forbidden', 403, null))).toContain('公开认证端点')
    expect(authCapabilityErrorMessage(new TypeError('Failed to fetch'))).toContain('无法连接')
    expect(authCapabilityErrorMessage(new Error('unknown'))).toContain('尚未确认')
    const timeout = new Error('timed out')
    timeout.name = 'TimeoutError'
    expect(authCapabilityErrorMessage(timeout)).toContain('8 秒')
  })
})
