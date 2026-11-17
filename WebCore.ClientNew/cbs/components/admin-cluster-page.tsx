'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { Modal, Toast } from '@adminlte/react'
import { useRouter } from 'next/navigation'
import { getCurrentUser } from '@/lib/webcore-auth'
import { webCoreApi, webCoreApiResponse } from '@/lib/webcore-api'

type TabKey = 'operations' | 'reload-system' | 'reload-role' | 'auto-sync'
type Endpoint = { recordCode: string; serviceName: string; instanceId: string; hostName?: string; status: string; healthStatus?: string; lastSeenAt?: string; updatedAt?: string; commandChannelMode?: string; isFresh: boolean; isDefaultTarget: boolean; environment?: string; platformType?: string }
type Coordination = { controlPlane?: string; commandStore?: string; signalTransport?: string; signalChannel?: string; pollFallbackSeconds?: number; signalEnabled?: boolean }
type Command = { commandId: number; commandType: string; status: string; phase: string; createdAt?: string; expiresAt?: string; payloadHash?: string; deliveries?: Array<{ targetRecordCode: string; deliveryStatus: string; attemptCount?: number; preparedHash?: string; applyWakeVia?: string; resultMessage?: string; lastError?: string; startedAt?: string; preparedAt?: string; finishedAt?: string; updatedAt?: string }> }
type ReloadCommandType = 'reload-system' | 'reload-role'
type AutoJob = { jobName: string; commandType: string; enabled: boolean; intervalSeconds: number; nextRunAt?: string; leaseUntil?: string; lastStatus?: string; lastMessage?: string; lastStartedCommandId?: number; lastCompletedCommandId?: number; lastCompletedAt?: string }
type AutoRun = { runId: number; jobName: string; commandType?: string; runKind?: string; status: string; commandId?: number; payloadHash?: string; targetCount: number; preparedCount?: number; succeededCount: number; failedCount: number; pendingCount?: number; scheduledFor?: string; startedAt?: string; finishedAt?: string; skipReason?: string; resultMessage?: string; errorMessage?: string }
type AutoSync = { jobs?: AutoJob[]; runs?: AutoRun[] }
type ToastState = { message: string; theme: 'success' | 'danger' | 'info' }

const tabs: Array<{ key: TabKey; title: string; caption: string }> = [
  { key: 'operations', title: 'Thông tin vận hành', caption: 'runtime endpoints' },
  { key: 'reload-system', title: 'Core Data', caption: 'reload-system' },
  { key: 'reload-role', title: 'Role / Permission', caption: 'reload-role' },
  { key: 'auto-sync', title: 'Auto Sync Jobs', caption: 'lịch chạy/lịch sử' },
]

function date(value?: string) { return value ? new Date(value).toLocaleString('vi-VN') : '-' }
function pill(value: string, theme: 'success' | 'danger' | 'warning' | 'primary' | 'secondary' = 'primary') { return <span className={`badge rounded-pill text-bg-${theme}`}>{value}</span> }

async function loadActiveCommand(commandType: ReloadCommandType): Promise<Command | undefined> {
  const response = await webCoreApiResponse(`/module/S004/cluster/commands/latest?commandType=${encodeURIComponent(commandType)}`)
  if (!response.ok) throw new Error(`Không thể tải command đang hoạt động (${response.status}).`)
  const body = await response.text()
  return body.trim() ? JSON.parse(body) as Command : undefined
}

const warningSeenAgeMs = 45_000
const unhealthySeenAgeMs = 90_000
function seenAgeMs(value?: string, now = Date.now()) { const timestamp = value ? new Date(value).getTime() : Number.NaN; return Number.isFinite(timestamp) ? Math.max(0, now - timestamp) : undefined }
function seenAge(value?: string, now = Date.now()) { const age = seenAgeMs(value, now); if (age == null) return '-'; return age < 60_000 ? `${Math.floor(age / 1000)}s` : `${Math.floor(age / 60_000)}m ${Math.floor((age % 60_000) / 1000)}s` }
function effectiveHealth(endpoint: Endpoint, now: number) { const age = seenAgeMs(endpoint.lastSeenAt, now); if (endpoint.healthStatus?.toLowerCase() === 'healthy' && age != null && age > unhealthySeenAgeMs) return 'unhealthy'; if (endpoint.healthStatus?.toLowerCase() === 'healthy' && age != null && age > warningSeenAgeMs) return 'warning'; return endpoint.healthStatus ?? '-' }
function isHealthyFresh(endpoint: Endpoint, now: number) { return endpoint.status === 'active' && endpoint.isFresh && effectiveHealth(endpoint, now) === 'healthy' }
function isReloadTarget(endpoint: Endpoint, now: number) { return endpoint.isDefaultTarget && isHealthyFresh(endpoint, now) }

export function AdminClusterPage() {
  const router = useRouter()
  const [allowed, setAllowed] = useState<boolean>()
  const [tab, setTab] = useState<TabKey>('operations')
  const [endpoints, setEndpoints] = useState<Endpoint[]>([])
  const [coordination, setCoordination] = useState<Coordination>()
  const [currentTimeMs, setCurrentTimeMs] = useState(() => Date.now())
  const [command, setCommand] = useState<Command>()
  const [activeCommands, setActiveCommands] = useState<Partial<Record<ReloadCommandType, Command>>>({})
  const [autoSync, setAutoSync] = useState<AutoSync>()
  const [historyPage, setHistoryPage] = useState(1)
  const [showAll, setShowAll] = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [autoSyncRefresh, setAutoSyncRefresh] = useState(true)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<ToastState>()
  const [portalTarget, setPortalTarget] = useState<HTMLElement | null>(null)
  const notify = (message: string, theme: ToastState['theme']) => setToast({ message, theme })

  useEffect(() => { setPortalTarget(document.body) }, [])
  useEffect(() => { const timer = window.setInterval(() => setCurrentTimeMs(Date.now()), 1000); return () => window.clearInterval(timer) }, [])

  const load = useCallback(async (refresh = false, silent = false) => {
    if (!silent) setBusy(true)
    try {
      const path = refresh ? '/module/S004/cluster/endpoints/refresh' : '/module/S004/cluster/endpoints'
      const [data, reloadSystem, reloadRole] = await Promise.all([
        refresh
          ? webCoreApi<{ endpoints: Endpoint[]; coordination?: Coordination }>(path, { method: 'POST' })
          : webCoreApi<{ endpoints: Endpoint[]; coordination?: Coordination }>(path),
        loadActiveCommand('reload-system'),
        loadActiveCommand('reload-role'),
      ])
      setEndpoints(data.endpoints ?? []); setCoordination(data.coordination)
      setActiveCommands({ 'reload-system': reloadSystem, 'reload-role': reloadRole })
    } catch (error) { notify(error instanceof Error ? error.message : 'Không tải được runtime endpoint.', 'danger') }
    finally { if (!silent) setBusy(false) }
  }, [])

  const loadAutoSync = useCallback(async () => {
    try { setAutoSync(await webCoreApi<AutoSync>('/module/S004/cluster/auto-sync')) }
    catch (error) { notify(error instanceof Error ? error.message : 'Không tải được Auto Sync Jobs.', 'danger') }
  }, [])

  useEffect(() => {
    void getCurrentUser().then(async response => {
      const user = response.ok ? await response.json() as { isPrivilegedAccount?: boolean } : null
      setAllowed(user?.isPrivilegedAccount === true)
      if (user?.isPrivilegedAccount) void load()
    }).catch(() => setAllowed(false))
  }, [load])

  useEffect(() => {
    if (!autoRefresh || allowed !== true) return
    const timer = window.setInterval(() => void load(false, true), 10000)
    return () => window.clearInterval(timer)
  }, [allowed, autoRefresh, load])

  // A browser refresh loses local command state. Restore the active command
  // for the selected reload tab so an already-prepared command can still be
  // applied or aborted instead of only appearing as a blocker.
  useEffect(() => {
    if (tab !== 'reload-system' && tab !== 'reload-role') return
    setCommand(activeCommands[tab])
  }, [activeCommands, tab])

  useEffect(() => { if (tab === 'auto-sync') void loadAutoSync() }, [tab, loadAutoSync])
  useEffect(() => {
    if (tab !== 'auto-sync') return
    const hasRunningAutoJob = Boolean(
      autoSync?.runs?.some(run => ['running', 'pending', 'preparing', 'applying'].includes(run.status))
      || autoSync?.jobs?.some(job => job.leaseUntil && new Date(job.leaseUntil).getTime() > Date.now()),
    )
    if (!autoSyncRefresh && !hasRunningAutoJob) return
    const timer = window.setInterval(() => void loadAutoSync(), hasRunningAutoJob ? 2000 : 30000)
    return () => window.clearInterval(timer)
  }, [autoSync, autoSyncRefresh, loadAutoSync, tab])

  useEffect(() => {
    const commandId = command?.commandId
    // A failed/aborted command remains actionable by workers while phase=abort.
    // Keep polling until every target has discarded/rolled back and phase=completed.
    const terminal = command?.phase === 'completed'
    if (!commandId || terminal) return
    let disposed = false
    const poll = async () => {
      try {
        const next = await webCoreApi<Command>(`/module/S004/cluster/commands/${commandId}`)
        if (disposed) return
        setCommand(next)
        if (next.phase === 'completed') {
          void load(false, true)
          setToast({ theme: next.status === 'succeeded' ? 'success' : 'danger', message: next.status === 'succeeded' ? `Command #${next.commandId} đã hoàn tất.` : `Command #${next.commandId} kết thúc với trạng thái ${next.status}.` })
        }
      } catch (error) {
        if (!disposed) setToast({ message: error instanceof Error ? error.message : 'Không tải được trạng thái command.', theme: 'danger' })
      }
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 2000)
    return () => { disposed = true; window.clearInterval(timer) }
  }, [command?.commandId, command?.phase, command?.status, load])
  if (allowed === false) { router.replace('/'); return null }

  const healthy = endpoints.filter(item => effectiveHealth(item, currentTimeMs) === 'healthy')
  const fresh = endpoints.filter(item => item.isFresh)
  const defaultTargets = endpoints.filter(item => isReloadTarget(item, currentTimeMs))
  const visibleEndpoints = useMemo(() => showAll ? endpoints : endpoints.filter(item => isHealthyFresh(item, currentTimeMs)), [endpoints, showAll, currentTimeMs])
  const historyPageSize = 10
  const historyRuns = autoSync?.runs ?? []
  const historyPageCount = Math.max(1, Math.ceil(historyRuns.length / historyPageSize))
  const displayedHistoryPage = Math.min(historyPage, historyPageCount)
  const displayedHistoryRuns = historyRuns.slice((displayedHistoryPage - 1) * historyPageSize, displayedHistoryPage * historyPageSize)

  const prepare = async (type: 'reload-system' | 'reload-role') => {
    if (!defaultTargets.length) { notify('Không có target node active, healthy và fresh.', 'info'); return }
    setBusy(true)
    try {
      const result = await webCoreApi<Command>(`/module/S004/cluster/commands/${type}/prepare`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ targetRecordCodes: defaultTargets.map(item => item.recordCode) }) })
      setCommand(result); setActiveCommands(current => ({ ...current, [type]: result })); notify(`Đã tạo command #${result.commandId}.`, 'success')
    } catch (error) { notify(error instanceof Error ? error.message : 'Không thể chuẩn bị lệnh reload.', 'danger') }
    finally { setBusy(false) }
  }

  const apply = async () => {
    if (!command) return
    setBusy(true)
    try { const result = await webCoreApi<Command>(`/module/S004/cluster/commands/${command.commandId}/apply`, { method: 'POST' }); setCommand(result); notify(`Đã bắt đầu áp dụng command #${result.commandId}.`, 'success'); void load(false, true) }
    catch (error) { notify(error instanceof Error ? error.message : 'Không thể áp dụng lệnh reload.', 'danger') }
    finally { setBusy(false) }
  }

  const abort = async () => {
    if (!command) return
    setBusy(true)
    try {
      const result = await webCoreApi<Command>(`/module/S004/cluster/commands/${command.commandId}/abort`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: 'Aborted from cluster management screen.' }),
      })
      setCommand(result)
      void load(false, true)
      notify(`Abort requested for command #${result.commandId}; waiting for node rollback/staging cleanup.`, 'info')
    } catch (error) { notify(error instanceof Error ? error.message : 'Unable to abort command.', 'danger') }
    finally { setBusy(false) }
  }

  const updateAutoJob = async (job: AutoJob, enabled: boolean, intervalSeconds: number) => {
    setBusy(true)
    try {
      const result = await webCoreApi<AutoSync>(`/module/S004/cluster/auto-sync/jobs/${encodeURIComponent(job.jobName)}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled, intervalSeconds }),
      })
      setAutoSync(result)
      const saved = result.jobs?.find(item => item.jobName === job.jobName)
      if (!saved || saved.enabled !== enabled || saved.intervalSeconds !== intervalSeconds) {
        notify(`Server chưa lưu đúng cấu hình ${job.jobName}; đã tải lại trạng thái thực tế.`, 'danger')
      } else {
        notify(`Đã ${enabled ? 'bật' : 'tắt'} ${job.jobName}; chu kỳ ${intervalSeconds} giây.`, 'success')
      }
    } catch (error) { notify(error instanceof Error ? error.message : 'Không thể cập nhật Auto Sync Job.', 'danger') }
    finally { setBusy(false) }
  }

  const runAutoJob = async (job: AutoJob, intervalSeconds?: number) => {
    setBusy(true)
    try {
      const result = await webCoreApi<AutoSync>(`/module/S004/cluster/auto-sync/jobs/${encodeURIComponent(job.jobName)}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: intervalSeconds == null ? undefined : JSON.stringify({ intervalSeconds }),
      })
      setAutoSync(result)
      const latestRun = result.runs?.find(item => item.jobName === job.jobName && item.runKind === 'manual')
      if (latestRun?.status === 'skipped') {
        notify(latestRun.errorMessage ?? latestRun.resultMessage ?? `Không thể chạy ${job.jobName}: chưa có node runtime active, healthy và fresh.`, 'info')
      } else if (latestRun?.status === 'failed') {
        notify(latestRun.errorMessage ?? latestRun.resultMessage ?? `Chạy ${job.jobName} thất bại.`, 'danger')
      } else {
        notify(`Đã yêu cầu chạy ${job.jobName}.`, 'success')
      }
    } catch (error) { notify(error instanceof Error ? error.message : 'Không thể chạy Auto Sync Job.', 'danger') }
    finally { setBusy(false) }
  }

  const commandTab = tab === 'reload-system' || tab === 'reload-role'
  const commandLabel = tab === 'reload-role' ? 'Role / Permission' : 'Core Data'
  const activeCommand = commandTab ? activeCommands[tab] : undefined
  const globalActiveCommand = Object.values(activeCommands).find(Boolean)
  const canAbort = Boolean(command
    && ['prepare', 'apply'].includes(command.phase)
    && !['succeeded', 'failed', 'aborted', 'expired'].includes(command.status)
    && (command.deliveries ?? []).some(item => !['succeeded', 'failed', 'aborted', 'expired', 'skipped'].includes(item.deliveryStatus)))

  return <div className="container-fluid py-3 admin-page">
    <section className="card card-primary card-outline shadow-sm mb-3"><div className="card-body d-flex flex-wrap justify-content-between gap-3"><div><div className="d-flex align-items-center gap-2"><span className="admin-tools-header-icon"><i className="bi bi-diagram-3" /></span><h1 className="h4 mb-0">Quản lý Cluster</h1></div><p className="text-body-secondary mb-0 mt-2">Xem trạng thái runtime cluster, prepare reload cache trên các node bắt buộc, rồi switch active cache đồng bộ.</p></div><button className="btn btn-outline-primary" disabled={busy} onClick={() => void load(true)} type="button"><i className="bi bi-arrow-clockwise me-1" />TẢI LẠI NODE</button></div></section>
    <section className="card shadow-sm mb-3 admin-tools-tab-card"><div className="card-body py-2"><div className="nav nav-pills gap-1">{tabs.map(item => <button className={`nav-link text-start ${tab === item.key ? 'active' : ''}`} key={item.key} onClick={() => setTab(item.key)} type="button"><span className="d-block fw-semibold">{item.title}</span><small>{item.caption}</small></button>)}</div></div></section>

    {tab === 'operations' && <><section className="card card-info card-outline shadow-sm mb-3"><div className="card-body"><h2 className="h5 mb-1">Thông tin vận hành</h2><p className="small text-body-secondary">Registry runtime endpoint hiện tại từ ops.runtime_endpoint. Node fresh và healthy là target reload mặc định.</p><div className="row">{[['Tổng node', endpoints.length, 'primary', 'bi-hdd-network'], ['Active', endpoints.filter(item => item.status === 'active').length, 'success', 'bi-play-circle'], ['Healthy', healthy.length, 'success', 'bi-heart-pulse'], ['Fresh', fresh.length, 'info', 'bi-arrow-clockwise'], ['Target mặc định', defaultTargets.length, 'primary', 'bi-bullseye']].map(([label, value, color, icon]) => <div className="col-12 col-sm-6 col-xl" key={String(label)}><div className="info-box shadow-sm"><span className={`info-box-icon text-bg-${color}`}><i className={`bi ${icon}`} /></span><div className="info-box-content"><span className="info-box-text">{label}</span><span className="info-box-number">{value}</span></div></div></div>)}</div></div></section>
      <section className="card card-secondary card-outline shadow-sm mb-3"><div className="card-body"><h2 className="h5 mb-1">Điều phối cluster</h2><p className="small text-body-secondary">Các runtime node điều phối qua trạng thái DB durable; notify/pubsub chỉ đánh thức worker nhanh hơn.</p><div className="row g-2">{[['Control plane', coordination?.controlPlane ?? '-'], ['Wake-up bus', coordination?.signalTransport ?? '-'], ['Channel', coordination?.signalChannel ?? '-'], ['Poll fallback', coordination?.pollFallbackSeconds == null ? '-' : `${coordination.pollFallbackSeconds}s`], ['Trạng thái signal', coordination?.signalEnabled ? 'enabled' : 'disabled'], ['Command store', coordination?.commandStore ?? 'database']].map(([label, value]) => <div className="col-sm-6 col-xl-2" key={label}><div className="border rounded bg-body-tertiary p-3 h-100"><span className="small text-body-secondary d-block">{label}</span><strong className="text-break">{value}</strong></div></div>)}</div></div></section>
      <EndpointTable endpoints={visibleEndpoints} currentTimeMs={currentTimeMs} autoRefresh={autoRefresh} onAutoRefresh={setAutoRefresh} showAll={showAll} onShowAll={setShowAll} /></>}

    {commandTab && <><section className="card card-primary card-outline shadow-sm mb-3"><div className="card-body d-flex flex-wrap justify-content-between align-items-center gap-3"><div><h2 className="h5 mb-1">{commandLabel}</h2><p className="text-body-secondary mb-0">Tự lấy mọi node target active, healthy, fresh từ cache endpoint hiện tại; prepare rồi apply khi mọi node sẵn sàng.</p>{activeCommand && <p className="text-warning small mb-0 mt-2">Đang bị chặn bởi command #{activeCommand.commandId} ({activeCommand.phase}/{activeCommand.status}).</p>}</div><div className="d-flex gap-2"><button className="btn btn-outline-secondary" disabled={busy || !command} onClick={() => command && void webCoreApi<Command>(`/module/S004/cluster/commands/${command.commandId}`).then(setCommand).catch(error => notify(error instanceof Error ? error.message : 'Không tải được trạng thái command.', 'danger'))} type="button"><i className="bi bi-arrow-clockwise me-1" />TẢI LẠI TRẠNG THÁI</button><button className="btn btn-primary" disabled={busy || !defaultTargets.length || Boolean(activeCommand)} onClick={() => void prepare(tab)} title={activeCommand ? `Bị chặn bởi command #${activeCommand.commandId}.` : undefined} type="button"><i className="bi bi-send-check me-1" />PREPARE {tab === 'reload-role' ? 'RELOAD ROLE' : 'RELOAD CORE'}</button></div></div></section><EndpointTable endpoints={defaultTargets} currentTimeMs={currentTimeMs} />{command && <section className="card card-success card-outline shadow-sm mt-3"><div className="card-body"><div className="d-flex flex-wrap justify-content-between gap-2"><div><h2 className="h5 mb-1">Command #{command.commandId}</h2><p className="text-body-secondary mb-0">{pill(command.status, command.status === 'succeeded' ? 'success' : 'primary')} <span className="ms-2">Phase: {command.phase}</span></p></div><div className="d-flex gap-2"><button className="btn btn-outline-danger" data-bs-target="#cluster-abort-confirm" data-bs-toggle="modal" disabled={busy || !canAbort} type="button"><i className="bi bi-x-octagon me-1" />HỦY COMMAND</button><button className="btn btn-success" disabled={busy || command.status !== 'prepared' || (command.expiresAt != null && new Date(command.expiresAt).getTime() <= currentTimeMs)} onClick={() => void apply()} type="button"><i className="bi bi-play-fill me-1" />APPLY COMMAND</button></div></div><div className="table-responsive mt-3"><table className="table table-sm align-middle mb-0"><thead><tr><th>Target</th><th>Trạng thái</th><th>Attempts</th><th>Prepared hash</th><th>Apply wake</th><th>Kết quả/lỗi</th><th>Cập nhật</th></tr></thead><tbody>{command.deliveries?.map(item => <tr key={item.targetRecordCode}><td>{item.targetRecordCode}</td><td>{pill(item.deliveryStatus, item.deliveryStatus === 'succeeded' ? 'success' : item.deliveryStatus === 'failed' ? 'danger' : 'warning')}</td><td>{item.attemptCount ?? 0}</td><td><code>{item.preparedHash ?? '-'}</code></td><td>{item.applyWakeVia ?? '-'}</td><td>{item.lastError ?? item.resultMessage ?? '-'}</td><td>{date(item.updatedAt)}</td></tr>)}</tbody></table></div></div></section>}</>}

    {tab === 'auto-sync' && <><section className="card card-warning card-outline shadow-sm mb-3"><div className="card-body d-flex flex-wrap justify-content-between gap-2"><div><h2 className="h5 mb-1">Auto Sync Jobs</h2><p className="text-body-secondary small mb-0">Job định kỳ được một runtime node claim bằng DB lease, sau đó tạo command reload cluster bình thường.</p></div><button className="btn btn-outline-warning" disabled={busy} onClick={() => void loadAutoSync()} type="button"><i className="bi bi-arrow-clockwise me-1" />TẢI LẠI</button></div></section><section className="card card-secondary card-outline shadow-sm mb-3"><div className="card-body"><h2 className="h5 mb-1">Lịch chạy job</h2><p className="text-body-secondary small">{autoSync?.jobs?.length ?? 0} job đang được cấu hình.</p><div className="table-responsive"><table className="table table-hover align-middle mb-0"><thead><tr><th>Job</th><th>Command</th><th>Bật</th><th>Chu kỳ</th><th>Lần chạy kế</th><th>Trạng thái cuối</th><th>Command đã tạo</th><th>Hoàn tất lúc</th><th>Thao tác</th></tr></thead><tbody>{autoSync?.jobs?.map(job => <AutoJobRow activeCommand={globalActiveCommand} busy={busy} job={job} key={job.jobName} onRun={runAutoJob} onUpdate={updateAutoJob} />)}{!autoSync?.jobs?.length && <tr><td className="text-center text-body-secondary py-4" colSpan={9}>Chưa có Auto Sync Job.</td></tr>}</tbody></table></div></div></section><section className="card card-info card-outline shadow-sm"><div className="card-body"><div className="d-flex flex-wrap justify-content-between gap-3"><div><h2 className="h5 mb-1">Lịch sử chạy</h2><p className="text-body-secondary small mb-0">Hiển thị 10 bản ghi mới nhất mỗi trang; kéo thanh cuộn ngang bên dưới bảng để xem các cột còn lại.</p></div><label className="form-check"><input className="form-check-input" checked={autoSyncRefresh} onChange={event => setAutoSyncRefresh(event.target.checked)} type="checkbox" /> <span className="form-check-label">Tự tải lại 30s</span></label></div><div className="table-responsive mt-3" style={{ overflowX: 'auto' }}><table className="table table-hover align-middle mb-0" style={{ minWidth: '1650px' }}><thead><tr><th>Run</th><th>Job</th><th>Command</th><th>Loại</th><th>Trạng thái</th><th>Node</th><th>Command / blocker</th><th>Payload hash</th><th>Lịch chạy</th><th>Bắt đầu</th><th>Kết thúc</th><th>Giải thích</th></tr></thead><tbody>{displayedHistoryRuns.map(run => { const blocked = run.status === 'skipped' && run.skipReason === 'active_command_exists' && run.commandId != null; return <tr key={run.runId}><td>{run.runId}</td><td>{run.jobName}</td><td>{run.commandType ?? '-'}</td><td>{run.runKind ?? '-'}</td><td>{pill(run.status, run.status === 'succeeded' ? 'success' : run.status === 'failed' ? 'danger' : 'warning')}</td><td>{run.targetCount ? `${run.succeededCount ?? 0}/${run.targetCount}` : '-'}</td><td>{blocked ? `Bị chặn bởi #${run.commandId}` : run.commandId ?? '-'}</td><td><code>{run.payloadHash ?? '-'}</code></td><td>{date(run.scheduledFor)}</td><td>{date(run.startedAt)}</td><td>{date(run.finishedAt)}</td><td className="text-break" style={{ minWidth: '20rem', maxWidth: '28rem' }} title={run.errorMessage ?? run.resultMessage}>{run.errorMessage ?? run.resultMessage ?? '-'}</td></tr> })}{!historyRuns.length && <tr><td className="text-center text-body-secondary py-4" colSpan={12}>Chưa có lịch sử chạy.</td></tr>}</tbody></table></div>{historyRuns.length > 0 && <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 mt-3"><small className="text-body-secondary">Hiển thị {(displayedHistoryPage - 1) * historyPageSize + 1}-{Math.min(displayedHistoryPage * historyPageSize, historyRuns.length)} / {historyRuns.length} bản ghi mới nhất.</small><nav aria-label="Phân trang lịch sử Auto Sync"><ul className="pagination pagination-sm mb-0"><li className={`page-item ${displayedHistoryPage === 1 ? 'disabled' : ''}`}><button className="page-link" disabled={displayedHistoryPage === 1} onClick={() => setHistoryPage(page => Math.max(1, page - 1))} type="button">Trước</button></li>{Array.from({ length: historyPageCount }, (_, index) => index + 1).map(page => <li className={`page-item ${page === displayedHistoryPage ? 'active' : ''}`} key={page}><button className="page-link" onClick={() => setHistoryPage(page)} type="button">{page}</button></li>)}<li className={`page-item ${displayedHistoryPage === historyPageCount ? 'disabled' : ''}`}><button className="page-link" disabled={displayedHistoryPage === historyPageCount} onClick={() => setHistoryPage(page => Math.min(historyPageCount, page + 1))} type="button">Sau</button></li></ul></nav></div>}</div></section></>}
    {portalTarget && command && createPortal(<Modal centered footer={<><button className="btn btn-light" data-bs-dismiss="modal" type="button">Hủy</button><button className="btn btn-danger" data-bs-dismiss="modal" disabled={busy || !canAbort} onClick={() => void abort()} type="button"><i className="bi bi-x-octagon me-1" />Hủy command</button></>} id="cluster-abort-confirm" title="Xác nhận hủy command"><div className="d-flex gap-3 align-items-start"><i className="bi bi-exclamation-triangle-fill text-warning fs-3" /><div><p className="mb-1">Bạn có chắc muốn hủy command #{command.commandId}?</p><small className="text-muted">Các node chưa apply sẽ xóa staging; node đã apply sẽ khôi phục snapshot trước đó.</small></div></div></Modal>, portalTarget)}
    <div className="toast-container position-fixed bottom-0 end-0 p-3"><Toast autohideMs={5000} onClose={() => setToast(undefined)} show={Boolean(toast)} theme={toast?.theme} title={toast?.theme === 'success' ? 'Thành công' : toast?.theme === 'danger' ? 'Thao tác thất bại' : 'Thông báo'}>{toast?.message ?? ''}</Toast></div>
  </div>
}

function EndpointTable({ endpoints, currentTimeMs, autoRefresh, onAutoRefresh, showAll, onShowAll }: { endpoints: Endpoint[]; currentTimeMs: number; autoRefresh?: boolean; onAutoRefresh?: (value: boolean) => void; showAll?: boolean; onShowAll?: (value: boolean) => void }) {
  const controls = autoRefresh != null && onAutoRefresh && showAll != null && onShowAll
  return <section className="card card-secondary card-outline shadow-sm"><div className="card-body"><div className="d-flex flex-wrap justify-content-between gap-2 mb-3"><div><h2 className="h5 mb-1">{controls ? 'Runtime Endpoints' : 'Target Nodes'}</h2><p className="small text-body-secondary mb-0">{controls ? `Đang hiển thị ${endpoints.length} node runtime.` : `Toàn bộ ${endpoints.length} node active, healthy, fresh là target bắt buộc để switch đồng bộ.`}</p></div>{controls && <div className="d-flex flex-wrap gap-3"><label className="form-check"><input className="form-check-input" checked={autoRefresh} onChange={event => onAutoRefresh(event.target.checked)} type="checkbox" /> <span className="form-check-label">Tự tải lại 10s</span></label><label className="form-check"><input className="form-check-input" checked={showAll} onChange={event => onShowAll(event.target.checked)} type="checkbox" /> <span className="form-check-label">Hiển thị node unhealthy / stale</span></label></div>}</div><div className="table-responsive"><table className="table table-hover align-middle mb-0"><thead><tr><th>Record code</th><th>Service</th><th>Instance</th><th>Host</th><th>Kênh kết nối</th><th>Trạng thái</th><th>Health</th><th>Fresh</th><th>Target mặc định</th><th>Seen age</th><th>Last seen</th><th>Cập nhật</th></tr></thead><tbody>{endpoints.map(item => { const health = effectiveHealth(item, currentTimeMs); return <tr key={item.recordCode}><td>{item.recordCode}</td><td>{item.serviceName}</td><td>{item.instanceId}</td><td>{item.hostName ?? item.instanceId ?? '-'}</td><td>{item.commandChannelMode ?? 'DB poll'}</td><td>{pill(item.status, item.status === 'active' ? 'success' : 'warning')}</td><td title={health !== item.healthStatus ? `DB health=${item.healthStatus}; Last seen ${seenAge(item.lastSeenAt, currentTimeMs)} trước` : undefined}>{pill(health, health === 'healthy' ? 'success' : health === 'warning' ? 'warning' : 'danger')}</td><td>{pill(item.isFresh ? 'có' : 'không', item.isFresh ? 'success' : 'warning')}</td><td>{pill(item.isDefaultTarget ? 'có' : 'không', item.isDefaultTarget ? 'success' : 'secondary')}</td><td>{seenAge(item.lastSeenAt, currentTimeMs)}</td><td>{date(item.lastSeenAt)}</td><td>{date(item.updatedAt)}</td></tr> })}{!endpoints.length && <tr><td className="text-center text-body-secondary py-4" colSpan={12}>Chưa có runtime endpoint phù hợp.</td></tr>}</tbody></table></div></div></section>
}

function AutoJobRow({ job, activeCommand, busy, onUpdate, onRun }: { job: AutoJob; activeCommand?: Command; busy: boolean; onUpdate: (job: AutoJob, enabled: boolean, intervalSeconds: number) => void; onRun: (job: AutoJob, intervalSeconds?: number) => void }) {
  const [interval, setInterval] = useState(job.intervalSeconds)
  useEffect(() => setInterval(job.intervalSeconds), [job.intervalSeconds])
  const normalizedInterval = () => {
    const value = Math.max(30, Math.round(interval || job.intervalSeconds))
    setInterval(value)
    return value
  }
  const saveInterval = () => {
    const value = normalizedInterval()
    if (value !== job.intervalSeconds) onUpdate(job, job.enabled, value)
  }
  const runNow = () => {
    const value = normalizedInterval()
    onRun(job, value === job.intervalSeconds ? undefined : value)
  }
  const leased = Boolean(job.leaseUntil && new Date(job.leaseUntil).getTime() > Date.now())
  const blocked = Boolean(activeCommand)
  return <tr><td>{job.jobName}</td><td>{job.commandType}</td><td>{pill(job.enabled ? 'bật' : 'không', job.enabled ? 'success' : 'danger')}</td><td><div className="input-group input-group-sm" style={{ minWidth: '8rem' }}><input aria-label={`Chu kỳ ${job.jobName}`} className="form-control" min={30} onBlur={event => { if (!(event.relatedTarget instanceof HTMLElement && event.relatedTarget.closest('[data-auto-sync-action]'))) saveInterval() }} onChange={event => setInterval(Number(event.target.value))} type="number" value={interval} /><span className="input-group-text">giây</span></div></td><td>{date(job.nextRunAt)}</td><td>{pill(job.lastStatus ?? '-', job.lastStatus === 'succeeded' ? 'success' : job.lastStatus === 'failed' ? 'danger' : 'warning')}</td><td>{job.lastStartedCommandId ?? '-'}</td><td>{date(job.lastCompletedAt)}</td><td><div className="d-flex gap-2"><button className={`btn btn-sm ${job.enabled ? 'btn-outline-secondary' : 'btn-outline-success'}`} data-auto-sync-action disabled={busy || leased} onClick={() => onUpdate(job, !job.enabled, interval)} type="button"><i className={`bi ${job.enabled ? 'bi-pause' : 'bi-check-lg'} me-1`} />{job.enabled ? 'TẮT' : 'BẬT'}</button><button className="btn btn-sm btn-primary" data-auto-sync-action disabled={busy || leased || blocked} onClick={runNow} title={blocked ? `Bị chặn bởi command #${activeCommand?.commandId} (${activeCommand?.phase}/${activeCommand?.status}).` : leased ? 'Job đang chạy; chờ lease kết thúc.' : undefined} type="button"><i className={`bi ${leased || blocked ? 'bi-hourglass-split' : 'bi-person-running'} me-1`} />{blocked ? `BỊ CHẶN #${activeCommand?.commandId}` : leased ? 'ĐANG CHẠY' : 'CHẠY NGAY'}</button></div></td></tr>
}
