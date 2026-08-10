export type HybridMode = 'local_strict' | 'local_first' | 'balanced' | 'cloud_first'
export type DataClassification = 'unknown' | 'sensitive' | 'internal' | 'public'

export interface ProjectPolicy {
  organizationId: string
  projectId: string
  environmentId: string
  maximumMode: HybridMode
  defaultClassification: DataClassification
  allowedProviders: string[]
  allowedModels: string[]
  allowedRegions: string[]
  allowedApiVersions: string[]
  cloudEnabled: boolean
  updatedBy: string
  updatedAtMs: number
}

export interface ChangeApproval {
  actorId: string
  actorName: string
  approvedAtMs: number
}

export interface GovernanceChangeRequest {
  id: string
  action: string
  target: string
  payload: unknown
  payloadSha256: string
  reason: string
  risk: 'high'
  status: 'pending_second_approval' | 'approved' | 'applied'
  requestedBy: string
  requestedByName: string
  approvals: ChangeApproval[]
  createdAtMs: number
  updatedAtMs: number
  appliedAtMs: number | null
}

export interface LocalSchedulerSnapshot {
  running: number
  interactiveQueued: number
  batchQueued: number
  usersQueued: number
  estimatedServiceMs: number
  oldestInteractiveWaitMs: number
  oldestBatchWaitMs: number
  limits: {
    executingPerUser: number
    queuedPerUser: number
    globalInteractiveQueue: number
    globalBatchQueue: number
    overflowAfterSeconds: number
    strictWaitSeconds: number
  }
}

export interface GovernanceOverview {
  view: 'administrator-control-plane'
  ready: boolean
  dualApprovalRequired: boolean
  projectPolicies: ProjectPolicy[]
  changeRequests: GovernanceChangeRequest[]
  scheduler: LocalSchedulerSnapshot
  highRiskActions: string[]
}

export interface GovernanceChangeInput {
  action: string
  target: string
  payload: unknown
  reason: string
}
