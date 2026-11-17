'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AppContent, Toast } from '@adminlte/react'
import { webCoreApi } from '@/lib/webcore-api'

type Item = { key: string; path: string; name: string; isDirectory: boolean; size?: number; dateModified?: string }
type Ui = { moduleId: string; moduleName: string; configUi?: { fileManager?: { allowedFileExtensions?: string[] } }; permission?: { operations?: string[]; isPrivilegedAccount?: boolean } }
type ToastState = { message: string; theme: 'danger' | 'success' | 'warning' }
type Saved = { starred: string[]; recent: string[]; shared: string[] }

const base = (id: string) => `/upload/${encodeURIComponent(id)}`
const has = (ui: Ui | undefined, operation: string) => ui?.permission?.isPrivilegedAccount || ui?.permission?.operations?.some(value => value === '*' || value.toLowerCase() === operation.toLowerCase())
const folderParent = (path: string) => path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
const formatSize = (size?: number) => !size ? '—' : new Intl.NumberFormat(undefined, { style: 'unit', unit: 'byte', unitDisplay: 'narrow', notation: size >= 1024 ? 'compact' : 'standard' }).format(size)
const formatDate = (value?: string) => value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
const icon = (item: Item) => item.isDirectory ? 'bi-folder-fill text-warning' : item.name.endsWith('.pdf') ? 'bi-file-earmark-pdf-fill text-danger' : /\.(png|jpg|jpeg|gif|webp)$/i.test(item.name) ? 'bi-file-earmark-image-fill text-primary' : /\.(xlsx|xls|csv)$/i.test(item.name) ? 'bi-file-earmark-spreadsheet-fill text-success' : /\.(doc|docx)$/i.test(item.name) ? 'bi-file-earmark-word-fill text-info' : 'bi-file-earmark-fill text-secondary'

export function RuntimeDriveModule({ moduleId }: { moduleId: string }) {
  const [ui, setUi] = useState<Ui>()
  const [items, setItems] = useState<Item[]>([])
  const [rootFolders, setRootFolders] = useState<Item[]>([])
  const [path, setPath] = useState('')
  const [view, setView] = useState<'grid' | 'list'>('list')
  const [section, setSection] = useState<'drive' | 'shared' | 'starred' | 'recent' | 'trash'>('drive')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState<ToastState>()
  const [saved, setSaved] = useState<Saved>({ starred: [], recent: [], shared: [] })
  const uploadRef = useRef<HTMLInputElement>(null)
  const storeKey = `webcore.drive.${moduleId}`
  const showToast = (message: string, theme: ToastState['theme'] = 'danger') => setToast({ message, theme })
  const api = useCallback(<T,>(suffix: string, init?: RequestInit) => webCoreApi<T>(`${base(moduleId)}${suffix}`, init), [moduleId])
  const post = useCallback((suffix: string, body: unknown) => api<Record<string, unknown>>(suffix, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }), [api])

  const persist = useCallback((next: Saved) => { setSaved(next); localStorage.setItem(storeKey, JSON.stringify(next)) }, [storeKey])
  const load = useCallback(async (nextPath = path) => {
    setLoading(true)
    try {
      const result = await api<{ items: Item[] }>(`/files?path=${encodeURIComponent(nextPath)}`)
      setItems(result.items)
      if (!nextPath) setRootFolders(result.items.filter(item => item.isDirectory && !item.path.startsWith('.trash')))
    } catch (error) { showToast(error instanceof Error ? error.message : 'Cannot load files.') } finally { setLoading(false) }
  }, [api, path])

  useEffect(() => {
    const raw = localStorage.getItem(storeKey)
    if (raw) try { const previous = JSON.parse(raw) as Partial<Saved>; setSaved({ starred: previous.starred ?? [], recent: previous.recent ?? [], shared: previous.shared ?? [] }) } catch { localStorage.removeItem(storeKey) }
    setPath(''); setSection('drive')
    void api<Ui>('/ui').then(setUi).catch(error => showToast(error instanceof Error ? error.message : 'Cannot load module.')).finally(() => setLoading(false))
  }, [api, storeKey])
  useEffect(() => { if (ui) void load(path) }, [ui, path, load])

  const shown = useMemo(() => {
    const term = query.trim().toLowerCase()
    const source = section === 'shared' ? items.filter(item => saved.shared.includes(item.path)) : section === 'starred' ? items.filter(item => saved.starred.includes(item.path)) : section === 'recent' ? items.filter(item => saved.recent.includes(item.path)) : items
    return source.filter(item => (section === 'trash' || !item.path.startsWith('.trash')) && (!term || item.name.toLowerCase().includes(term)))
  }, [items, query, saved, section])
  const open = (item: Item) => {
    if (!item.isDirectory) return void download(item)
    setSection('drive'); setPath(item.path); persist({ ...saved, recent: [item.path, ...saved.recent.filter(value => value !== item.path)].slice(0, 30) })
  }
  const download = async (item: Item) => {
    try { const result = await api<{ url: string }>(`/files/download-url?path=${encodeURIComponent(item.path)}`); window.open(result.url, '_blank', 'noopener'); persist({ ...saved, recent: [item.path, ...saved.recent.filter(value => value !== item.path)].slice(0, 30) }) }
    catch (error) { showToast(error instanceof Error ? error.message : 'Cannot download file.') }
  }
  const share = async (item: Item) => {
    try { const result = await api<{ url: string }>(`/files/download-url?path=${encodeURIComponent(item.path)}`); await navigator.clipboard.writeText(result.url); persist({ ...saved, shared: saved.shared.includes(item.path) ? saved.shared : [...saved.shared, item.path] }); showToast('Temporary download link copied.', 'success') }
    catch (error) { showToast(error instanceof Error ? error.message : 'Cannot create share link.') }
  }
  const remove = async (item: Item) => {
    if (!window.confirm(`Move “${item.name}” to Trash?`)) return
    try { await post('/items/move', { sourcePath: item.path, destinationParentPath: '.trash', isDirectory: item.isDirectory, overwrite: false }); showToast('Moved to Trash.', 'success'); void load() }
    catch (error) { showToast(error instanceof Error ? error.message : 'Cannot move item to Trash.') }
  }
  const createFolder = async () => {
    const name = window.prompt('Folder name')?.trim(); if (!name) return
    try { await post('/folders', { parentPath: path, name }); showToast('Folder created.', 'success'); void load() }
    catch (error) { showToast(error instanceof Error ? error.message : 'Cannot create folder.') }
  }
  const upload = async (files: FileList | null) => {
    if (!files?.length) return
    try {
      for (const file of Array.from(files)) {
        const session = await post('/uploads/init', { targetDirectoryPath: path, files: [{ clientFileName: file.name, fileSize: file.size, contentType: file.type || undefined }] }) as unknown as { uploadId: string; files: Array<{ url: string; uploadObjectKey: string; headers?: Record<string, string> }> }
        const target = session.files[0]; const response = await fetch(target.url, { method: 'PUT', body: file, headers: { 'Content-Type': file.type || 'application/octet-stream', ...(target.headers ?? {}) } })
        if (!response.ok) throw new Error(`Storage upload failed (${response.status}).`)
        await post('/uploads/complete', { uploadId: session.uploadId, files: [{ clientFileName: file.name, uploadObjectKey: target.uploadObjectKey }] })
      }
      showToast('Upload completed.', 'success'); void load()
    } catch (error) { showToast(error instanceof Error ? error.message : 'Upload failed.') } finally { if (uploadRef.current) uploadRef.current.value = '' }
  }
  const toggleStar = (item: Item) => persist({ ...saved, starred: saved.starred.includes(item.path) ? saved.starred.filter(value => value !== item.path) : [...saved.starred, item.path] })
  const navigate = (nextPath: string) => { setSection('drive'); setPath(nextPath) }

  if (!ui && loading) return <div className="p-4">Loading File Manager…</div>
  if (!ui) return <AppContent><div className="alert alert-danger">Unable to load U002.</div></AppContent>

  return <AppContent title={ui.moduleName} breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Files' }]}>
    <input ref={uploadRef} type="file" multiple hidden accept={ui.configUi?.fileManager?.allowedFileExtensions?.join(',')} onChange={event => void upload(event.target.files)} />
    <div className="row g-3"><aside className="col-lg-3"><div className="d-grid gap-2 mb-3"><button className="btn btn-primary" disabled={!has(ui, 'Upload')} onClick={() => uploadRef.current?.click()}><i className="bi bi-cloud-upload me-1" />Upload files</button><button className="btn btn-outline-secondary" disabled={!has(ui, 'Upload')} onClick={() => void createFolder()}><i className="bi bi-folder-plus me-1" />New folder</button></div>
      <div className="card"><div className="list-group list-group-flush"><button className={`list-group-item list-group-item-action text-start d-flex justify-content-between ${section === 'drive' && !path ? 'active' : ''}`} onClick={() => navigate('')}><span><i className="bi bi-house me-2" />My Drive</span><small>{rootFolders.length}</small></button>{rootFolders.map(folder => <button key={folder.path} className={`list-group-item list-group-item-action text-start ps-4 ${path === folder.path ? 'active' : ''}`} onClick={() => navigate(folder.path)}><i className="bi bi-folder me-2" />{folder.name}</button>)}
        <button className={`list-group-item list-group-item-action text-start ${section === 'shared' ? 'active' : ''}`} onClick={() => { setSection('shared'); setPath('') }}><i className="bi bi-people me-2" />Shared with me <small className="float-end">{saved.shared.length}</small></button><button className={`list-group-item list-group-item-action text-start ${section === 'starred' ? 'active' : ''}`} onClick={() => { setSection('starred'); setPath('') }}><i className="bi bi-star me-2" />Starred <small className="float-end">{saved.starred.length}</small></button><button className={`list-group-item list-group-item-action text-start ${section === 'recent' ? 'active' : ''}`} onClick={() => { setSection('recent'); setPath('') }}><i className="bi bi-clock-history me-2" />Recent</button><button className={`list-group-item list-group-item-action text-start ${section === 'trash' ? 'active' : ''}`} onClick={() => { setSection('trash'); setPath('.trash') }}><i className="bi bi-trash me-2" />Trash</button></div></div>
      <div className="card mt-3"><div className="card-body"><p className="fw-semibold mb-2 small"><i className="bi bi-cloud me-1" />Storage</p><div className="progress mb-2" style={{ height: 8 }}><div className="progress-bar" style={{ width: '8%' }} /></div><small className="text-secondary">S3 storage for {moduleId}</small></div></div>
    </aside><main className="col-lg-9"><section className="card"><div className="card-header d-flex flex-wrap gap-2 align-items-center"><nav className="flex-grow-1"><ol className="breadcrumb mb-0"><li className="breadcrumb-item"><button className="btn btn-link p-0" onClick={() => navigate('')}><i className="bi bi-house" /></button></li><li className="breadcrumb-item"><button className="btn btn-link p-0" onClick={() => navigate('')}>My Drive</button></li>{path.split('/').filter(Boolean).map((segment, index, all) => <li className="breadcrumb-item" key={`${segment}-${index}`}><button className="btn btn-link p-0" onClick={() => navigate(all.slice(0, index + 1).join('/'))}>{segment}</button></li>)}</ol></nav><div className="input-group input-group-sm" style={{ width: '14rem' }}><span className="input-group-text"><i className="bi bi-search" /></span><input className="form-control" placeholder="Search files…" value={query} onChange={event => setQuery(event.target.value)} /></div><div className="btn-group btn-group-sm"><button className={`btn btn-outline-secondary ${view === 'grid' ? 'active' : ''}`} onClick={() => setView('grid')}><i className="bi bi-grid-3x3-gap" /></button><button className={`btn btn-outline-secondary ${view === 'list' ? 'active' : ''}`} onClick={() => setView('list')}><i className="bi bi-list-ul" /></button></div></div>
      <div className="card-body">{loading ? <div className="text-center p-5 text-secondary">Loading…</div> : view === 'grid' ? <div className="row row-cols-2 row-cols-md-3 row-cols-xl-4 g-3">{shown.map(item => <div className="col" key={item.path}><button className="card text-center w-100 h-100 border text-body" onDoubleClick={() => open(item)}><div className="card-body"><i className={`bi ${icon(item)} display-5 mb-3`} /><p className="mb-1 text-break">{item.name}</p><small className="text-secondary">{item.isDirectory ? 'Folder' : formatSize(item.size)}</small></div></button></div>)}</div> : <div className="table-responsive"><table className="table align-middle mb-0"><thead><tr><th>Name</th><th>Size</th><th>Modified</th><th className="text-end">Actions</th></tr></thead><tbody>{shown.map(item => <tr key={item.path}><td><button className="btn btn-link text-start text-decoration-none text-body p-0" onClick={() => open(item)}><i className={`bi ${icon(item)} me-2`} />{item.name}</button></td><td>{item.isDirectory ? '—' : formatSize(item.size)}</td><td>{formatDate(item.dateModified)}</td><td className="text-end"><div className="btn-group btn-group-sm"><button className="btn btn-outline-secondary" title="Star" onClick={() => toggleStar(item)}><i className={`bi bi-star${saved.starred.includes(item.path) ? '-fill text-warning' : ''}`} /></button>{!item.isDirectory && <><button className="btn btn-outline-secondary" title="Download" onClick={() => void download(item)}><i className="bi bi-download" /></button><button className="btn btn-outline-secondary" title="Copy temporary link" onClick={() => void share(item)}><i className="bi bi-share" /></button></>}<button className="btn btn-outline-danger" title="Move to Trash" disabled={!has(ui, 'Move')} onClick={() => void remove(item)}><i className="bi bi-trash" /></button></div></td></tr>)}</tbody></table></div>}</div><div className="card-footer text-secondary small">{shown.length} items</div></section></main></div>
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}><Toast show={Boolean(toast)} autohideMs={6000} onClose={() => setToast(undefined)} theme={toast?.theme} title={toast?.theme === 'success' ? 'Success' : toast?.theme === 'warning' ? 'Notice' : 'Error'}>{toast?.message ?? ''}</Toast></div>
  </AppContent>
}
