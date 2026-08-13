import type { ModelAlias, Provider } from '@/types'

export interface AvailableModelOption {
  id: string
  displayName: string
  providerId: string
  resolvedModel: string
  kind: 'alias' | 'route'
}

function providerModels(provider: Provider): string[] {
  const disabled = new Set(
    (provider.modelInventory ?? [])
      .filter((item) => item.status === 'disabled')
      .map((item) => item.model),
  )
  return provider.models.filter((model) => !disabled.has(model))
}

function aliasResolution(alias: ModelAlias): { providerId: string; model: string } | null {
  if (alias.resolvedProvider && alias.resolvedModel) {
    return { providerId: alias.resolvedProvider, model: alias.resolvedModel }
  }
  const separator = alias.target.indexOf(':')
  if (separator <= 0 || separator === alias.target.length - 1) return null
  return {
    providerId: alias.target.slice(0, separator),
    model: alias.target.slice(separator + 1),
  }
}

/**
 * Builds display options from the server-effective catalog. Authorization is
 * intentionally not reimplemented in the browser: non-admin Provider and
 * alias endpoints already apply the owned-key and team policy engine.
 */
export function availableModelOptions(
  providers: readonly Provider[],
  aliases: readonly ModelAlias[],
): AvailableModelOption[] {
  const providerMap = new Map(
    providers
      .filter((provider) => (
        provider.status === 'active'
        && (
          provider.hasApiKey
          || !provider.apiKeyRequired
          || Boolean(provider.credentials?.some((credential) => credential.status === 'active' && credential.hasApiKey))
        )
      ))
      .map((provider) => [provider.id, provider]),
  )
  const routeOptions: AvailableModelOption[] = []
  const availableRoutes = new Set<string>()

  for (const provider of providerMap.values()) {
    for (const model of providerModels(provider)) {
      const id = `${provider.id}:${model}`
      availableRoutes.add(id)
      routeOptions.push({
        id,
        displayName: `${provider.displayName} · ${model}`,
        providerId: provider.id,
        resolvedModel: model,
        kind: 'route',
      })
    }
  }

  const aliasOptions = aliases.flatMap((alias): AvailableModelOption[] => {
    const resolved = aliasResolution(alias)
    if (!resolved || !availableRoutes.has(`${resolved.providerId}:${resolved.model}`)) return []
    return [{
      id: alias.alias,
      displayName: `${alias.alias} → ${resolved.providerId}:${resolved.model}`,
      providerId: resolved.providerId,
      resolvedModel: resolved.model,
      kind: 'alias',
    }]
  })

  return [...aliasOptions, ...routeOptions]
}

export function preferredAvailableModel(
  options: readonly AvailableModelOption[],
  defaultProvider?: string,
): string {
  const logicalModel = options.find((option) => option.kind === 'alias' && (
    option.id === 'polygate-auto' || option.id.startsWith('code-')
  )) ?? options.find((option) => option.kind === 'alias')
  if (logicalModel) return logicalModel.id

  return options.find((option) => option.providerId === defaultProvider)?.id
    ?? options[0]?.id
    ?? ''
}
