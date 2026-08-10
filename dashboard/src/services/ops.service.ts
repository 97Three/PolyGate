import { api } from '@/lib/api-client'
import { isMockMode, mockDelay } from '@/lib/mock-mode'
import type {
  OpsIncidentDetail,
  OpsIncidentFeedbackInput,
  OpsIncidentList,
  OpsIncidentStatusInput,
  OpsIncidentSummary,
  OpsAgentConfiguration,
  OpsAgentConfigurationInput,
} from '@/types'

const emptyIncidents: OpsIncidentList = {
  items: [],
  total: 0,
  open: 0,
  highestOpenSeverity: null,
  agents: [],
}

let mockConfiguration: OpsAgentConfiguration = {
  enabled: false,
  analysisEnabled: false,
  selectedModel: null,
  preferLocal: true,
  modelReady: false,
  selectedModelLocal: false,
  recommendedModel: null,
  candidates: [],
}

export const opsService = {
  runCheck: (): Promise<{ checked: boolean; enabled?: boolean; activeConditions?: number }> => (
    isMockMode ? mockDelay({ checked: true, enabled: mockConfiguration.enabled, activeConditions: 0 }) : api.post('/admin/ops/run', {})
  ),
  listIncidents: (): Promise<OpsIncidentList> => (
    isMockMode ? mockDelay(emptyIncidents) : api.get('/admin/ops/incidents?limit=200')
  ),

  getConfiguration: (): Promise<OpsAgentConfiguration> => (
    isMockMode ? mockDelay(mockConfiguration) : api.get('/admin/ops/configuration')
  ),

  updateConfiguration: (input: OpsAgentConfigurationInput): Promise<OpsAgentConfiguration> => {
    if (!isMockMode) return api.put('/admin/ops/configuration', input)
    mockConfiguration = {
      ...mockConfiguration,
      ...input,
      modelReady: Boolean(input.selectedModel),
      selectedModelLocal: mockConfiguration.candidates.some(
        (candidate) => candidate.id === input.selectedModel && candidate.local,
      ),
    }
    return mockDelay(mockConfiguration)
  },

  getIncident: (id: string): Promise<OpsIncidentDetail> => (
    api.get(`/admin/ops/incidents/${encodeURIComponent(id)}`)
  ),

  updateStatus: (id: string, input: OpsIncidentStatusInput): Promise<OpsIncidentSummary> => (
    api.post(`/admin/ops/incidents/${encodeURIComponent(id)}/status`, input)
  ),

  recordFeedback: (id: string, input: OpsIncidentFeedbackInput): Promise<{ accepted: true }> => (
    api.post(`/admin/ops/incidents/${encodeURIComponent(id)}/feedback`, input)
  ),
}
