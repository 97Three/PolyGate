export type OpsSeverity = 'SEV-1' | 'SEV-2' | 'SEV-3' | 'SEV-4'

export type OpsIncidentStatus =
  | 'open'
  | 'acknowledged'
  | 'mitigating'
  | 'monitoring'
  | 'resolved'
  | 'suppressed'

export interface OpsIncidentSummary {
  id: string
  eventKey: string
  detectorType: string
  severity: OpsSeverity
  status: OpsIncidentStatus
  title: string
  summary: string
  affectedScope: Record<string, unknown>
  recoveryCriteria: string
  firstSeenAtMs: number
  lastSeenAtMs: number
  resolvedAtMs: number | null
  occurrenceCount: number
}

export interface OpsIncidentEvidence {
  id: string
  incidentId: string
  observedAtMs: number
  evidence: Record<string, unknown>
}

export interface OpsIncidentTimelineEntry {
  id: string
  incidentId: string
  eventType: string
  actorId: string
  actorName: string
  message: string
  occurredAtMs: number
}

export interface OpsIncidentDetail extends OpsIncidentSummary {
  evidence: OpsIncidentEvidence[]
  timeline: OpsIncidentTimelineEntry[]
}

export interface OpsIncidentList {
  items: OpsIncidentSummary[]
  total: number
  open: number
  highestOpenSeverity: OpsSeverity | null
  agents: OpsAgentSummary[]
}

export interface OpsAgentSummary {
  instanceId: string
  agentVersion: string
  mode: 'disabled' | 'replay' | 'shadow' | 'read_only'
  ruleSetVersion: string
  observedAtMs: number
  queueDepth: number
  intervalSeconds: number
  online: boolean
  analysisEnabled: boolean
  selectedModel: string | null
  modelStatus: 'disabled' | 'configured' | 'missing_credential' | 'error'
  modelLastSuccessAtMs: number | null
}

export interface OpsModelCandidate {
  id: string
  providerId: string
  model: string
  displayName: string
  local: boolean
}

export interface OpsAgentConfiguration {
  enabled: boolean
  analysisEnabled: boolean
  selectedModel: string | null
  preferLocal: boolean
  modelReady: boolean
  selectedModelLocal: boolean
  recommendedModel: string | null
  candidates: OpsModelCandidate[]
}

export interface OpsAgentConfigurationInput {
  enabled: boolean
  analysisEnabled: boolean
  selectedModel: string | null
  preferLocal: boolean
}

export interface OpsIncidentStatusInput {
  status: Exclude<OpsIncidentStatus, 'open' | 'resolved'>
  reason: string
}

export interface OpsIncidentFeedbackInput {
  outcome: 'true_positive' | 'false_positive' | 'needs_review'
  rootCauseCorrect?: boolean
  recommendationAdopted?: boolean
  note?: string
}
