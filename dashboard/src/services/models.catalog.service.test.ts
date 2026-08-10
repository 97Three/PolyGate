import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: false,
  mockDelay: <T>(value: T) => Promise.resolve(value),
}))

import { modelsService } from './models.service'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('effective model catalog contract', () => {
  it('binds Provider and alias reads to the selected owned API key', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json([]))
    vi.stubGlobal('fetch', fetchMock)

    await modelsService.getProviders('key/a b')
    await modelsService.getAliases('key/a b')

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/admin/providers?apiKeyId=key%2Fa%20b')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/admin/aliases?apiKeyId=key%2Fa%20b')
  })
})
