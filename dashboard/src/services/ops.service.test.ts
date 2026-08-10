import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: false,
  mockDelay: <T>(value: T) => Promise.resolve(value),
}))

import { opsService } from './ops.service'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('operations service', () => {
  it('loads and updates the opt-in agent configuration with CSRF protection', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(Response.json({
      enabled: true,
      analysisEnabled: true,
      selectedModel: 'local_vllm:qwen',
      preferLocal: true,
      modelReady: true,
      selectedModelLocal: true,
      recommendedModel: 'local_vllm:qwen',
      candidates: [],
    })))
    vi.stubGlobal('fetch', fetchMock)

    await opsService.getConfiguration()
    await opsService.updateConfiguration({
      enabled: true,
      analysisEnabled: true,
      selectedModel: 'local_vllm:qwen',
      preferLocal: true,
    })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/admin/ops/configuration')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/admin/ops/configuration')
    const update = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(update.method).toBe('PUT')
    expect(new Headers(update.headers).get('X-PolyGate-CSRF')).toBe('1')
  })

  it('uses encoded incident ids and CSRF-protected workflow writes', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ accepted: true })))
    vi.stubGlobal('fetch', fetchMock)

    await opsService.getIncident('opi/one two')
    await opsService.updateStatus('opi/one two', {
      status: 'acknowledged',
      reason: 'operator acknowledged the incident',
    })
    await opsService.recordFeedback('opi/one two', {
      outcome: 'false_positive',
      note: 'maintenance window',
    })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/admin/ops/incidents/opi%2Fone%20two')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/admin/ops/incidents/opi%2Fone%20two/status')
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/admin/ops/incidents/opi%2Fone%20two/feedback')
    for (const [, init] of fetchMock.mock.calls.slice(1) as Array<[string, RequestInit]>) {
      expect(new Headers(init.headers).get('X-PolyGate-CSRF')).toBe('1')
      expect(init.method).toBe('POST')
    }
  })
})
