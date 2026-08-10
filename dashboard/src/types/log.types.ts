import type { ProviderProtocol } from './model.types'

export type RequestStatus = 'success' | 'error' | 'timeout'
export type StreamMode = 'stream' | 'non-stream'
export type ToolUseMode = 'requested' | 'not-requested'
export type TrafficClass = 'business' | 'synthetic' | 'diagnostic'
export type ReconciliationStatus = 'billable' | 'partially_billable' | 'actual_unbillable' | 'estimate_only'

export interface ModelPricingRates {
  inputPerMillion: number
  outputPerMillion: number
  cacheWritePerMillion: number
  cacheReadPerMillion: number
}

export interface PricingEvidence {
  provider: string
  model: string
  method: 'provider_reported' | 'exact_rate_card'
  currency: string
  version: string
  effectiveAt: string
  source: 'provider_published' | 'provider_contract' | 'internal_chargeback' | 'legacy_estimate'
  serviceTier: 'standard' | 'batch' | 'flex' | 'priority' | 'custom'
  region?: string | null
  evidence: string
  rates?: ModelPricingRates | null
}

export interface RequestLog {
  id: string
  requestId?: string | null
  attemptId?: string | null
  timestamp: string
  userId: string
  username: string
  apiKeyId?: string | null
  apiKeyName?: string | null
  apiKeyGroup?: string | null
  teamId?: string | null
  teamName?: string | null
  model: string
  resolvedModel: string
  provider: string
  protocol: ProviderProtocol
  clientProtocol?: 'anthropic-messages' | 'openai-chat-completions'
  toolUseRequested?: boolean
  trafficClass?: TrafficClass
  toolOutcome?: 'not_requested' | 'continuation_tool_called' | 'tool_called' | 'final_answer' | 'answered_without_tool' | 'completed_unobserved' | 'client_cancelled' | 'timeout' | 'protocol_error' | 'upstream_or_delivery_error'
  stream: StreamMode
  status: RequestStatus
  statusCode: number
  terminalReason?: string
  inputTokens: number
  outputTokens: number
  cacheWriteTokens?: number
  cacheReadTokens?: number
  billedInputTokens?: number
  totalTokens?: number
  cacheHitRate?: number
  costEstimate?: number
  actualCost?: number | null
  billableCost?: number | null
  reconciliationStatus?: ReconciliationStatus
  pricingEvidence?: PricingEvidence | null
  modelPricing?: ModelPricingRates
  costBreakdown?: {
    inputCost: number
    outputCost: number
    cacheWriteCost: number
    cacheReadCost: number
    totalCost: number
  }
  latencyMs: number
  firstByteLatencyMs?: number
  retryCount?: number
  clientIp?: string | null
  requestPath?: string
  billingMode?: string
  usageEstimated?: boolean
  errorMessage: string | null
}

export interface LogFilters {
  userId?: string
  apiKeyId?: string
  model?: string
  provider?: string
  group?: string
  username?: string
  status?: RequestStatus
  stream?: StreamMode
  toolUse?: ToolUseMode
  trafficClass?: TrafficClass
  dateFrom?: string
  dateTo?: string
  search?: string
}

export interface LogSummary {
  totalRequests: number
  successRequests: number
  toolUseRequests?: number
  toolUseSuccessRequests?: number
  totalInputTokens: number
  totalOutputTokens: number
  totalCacheWriteTokens: number
  totalCacheReadTokens: number
  totalTokens: number
  totalCostEstimate: number
  totalActualCost: number
  totalBillableCost: number
  billableRequests: number
  estimateOnlyRequests: number
  latencyP95Ms?: number
  latencySampleCount?: number
  firstByteLatencyP95Ms?: number
  firstByteLatencySampleCount?: number
  rpm: number
  tpm: number
}

export interface LatencyStats {
  p50: number
  p90: number
  p95: number
  p99: number
  avg: number
  max: number
  sampleCount?: number
  percentilesEstimated?: boolean
  byModel: Record<string, { p50: number; p95: number; avg: number }>
  byProvider: Record<string, { p50: number; p95: number; avg: number }>
}
