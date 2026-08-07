import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { settingsService } from '@/services/settings.service'
import { queryKeys } from './use-dashboard'
import type { SystemSettings } from '@/types'
import type { RetentionRunInput } from '@/services/settings.service'

export function useSettings(enabled = true) {
  return useQuery({
    queryKey: queryKeys.settings,
    queryFn: () => settingsService.getSettings(),
    enabled,
  })
}

export function useRouterStatus() {
  return useQuery({
    queryKey: queryKeys.routerStatus,
    queryFn: () => settingsService.getRouterStatus(),
    refetchInterval: 30_000,
  })
}

export function useUpdateSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (settings: Partial<SystemSettings>) => settingsService.updateSettings(settings),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.settings }),
  })
}

export function useTestProviderConnection() {
  return useMutation({
    mutationFn: (providerId: string) => settingsService.testProviderConnection(providerId),
  })
}

export function useReloadConfig() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => settingsService.reloadConfig(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.settings })
      qc.invalidateQueries({ queryKey: queryKeys.routerStatus })
      qc.invalidateQueries({ queryKey: queryKeys.providers })
      qc.invalidateQueries({ queryKey: queryKeys.aliases })
      qc.invalidateQueries({ queryKey: queryKeys.dashboard })
      qc.invalidateQueries({ queryKey: ['audit-events'] })
    },
  })
}

export function useAuditEvents() {
  return useQuery({
    queryKey: ['audit-events'] as const,
    queryFn: () => settingsService.getAuditEvents(),
  })
}

export function useExportBackup() {
  return useMutation({
    mutationFn: () => settingsService.exportBackup(),
  })
}

export function useRunRetention() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: RetentionRunInput) => settingsService.runRetention(input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['audit-events'] }),
  })
}
