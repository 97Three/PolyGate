import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores'
import { authService, type AuthMethods } from '@/services/auth.service'
import {
  authCapabilityErrorMessage,
  buildOidcStartUrl,
  loginErrorMessage,
  oidcErrorMessage,
  safeReturnPath,
  withoutOidcError,
} from '@/features/auth/login-auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import {
  Eye,
  EyeOff,
  Fingerprint,
  KeyRound,
  Loader2,
  AlertTriangle,
  RefreshCw,
  ShieldCheck,
  UserRound,
} from 'lucide-react'

function GateMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M6 20.5V10.2a6 6 0 0 1 12 0v10.3" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M10 20.5v-7.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" opacity="0.45" />
      <path d="M14 20.5v-7.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" opacity="0.45" />
      <path d="M4 20.5h16" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  )
}

function readSessionValue(key: string): string {
  try {
    return window.sessionStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState(() => window.localStorage.getItem('polygate_last_username') || '')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [oidcError, setOidcError] = useState(() => oidcErrorMessage(location.search))
  const [authMethods, setAuthMethods] = useState<AuthMethods | null>(null)
  const [authMethodsLoading, setAuthMethodsLoading] = useState(true)
  const [authMethodsError, setAuthMethodsError] = useState('')
  const [authMethodsAttempt, setAuthMethodsAttempt] = useState(0)
  const [sessionNotice] = useState(() => readSessionValue('polygate_auth_notice'))
  const [storedReturnTo] = useState(() => readSessionValue('polygate_return_to'))
  const login = useAuthStore((s) => s.login)
  const from = (location.state as { from?: { pathname?: string; search?: string; hash?: string } } | null)?.from
  const locationReturnTo = from?.pathname ? `${from.pathname}${from.search || ''}${from.hash || ''}` : ''
  const returnTo = safeReturnPath(locationReturnTo) || safeReturnPath(storedReturnTo) || '/dashboard'
  const passwordEnabled = authMethods?.passwordEnabled === true
  const offerPasswordRecovery = Boolean(authMethodsError)
  const showPasswordLogin = passwordEnabled || offerPasswordRecovery
  const oidcEnabled = authMethods?.oidc.enabled === true && !!authMethods.oidc.startUrl.trim()

  useEffect(() => {
    try {
      window.sessionStorage.removeItem('polygate_auth_notice')
      window.sessionStorage.removeItem('polygate_return_to')
    } catch {
      // Session storage can be unavailable in hardened browser contexts.
    }
  }, [])

  useEffect(() => {
    let active = true
    authService.getMethods()
      .then((methods) => {
        if (!active) return
        setAuthMethods(methods)
        setAuthMethodsLoading(false)
      })
      .catch((probeError: unknown) => {
        if (!active) return
        setAuthMethods(null)
        setAuthMethodsError(authCapabilityErrorMessage(probeError))
        setAuthMethodsLoading(false)
      })
    return () => {
      active = false
    }
  }, [authMethodsAttempt])

  useEffect(() => {
    const message = oidcErrorMessage(location.search)
    if (!message) return

    navigate({
      pathname: location.pathname,
      search: withoutOidcError(location.search),
      hash: location.hash,
    }, { replace: true, state: location.state })
  }, [location.hash, location.pathname, location.search, location.state, navigate])

  const handleOidcLogin = () => {
    if (!oidcEnabled || !authMethods) return
    try {
      window.location.assign(buildOidcStartUrl(authMethods.oidc.startUrl, returnTo, window.location.origin))
    } catch {
      setOidcError('企业单点登录暂不可用，请联系管理员。')
    }
  }

  const handleOidcRecovery = () => {
    try {
      window.location.assign(buildOidcStartUrl('/admin/auth/oidc/start', returnTo, window.location.origin))
    } catch {
      setOidcError('企业单点登录恢复入口暂不可用，请联系管理员。')
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!username.trim() || !password) {
      setError('请输入用户名和密码')
      return
    }

    setLoading(true)
    setError('')

    try {
      await login(username.trim(), password)
      window.localStorage.setItem('polygate_last_username', username.trim())
      navigate(returnTo, { replace: true })
    } catch (loginError) {
      setError(loginErrorMessage(loginError))
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-[#f6f1e9] px-4 py-8 sm:px-8">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_16%_10%,rgba(194,83,107,0.14),transparent_34%),radial-gradient(circle_at_84%_6%,rgba(47,111,143,0.14),transparent_32%),radial-gradient(circle_at_52%_108%,rgba(217,131,36,0.12),transparent_38%)]" />
      <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(to_right,rgba(29,36,51,0.045)_1px,transparent_1px),linear-gradient(to_bottom,rgba(29,36,51,0.035)_1px,transparent_1px)] [background-size:64px_64px] [mask-image:radial-gradient(ellipse_at_center,black,transparent_78%)]" />

      <Card className="relative grid h-auto min-h-[556px] w-full max-w-[1340px] overflow-hidden rounded-[26px] border border-[#e7dbcd] bg-[#fffdf8] shadow-[0_36px_110px_-24px_rgba(58,40,30,0.32),0_2px_0_rgba(255,255,255,0.7)_inset] lg:min-h-[720px] lg:grid-cols-[1.38fr_0.86fr]">
        <div className="relative hidden min-h-[720px] overflow-hidden bg-[#f4ece1] lg:block">
          <img
            src="/login-gateway-hero-v9.png"
            alt=""
            aria-hidden="true"
            fetchPriority="high"
            decoding="async"
            className="absolute inset-0 h-full w-full object-cover object-center"
          />
          <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(102deg,rgba(252,247,240,0.94)_0%,rgba(252,247,240,0.66)_38%,rgba(252,247,240,0.22)_62%,transparent_82%)]" />
          <div className="pointer-events-none absolute inset-x-0 bottom-0 h-44 bg-gradient-to-t from-[#f8f2e7]/95 via-[#f8f2e7]/40 to-transparent" />
          <div className="pointer-events-none absolute inset-y-0 right-0 w-px bg-gradient-to-b from-transparent via-[#c9b8a4]/60 to-transparent" />

          <div className="relative flex min-h-[720px] flex-col p-10 xl:p-14">
            <header className="flex items-start justify-between gap-6">
              <div className="flex items-center gap-3.5">
                <div className="flex h-12 w-12 items-center justify-center rounded-[14px] border border-white/70 bg-white/70 text-[#a8435c] shadow-[0_14px_34px_-12px_rgba(168,67,92,0.45)] backdrop-blur-md">
                  <GateMark className="h-6 w-6" />
                </div>
                <div>
                  <p className="text-[1.02rem] font-semibold tracking-[-0.02em] text-[#1d2433]">PolyGate</p>
                  <p className="mt-0.5 font-mono text-[10.5px] uppercase tracking-[0.16em] text-[#7c6a5c]">unified model gateway</p>
                </div>
              </div>
            </header>

            <section className="mt-14 max-w-[420px]">
              <p className="font-mono text-[10.5px] font-medium uppercase tracking-[0.3em] text-[#a8435c]">one gateway · every model</p>
              <h2 className="mt-4 text-[2.05rem] font-semibold leading-[1.16] tracking-[-0.045em] text-[#1d2433] xl:text-[2.42rem]">
                所有模型，
                <br />
                <span className="bg-[linear-gradient(96deg,#a8435c_4%,#d98324_46%,#2f6f8f_96%)] bg-clip-text text-transparent">同一道闸门。</span>
              </h2>
              <div className="mt-6 h-[3px] w-14 rounded-full bg-[linear-gradient(90deg,#a8435c,#d98324)]" />
              <p className="mt-5 max-w-[352px] text-[13.5px] leading-7 text-[#5b4a3c]">
                把客户端、策略与上游供应商收进同一条安全边界。每一次模型调用都留下可追溯的凭证、用量与结算记录。
              </p>
            </section>

            <footer className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-[#1d2433]/10 pt-5 font-mono text-[10px] uppercase tracking-[0.14em] text-[#6b5b4d]">
              <span>Anthropic Native</span>
              <span className="h-1 w-1 rounded-full bg-[#a8435c]" />
              <span>OpenAI Compatible</span>
              <span className="h-1 w-1 rounded-full bg-[#2f6f8f]" />
              <span>Policy &amp; Budgets</span>
            </footer>
          </div>
        </div>

        <div className="relative flex min-h-[556px] items-center justify-center border-[#1d2433]/10 px-7 py-12 sm:px-10 lg:min-h-[720px] lg:border-l">
          <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(165deg,rgba(255,255,255,0.9),rgba(246,241,233,0.35)_58%,rgba(255,255,255,0.65))]" />
          <div className="relative w-full max-w-[352px]">
            <CardHeader className="items-start px-0 pb-8 text-left">
              <div className="mb-5 flex w-full items-center justify-between">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-[#a8435c]/20 bg-[#fdf3f2] text-[#a8435c]">
                  <GateMark className="h-5 w-5" />
                </div>
                <span className="font-mono text-[9.5px] font-medium uppercase tracking-[0.26em] text-[#9b8b7d]">console&nbsp;/&nbsp;v1</span>
              </div>
              <div className="space-y-2">
                <p className="font-mono text-[10.5px] font-semibold uppercase tracking-[0.24em] text-[#a8435c]">PolyGate Console</p>
                <h1 className="text-[1.72rem] font-semibold tracking-[-0.035em] text-[#1d2433]">登录控制台</h1>
                <p className="text-[13px] leading-6 text-[#6b6470]">使用组织账户进入统一模型控制台</p>
              </div>
            </CardHeader>

            <CardContent className="px-0">
              {sessionNotice && (
                <div role="status" className="mb-5 border-l-2 border-[#d98324] bg-[#fdf6e8] px-3 py-2.5 text-xs leading-5 text-[#8a5a12]">
                  {sessionNotice}
                </div>
              )}
              {oidcError && (
                <div id="oidc-login-error" role="alert" aria-live="polite" className="mb-5 border-l-2 border-[#c2536b] bg-[#fdf1f3] px-3 py-2.5 text-xs leading-5 text-[#9b3450]">
                  {oidcError}
                </div>
              )}
              {authMethodsLoading && (
                <div role="status" className="mb-5 flex items-center gap-2 border-l-2 border-[#c9b8a4] bg-[#faf6f0] px-3 py-2.5 text-xs leading-5 text-[#6b5b4d]">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  正在确认当前实例启用的登录方式…
                </div>
              )}
              {authMethodsError && !authMethodsLoading && (
                <div role="alert" className="mb-5 border-l-2 border-[#d98324] bg-[#fdf6e8] px-3 py-2.5 text-xs leading-5 text-[#8a5a12]">
                  <div className="flex items-start gap-2">
                    <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <p>{authMethodsError}</p>
                      <p className="mt-1 text-[#a4772a]">下方密码入口仅作为恢复尝试，并不表示服务端已启用密码登录；若实例只启用 SSO，该尝试会被拒绝。</p>
                    </div>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-8 border-[#e7cfa6] bg-white/70 text-[#8a5a12] hover:bg-white"
                      onClick={() => {
                        setAuthMethodsLoading(true)
                        setAuthMethodsError('')
                        setAuthMethodsAttempt((attempt) => attempt + 1)
                      }}
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                      重新探测
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-8 border-[#e7cfa6] bg-white/70 text-[#8a5a12] hover:bg-white"
                      onClick={handleOidcRecovery}
                    >
                      <Fingerprint className="h-3.5 w-3.5" />
                      尝试 SSO
                    </Button>
                  </div>
                </div>
              )}
              {oidcEnabled && (
                <div className={showPasswordLogin ? 'mb-6' : ''}>
                  <Button
                    type="button"
                    size="lg"
                    onClick={handleOidcLogin}
                    className="h-12 w-full rounded-xl bg-[#a8435c] text-sm font-semibold text-white shadow-[0_16px_34px_-14px_rgba(168,67,92,0.7)] transition-colors hover:bg-[#b9506a]"
                  >
                    <Fingerprint className="h-4 w-4" />
                    {authMethods?.oidc.label.trim() || '企业单点登录'}
                  </Button>
                  {showPasswordLogin && (
                    <div className="mt-6 flex items-center gap-3 font-mono text-[10px] uppercase tracking-[0.18em] text-[#a3948a]" aria-hidden="true">
                      <div className="h-px flex-1 bg-[#e7dbcd]" />
                      <span>或使用密码</span>
                      <div className="h-px flex-1 bg-[#e7dbcd]" />
                    </div>
                  )}
                </div>
              )}
              {showPasswordLogin && (
                <form onSubmit={handleSubmit} className="space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="username" className="text-[11px] font-medium uppercase tracking-[0.08em] text-[#6b5b4d]">用户名</Label>
                    <div className="relative">
                      <UserRound className="absolute left-3.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#a3948a]" />
                      <Input
                        id="username"
                        type="text"
                        placeholder="请输入用户名"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        disabled={loading}
                        autoComplete="username"
                        autoFocus={!username}
                        aria-invalid={!!error}
                        aria-describedby={error ? 'login-error' : undefined}
                        className="h-11 rounded-lg border-[#e7dbcd] bg-white pl-9 pr-4 text-sm text-[#1d2433] shadow-[0_1px_2px_rgba(29,36,51,0.03)] transition-colors placeholder:text-[#b4a79c] hover:border-[#d5c4b1] focus-visible:border-[#a8435c]/70 focus-visible:ring-[#a8435c]/15"
                      />
                    </div>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="password" className="text-[11px] font-medium uppercase tracking-[0.08em] text-[#6b5b4d]">密码</Label>
                    <div className="relative">
                      <KeyRound className="absolute left-3.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#a3948a]" />
                      <Input
                        id="password"
                        type={showPassword ? 'text' : 'password'}
                        placeholder="请输入密码"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        onKeyDown={(e) => setCapsLock(e.getModifierState('CapsLock'))}
                        onKeyUp={(e) => setCapsLock(e.getModifierState('CapsLock'))}
                        disabled={loading}
                        autoComplete="current-password"
                        autoFocus={!!username}
                        aria-invalid={!!error}
                        aria-describedby={error ? 'login-error' : capsLock ? 'caps-lock-warning' : undefined}
                        className="h-11 rounded-lg border-[#e7dbcd] bg-white pl-9 pr-10 text-sm text-[#1d2433] shadow-[0_1px_2px_rgba(29,36,51,0.03)] transition-colors placeholder:text-[#b4a79c] hover:border-[#d5c4b1] focus-visible:border-[#a8435c]/70 focus-visible:ring-[#a8435c]/15"
                      />
                      <button
                        type="button"
                        className="absolute right-3 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md text-[#a3948a] transition-colors hover:bg-[#f6f1e9] hover:text-[#5b4a3c] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#a8435c]/20"
                        onClick={() => setShowPassword((current) => !current)}
                        aria-label={showPassword ? '隐藏密码' : '显示密码'}
                      >
                        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                    {capsLock && (
                      <p id="caps-lock-warning" className="text-xs text-[#a4772a]">大写锁定已开启</p>
                    )}
                    {error && (
                      <p id="login-error" role="alert" aria-live="polite" className="border-l-2 border-[#c2536b] bg-[#fdf1f3] px-3 py-2.5 text-xs leading-5 text-[#9b3450]">
                        {error}
                      </p>
                    )}
                  </div>
                  <Button
                    type="submit"
                    size="lg"
                    aria-busy={loading}
                    className="mt-3 h-11 w-full rounded-xl bg-[#1d2433] text-sm font-medium text-[#fdf8f2] shadow-[0_18px_36px_-18px_rgba(29,36,51,0.9)] transition-colors hover:bg-[#2b3550]"
                    disabled={loading}
                  >
                    {loading && <Loader2 className="h-4 w-4 animate-spin" />}
                    {loading ? '正在登录…' : '登录'}
                  </Button>
                  <div className="flex items-center gap-3 pt-4">
                    <div className="h-px flex-1 bg-[#eee4d8]" />
                    <ShieldCheck className="h-4 w-4 text-[#b4a79c]" />
                    <div className="h-px flex-1 bg-[#eee4d8]" />
                  </div>
                  <p className="text-center font-mono text-[10px] uppercase tracking-[0.18em] text-[#a3948a]">secure session · polygate</p>
                </form>
              )}
              {!authMethodsLoading && !authMethodsError && !passwordEnabled && !oidcEnabled && (
                <p role="status" className="rounded-lg border border-[#e7cfa6] bg-[#fdf6e8] px-3 py-3 text-center text-xs leading-5 text-[#8a5a12]">
                  当前实例未启用可用的登录方式，请联系管理员。
                </p>
              )}
              {!passwordEnabled && oidcEnabled && (
                <p className="mt-4 text-center text-[11px] text-[#8c7d72]">将通过企业身份提供方完成安全认证</p>
              )}
            </CardContent>
          </div>
        </div>
      </Card>
    </main>
  )
}
