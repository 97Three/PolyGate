import { api } from '@/lib/api-client'
import { isMockMode, mockDelay } from '@/lib/mock-mode'
import type {
  GovernanceChangeInput,
  GovernanceChangeRequest,
  GovernanceOverview,
} from '@/types'

const emptyOverview: GovernanceOverview = {
  view: 'administrator-control-plane',
  ready: true,
  dualApprovalRequired: false,
  projectPolicies: [],
  changeRequests: [],
  scheduler: {
    running: 0,
    interactiveQueued: 0,
    batchQueued: 0,
    usersQueued: 0,
    estimatedServiceMs: 5000,
    oldestInteractiveWaitMs: 0,
    oldestBatchWaitMs: 0,
    limits: {
      executingPerUser: 1,
      queuedPerUser: 2,
      globalInteractiveQueue: 16,
      globalBatchQueue: 16,
      overflowAfterSeconds: 5,
      strictWaitSeconds: 60,
    },
  },
  highRiskActions: [],
}

export const governanceService = {
  getOverview: (): Promise<GovernanceOverview> => (
    isMockMode ? mockDelay(emptyOverview) : api.get('/admin/governance')
  ),

  createChange: (input: GovernanceChangeInput): Promise<GovernanceChangeRequest> => (
    isMockMode
      ? mockDelay({
        id: `chg_mock_${Date.now()}`,
        ...input,
        payloadSha256: 'mock',
        risk: 'high',
        status: 'pending_second_approval',
        requestedBy: 'mock-admin',
        requestedByName: 'mock-admin',
        approvals: [],
        createdAtMs: Date.now(),
        updatedAtMs: Date.now(),
        appliedAtMs: null,
      })
      : api.post('/admin/governance/change-requests', input)
  ),

  approveChange: (id: string): Promise<GovernanceChangeRequest> => (
    api.post(`/admin/governance/change-requests/${encodeURIComponent(id)}/approve`)
  ),

  applyChange: (id: string): Promise<{ ok: true; changeId: string; action: string; result: unknown }> => (
    api.post(`/admin/governance/change-requests/${encodeURIComponent(id)}/apply`)
  ),
}
