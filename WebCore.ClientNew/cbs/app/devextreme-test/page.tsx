'use client'

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import DataGrid, { Column, ColumnChooser, HeaderFilter, Pager, Paging, Selection, StateStoring } from 'devextreme-react/data-grid'
import { AppContent, Toast } from '@adminlte/react'
import styles from '@/components/document-management-demo.module.css'
import { webCoreApi } from '@/lib/webcore-api'

type DocumentStatus = 'Hiệu lực' | 'Chờ phê duyệt' | 'Hết hiệu lực' | 'Từ chối'
type DetailTab = 'info' | 'history' | 'approval'
type Dialog = 'upload' | 'edit' | 'version' | undefined
type ToastState = { message: string; theme: 'success' | 'danger' }
type DocumentItem = { id: number; code: string; name: string; category: string; documentType: string; version: string; status: DocumentStatus; updatedAt: string; owner: string; description: string }
type VersionItem = { version: string; fileName: string; fileSizeBytes: number; note: string; isCurrent: boolean; hasFile: boolean; createdAt: string; createdBy: string }
type ApprovalItem = { stepNo: number; approverName: string; approvalStatus: string; note: string; actedBy?: string; actedAt?: string }

const all = 'Tất cả'
const categories = [all, 'Quy trình', 'Hướng dẫn', 'Biểu mẫu', 'Chính sách', 'Đào tạo']
const types = [all, 'Quy trình nghiệp vụ', 'Hướng dẫn sử dụng', 'Biểu mẫu nội bộ', 'Chính sách công ty', 'Tài liệu đào tạo']
const statuses: DocumentStatus[] = ['Hiệu lực', 'Chờ phê duyệt', 'Hết hiệu lực', 'Từ chối']

function StatusBadge({ status }: { status: DocumentStatus }) {
  const tone = status === 'Hiệu lực' ? styles.success : status === 'Chờ phê duyệt' ? styles.warning : status === 'Từ chối' ? styles.danger : styles.muted
  return <span className={`${styles.status} ${tone}`}>{status}</span>
}
function Field({ label, children }: { label: string; children: ReactNode }) { return <label className={styles.field}><span>{label}</span>{children}</label> }
function fileSize(bytes: number) { if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'; if (bytes < 1024) return `${bytes} B`; if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / (1024 * 1024)).toFixed(1)} MB` }

export default function DevExtremeDocumentManagementPage() {
  const gridRef = useRef<DataGrid>(null)
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState(all)
  const [documentType, setDocumentType] = useState(all)
  const [status, setStatus] = useState(all)
  const [selected, setSelected] = useState<DocumentItem | undefined>()
  const [pageSize, setPageSize] = useState(25)
  const [pageIndex, setPageIndex] = useState(0)
  const [tab, setTab] = useState<DetailTab>('info')
  const [dialog, setDialog] = useState<Dialog>()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [fileName, setFileName] = useState('')
  const [newName, setNewName] = useState('')
  const [newCode, setNewCode] = useState('')
  const [newCategory, setNewCategory] = useState(categories[1])
  const [newDocumentType, setNewDocumentType] = useState(types[1])
  const [newDescription, setNewDescription] = useState('')
  const [newInitialVersion, setNewInitialVersion] = useState('1.0')
  const [newFile, setNewFile] = useState<File>()
  const [editName, setEditName] = useState('')
  const [editCode, setEditCode] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [versionNo, setVersionNo] = useState('')
  const [effectiveDate, setEffectiveDate] = useState('')
  const [versionNote, setVersionNote] = useState('')
  const [versionFile, setVersionFile] = useState<File>()
  const [sendForApproval, setSendForApproval] = useState(true)
  const [toast, setToast] = useState<ToastState>()
  const [preview, setPreview] = useState<{ url: string; fileName: string }>()
  const [rejectOpen, setRejectOpen] = useState(false)
  const [rejectReason, setRejectReason] = useState('')
  const [versions, setVersions] = useState<VersionItem[]>([])
  const [approvals, setApprovals] = useState<ApprovalItem[]>([])
  const [versionReload, setVersionReload] = useState(0)
  useEffect(() => {
    let active = true
    void webCoreApi<DocumentItem[]>('/dms/documents')
      .then(items => { if (active) { setDocuments(items); setSelected(items[0]) } })
      .catch(() => { if (active) { setDocuments([]); setSelected(undefined) } })
    return () => { active = false }
  }, [])
  useEffect(() => {
    if (!selected) { setVersions([]); return }
    let active = true
    void webCoreApi<VersionItem[]>(`/dms/documents/${selected.id}/versions`)
      .then(items => { if (active) setVersions(items) })
      .catch(() => { if (active) setVersions([]) })
    return () => { active = false }
  }, [selected?.id, versionReload])
  useEffect(() => {
    if (!selected) { setApprovals([]); return }
    let active = true
    void webCoreApi<ApprovalItem[]>(`/dms/documents/${selected.id}/approvals`)
      .then(items => { if (active) setApprovals(items) })
      .catch(() => { if (active) setApprovals([]) })
    return () => { active = false }
  }, [selected?.id, versionReload])
  const filtered = useMemo(() => documents.filter(item => `${item.name} ${item.code}`.toLowerCase().includes(search.trim().toLowerCase()) && (category === all || item.category === category) && (documentType === all || item.documentType === documentType) && (status === all || item.status === status)), [category, documentType, documents, search, status])
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const currentPageIndex = Math.min(pageIndex, pageCount - 1)
  const currentVersionFile = versions.find(item => item.isCurrent)
  const closeDialog = () => { setDialog(undefined); setFileName('') }
  const openEdit = () => {
    if (!selected) return
    setEditName(selected.name); setEditCode(selected.code); setEditDescription(selected.description)
    setDialog('edit')
  }
  const openUpload = () => {
    setNewName(''); setNewCode(''); setNewCategory(categories[1]); setNewDocumentType(types[1]); setNewDescription(''); setNewInitialVersion('1.0'); setNewFile(undefined)
    setUploadOpen(true)
  }
  const saveDocument = async () => {
    if (!newName.trim() || !newCode.trim() || !newFile) { setToast({ theme: 'danger', message: 'Hãy nhập tên, mã tài liệu và chọn tệp.' }); return }
    try {
      const payload = new FormData()
      payload.set('name', newName.trim()); payload.set('code', newCode.trim()); payload.set('category', newCategory); payload.set('documentType', newDocumentType)
      payload.set('description', newDescription.trim()); payload.set('initialVersion', newInitialVersion.trim() || '1.0'); payload.set('file', newFile)
      const item = await webCoreApi<DocumentItem>('/dms/documents', { method: 'POST', body: payload })
      setDocuments(current => [item, ...current]); setSelected(item); setTab('info'); setUploadOpen(false)
      setToast({ theme: 'success', message: 'Đã tạo tài liệu và tải tệp lên thành công.' })
    } catch (error) { setToast({ theme: 'danger', message: error instanceof Error ? error.message : 'Không thể tạo tài liệu.' }) }
  }
  const openCreateVersion = () => {
    if (!selected) return
    setVersionNo((Number(selected.version) + .1).toFixed(1))
    setEffectiveDate('')
    setVersionNote('')
    setVersionFile(undefined)
    setSendForApproval(true)
    setDialog('version')
  }
  const saveEdit = async () => {
    if (!selected) return
    try {
      const updated = await webCoreApi<DocumentItem>(`/dms/documents/${selected.id}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: editName, code: editCode, description: editDescription }),
      })
      setDocuments(items => items.map(item => item.id === updated.id ? updated : item))
      setSelected(updated); closeDialog()
      setToast({ theme: 'success', message: 'Đã cập nhật tài liệu thành công.' })
    } catch (error) {
      setToast({ theme: 'danger', message: error instanceof Error ? error.message : 'Không thể cập nhật tài liệu.' })
    }
  }
  const saveVersion = async () => {
    if (!selected) return
    if (!versionNo || !effectiveDate || !versionNote.trim() || !versionFile) {
      setToast({ theme: 'danger', message: 'Hãy nhập đủ số phiên bản, ngày hiệu lực, nội dung thay đổi và chọn tệp.' })
      return
    }
    try {
      const payload = new FormData()
      payload.set('versionNo', versionNo)
      payload.set('effectiveDate', effectiveDate)
      payload.set('versionNote', versionNote)
      payload.set('sendForApproval', String(sendForApproval))
      payload.set('file', versionFile)
      const updated = await webCoreApi<DocumentItem>(`/dms/documents/${selected.id}/versions`, {
        method: 'POST', body: payload,
      })
      setDocuments(items => items.map(item => item.id === updated.id ? updated : item))
      setSelected(updated); setTab('history'); setVersionReload(value => value + 1); closeDialog()
      setToast({ theme: 'success', message: `Đã tạo phiên bản ${updated.version}.` })
    } catch (error) {
      setToast({ theme: 'danger', message: error instanceof Error ? error.message : 'Không thể tạo phiên bản mới.' })
    }
  }
  const addDocument = () => {
    if (!fileName) return
    const item: DocumentItem = { id: Math.max(0, ...documents.map(document => document.id)) + 1, name: fileName.replace(/\.[^.]+$/, '') || 'Tài liệu mới', code: `TL-${String(documents.length + 1).padStart(3, '0')}`, category: 'Quy trình', documentType: 'Quy trình nghiệp vụ', version: '1.0', status: 'Chờ phê duyệt', updatedAt: new Date().toLocaleString('vi-VN'), owner: 'Admin', description: 'Tài liệu mới được tải lên.' }
    setDocuments(current => [item, ...current]); setSelected(item); setTab('info'); closeDialog()
  }
  const createVersion = () => {
    if (!selected) return
    const updated = { ...selected, version: (Number(selected.version) + .1).toFixed(1), status: 'Chờ phê duyệt' as const, updatedAt: new Date().toLocaleString('vi-VN') }
    setDocuments(current => current.map(item => item.id === updated.id ? updated : item)); setSelected(updated); setTab('history'); closeDialog()
  }
  const viewVersion = async (version: string) => {
    if (!selected) return
    const item = versions.find(candidate => candidate.version === version)
    if (!item?.hasFile) { setToast({ theme: 'danger', message: 'Phiên bản này chưa có file upload.' }); return }
    const url = `/api/webcore-api/dms/documents/${selected.id}/versions/${encodeURIComponent(version)}/content`
    if (item.fileName.toLowerCase().endsWith('.pdf')) {
      try {
        const response = await fetch(url, { credentials: 'include', headers: { Accept: 'application/pdf' } })
        if (!response.ok) {
          const error = await response.json().catch(() => null) as { errorMessage?: string } | null
          throw new Error(error?.errorMessage ?? `Không thể tải file (${response.status}).`)
        }
        const contentType = response.headers.get('content-type') ?? ''
        if (!contentType.includes('pdf')) throw new Error('Máy chủ không trả về dữ liệu PDF.')
        const blob = await response.blob()
        if (!blob.size) throw new Error('File PDF trống.')
        setPreview({ url: URL.createObjectURL(blob), fileName: item.fileName })
      } catch (error) {
        setToast({ theme: 'danger', message: error instanceof Error ? error.message : 'Không thể mở preview PDF.' })
      }
    }
    else window.open(url, '_blank', 'noopener,noreferrer')
  }
  const closePreview = () => { if (preview?.url.startsWith('blob:')) URL.revokeObjectURL(preview.url); setPreview(undefined) }
  const decideApproval = async (action: 'approve' | 'reject', note = '') => {
    if (!selected) return
    if (action === 'reject' && !note.trim()) return
    try {
      const updated = await webCoreApi<DocumentItem>(`/dms/documents/${selected.id}/approval`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, note: note.trim() }),
      })
      setDocuments(items => items.map(item => item.id === updated.id ? updated : item))
      setSelected(updated); setVersionReload(value => value + 1); setRejectOpen(false); setRejectReason('')
      setToast({ theme: 'success', message: action === 'approve' ? 'Đã phê duyệt bước hiện tại.' : 'Đã từ chối phê duyệt tài liệu.' })
    } catch (error) {
      setToast({ theme: 'danger', message: error instanceof Error ? error.message : 'Không thể cập nhật phê duyệt.' })
    }
  }
  return <AppContent title="Danh sách tài liệu" breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Tài liệu' }, { label: 'Tất cả tài liệu' }]}>
    <main className={styles.page}>
    <section className={styles.filters}>
      <Field label="Loại tài liệu"><select value={category} onChange={event => setCategory(event.target.value)}>{categories.map(value => <option key={value}>{value}</option>)}</select></Field>
      <Field label="Kiểu tài liệu"><select value={documentType} onChange={event => setDocumentType(event.target.value)}>{types.map(value => <option key={value}>{value}</option>)}</select></Field>
      <Field label="Trạng thái"><select value={status} onChange={event => setStatus(event.target.value)}><option>{all}</option>{statuses.map(value => <option key={value}>{value}</option>)}</select></Field>
      <Field label="Tìm kiếm"><div className={styles.search}><input value={search} onChange={event => setSearch(event.target.value)} placeholder="Tên tài liệu, mã..." /><i className="bi bi-search" /></div></Field>
      <button className={styles.primaryButton} onClick={openUpload}><i className="bi bi-plus-lg" /> Tải tài liệu lên</button>
    </section>
    <section className={selected ? styles.workspace : `${styles.workspace} ${styles.workspaceCollapsed}`}>
      <div className={styles.gridCard}>
        <DataGrid ref={gridRef} dataSource={filtered} keyExpr="id" height="100%" showBorders={false} showRowLines rowAlternationEnabled hoverStateEnabled focusedRowEnabled focusedRowKey={selected?.id} selectedRowKeys={selected ? [selected.id] : []} allowColumnReordering allowColumnResizing columnAutoWidth columnMinWidth={90} onToolbarPreparing={event => { event.toolbarOptions.items = event.toolbarOptions.items?.filter(item => item.name !== 'columnChooserButton') }} onRowClick={event => { setSelected(event.data as DocumentItem); setTab('info') }} noDataText="Không có tài liệu phù hợp" className={styles.grid}>
          <Selection mode="single" /><Paging pageSize={pageSize} pageIndex={currentPageIndex} /><Pager visible={false} /><HeaderFilter visible /><ColumnChooser enabled mode="select" /><StateStoring enabled type="localStorage" storageKey="demo1-dms-document-grid" />
          <Column dataField="name" caption="Tên tài liệu" minWidth={245} cellRender={({ data }: { data: DocumentItem }) => <div className={styles.documentCell}><span className={styles.pdf}>PDF</span><div><strong>{data.name}</strong><small>{data.code}</small></div></div>} />
          <Column dataField="category" caption="Loại tài liệu" width={135} cellRender={({ value }: { value: string }) => <span className={styles.tag}>{value}</span>} /><Column dataField="documentType" caption="Kiểu tài liệu" minWidth={180} /><Column dataField="version" caption="Phiên bản" width={95} alignment="center" />
          <Column dataField="status" caption="Trạng thái" width={145} cellRender={({ value }: { value: DocumentStatus }) => <StatusBadge status={value} />} /><Column dataField="updatedAt" caption="Cập nhật lần cuối" minWidth={165} /><Column caption="" width={48} allowSorting={false} allowFiltering={false} allowHeaderFiltering={false} cellRender={() => <button className={styles.more} aria-label="Thao tác"><i className="bi bi-three-dots-vertical" /></button>} />
        </DataGrid><footer className={styles.gridFooter}><div><button type="button"><i className="bi bi-funnel-fill" /> Tạo Bộ Lọc</button><button type="button" disabled><i className="bi bi-x-circle-fill" /> Xóa Bộ Lọc</button><button className={styles.columnChooser} type="button" title="Chọn cột hiển thị" onClick={() => gridRef.current?.instance.showColumnChooser()}><i className="bi bi-layout-three-columns" /></button></div><div><span>Trang {currentPageIndex + 1} trên {pageCount} ({filtered.length} bản ghi)</span><select aria-label="Số dòng mỗi trang" value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setPageIndex(0) }}>{[25, 50, 100, 200].map(value => <option key={value} value={value}>{value}</option>)}</select><button type="button" disabled={currentPageIndex === 0} onClick={() => setPageIndex(currentPageIndex - 1)} aria-label="Trang trước"><i className="bi bi-chevron-left" /></button><input className={styles.pageInput} type="number" min={1} max={pageCount} aria-label="Số trang" value={currentPageIndex + 1} onChange={event => { const nextPage = Number(event.target.value); if (Number.isFinite(nextPage) && nextPage >= 1 && nextPage <= pageCount) setPageIndex(nextPage - 1) }} /><span>/ {pageCount}</span><button type="button" disabled={currentPageIndex >= pageCount - 1} onClick={() => setPageIndex(currentPageIndex + 1)} aria-label="Trang sau"><i className="bi bi-chevron-right" /></button></div></footer></div>
      {selected && <aside className={styles.detail}><div className={styles.detailHeader}><h2>Chi tiết tài liệu</h2><button onClick={() => setSelected(undefined)} aria-label="Đóng"><i className="bi bi-x-lg" /></button></div><div className={styles.detailTitle}><span className={`${styles.pdf} ${styles.largePdf}`}>PDF</span><div><h3>{selected.name}</h3><p>{selected.code} · Phiên bản {selected.version}</p></div><StatusBadge status={selected.status} /></div><div className={styles.tabs}><button className={tab === 'info' ? styles.activeTab : ''} onClick={() => setTab('info')}>Thông tin</button><button className={tab === 'history' ? styles.activeTab : ''} onClick={() => setTab('history')}>Lịch sử phiên bản</button><button className={tab === 'approval' ? styles.activeTab : ''} onClick={() => setTab('approval')}>Phê duyệt</button></div>
        {tab === 'info' && <div className={styles.info}>{[['Loại tài liệu', selected.category], ['Kiểu tài liệu', selected.documentType], ['Mô tả', selected.description], ['Chủ sở hữu', selected.owner], ['Phiên bản', selected.version], ['Cập nhật', selected.updatedAt]].map(([label, value]) => <p key={label}><span>{label}</span><strong>{value}</strong></p>)}{currentVersionFile && <div className={styles.attachment}><span className={styles.pdf}>PDF</span><div><strong>{currentVersionFile.fileName}</strong><small>{fileSize(Number(currentVersionFile.fileSizeBytes))}</small></div></div>}</div>}
        {tab === 'history' && <div className={styles.timeline}>{versions.map(item => <div className={styles.timelineItem} key={item.version}><i className={item.isCurrent ? styles.currentDot : styles.dot} /><div><strong>Phiên bản {item.version}</strong>{item.isCurrent && <em>Hiện tại</em>}<p>{item.note || 'Không có nội dung thay đổi.'}</p><small>{item.createdAt} · {item.createdBy}</small></div><button onClick={() => void viewVersion(item.version)}>Xem</button></div>)}{versions.length === 0 && <p>Chưa có phiên bản nào để hiển thị.</p>}</div>}
        {tab === 'approval' && <div className={styles.approval}><p><span>Trạng thái phê duyệt</span><StatusBadge status={selected.status} /></p>{approvals.map(item => <div className={`${styles.approvalStep} ${item.approvalStatus === 'Chờ xử lý' ? styles.approvalStepPending : ''}`} key={item.stepNo}><i>{item.stepNo}</i><div><strong>{item.approverName}</strong><small>{item.note || item.approvalStatus}{item.actedBy ? ` · ${item.actedBy}` : ''}{item.actedAt ? ` · ${item.actedAt}` : ''}</small></div></div>)}{approvals.length === 0 && <p className={styles.approvalEmpty}>Chưa có quy trình phê duyệt.</p>}{selected.status === 'Chờ phê duyệt' && approvals.some(item => item.approvalStatus === 'Chờ xử lý') && <div className={styles.approvalActions}><span>Bạn là người xử lý ở bước hiện tại.</span><div><button type="button" className={styles.rejectButton} onClick={() => { setRejectReason(''); setRejectOpen(true) }}>Từ chối</button><button type="button" className={styles.approveButton} onClick={() => void decideApproval('approve')}><i className="bi bi-check-lg" /> Phê duyệt</button></div></div>}</div>}
        {tab !== 'approval' && <footer className={styles.detailActions}><button onClick={openEdit}>Chỉnh sửa</button><button className={styles.primaryButton} onClick={openCreateVersion}><i className="bi bi-plus-lg" /> Tạo phiên bản mới</button></footer>}</aside>}
    </section>
    {uploadOpen && <div className={styles.backdrop} onMouseDown={() => setUploadOpen(false)}><section className={styles.dialog} onMouseDown={event => event.stopPropagation()}><header><div><span>TẢI TÀI LIỆU MỚI</span><h2>Thêm tài liệu</h2><p>Nhập thông tin và chọn tệp để tạo tài liệu mới.</p></div><button onClick={() => setUploadOpen(false)} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><div className={styles.formGrid}><Field label="Tên tài liệu"><input value={newName} placeholder="Ví dụ: Quy trình mua hàng" onChange={event => setNewName(event.target.value)} /></Field><Field label="Mã tài liệu"><input value={newCode} placeholder="Ví dụ: QP-MH-001" onChange={event => setNewCode(event.target.value)} /></Field><Field label="Loại tài liệu"><select value={newCategory} onChange={event => setNewCategory(event.target.value)}>{categories.slice(1).map(value => <option key={value}>{value}</option>)}</select></Field><Field label="Kiểu tài liệu"><select value={newDocumentType} onChange={event => setNewDocumentType(event.target.value)}>{types.slice(1).map(value => <option key={value}>{value}</option>)}</select></Field><Field label="Phiên bản khởi tạo"><input value={newInitialVersion} onChange={event => setNewInitialVersion(event.target.value)} /></Field><Field label="Mô tả"><textarea value={newDescription} placeholder="Mô tả ngắn về tài liệu..." onChange={event => setNewDescription(event.target.value)} /></Field></div><label className={styles.upload}><i className="bi bi-upload" /><strong>{newFile ? newFile.name : 'Kéo thả hoặc bấm để chọn tệp'}</strong><small>PDF, DOCX, XLSX — tối đa 20 MB</small><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" onChange={event => setNewFile(event.target.files?.[0])} /></label>{!newFile && <p className={styles.hint}>Bạn cần chọn tệp trước khi có thể tải tài liệu lên.</p>}</div><footer><button onClick={() => setUploadOpen(false)}>Hủy</button><button className={styles.primaryButton} disabled={!newName.trim() || !newCode.trim() || !newFile} onClick={() => void saveDocument()}><i className="bi bi-upload" /> Tải tài liệu lên</button></footer></section></div>}
    {dialog && <div className={styles.backdrop} onMouseDown={closeDialog}><section className={styles.dialog} onMouseDown={event => event.stopPropagation()}><header><div><span>{dialog === 'edit' ? 'CHỈNH SỬA TÀI LIỆU' : 'TẠO PHIÊN BẢN MỚI'}</span><h2>{selected?.name}</h2><p>{dialog === 'edit' ? 'Thay đổi sẽ được lưu vào DMS.pbbdms.' : `Phiên bản hiện tại: ${selected?.version ?? ''}`}</p></div><button onClick={closeDialog} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}>{dialog === 'edit' && <div className={styles.formGrid}><Field label="Tên tài liệu"><input value={editName} onChange={event => setEditName(event.target.value)} /></Field><Field label="Mã tài liệu"><input value={editCode} onChange={event => setEditCode(event.target.value)} /></Field><Field label="Mô tả"><textarea value={editDescription} onChange={event => setEditDescription(event.target.value)} /></Field></div>}{dialog === 'version' && <><div className={styles.version}><span>Phiên bản cũ<strong>{selected?.version}</strong></span><i className="bi bi-arrow-right" /><span>Phiên bản mới<strong>{versionNo}</strong></span></div><div className={styles.formGrid}><Field label="Số phiên bản"><input value={versionNo} onChange={event => setVersionNo(event.target.value)} /></Field><Field label="Ngày hiệu lực"><input type="date" value={effectiveDate} onChange={event => setEffectiveDate(event.target.value)} /></Field><Field label="Nội dung thay đổi"><textarea value={versionNote} placeholder="Ví dụ: Cập nhật bước phê duyệt và biểu mẫu đính kèm..." onChange={event => setVersionNote(event.target.value)} /></Field></div><label className={styles.upload}><i className="bi bi-upload" /><strong>{versionFile ? versionFile.name : 'Kéo thả hoặc bấm để chọn tệp phiên bản mới'}</strong><small>PDF, DOCX, XLSX — tối đa 20 MB</small><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" onChange={event => setVersionFile(event.target.files?.[0])} /></label><label className={styles.approvalOption}><input type="checkbox" checked={sendForApproval} onChange={event => setSendForApproval(event.target.checked)} /> Gửi quy trình phê duyệt sau khi tạo</label></>}</div><footer><button onClick={closeDialog}>Hủy</button><button className={styles.primaryButton} onClick={() => { if (dialog === 'edit') void saveEdit(); if (dialog === 'version') void saveVersion() }}>{dialog === 'version' ? `Tạo phiên bản ${versionNo}` : 'Lưu thay đổi'}</button></footer></section></div>}
    {rejectOpen && <div className={styles.backdrop} onMouseDown={() => setRejectOpen(false)}><section className={`${styles.dialog} ${styles.rejectDialog}`} role="dialog" aria-modal="true" aria-labelledby="reject-title" onMouseDown={event => event.stopPropagation()}><header><div><span>TỪ CHỐI PHÊ DUYỆT</span><h2 id="reject-title">{selected?.name}</h2><p>Vui lòng nêu rõ lý do để người tạo tài liệu có thể xử lý.</p></div><button onClick={() => setRejectOpen(false)} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><Field label="Lý do từ chối"><textarea autoFocus value={rejectReason} placeholder="Ví dụ: Nội dung chưa đầy đủ, cần bổ sung biểu mẫu..." onChange={event => setRejectReason(event.target.value)} /></Field><p className={styles.requiredHint}>Lý do từ chối là bắt buộc.</p></div><footer><button onClick={() => setRejectOpen(false)}>Hủy</button><button className={styles.rejectConfirmButton} disabled={!rejectReason.trim()} onClick={() => void decideApproval('reject', rejectReason)}>Từ chối duyệt</button></footer></section></div>}
    {preview && <div className={styles.backdrop} onMouseDown={closePreview}><section className={`${styles.dialog} ${styles.previewDialog}`} onMouseDown={event => event.stopPropagation()}><header><div><span>XEM TÀI LIỆU</span><h2>{preview.fileName}</h2><p>PDF được tải qua phiên đăng nhập hiện tại.</p></div><button onClick={closePreview} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><iframe className={styles.previewFrame} src={preview.url} title={preview.fileName} /><footer><button onClick={() => window.open(preview.url, '_blank', 'noopener,noreferrer')}>Mở tab mới</button><button className={styles.primaryButton} onClick={closePreview}>Đóng</button></footer></section></div>}
    </main>
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 3000 }}>
      <Toast show={Boolean(toast)} autohideMs={5000} onClose={() => setToast(undefined)} theme={toast?.theme} title={toast?.theme === 'success' ? 'Thành công' : 'Cập nhật thất bại'}>{toast?.message ?? ''}</Toast>
    </div>
  </AppContent>
}
