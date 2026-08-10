import { describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/mock-mode', () => ({
  isMockMode: true,
  mockDelay: <T>(value: T) => Promise.resolve(value),
}))

import { modelsService } from './models.service'

describe('mock Provider connection evidence', () => {
  it('invalidates a successful probe when connection-affecting state changes', async () => {
    const provider = await modelsService.createProvider({
      id: 'evidence-test',
      displayName: 'Evidence Test',
      protocol: 'openai-compat',
      baseUrl: 'https://example.invalid/v1',
      apiKeyRequired: false,
      defaultModel: 'model-a',
      models: ['model-a', 'model-b'],
    })

    await modelsService.discoverProviderModels(provider.id)
    expect((await modelsService.getProvider(provider.id)).lastTest?.success).toBe(true)

    await modelsService.updateProvider(provider.id, { displayName: 'Changed endpoint evidence' })
    expect((await modelsService.getProvider(provider.id)).lastTest).toBeNull()

    await modelsService.discoverProviderModels(provider.id)
    await modelsService.setProviderDisabled(provider.id, true)
    expect((await modelsService.getProvider(provider.id)).lastTest).toBeNull()

    await modelsService.discoverProviderModels(provider.id)
    await modelsService.updateDefaultModel(provider.id, 'model-b')
    expect((await modelsService.getProvider(provider.id)).lastTest).toBeNull()

    await modelsService.discoverProviderModels(provider.id)
    await modelsService.createProviderCredential(provider.id, {
      name: 'alternate',
      apiKeyEnv: 'EVIDENCE_TEST_KEY',
    })
    expect((await modelsService.getProvider(provider.id)).lastTest).toBeNull()
  })
})
