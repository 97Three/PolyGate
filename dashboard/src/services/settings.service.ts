import type { AuditEventsResponse, BackupExport, ConfigReloadResult, SmartRouterStatus, SystemSettings } from '@/types'
import { api } from '@/lib/api-client'
import { isMockMode, mockDelay } from '@/lib/mock-mode'
import { mockProviders, mockSettings } from '@/mock'

let mockSettingsStore = mockSettings
let mockRetentionPreview: RetentionResult | null = null

export interface RetentionPolicy {
  requestDetailDays: number
  userUsageDays: number
  auditDays: number
  legalHold: boolean
  contentPersistence: boolean
}

export interface RetentionCounts {
  requestDetailsRedacted: number
  providerAttemptsRedacted: number
  routingDecisionsDeleted: number
  userUsageRowsDeidentified: number
  auditEventsDeleted: number
}

export interface RetentionResult {
  dryRun: boolean
  applied: boolean
  skippedReason: string | null
  evaluatedAtMs: number
  requestDetailCutoffMs: number
  userUsageCutoffMs: number
  auditCutoffMs: number
  policy: RetentionPolicy
  counts: RetentionCounts
  immutableBudgetEventsRetained: boolean
  previewToken: string | null
  previewExpiresAtMs: number | null
}

export interface RetentionRunInput {
  dryRun: boolean
  previewToken?: string
}

export const settingsService = {
  getSettings: (): Promise<SystemSettings> =>
    isMockMode ? mockDelay(mockSettingsStore) : api.get('/admin/settings'),

  getRouterStatus: (): Promise<SmartRouterStatus> => {
    if (!isMockMode) return api.get('/admin/router/status')
    return mockDelay({
      ...mockSettingsStore.smartRouting,
      groups: [{
        id: 'general',
        aliases: ['polygate-auto'],
        defaultProfile: mockSettingsStore.smartRouting.defaultProfile,
        candidateCount: mockSettingsStore.smartRouting.candidateCount,
      }],
      decisionsTotal: 42,
      activeDecisionsTotal: 0,
      shadowDecisionsTotal: 42,
      staticDecisionsTotal: 0,
      shadowDisagreementsTotal: 7,
      selectedByCandidate: { 'deepseek:deepseek-v4-pro': 42 },
      recommendedByCandidate: {
        'deepseek:deepseek-v4-pro': 35,
        'deepseek:deepseek-v4-flash': 7,
      },
      outcomes: [],
    }, 180)
  },

  updateSettings: (settings: Partial<SystemSettings>): Promise<SystemSettings> => {
    if (!isMockMode) return api.put('/admin/settings', settings)
    mockSettingsStore = { ...mockSettingsStore, ...settings }
    return mockDelay(mockSettingsStore)
  },

  updateDefaultProvider: async (providerId: string): Promise<void> => {
    if (!isMockMode) {
      await api.put('/admin/settings', { gateway: { defaultProvider: providerId } })
      return
    }
    mockSettingsStore = {
      ...mockSettingsStore,
      gateway: {
        ...mockSettingsStore.gateway,
        defaultProvider: providerId,
      },
    }
    await mockDelay(undefined)
  },

  updateProviderOrder: async (providerOrder: string[]): Promise<void> => {
    if (!isMockMode) {
      await api.put('/admin/settings', { gateway: { providerOrder } })
      return
    }
    mockSettingsStore = {
      ...mockSettingsStore,
      gateway: {
        ...mockSettingsStore.gateway,
        providerOrder,
      },
    }
    await mockDelay(undefined)
  },

  testProviderConnection: (providerId: string): Promise<{ success: boolean; message: string; testedAt?: string; testedCredentialId?: string | null; models?: string[]; modelCount?: number }> => {
    if (!isMockMode) return api.post('/admin/settings/test-provider', { providerId })
    const provider = mockProviders.find((item) => item.id === providerId)
    if (!provider) return mockDelay({ success: false, message: 'provider not found' }, 220)
    const success = provider.status === 'active' && (provider.hasApiKey || !provider.apiKeyRequired)
    const models = success ? provider.models : []
    return mockDelay({
      success,
      message: success ? 'mock connection ok' : 'mock missing API key or provider inactive',
      testedCredentialId: provider.activeCredentialId ?? null,
      models,
      modelCount: models.length,
      testedAt: new Date().toISOString(),
    }, 220)
  },

  reloadConfig: (): Promise<ConfigReloadResult> => {
    if (!isMockMode) return api.post('/admin/settings/reload-config')
    return mockDelay({
      ok: true,
      settings: mockSettingsStore,
      providerCount: mockSettingsStore.gateway.providerOrder.length,
      defaultProvider: mockSettingsStore.gateway.defaultProvider,
      providerOrder: mockSettingsStore.gateway.providerOrder,
      issues: mockSettingsStore.setup?.issues ?? [],
      reloadScope: {
        applied: ['providers', 'provider credentials', 'base urls', 'model lists', 'aliases', 'legacy client auth token'],
        requiresRestart: ['bind address', 'request body limit', 'concurrency layer', 'HTTP client timeouts', 'trusted proxies', 'admin bootstrap account'],
      },
    }, 220)
  },

  getAuditEvents: (): Promise<AuditEventsResponse> => {
    if (!isMockMode) return api.get('/admin/audit')
    return mockDelay({
      total: 3,
      events: [
        {
          id: 'act_mock_login',
          timestamp: Date.now().toString(),
          type: 'config_change',
          actor: 'admin',
          target: 'user:admin',
          message: '管理员 admin 登录控制台',
          severity: 'info',
        },
        {
          id: 'act_mock_provider',
          timestamp: (Date.now() - 3600000).toString(),
          type: 'config_change',
          actor: 'admin',
          target: 'provider:mimo',
          message: '测试供应商 mimo: connected',
          severity: 'info',
        },
        {
          id: 'act_mock_key',
          timestamp: (Date.now() - 7200000).toString(),
          type: 'config_change',
          actor: 'alice',
          target: 'api_key:key_mock',
          message: '更新 API Key alice-dev (active)',
          severity: 'warning',
        },
      ],
    }, 180)
  },

  exportBackup: (): Promise<BackupExport> => {
    if (!isMockMode) return api.post('/admin/backup')
    return mockDelay({
      schemaVersion: 1,
      service: 'model-port',
      generatedAt: Date.now().toString(),
      containsSecrets: false,
      containsPersonalData: true,
      settings: mockSettingsStore,
      users: [],
      control: {
        apiKeys: [],
        quotas: [],
        usage: [],
        routeConfig: mockSettingsStore.gateway,
        activities: [],
        providerTests: [],
      },
    }, 180)
  },

  runRetention: ({ dryRun, previewToken }: RetentionRunInput): Promise<RetentionResult> => {
    if (!isMockMode) return api.post('/admin/retention/run', { dryRun, ...(previewToken ? { previewToken } : {}) })
    const dayMs = 24 * 60 * 60 * 1_000
    if (!dryRun) {
      const preview = mockRetentionPreview
      mockRetentionPreview = null
      if (!previewToken || !preview?.previewToken || preview.previewToken !== previewToken || !preview.previewExpiresAtMs || preview.previewExpiresAtMs <= Date.now()) {
        return Promise.reject(new Error('执行令牌缺失、过期或已使用，请重新预览'))
      }
      return mockDelay({
        ...preview,
        dryRun: false,
        applied: true,
        counts: {
          requestDetailsRedacted: 10,
          providerAttemptsRedacted: 15,
          routingDecisionsDeleted: 8,
          userUsageRowsDeidentified: 3,
          auditEventsDeleted: 1,
        },
        previewToken: null,
        previewExpiresAtMs: null,
      }, 220)
    }
    const evaluatedAtMs = Date.now()
    const result: RetentionResult = {
      dryRun: true,
      applied: false,
      skippedReason: null,
      evaluatedAtMs,
      requestDetailCutoffMs: evaluatedAtMs - 30 * dayMs,
      userUsageCutoffMs: evaluatedAtMs - 90 * dayMs,
      auditCutoffMs: evaluatedAtMs - 395 * dayMs,
      policy: {
        requestDetailDays: 30,
        userUsageDays: 90,
        auditDays: 395,
        legalHold: false,
        contentPersistence: false,
      },
      counts: {
        requestDetailsRedacted: 12,
        providerAttemptsRedacted: 18,
        routingDecisionsDeleted: 9,
        userUsageRowsDeidentified: 4,
        auditEventsDeleted: 2,
      },
      immutableBudgetEventsRetained: true,
      previewToken: `retention-preview-${evaluatedAtMs}`,
      previewExpiresAtMs: evaluatedAtMs + 5 * 60 * 1_000,
    }
    mockRetentionPreview = result
    return mockDelay(result, 220)
  },
}
