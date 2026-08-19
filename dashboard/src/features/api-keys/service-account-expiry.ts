const MAX_SERVICE_ACCOUNT_LIFETIME_MS = 90 * 24 * 60 * 60 * 1000

export function serviceAccountExpiryError(value: string, now = Date.now()): string | null {
  if (!value) return '服务账号必须设置过期时间。'
  const expiresAt = new Date(value).getTime()
  if (!Number.isFinite(expiresAt)) return '请输入有效的过期时间。'
  if (expiresAt <= now) return '过期时间必须晚于当前时间。'
  if (expiresAt > now + MAX_SERVICE_ACCOUNT_LIFETIME_MS) {
    return '服务账号有效期不能超过 90 天。'
  }
  return null
}
