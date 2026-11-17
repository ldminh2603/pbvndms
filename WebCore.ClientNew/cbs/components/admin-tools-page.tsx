'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { Modal, Toast } from '@adminlte/react'
import { useRouter } from 'next/navigation'
import { getCurrentUser } from '@/lib/webcore-auth'
import { webCoreApi } from '@/lib/webcore-api'

type TabKey = 'data-sources' | 'quick-actions' | 'shortcuts' | 'system-info'
type Source = { sourceName: string; dataSourceType: string; sourceKind: string; status: string; isHealthy: boolean; phase: string; phaseCode: number; message: string; checkedAtUtc?: string; elapsedMs?: number; errorCode?: string; errorMessage?: string; stackTrace?: string }
type SourceStatus = { summary: { total: number; healthy: number; unhealthy: number; skipped: number }; sources: Source[] }
type CurrentUser = { displayName?: string; email?: string; isPrivilegedAccount?: boolean; IsPrivilegedAccount?: boolean; components?: string[] }
type ToastState = { message: string; theme: 'success' | 'danger' | 'info' }

const tabs: Array<{ key: TabKey; title: string; caption: string }> = [
  { key: 'data-sources', title: 'Nguồn dữ liệu', caption: 'trạng thái startup' },
  { key: 'quick-actions', title: 'Thao tác nhanh', caption: 'điều khiển core' },
  { key: 'shortcuts', title: 'Lối tắt', caption: 'màn hình admin' },
  { key: 'system-info', title: 'Thông tin hệ thống', caption: 'ngữ cảnh runtime' },
]

function formatDate(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('vi-VN')
}

function isPrivilegedAccount(user: CurrentUser | undefined) {
  return user?.isPrivilegedAccount === true || user?.IsPrivilegedAccount === true
}

export function AdminToolsPage() {
  const router = useRouter()
  const [allowed, setAllowed] = useState<boolean>()
  const [user, setUser] = useState<CurrentUser>()
  const [activeTab, setActiveTab] = useState<TabKey>('data-sources')
  const [sourceStatus, setSourceStatus] = useState<SourceStatus>()
  const [showAllSources, setShowAllSources] = useState(true)
  const [loading, setLoading] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [pendingReload, setPendingReload] = useState<'core' | 'role'>('core')
  const [selectedError, setSelectedError] = useState<Source>()
  const [toast, setToast] = useState<ToastState>()
  const [portalTarget, setPortalTarget] = useState<HTMLElement | null>(null)

  const notify = (message: string, theme: ToastState['theme']) => setToast({ message, theme })
  const loadSources = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try { setSourceStatus(await webCoreApi<SourceStatus>('/admin/tools/data-sources')) }
    catch (error) { notify(error instanceof Error ? error.message : 'Không tải được trạng thái nguồn dữ liệu.', 'danger') }
    finally { if (!silent) setLoading(false) }
  }, [])

  useEffect(() => {
    setPortalTarget(document.body)
    void getCurrentUser().then(async response => {
      const currentUser = response.ok ? await response.json() as CurrentUser : undefined
      setUser(currentUser); setAllowed(isPrivilegedAccount(currentUser))
      if (isPrivilegedAccount(currentUser)) void loadSources()
    }).catch(() => setAllowed(false))
  }, [loadSources])

  const reload = async () => {
    setReloading(true)
    try {
      const result = await webCoreApi<{ message?: string }>(`/admin/tools/reload-${pendingReload}`)
      notify(result.message ?? `Reload ${pendingReload} thành công.`, 'success')
      if (pendingReload === 'core') void loadSources(true)
    } catch (error) { notify(error instanceof Error ? error.message : `Reload ${pendingReload} thất bại.`, 'danger') }
    finally { setReloading(false) }
  }

  const visibleSources = useMemo(() => showAllSources ? sourceStatus?.sources ?? [] : (sourceStatus?.sources ?? []).filter(item => item.isHealthy), [showAllSources, sourceStatus])
  const summary = sourceStatus?.summary ?? { total: 0, healthy: 0, unhealthy: 0, skipped: 0 }
  if (allowed === false) { router.replace('/'); return null }

  const shortcuts = [
    ['Chọn component', 'Đi đến màn hình chọn component.', 'MỞ CHỌN COMPONENT', '/component', 'bi-ui-checks-grid'],
    ['Trình xem log', 'Xem log hiện tại và tải file gốc.', 'MỞ TRÌNH XEM LOG', '/admin/log-viewer', 'bi-file-earmark-text'],
    ['Quản lý Cluster', 'Theo dõi endpoint và điều phối reload node.', 'MỞ QUẢN LÝ CLUSTER', '/admin/cluster', 'bi-diagram-3'],
    ['Import/Export module', 'Tạo package ZIP hoặc nạp package triển khai.', 'MỞ IMPORT/EXPORT', '/admin/module-package', 'bi-box-seam'],
    ['Dashboard', 'Mở trang dashboard theo ngữ cảnh hiện tại.', 'MỞ DASHBOARD', '/', 'bi-speedometer2'],
    ['Danh sách module mặc định', 'Mở danh sách module mặc định của ứng dụng.', 'MỞ DANH SÁCH MODULE', '/list/L091', 'bi-list-ul'],
  ] as const

  const modal = portalTarget && createPortal(<>
    <Modal centered id="admin-tools-reload-confirm" title={`Xác nhận reload ${pendingReload}`} footer={<><button className="btn btn-light" data-bs-dismiss="modal" type="button">Hủy</button><button className="btn btn-primary" data-bs-dismiss="modal" disabled={reloading} onClick={() => void reload()} type="button"><i className="bi bi-arrow-repeat me-1" />Reload ngay</button></>}><div className="d-flex align-items-start gap-3"><i className="bi bi-exclamation-triangle-fill text-warning fs-3" /><div><p className="mb-1">Bạn có chắc muốn reload {pendingReload} ngay bây giờ không?</p><small className="text-body-secondary">Dữ liệu runtime đang nạp sẽ được làm mới.</small></div></div></Modal>
    {selectedError && <Modal centered id="admin-tools-error-detail" size="lg" title={`Chi tiết lỗi: ${selectedError.sourceName}`} footer={<button className="btn btn-secondary" data-bs-dismiss="modal" type="button">Đóng</button>}><dl className="row mb-0"><dt className="col-sm-3">Nguồn</dt><dd className="col-sm-9">{selectedError.sourceName}</dd><dt className="col-sm-3">Mã lỗi</dt><dd className="col-sm-9">{selectedError.errorCode ?? '-'}</dd><dt className="col-sm-3">Thông điệp</dt><dd className="col-sm-9 text-break">{selectedError.errorMessage ?? selectedError.message}</dd><dt className="col-sm-3">Chi tiết</dt><dd className="col-sm-9"><pre className="mb-0 p-2 bg-body-tertiary border rounded text-break text-wrap">{selectedError.stackTrace ?? JSON.stringify(selectedError, null, 2)}</pre></dd></dl></Modal>}
  </>, portalTarget)

  return <div className="container-fluid py-3 admin-page">
    <section className="card card-primary card-outline shadow-sm mb-3"><div className="card-body d-flex flex-wrap justify-content-between align-items-start gap-3"><div><div className="d-flex align-items-center gap-2"><span className="admin-tools-header-icon"><i className="bi bi-tools" /></span><h1 className="h4 mb-0">Công cụ Admin</h1></div><p className="mb-0 mt-2 text-body-secondary">Không gian đặc quyền để reload core, theo dõi trạng thái nguồn dữ liệu và mở nhanh công cụ vận hành.</p></div><button className="btn btn-outline-primary" disabled={loading} onClick={() => void loadSources()} type="button"><i className="bi bi-arrow-clockwise me-1" />TẢI LẠI NGUỒN</button></div></section>
    <section className="card shadow-sm mb-3 admin-tools-tab-card"><div className="card-body py-2"><div className="nav nav-pills gap-1" role="tablist">{tabs.map(tab => <button className={`nav-link text-start ${activeTab === tab.key ? 'active' : ''}`} key={tab.key} onClick={() => setActiveTab(tab.key)} type="button"><span className="d-block fw-semibold">{tab.title}</span><small>{tab.caption}</small></button>)}</div></div></section>
    {activeTab === 'data-sources' && <><section className="card card-info card-outline shadow-sm mb-3"><div className="card-body"><h2 className="h5 mb-1">Trạng thái nguồn dữ liệu</h2><p className="text-body-secondary small">Kết quả probe khi khởi động được lưu trong CoreData. Lỗi SQL/API nghiệp vụ được ghi tại đây và không chặn core load.</p><div className="row">{[['Tổng', summary.total, 'primary', 'bi-database'], ['Sẵn sàng', summary.healthy, 'success', 'bi-check-circle'], ['Lỗi', summary.unhealthy, 'danger', 'bi-exclamation-octagon'], ['Bỏ qua', summary.skipped, 'warning', 'bi-skip-forward']].map(([label, value, color, icon]) => <div className="col-12 col-sm-6 col-xl-3" key={String(label)}><div className="info-box shadow-sm"><span className={`info-box-icon text-bg-${color}`}><i className={`bi ${icon}`} /></span><div className="info-box-content"><span className="info-box-text">{label}</span><span className="info-box-number">{value}</span></div></div></div>)}</div></div></section><section className="card card-secondary card-outline shadow-sm"><div className="card-body"><div className="d-flex flex-wrap justify-content-between gap-2 mb-3"><div><h2 className="h5 mb-1">Nguồn đã cấu hình</h2><p className="small text-body-secondary mb-0">Đang hiển thị {visibleSources.length}/{summary.total} nguồn.</p></div><label className="form-check"><input className="form-check-input" checked={showAllSources} onChange={event => setShowAllSources(event.target.checked)} type="checkbox" /> <span className="form-check-label">Hiển thị nguồn lỗi / bỏ qua</span></label></div><div className="table-responsive"><table className="table table-hover align-middle mb-0"><thead><tr><th>Nguồn</th><th>Loại</th><th>Nhóm</th><th>Trạng thái</th><th>Pha</th><th>Thông điệp</th><th>Chi tiết lỗi</th><th>Thời điểm kiểm tra</th><th>Thời gian</th></tr></thead><tbody>{visibleSources.map(source => <tr key={source.sourceName}><td>{source.sourceName}</td><td>{source.dataSourceType}</td><td>{source.sourceKind}</td><td><span className={`badge rounded-pill text-bg-${source.isHealthy ? 'success' : source.status.toLowerCase() === 'skipped' ? 'warning' : 'danger'}`}>{source.isHealthy ? 'Sẵn sàng' : source.status.toLowerCase() === 'skipped' ? 'Bỏ qua' : 'Lỗi'}</span></td><td>{source.phaseCode} - {source.phase}</td><td>{source.message}</td><td>{source.isHealthy ? <span className="text-body-tertiary">-</span> : <button className="btn btn-link btn-sm p-0" data-bs-target="#admin-tools-error-detail" data-bs-toggle="modal" onClick={() => setSelectedError(source)} type="button"><i className="bi bi-info-circle me-1" />XEM</button>}</td><td>{formatDate(source.checkedAtUtc)}</td><td>{source.elapsedMs == null ? '-' : `${Math.round(source.elapsedMs)} ms`}</td></tr>)}{!loading && !visibleSources.length && <tr><td className="text-center text-body-secondary py-4" colSpan={9}>Chưa có trạng thái nguồn dữ liệu.</td></tr>}</tbody></table></div></div></section></>}
    {activeTab === 'quick-actions' && <section className="card card-primary card-outline shadow-sm"><div className="card-body d-flex flex-wrap justify-content-between align-items-center gap-3"><div><h2 className="h5 mb-1">Thao tác nhanh</h2><p className="mb-0 text-body-secondary">Chạy thao tác nhạy cảm từ không gian quản trị riêng.</p></div><div className="d-flex flex-wrap gap-2"><button className="btn btn-primary" data-bs-target="#admin-tools-reload-confirm" data-bs-toggle="modal" disabled={reloading} onClick={() => setPendingReload('core')} type="button"><i className="bi bi-arrow-repeat me-1" />RELOAD CORE</button><button className="btn btn-primary" data-bs-target="#admin-tools-reload-confirm" data-bs-toggle="modal" disabled={reloading} onClick={() => setPendingReload('role')} type="button"><i className="bi bi-people me-1" />RELOAD ROLE</button><button className="btn btn-warning" onClick={() => router.push('/admin/cluster')} type="button"><i className="bi bi-diagram-3 me-1" />QUẢN LÝ CLUSTER</button></div></div></section>}
    {activeTab === 'shortcuts' && <section className="card card-info card-outline shadow-sm"><div className="card-body"><h2 className="h5 mb-1">Màn hình đặc biệt</h2><p className="text-body-secondary small">Lối tắt đến các công cụ thường dùng khi vận hành và xử lý sự cố.</p><div className="row g-3">{shortcuts.map(([title, description, label, path, icon]) => <div className="col-md-6 col-xl-4" key={title}><article className="border rounded shadow-sm h-100 p-3 d-flex flex-column admin-tools-shortcut"><div className="fs-4 text-primary mb-2"><i className={`bi ${icon}`} /></div><h3 className="h6">{title}</h3><p className="small text-body-secondary flex-grow-1">{description}</p><button className="btn btn-outline-primary btn-sm align-self-start" onClick={() => router.push(path)} type="button">{label}</button></article></div>)}</div></div></section>}
    {activeTab === 'system-info' && <section className="card card-secondary card-outline shadow-sm"><div className="card-body"><h2 className="h5 mb-1">Thông tin hệ thống</h2><p className="text-body-secondary small">Thông tin runtime hữu ích khi chẩn đoán nhanh.</p><div className="row g-2">{[['Tên người dùng', user?.displayName || '-'], ['Email', user?.email || '-'], ['Component hiện tại', user?.components?.[0] || '-'], ['Route hiện tại', typeof window === 'undefined' ? '-' : window.location.pathname], ['Ứng dụng', 'Web Core'], ['Ngôn ngữ trình duyệt', typeof navigator === 'undefined' ? '-' : navigator.language], ['Múi giờ', Intl.DateTimeFormat().resolvedOptions().timeZone], ['Quyền đặc biệt', user?.isPrivilegedAccount ? 'Có' : 'Không']].map(([label, value]) => <div className="col-sm-6 col-lg-4 col-xl-3" key={label}><div className="info-box shadow-sm mb-0 admin-tools-system-info"><span className="info-box-icon text-bg-light"><i className="bi bi-info-circle" /></span><div className="info-box-content"><span className="info-box-text">{label}</span><span className="info-box-number text-break fs-6">{value}</span></div></div></div>)}</div></div></section>}
    {modal}<div className="toast-container position-fixed bottom-0 end-0 p-3"><Toast autohideMs={5000} onClose={() => setToast(undefined)} show={Boolean(toast)} theme={toast?.theme} title={toast?.theme === 'success' ? 'Thành công' : toast?.theme === 'danger' ? 'Thao tác thất bại' : 'Thông báo'}>{toast?.message ?? ''}</Toast></div>
  </div>
}
