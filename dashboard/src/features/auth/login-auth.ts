import { ApiError } from '@/lib/api-client'

const OIDC_ERROR_MESSAGES: Record<string, string> = {
  access_denied: '企业单点登录已取消或未获授权，请重试。',
  account_disabled: '当前账户已停用，请联系管理员。',
  account_not_allowed: '当前企业账户无权访问控制台，请联系管理员。',
  invalid_state: '单点登录会话已失效，请重新发起登录。',
  state_mismatch: '单点登录会话已失效，请重新发起登录。',
  oidc_unavailable: '企业单点登录暂不可用，请稍后重试或联系管理员。',
  provider_unavailable: '企业单点登录暂不可用，请稍后重试或联系管理员。',
  account_not_authorized: '当前企业账户无权访问控制台，请联系管理员。',
  invalid_callback: '单点登录回调无效，请重新发起登录。',
  token_exchange_failed: '企业身份验证未能完成，请重试或联系管理员。',
  token_invalid: '企业身份验证无效，请重新发起登录。',
  provider_error: '企业身份提供方返回错误，请重试或联系管理员。',
}

const GENERIC_OIDC_ERROR = '企业单点登录失败，请重试或联系管理员。'

export function safeReturnPath(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || /^\/[\\/]/.test(value) || value.startsWith('/login')) return ''
  return value
}

export function buildOidcStartUrl(startUrl: string, returnTo: string, origin: string): string {
  const normalizedStartUrl = startUrl.trim()
  if (normalizedStartUrl.startsWith('//')) {
    throw new Error('OIDC start URL must be same-origin')
  }

  const absoluteStartUrl = new URL(normalizedStartUrl, origin)
  if (absoluteStartUrl.protocol !== 'http:' && absoluteStartUrl.protocol !== 'https:') {
    throw new Error('Unsupported OIDC start URL protocol')
  }
  if (absoluteStartUrl.origin !== new URL(origin).origin) {
    throw new Error('OIDC start URL must be same-origin')
  }
  if (absoluteStartUrl.pathname !== '/admin/auth/oidc/start') {
    throw new Error('Unexpected OIDC start URL path')
  }

  absoluteStartUrl.searchParams.set('returnTo', returnTo)

  const startUrlIsAbsolute = /^[a-z][a-z\d+.-]*:/i.test(normalizedStartUrl)
  if (startUrlIsAbsolute) return absoluteStartUrl.toString()
  return `${absoluteStartUrl.pathname}${absoluteStartUrl.search}${absoluteStartUrl.hash}`
}

export function oidcErrorMessage(search: string): string {
  const rawCode = new URLSearchParams(search).get('oidc_error')
  if (rawCode === null) return ''
  const code = rawCode.trim().toLowerCase()
  return OIDC_ERROR_MESSAGES[code] || GENERIC_OIDC_ERROR
}

export function withoutOidcError(search: string): string {
  const params = new URLSearchParams(search)
  params.delete('oidc_error')
  const remaining = params.toString()
  return remaining ? `?${remaining}` : ''
}

export function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '用户名或密码错误，请重新输入。'
    if (error.status === 403) return '当前账户已停用或无权访问控制台，请联系管理员。'
    if ([502, 503, 504].includes(error.status)) {
      return '控制面暂不可用；后端、数据库或反向代理可能尚未就绪，请检查 /readyz 后重试。'
    }
    if (error.status >= 500) {
      return `控制面返回内部错误（HTTP ${error.status}），凭证尚未完成校验；请检查后端日志后重试。`
    }
    if (error.status >= 400) return `登录请求被拒绝（HTTP ${error.status}）：${error.message}`
  }

  if (error instanceof TypeError || (error instanceof Error && /fetch|network|load failed/i.test(error.message))) {
    return '无法连接 PolyGate 控制面；请检查网络、后端进程和反向代理配置。'
  }

  return '登录请求未完成，请稍后重试；若持续失败，请检查控制面日志。'
}

export function authCapabilityErrorMessage(error: unknown): string {
  if (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return '认证方式探测超时：控制面在 8 秒内没有响应。你仍可尝试密码登录，或检查后端与反向代理后重试。'
  }
  if (error instanceof ApiError) {
    if ([502, 503, 504].includes(error.status)) {
      return '认证方式探测失败：控制面、数据库或反向代理尚未就绪。'
    }
    if (error.status >= 500) return `认证方式探测失败：控制面返回 HTTP ${error.status}。`
    if (error.status === 401 || error.status === 403) return '认证方式探测被控制面拒绝，请检查公开认证端点配置。'
    return `认证方式探测失败（HTTP ${error.status}）。`
  }
  if (error instanceof TypeError || (error instanceof Error && /fetch|network|load failed/i.test(error.message))) {
    return '认证方式探测失败：浏览器无法连接 PolyGate 控制面。'
  }
  return '认证方式探测失败，当前实例支持的登录方式尚未确认。'
}
