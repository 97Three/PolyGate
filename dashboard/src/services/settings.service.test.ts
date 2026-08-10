import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: false,
  mockDelay: <T>(value: T) => Promise.resolve(value),
}))

import { settingsService, type RetentionResult } from './settings.service'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('settings service retention contract', () => {
  it('uses the administrator preview/apply endpoint and CSRF contract', async () => {
    const result = {
      dryRun: true,
      applied: false,
      skippedReason: null,
      evaluatedAtMs: 1,
      requestDetailCutoffMs: 2,
      userUsageCutoffMs: 3,
      auditCutoffMs: 4,
      policy: {
        requestDetailDays: 30,
        userUsageDays: 90,
        auditDays: 395,
        legalHold: false,
        contentPersistence: false,
      },
      counts: {
        requestDetailsRedacted: 1,
        providerAttemptsRedacted: 2,
        routingDecisionsDeleted: 3,
        userUsageRowsDeidentified: 4,
        auditEventsDeleted: 5,
      },
      immutableBudgetEventsRetained: true,
      previewToken: 'preview-token',
      previewExpiresAtMs: 300_001,
    } satisfies RetentionResult
    const fetchMock = vi.fn().mockResolvedValue(Response.json(result))
    vi.stubGlobal('fetch', fetchMock)

    await expect(settingsService.runRetention({ dryRun: true })).resolves.toEqual(result)

    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/admin/retention/run')
    expect(options.method).toBe('POST')
    expect(JSON.parse(String(options.body))).toEqual({ dryRun: true })
    expect(new Headers(options.headers).get('X-PolyGate-CSRF')).toBe('1')
  })

  it('binds apply to the server-issued preview token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ dryRun: false, applied: true }))
    vi.stubGlobal('fetch', fetchMock)

    await settingsService.runRetention({ dryRun: false, previewToken: 'preview-token' })

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(options.body))).toEqual({ dryRun: false, previewToken: 'preview-token' })
  })
})
