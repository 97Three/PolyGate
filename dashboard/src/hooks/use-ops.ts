import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { opsService } from '@/services/ops.service'
import type { OpsAgentConfigurationInput, OpsIncidentFeedbackInput, OpsIncidentStatusInput } from '@/types'

const incidentsKey = ['ops-incidents'] as const
const configurationKey = ['ops-agent-configuration'] as const

export function useRunOpsCheck() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => opsService.runCheck(),
    onSuccess: () => { void client.invalidateQueries({ queryKey: incidentsKey }) },
  })
}

export function useOpsAgentConfiguration() {
  return useQuery({
    queryKey: configurationKey,
    queryFn: () => opsService.getConfiguration(),
    staleTime: 5_000,
  })
}

export function useUpdateOpsAgentConfiguration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: OpsAgentConfigurationInput) => opsService.updateConfiguration(input),
    onSuccess: (configuration) => {
      client.setQueryData(configurationKey, configuration)
      void client.invalidateQueries({ queryKey: incidentsKey })
    },
  })
}

export function useOpsIncidents() {
  return useQuery({
    queryKey: incidentsKey,
    queryFn: () => opsService.listIncidents(),
    refetchInterval: 15_000,
    staleTime: 5_000,
  })
}

export function useOpsIncident(id: string | null) {
  return useQuery({
    queryKey: [...incidentsKey, id],
    queryFn: () => opsService.getIncident(id as string),
    enabled: Boolean(id),
    staleTime: 0,
  })
}

export function useUpdateOpsIncidentStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: OpsIncidentStatusInput }) => (
      opsService.updateStatus(id, input)
    ),
    onSuccess: (_, variables) => {
      void client.invalidateQueries({ queryKey: incidentsKey })
      void client.invalidateQueries({ queryKey: [...incidentsKey, variables.id] })
    },
  })
}

export function useRecordOpsIncidentFeedback() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: OpsIncidentFeedbackInput }) => (
      opsService.recordFeedback(id, input)
    ),
    onSuccess: (_, variables) => {
      void client.invalidateQueries({ queryKey: [...incidentsKey, variables.id] })
    },
  })
}
