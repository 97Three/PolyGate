import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: false,
  mockDelay: <T>(value: T) => Promise.resolve(value),
  nextMockId: (prefix: string) => `${prefix}_mock`,
}))

import { usersService, type RevealedApiKey } from './users.service'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('API key service contract', () => {
  it('uses the backend self-service creation and confirmed rotation contracts', async () => {
    const created = {
      id: 'key_created',
      userId: 'usr_self',
      name: 'Claude Code',
      principalType: 'user',
      keyPrefix: 'sk-mp-test',
      keyPreview: 'sk-mp-test...1234',
      key: 'sk-mp-test-secret-1234',
      createdAt: '1',
      lastUsedAt: null,
      expiresAt: '2',
      status: 'active',
    } satisfies RevealedApiKey
    const rotated = {
      ...created,
      id: 'key_rotated',
      key: 'sk-mp-rotated-secret-5678',
      status: 'pending_rotation' as const,
    }
    const confirmed = { ...created, id: rotated.id }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(created))
      .mockResolvedValueOnce(Response.json(rotated))
      .mockResolvedValueOnce(Response.json(confirmed))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(usersService.createApiKey({
      userId: 'usr_self',
      username: 'self-user',
      name: 'Claude Code',
      principalType: 'user',
      group: 'dev',
    })).resolves.toEqual(created)
    await expect(usersService.rotateApiKey('key/created')).resolves.toEqual(rotated)
    await expect(usersService.confirmApiKeyRotation('key/created', 'key/rotated')).resolves.toEqual(confirmed)
    await expect(usersService.cancelApiKeyRotation('key/created', 'key/rotated')).resolves.toBeUndefined()

    const [createPath, createOptions] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(createPath).toBe('/admin/api-keys')
    expect(createOptions.method).toBe('POST')
    expect(JSON.parse(String(createOptions.body))).toEqual({
      userId: 'usr_self',
      username: 'self-user',
      name: 'Claude Code',
      principalType: 'user',
      group: 'dev',
    })

    const [rotatePath, rotateOptions] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(rotatePath).toBe('/admin/api-keys/key%2Fcreated/rotate')
    expect(rotateOptions.method).toBe('POST')
    expect(rotateOptions.body).toBeUndefined()
    expect(new Headers(rotateOptions.headers).get('X-PolyGate-CSRF')).toBe('1')

    const [confirmPath, confirmOptions] = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(confirmPath).toBe('/admin/api-keys/key%2Fcreated/rotate/key%2Frotated')
    expect(confirmOptions.method).toBe('POST')
    expect(new Headers(confirmOptions.headers).get('X-PolyGate-CSRF')).toBe('1')

    const [cancelPath, cancelOptions] = fetchMock.mock.calls[3] as [string, RequestInit]
    expect(cancelPath).toBe('/admin/api-keys/key%2Fcreated/rotate/key%2Frotated')
    expect(cancelOptions.method).toBe('DELETE')
  })
})
