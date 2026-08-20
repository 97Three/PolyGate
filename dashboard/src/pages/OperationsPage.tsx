import { useState } from 'react'
import { AlertTriangle, Bot, CheckCircle2, RefreshCw, ShieldAlert } from 'lucide-react'
import { ErrorState } from '@/components/shared/ErrorState'
import { LoadingPage } from '@/components/shared/LoadingPage'
import { PageHeader } from '@/components/shared/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  useOpsIncident,
  useOpsIncidents,
  useOpsAgentConfiguration,
  useRecordOpsIncidentFeedback,
  useUpdateOpsAgentConfiguration,
  useUpdateOpsIncidentStatus,
  useRunOpsCheck,
} from '@/hooks/use-ops'
import type { OpsAgentConfigurationInput, OpsIncidentEvidence, OpsIncidentStatusInput, OpsIncidentSummary, OpsSeverity } from '@/types'

const severityClass: Record<OpsSeverity, string> = {
  'SEV-1': 'border-red-400 bg-red-100 text-red-900',
  'SEV-2': 'border-orange-400 bg-orange-100 text-orange-900',
  'SEV-3': 'border-amber-400 bg-amber-100 text-amber-900',
  'SEV-4': 'border-slate-300 bg-slate-100 text-slate-800',
}

const statusLabel: Record<string, string> = {
  open: '待响应',
  acknowledged: '已确认',
  mitigating: '处理中',
  monitoring: '观察中',
  resolved: '已恢复',
  suppressed: '已抑制',
}

export function OperationsPage() {
  const incidents = useOpsIncidents()
  const configuration = useOpsAgentConfiguration()
  const updateConfiguration = useUpdateOpsAgentConfiguration()
  const runCheck = useRunOpsCheck()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const detail = useOpsIncident(selectedId)
  const updateStatus = useUpdateOpsIncidentStatus()
  const recordFeedback = useRecordOpsIncidentFeedback()
  const [reason, setReason] = useState('已由值班人员确认并开始排查')
  const [feedbackNote, setFeedbackNote] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [configurationDraft, setConfigurationDraft] = useState<OpsAgentConfigurationInput | null>(null)

  if (incidents.isLoading || configuration.isLoading) return <LoadingPage />
  if (incidents.error || !incidents.data || configuration.error || !configuration.data) {
    return (
      <ErrorState
        title="运维事件加载失败"
        message={incidents.error instanceof Error
          ? incidents.error.message
          : configuration.error instanceof Error
            ? configuration.error.message
            : '无法读取事件账本或 Agent 配置'}
        onRetry={() => {
          void incidents.refetch()
          void configuration.refetch()
        }}
      />
    )
  }

  const selected = detail.data
  const onlineAgents = incidents.data.agents.filter((agent) => agent.online)
  const activeAgent = onlineAgents[0]
  const agentConfiguration = configurationDraft ?? {
    enabled: configuration.data.enabled,
    analysisEnabled: configuration.data.analysisEnabled,
    selectedModel: configuration.data.selectedModel ?? configuration.data.recommendedModel,
    preferLocal: configuration.data.preferLocal,
  }
  const saveConfiguration = () => {
    setNotice(null)
    updateConfiguration.mutate({
      ...agentConfiguration,
      analysisEnabled: agentConfiguration.enabled && agentConfiguration.analysisEnabled,
    }, {
      onSuccess: () => {
        setConfigurationDraft(null)
        setNotice(agentConfiguration.enabled
          ? '配置已保存；内置 Java 运维检测将在下个采集周期生效，也可立即检查。'
          : 'Agent 已关闭；历史事件与审计记录仍会保留。')
      },
      onError: (error) => setNotice(error instanceof Error ? error.message : 'Agent 配置保存失败'),
    })
  }
  const mutateStatus = (status: OpsIncidentStatusInput['status']) => {
    if (!selectedId || reason.trim().length < 4) return
    setNotice(null)
    updateStatus.mutate({ id: selectedId, input: { status, reason: reason.trim() } }, {
      onSuccess: () => setNotice('事件状态已更新并写入审计时间线。'),
      onError: (error) => setNotice(error instanceof Error ? error.message : '状态更新失败'),
    })
  }
  const feedback = (outcome: 'true_positive' | 'false_positive' | 'needs_review') => {
    if (!selectedId) return
    setNotice(null)
    recordFeedback.mutate({
      id: selectedId,
      input: { outcome, note: feedbackNote.trim() || undefined },
    }, {
      onSuccess: () => {
        setFeedbackNote('')
        setNotice('反馈已记录，可用于后续规则评估。')
      },
      onError: (error) => setNotice(error instanceof Error ? error.message : '反馈记录失败'),
    })
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="运维事件中心"
        description="内置 Java 运维检测根据汇总指标发现和跟踪事件；模型只提供诊断建议，不会执行命令或自动修改配置。"
        action={{ label: '刷新事件', icon: RefreshCw, onClick: () => void incidents.refetch() }}
      />

      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle>Agent 启用与基础模型</CardTitle>
              <CardDescription className="mt-1 max-w-3xl">
                默认关闭。开启后每 30 秒检查一次，无需部署额外容器；确定性规则负责告警，模型诊断可独立启用。
              </CardDescription>
            </div>
            <Badge variant="outline" className={agentConfiguration.enabled ? 'border-emerald-400 text-emerald-700' : ''}>
              {agentConfiguration.enabled ? (activeAgent ? '已启用 · Agent 在线' : '已启用 · 等待心跳') : '未启用'}
            </Badge>
          </div>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-5 lg:grid-cols-2">
            <div className="space-y-4 rounded-lg border p-4">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label htmlFor="ops-agent-enabled">启用运维 Agent</Label>
                  <p className="mt-1 text-xs text-muted-foreground">保存后自动开始检测；关闭后保留历史事件和证据。</p>
                </div>
                <Switch
                  id="ops-agent-enabled"
                  checked={agentConfiguration.enabled}
                  onCheckedChange={(checked) => {
                    setConfigurationDraft({
                      ...agentConfiguration,
                      enabled: checked,
                      analysisEnabled: checked && agentConfiguration.analysisEnabled,
                    })
                  }}
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label htmlFor="ops-prefer-local">本地模型优先</Label>
                  <p className="mt-1 text-xs text-muted-foreground">Ollama、local_vLLM、SGLang 和 llama.cpp 路由会排在候选列表前面。</p>
                </div>
                <Switch
                  id="ops-prefer-local"
                  checked={agentConfiguration.preferLocal}
                  onCheckedChange={(checked) => setConfigurationDraft({ ...agentConfiguration, preferLocal: checked })}
                />
              </div>
            </div>

            <div className="space-y-4 rounded-lg border p-4">
              <div className="space-y-2">
                <Label htmlFor="ops-base-model">基础模型</Label>
                <Select
                  value={agentConfiguration.selectedModel ?? undefined}
                  onValueChange={(value) => setConfigurationDraft({ ...agentConfiguration, selectedModel: value })}
                >
                  <SelectTrigger id="ops-base-model" aria-describedby="ops-base-model-help">
                    <SelectValue placeholder={configuration.data.candidates.length > 0 ? '选择用于运维诊断的模型' : '没有可路由模型'} />
                  </SelectTrigger>
                  <SelectContent>
                    {configuration.data.candidates.map((candidate) => (
                      <SelectItem key={candidate.id} value={candidate.id}>
                        {candidate.local ? '本地 · ' : '云端 · '}{candidate.displayName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p id="ops-base-model-help" className="text-xs text-muted-foreground">
                  {configuration.data.recommendedModel
                    ? `推荐：${configuration.data.recommendedModel}${configuration.data.candidates.find((candidate) => candidate.id === configuration.data.recommendedModel)?.local ? '（本地）' : ''}`
                    : '请先在模型管理中配置并验证至少一个可路由模型。'}
                </p>
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label htmlFor="ops-analysis-enabled">启用模型诊断</Label>
                  <p className="mt-1 text-xs text-muted-foreground">模型调用按正常预算与权限检查；内置分析账号默认每日上限 1 USD，每个事件 15 分钟内最多分析一次，每小时最多四次。</p>
                </div>
                <Switch
                  id="ops-analysis-enabled"
                  checked={agentConfiguration.analysisEnabled}
                  disabled={!agentConfiguration.enabled || !agentConfiguration.selectedModel}
                  onCheckedChange={(checked) => setConfigurationDraft({ ...agentConfiguration, analysisEnabled: checked })}
                />
              </div>
            </div>
          </div>

          {activeAgent?.analysisEnabled && (
            <div className="rounded-lg border bg-muted/40 p-3 text-sm">
              模型状态：{modelStatusLabel(activeAgent.modelStatus)}
              {activeAgent.selectedModel ? ` · ${activeAgent.selectedModel}` : ''}
              {activeAgent.modelLastSuccessAtMs ? ` · 最近成功 ${formatTime(activeAgent.modelLastSuccessAtMs)}` : ''}
            </div>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={saveConfiguration} disabled={updateConfiguration.isPending || (agentConfiguration.analysisEnabled && !agentConfiguration.selectedModel)}>
              {updateConfiguration.isPending ? '正在保存…' : '保存 Agent 配置'}
            </Button>
            <Button variant="outline" disabled={runCheck.isPending || !configuration.data.enabled} onClick={() => runCheck.mutate(undefined, { onSuccess: (result) => setNotice(result.enabled ? `检测完成，当前异常条件 ${result.activeConditions ?? 0} 项。` : '运维检测尚未启用。'), onError: (error) => setNotice(error instanceof Error ? error.message : '检查失败') })}>
              {runCheck.isPending ? '正在检查…' : '立即检查'}
            </Button>
            <span className="text-xs text-muted-foreground">显式选择会覆盖本地优先推荐；关闭 Agent 不删除历史事件。</span>
          </div>
        </CardContent>
      </Card>

      {notice && <div role="status" className="rounded-lg border bg-muted/50 p-3 text-sm">{notice}</div>}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <Metric title="未恢复事件" value={incidents.data.open} icon={ShieldAlert} detail="含已确认、处理中和观察中的事件" />
        <Metric title="最高等级" value={incidents.data.highestOpenSeverity ?? '无'} icon={AlertTriangle} detail="SEV-1 最高，SEV-4 最低" />
        <Metric title="历史事件" value={incidents.data.total} icon={Bot} detail="PostgreSQL 权威事件账本" />
        <Metric title="Agent 状态" value={activeAgent ? (activeAgent.mode === 'disabled' ? '待启用' : '在线') : '未连接'} icon={Bot} detail={activeAgent ? `${activeAgent.mode} · ${activeAgent.ruleSetVersion} · 队列 ${activeAgent.queueDepth}` : '最近没有收到有效心跳'} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>事件列表</CardTitle>
          <CardDescription>恢复状态只能由同一确定性检测器的恢复证据关闭，人工操作不会伪造恢复。</CardDescription>
        </CardHeader>
        <CardContent>
          {incidents.data.items.length === 0 ? (
            <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
              {activeAgent?.mode === 'disabled'
                ? '尚无运维事件。Agent 进程在线，但后台启用开关仍处于关闭状态。'
                : onlineAgents.length > 0
                  ? '尚无运维事件。Agent 当前在线；shadow 模式只评估，切换到 read_only 后才会写入事件账本。'
                : '尚无运维事件，且 15 分钟内没有 Agent 心跳。请先检查可选容器、专用服务账号和运行模式。'}
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>等级</TableHead>
                    <TableHead>事件</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>最近观测</TableHead>
                    <TableHead className="text-right">次数</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {incidents.data.items.map((incident) => (
                    <IncidentRow key={incident.id} incident={incident} onSelect={() => setSelectedId(incident.id)} />
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {selectedId && (
        <Card>
          <CardHeader>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <CardTitle>{selected?.title ?? '正在加载事件详情'}</CardTitle>
                <CardDescription>{selected?.eventKey ?? selectedId}</CardDescription>
              </div>
              <Button variant="outline" size="sm" onClick={() => setSelectedId(null)}>关闭详情</Button>
            </div>
          </CardHeader>
          <CardContent className="space-y-5">
            {detail.isLoading && <p className="text-sm text-muted-foreground">正在加载证据与时间线…</p>}
            {detail.error && <ErrorState title="事件详情加载失败" message={detail.error instanceof Error ? detail.error.message : '未知错误'} onRetry={() => void detail.refetch()} />}
            {selected && (
              <>
                <div className="grid gap-4 lg:grid-cols-2">
                  <section className="rounded-lg border p-4">
                    <h3 className="font-semibold">事实摘要</h3>
                    <p className="mt-2 text-sm leading-6 text-muted-foreground">{selected.summary}</p>
                    <p className="mt-3 text-xs text-muted-foreground">恢复条件：{selected.recoveryCriteria}</p>
                    <pre className="mt-4 max-h-72 overflow-auto rounded-md bg-muted p-3 text-xs">{JSON.stringify(selected.evidence[0]?.evidence ?? {}, null, 2)}</pre>
                    <ModelAnalysis evidence={selected.evidence} />
                  </section>
                  <section className="space-y-3 rounded-lg border p-4">
                    <h3 className="font-semibold">人工响应</h3>
                    <div className="space-y-2">
                      <Label htmlFor="ops-status-reason">状态变更依据</Label>
                      <Input id="ops-status-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button size="sm" onClick={() => mutateStatus('acknowledged')} disabled={updateStatus.isPending || selected.status === 'resolved'}>确认</Button>
                      <Button size="sm" variant="outline" onClick={() => mutateStatus('mitigating')} disabled={updateStatus.isPending || selected.status === 'resolved'}>处理中</Button>
                      <Button size="sm" variant="outline" onClick={() => mutateStatus('monitoring')} disabled={updateStatus.isPending || selected.status === 'resolved'}>观察中</Button>
                      <Button size="sm" variant="outline" onClick={() => mutateStatus('suppressed')} disabled={updateStatus.isPending || selected.status === 'resolved'}>抑制</Button>
                    </div>
                    <div className="space-y-2 border-t pt-3">
                      <Label htmlFor="ops-feedback-note">规则反馈（可选说明）</Label>
                      <Input id="ops-feedback-note" value={feedbackNote} onChange={(event) => setFeedbackNote(event.target.value)} placeholder="误报原因、缺少的证据或规则改进建议" />
                      <div className="flex flex-wrap gap-2">
                        <Button size="sm" variant="outline" onClick={() => feedback('true_positive')} disabled={recordFeedback.isPending}>有效告警</Button>
                        <Button size="sm" variant="outline" onClick={() => feedback('false_positive')} disabled={recordFeedback.isPending}>误报</Button>
                        <Button size="sm" variant="outline" onClick={() => feedback('needs_review')} disabled={recordFeedback.isPending}>待复核</Button>
                      </div>
                    </div>
                  </section>
                </div>
                <section>
                  <h3 className="mb-3 font-semibold">事件时间线</h3>
                  <div className="space-y-2">
                    {selected.timeline.map((entry) => (
                      <div key={entry.id} className="flex gap-3 rounded-lg border p-3 text-sm">
                        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
                        <div>
                          <p>{entry.message}</p>
                          <p className="mt-1 text-xs text-muted-foreground">{entry.actorName} · {formatTime(entry.occurredAtMs)} · {entry.eventType}</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              </>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

function IncidentRow({ incident, onSelect }: { incident: OpsIncidentSummary; onSelect: () => void }) {
  return (
    <TableRow className="cursor-pointer" tabIndex={0} onClick={onSelect} onKeyDown={(event) => {
      if (event.key === 'Enter' || event.key === ' ') onSelect()
    }}>
      <TableCell><Badge variant="outline" className={severityClass[incident.severity]}>{incident.severity}</Badge></TableCell>
      <TableCell>
        <p className="font-medium">{incident.title}</p>
        <p className="max-w-xl truncate text-xs text-muted-foreground">{incident.summary}</p>
      </TableCell>
      <TableCell>{statusLabel[incident.status] ?? incident.status}</TableCell>
      <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{formatTime(incident.lastSeenAtMs)}</TableCell>
      <TableCell className="text-right">{incident.occurrenceCount}</TableCell>
    </TableRow>
  )
}

function Metric({ title, value, detail, icon: Icon }: { title: string; value: string | number; detail: string; icon: typeof Bot }) {
  return (
    <Card>
      <CardContent className="flex items-start justify-between p-5">
        <div><p className="text-sm text-muted-foreground">{title}</p><p className="mt-2 text-2xl font-bold">{value}</p><p className="mt-1 text-xs text-muted-foreground">{detail}</p></div>
        <Icon className="h-5 w-5 text-primary" />
      </CardContent>
    </Card>
  )
}

function ModelAnalysis({ evidence }: { evidence: OpsIncidentEvidence[] }) {
  const value = evidence
    .map((item) => item.evidence.modelAnalysis)
    .find((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
  const content = typeof value?.content === 'string' ? value.content : null
  const model = typeof value?.model === 'string' ? value.model : '未知模型'
  if (!content) return null
  return (
    <div className="mt-4 rounded-md border border-sky-200 bg-sky-50 p-3 text-sky-950">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-semibold">模型诊断建议</p>
        <Badge variant="outline">只读建议 · {model}</Badge>
      </div>
      <p className="mt-2 whitespace-pre-wrap text-sm leading-6">{content}</p>
      <p className="mt-2 text-xs text-sky-800">该内容由模型根据脱敏事实生成，必须由人工验证；它不会触发任何操作。</p>
    </div>
  )
}

function formatTime(value: number) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value))
}

function modelStatusLabel(value: string) {
  const labels: Record<string, string> = {
    disabled: '未启用',
    configured: '已配置',
    missing_credential: '缺少独立模型密钥',
    error: '最近诊断失败',
  }
  return labels[value] ?? value
}
