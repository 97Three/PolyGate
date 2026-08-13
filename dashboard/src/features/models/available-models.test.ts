import { describe, expect, it } from 'vitest'

import type { ModelAlias, Provider } from '@/types'
import { availableModelOptions, preferredAvailableModel } from './available-models'

function provider(overrides: Partial<Provider> = {}): Provider {
  return {
    id: 'local',
    displayName: 'Local',
    protocol: 'openai-compat',
    baseUrl: 'http://127.0.0.1:8000/v1',
    apiKeyEnv: null,
    apiKeyRequired: false,
    defaultModel: 'coder',
    models: ['coder', 'disabled-model'],
    modelPrefixes: [],
    passthroughUnknownModels: false,
    maxTokensField: 'max_tokens',
    deduplicateStreamText: false,
    bufferStreamText: false,
    status: 'active',
    hasApiKey: true,
    modelInventory: [
      { model: 'coder', status: 'active' },
      { model: 'disabled-model', status: 'disabled' },
    ],
    ...overrides,
  }
}

const alias: ModelAlias = {
  alias: 'code-private',
  target: 'local:coder',
  resolvedProvider: 'local',
  resolvedModel: 'coder',
}

describe('available model catalog', () => {
  it('only includes routable, credential-resolved and enabled models', () => {
    const options = availableModelOptions([
      provider(),
      provider({ id: 'missing-secret', apiKeyRequired: true, hasApiKey: false }),
      provider({ id: 'disabled', status: 'disabled' }),
    ], [alias])

    expect(options.map((option) => option.id)).toEqual(['code-private', 'local:coder'])
  })

  it('does not reinterpret API-key policy in the browser', () => {
    const options = availableModelOptions([provider()], [alias])
    expect(options.map((option) => option.id)).toEqual(['code-private', 'local:coder'])
  })

  it('prefers a stable logical alias over a provider-specific route', () => {
    const options = availableModelOptions([provider()], [alias])
    expect(preferredAvailableModel(options, 'local')).toBe('code-private')
  })
})
