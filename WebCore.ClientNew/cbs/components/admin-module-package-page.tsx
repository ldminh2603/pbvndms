'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { getCurrentUser } from '@/lib/webcore-auth'
import { webCoreApi } from '@/lib/webcore-api'

type Module = { moduleId: string; moduleName?: string; moduleType: string; status: string; risk?: string }
type PackageResult = { packageId: string; fileCount: number; warnings: string[] }

export function AdminModulePackagePage() {
  const router = useRouter(); const [allowed, setAllowed] = useState<boolean>(); const [modules, setModules] = useState<Module[]>([]); const [selected, setSelected] = useState<string[]>([]); const [busy, setBusy] = useState(false); const [message, setMessage] = useState<string>()
  const base = '/module/s001/modulepackage'
  const load = async () => { try { const data = await webCoreApi<{ modules: Module[] }>(`${base}/export/modules`); setModules(data.modules ?? []) } catch (error) { setMessage(error instanceof Error ? error.message : 'Không tải được danh sách module.') } }
  useEffect(() => { void getCurrentUser().then(async response => { const user = response.ok ? await response.json() as { isPrivilegedAccount?: boolean } : null; setAllowed(user?.isPrivilegedAccount === true); if (user?.isPrivilegedAccount) void load() }).catch(() => setAllowed(false)) }, [])
  if (allowed === false) { router.replace('/'); return null }
  const toggle = (id: string) => setSelected(current => current.includes(id) ? current.filter(item => item !== id) : [...current, id])
  const exportPackage = async () => { if (!selected.length) return setMessage('Chọn ít nhất một module để export.'); setBusy(true); setMessage(undefined); try { const result = await webCoreApi<PackageResult>(`${base}/export/packages`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ selectedModuleIds: selected, exportMode: 'Full' }) }); window.open(`/api/webcore-api${base}/export/packages/${encodeURIComponent(result.packageId)}/zip`, '_blank', 'noopener'); setMessage(`Đã tạo package ${result.packageId} (${result.fileCount} file).`) } catch (error) { setMessage(error instanceof Error ? error.message : 'Không tạo được package export.') } finally { setBusy(false) } }
  return <div className="container-fluid py-3 admin-page"><section className="card shadow-sm mb-3"><div className="card-body d-flex flex-wrap justify-content-between gap-3"><div><h1 className="h4 mb-1">Import/Export module</h1><p className="text-body-secondary mb-0">Chọn module, tạo package ZIP gồm metadata và dependency để triển khai sang môi trường khác.</p></div><button className="btn btn-primary" disabled={busy} onClick={() => void exportPackage()}><i className="bi bi-box-arrow-up me-1" />TẠO PACKAGE EXPORT</button></div></section>{message && <div className="alert alert-info">{message}</div>}<section className="card shadow-sm"><div className="card-body"><div className="table-responsive"><table className="table table-hover align-middle"><thead><tr><th></th><th>Module</th><th>Tên</th><th>Loại</th><th>Trạng thái</th><th>Risk</th></tr></thead><tbody>{modules.map(module => <tr key={module.moduleId}><td><input className="form-check-input" type="checkbox" checked={selected.includes(module.moduleId)} onChange={() => toggle(module.moduleId)} /></td><td>{module.moduleId}</td><td>{module.moduleName ?? '-'}</td><td>{module.moduleType}</td><td>{module.status}</td><td>{module.risk ?? '-'}</td></tr>)}{!modules.length && <tr><td colSpan={6} className="text-center text-body-secondary py-4">Chưa có module exportable hoặc API export chưa được bật.</td></tr>}</tbody></table></div></div></section></div>
}
