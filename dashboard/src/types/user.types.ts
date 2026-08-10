export type UserRole = 'admin' | 'user' | 'viewer'

export interface User {
  id: string
  username: string
  email: string
  role: UserRole
  status: 'active' | 'disabled' | 'suspended'
  createdAt: string
  lastLoginAt: string | null
  apiKeyCount: number
  requestCount24h: number
}

export interface CreateUserInput {
  username: string
  email: string
  password: string
  role: UserRole
  status: 'active' | 'disabled' | 'suspended'
}

export interface UpdateUserInput {
  email?: string
  password?: string
  role?: UserRole
  status?: User['status']
}

export interface ApiKey {
  id: string
  userId: string
  username?: string
  name: string
  principalType?: 'user' | 'service_account' | 'legacy'
  purpose?: string | null
  keyPrefix: string
  keyPreview?: string
  key?: string
  group?: string | null
  teamId?: string | null
  teamName?: string | null
  allowedModels?: string[]
  allowedProviders?: string[]
  createdAt: string
  lastUsedAt: string | null
  expiresAt: string | null
  status: 'active' | 'revoked'
  requestsToday?: number
  tokensToday?: number
  ipRestricted?: boolean
  allowedIps?: string[]
  spendLimitUsd?: number
  rateLimited?: boolean
  fiveHourLimitUsd?: number
  dailyLimitUsd?: number
  weeklyLimitUsd?: number
  monthlyLimitUsd?: number
  supersededByKeyId?: string | null
}

export interface Team {
  id: string
  name: string
  slug: string
  description?: string | null
  status: 'active' | 'archived' | 'disabled'
  dailyLimitUsd: number
  monthlyLimitUsd: number
  dailySpendUsd: number
  monthlySpendUsd: number
  allowedModels: string[]
  allowedProviders: string[]
  activeApiKeys: number
  requestsToday: number
  createdAt: string
  updatedAt: string
}

export interface UpsertTeamInput {
  id?: string
  name: string
  slug?: string
  description?: string
  status?: Team['status']
  dailyLimitUsd?: number
  monthlyLimitUsd?: number
  allowedModels?: string[]
  allowedProviders?: string[]
}
