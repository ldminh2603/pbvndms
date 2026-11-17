'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import DataGrid, { Column, ColumnChooser, FilterBuilderPopup, HeaderFilter, Pager, Paging, Scrolling, Selection } from 'devextreme-react/data-grid'
import { AppContent } from '@adminlte/react'
import styles from '@/components/document-management-demo.module.css'
import { webCoreApi, webCoreApiResponse } from '@/lib/webcore-api'

type DocumentRow = { id: string; name: string; referenceText: string; category: string; documentType: string; version: string; documentStatus: string; scanStatus: string; workflowState: string; fileSizeBytes: number; mimeType: string; createdBy: string; branchCode: string; legalHold: boolean; objectType: string; objectRef: string; createdAt: string }
type Version = { id: string; version: string; fileName: string; fileSizeBytes: number; documentStatus: string; createdBy: string; createdAt: string }
type WorkflowEvent = { action: string; actor: string; remarks: string; eventTime: string }
type AmendmentCycle = { caseId: string; amendmentNo: number; state: string; reason: string; openedBy: string; maker: string; authorisedBy: string; createdAt: string; updatedAt: string; documentCount: number; events: WorkflowEvent[] }
type WorkflowAction = { action: string; event: string; requiresRemarks: boolean; requiredRole: string; allowed: boolean; blockedReason: string }
type Workflow = { caseId: string; objectType: string; objectRef: string; state: string; maker: string; submittedBy: string; checker: string; authorisedBy: string; workflowVersion?: number; amendmentNo?: number; canEditDocuments?: boolean; canOpenAmendment?: boolean; canCancelAmendment?: boolean; events: WorkflowEvent[]; actions: WorkflowAction[] }
type Metadata = { categories: { code: string; description: string }[]; types: { code: string; categoryCode: string; description: string }[]; objectTypes: { code: string }[]; productTypes: { code: string; description: string }[]; requestTypes: { code: string; productTypeCode: string; description: string }[] }
type CaseRow = { caseId: string; objectType: string; objectRef: string; branchCode: string; state: string; maker: string; customerNumber: string; customerName: string; amendmentNo: number; activeDocumentCount: number; updatedAt: string }

const bytes = (value: number) => value < 1024 * 1024 ? `${Math.max(1, value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
const badge = (value: string) => value === 'AUTHORISED' || value === 'ACTIVE' || value === 'CLEAN' ? styles.success : value === 'RETURNED' || value === 'DELETED' || value === 'INFECTED' ? styles.danger : styles.warning

export default function DocsvcDmsPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const selectedCaseId = searchParams.get('caseId')
  const gridRef = useRef<DataGrid>(null)
  const [rows, setRows] = useState<DocumentRow[]>([])
  const [cases, setCases] = useState<CaseRow[]>([])
  const [activeCase, setActiveCase] = useState<CaseRow>()
  const [caseSearch, setCaseSearch] = useState('')
  const [caseState, setCaseState] = useState('ALL')
  const [creatingCase, setCreatingCase] = useState(false)
  const [caseForm, setCaseForm] = useState({ objectType: '', objectRef: '', branchCode: '', customerNumber: '', customerName: '', productTypeCode: '', requestTypeCode: '' })
  const [selected, setSelected] = useState<DocumentRow>()
  const [tab, setTab] = useState<'info' | 'versions' | 'workflow'>('info')
  const [search, setSearch] = useState('')
  const [versions, setVersions] = useState<Version[]>([])
  const [workflow, setWorkflow] = useState<Workflow>()
  const [amendmentHistory, setAmendmentHistory] = useState<AmendmentCycle[]>([])
  const [preview, setPreview] = useState<{ url: string; name: string }>()
  const [error, setError] = useState('')
  const [metadata, setMetadata] = useState<Metadata>({ categories: [], types: [], objectTypes: [], productTypes: [], requestTypes: [] })
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadFile, setUploadFile] = useState<File>()
  const [uploadForm, setUploadForm] = useState({ objectType: '', objectRef: '', branchCode: '', categoryCode: '', typeCode: '', referenceText: '', customerNumber: '', customerName: '' })
  const [versionOpen, setVersionOpen] = useState(false)
  const [versionUploading, setVersionUploading] = useState(false)
  const [versionFile, setVersionFile] = useState<File>()
  const [versionReference, setVersionReference] = useState('')
  const [removeOpen, setRemoveOpen] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [removalReason, setRemovalReason] = useState('')
  const [workflowAction, setWorkflowAction] = useState<WorkflowAction>()
  const [workflowRemarks, setWorkflowRemarks] = useState('')
  const [workflowActing, setWorkflowActing] = useState(false)
  const [amendmentOpen, setAmendmentOpen] = useState(false)
  const [amendmentReason, setAmendmentReason] = useState('')
  const [amendmentOpening, setAmendmentOpening] = useState(false)
  const [amendmentCancelling, setAmendmentCancelling] = useState(false)
  const [amendmentCancelOpen, setAmendmentCancelOpen] = useState(false)
  const [pageSize, setPageSize] = useState(25)
  const [pageIndex, setPageIndex] = useState(0)
  const [filterBuilderVisible, setFilterBuilderVisible] = useState(false)
  const [hasGridFilter, setHasGridFilter] = useState(false)

  const reloadDocuments = async (selectId?: string, restoreActiveFor?: Pick<DocumentRow, 'objectType' | 'objectRef'>) => {
    const items = await webCoreApi<DocumentRow[]>('/dms-docsvc/documents')
    setRows(items)
    setSelected(current => {
      const restored = restoreActiveFor && items.find(item => item.objectType === restoreActiveFor.objectType && item.objectRef === restoreActiveFor.objectRef && item.documentStatus === 'ACTIVE')
      return restored || items.find(item => item.id === selectId) || items.find(item => item.id === current?.id)
    })
  }
  useEffect(() => { void reloadDocuments().catch(e => setError(e instanceof Error ? e.message : 'Không thể tải docsvc.')) }, [])
  const reloadCases = async () => setCases(await webCoreApi<CaseRow[]>('/dms-docsvc/cases'))
  useEffect(() => { void reloadCases().catch(e => setError(e instanceof Error ? e.message : 'Unable to load transaction cases.')) }, [])
  useEffect(() => { void webCoreApi<Metadata>('/dms-docsvc/documents/metadata').then(items => { setMetadata(items); setUploadForm(current => ({ ...current, objectType: current.objectType || items.objectTypes[0]?.code || '', categoryCode: current.categoryCode || items.categories[0]?.code || '' })); setCaseForm(current => { const productTypeCode = current.productTypeCode || items.productTypes[0]?.code || ''; const requestTypeCode = current.requestTypeCode || items.requestTypes.find(item => item.productTypeCode === productTypeCode)?.code || ''; return { ...current, objectType: current.objectType || items.objectTypes[0]?.code || '', productTypeCode, requestTypeCode } }) }).catch(e => setError(e instanceof Error ? e.message : 'Unable to load docsvc metadata.')) }, [])
  useEffect(() => {
    const next = selectedCaseId ? cases.find(item => item.caseId === selectedCaseId) : undefined
    setActiveCase(next)
  }, [cases, selectedCaseId])
  useEffect(() => { setSelected(activeCase ? rows.find(item => item.objectType === activeCase.objectType && item.objectRef === activeCase.objectRef && item.documentStatus === 'ACTIVE') : undefined) }, [activeCase?.caseId, rows])
  useEffect(() => {
    if (!selected) return
    void webCoreApi<Version[]>(`/dms-docsvc/documents/${selected.id}/versions`).then(setVersions).catch(e => setError(e instanceof Error ? e.message : 'Không thể tải phiên bản.'))
    void webCoreApi<Workflow>(`/dms-docsvc/documents/${selected.id}/workflow?objectType=${encodeURIComponent(selected.objectType)}&objectRef=${encodeURIComponent(selected.objectRef)}`).then(setWorkflow).catch(e => setError(e instanceof Error ? e.message : 'Không thể tải workflow.'))
    void webCoreApi<AmendmentCycle[]>(`/dms-docsvc/documents/${selected.id}/amendments/history`).then(setAmendmentHistory).catch(e => setError(e instanceof Error ? e.message : 'Không thể tải lịch sử amendment.'))
  }, [selected?.id, selected?.objectRef, selected?.objectType])
  const filtered = useMemo(() => rows.filter(row => !activeCase || (row.objectType === activeCase.objectType && row.objectRef === activeCase.objectRef)).filter(row => `${row.name} ${row.referenceText} ${row.objectRef}`.toLowerCase().includes(search.toLowerCase())), [rows, search, activeCase])
  const worklistBucket = (state: string) => {
    const value = state.toUpperCase()
    if (value === 'DRAFT') return 'DRAFT'
    if (value.startsWith('RETURNED')) return 'RETURNED'
    if (value === 'AUTHORISED' || value === 'AUTHORIZED') return 'AUTHORISED'
    return 'TO_APPROVE'
  }
  const visibleCases = useMemo(() => cases.filter(item => (caseState === 'ALL' || worklistBucket(item.state) === caseState) && `${item.objectRef} ${item.customerNumber} ${item.customerName}`.toLowerCase().includes(caseSearch.toLowerCase())), [cases, caseSearch, caseState])
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const openPreview = async (row: DocumentRow | Version) => {
    try {
      const response = await webCoreApiResponse(`/dms-docsvc/documents/${row.id}/content`)
      if (!response.ok) throw new Error(`Không thể mở file (${response.status}).`)
      const blob = await response.blob(); setPreview({ url: URL.createObjectURL(blob), name: 'name' in row ? row.name : row.fileName })
    } catch (e) { setError(e instanceof Error ? e.message : 'Không thể preview tài liệu.') }
  }
  const closePreview = () => { if (preview) URL.revokeObjectURL(preview.url); setPreview(undefined) }
  const availableTypes = metadata.types.filter(item => item.categoryCode === uploadForm.categoryCode)
  const availableRequestTypes = metadata.requestTypes.filter(item => item.productTypeCode === caseForm.productTypeCode)
  const openUpload = () => { setError(''); setUploadFile(undefined); setUploadForm(current => ({ ...current, objectType: activeCase?.objectType || current.objectType, objectRef: activeCase?.objectRef || '', branchCode: activeCase?.branchCode || '', customerNumber: activeCase?.customerNumber || '', customerName: activeCase?.customerName || '', referenceText: '', typeCode: '' })); setUploadOpen(true) }
  const createCase = async () => {
    if (!caseForm.objectType || !caseForm.objectRef.trim() || !caseForm.branchCode.trim() || !caseForm.customerNumber.trim() || !caseForm.customerName.trim() || !caseForm.productTypeCode || !caseForm.requestTypeCode) { setError('Please complete case type, product type, request type, reference, branch and customer information.'); return }
    setCreatingCase(true); setError('')
    try {
      const item = await webCoreApi<CaseRow>('/dms-docsvc/cases', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(caseForm) })
      await reloadCases(); router.push(`/dms?caseId=${encodeURIComponent(item.caseId)}`); setCaseForm(current => ({ ...current, objectRef: '', customerNumber: '', customerName: '' }))
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to open transaction case.') } finally { setCreatingCase(false) }
  }
  const upload = async () => {
    if (!uploadFile || !uploadForm.objectType || !uploadForm.objectRef.trim() || !uploadForm.branchCode.trim() || !uploadForm.categoryCode || !uploadForm.typeCode) { setError('Please complete the case, document type and file.'); return }
    setUploading(true); setError('')
    try {
      const payload = new FormData()
      Object.entries(uploadForm).forEach(([key, value]) => payload.set(key, value.trim()))
      payload.set('file', uploadFile)
      const item = await webCoreApi<DocumentRow>('/dms-docsvc/documents', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: payload })
      setRows(current => [item, ...current]); await reloadCases(); setSelected(item); setTab('info'); setUploadOpen(false)
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to create document.') } finally { setUploading(false) }
  }
  const openVersion = () => { if (!selected) return; if (selected.documentStatus !== 'ACTIVE') { setError('Chỉ có thể tạo phiên bản mới từ tài liệu ACTIVE.'); return }; setError(''); setVersionFile(undefined); setVersionReference(selected.referenceText); setVersionOpen(true) }
  const createVersion = async () => {
    if (!selected || !versionFile) { setError('Choose the replacement file.'); return }
    setVersionUploading(true); setError('')
    try {
      const payload = new FormData(); payload.set('referenceText', versionReference.trim()); payload.set('file', versionFile)
      const item = await webCoreApi<DocumentRow>(`/dms-docsvc/documents/${selected.id}/versions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: payload })
      await reloadDocuments(item.id); await reloadCases(); setTab('versions'); setVersionOpen(false)
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to create version.') } finally { setVersionUploading(false) }
  }
  const openRemove = () => {
    if (!selected || selected.documentStatus !== 'ACTIVE' || !workflow?.canEditDocuments) return
    setError(''); setRemovalReason(''); setRemoveOpen(true)
  }
  const removeDocument = async () => {
    if (!selected || !removalReason.trim()) { setError('Nhập lý do xóa tài liệu.'); return }
    setRemoving(true); setError('')
    try {
      await webCoreApi(`/dms-docsvc/documents/${selected.id}/remove?reason=${encodeURIComponent(removalReason.trim())}`, { method: 'POST' })
      await reloadDocuments(); await reloadCases(); setRemoveOpen(false); setRemovalReason(''); setTab('info')
    } catch (e) { setError(e instanceof Error ? e.message : 'Không thể xóa tài liệu.') } finally { setRemoving(false) }
  }
  const actionLabel = (action: string) => ({ submit: 'Gửi kiểm soát', check: 'Kiểm soát', return: 'Trả về', authorise: 'Phê duyệt' }[action.toLowerCase()] || action)
  const actWorkflow = async (action: WorkflowAction, remarks = '') => {
    if (!selected || !workflow?.caseId) return
    setWorkflowActing(true); setError('')
    try {
      await webCoreApi(`/module/02D45/maintain/MED/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ buttonName: `BTN_${action.action.toUpperCase()}`, values: { CASE_ID: workflow?.caseId, REMARKS: remarks } }) })
      const next = await webCoreApi<Workflow>(`/dms-docsvc/documents/${selected.id}/workflow?objectType=${encodeURIComponent(selected.objectType)}&objectRef=${encodeURIComponent(selected.objectRef)}`)
      setWorkflow(next); setWorkflowAction(undefined); setWorkflowRemarks('')
      setRows(current => current.map(row => row.id === selected.id ? { ...row, workflowState: next.state } : row))
      setSelected(current => current ? { ...current, workflowState: next.state } : current)
    } catch (e) { setError(e instanceof Error ? e.message : 'Không thể chuyển trạng thái workflow.') } finally { setWorkflowActing(false) }
  }
  const requestWorkflowAction = (action: WorkflowAction) => {
    if (action.requiresRemarks) { setWorkflowRemarks(''); setWorkflowAction(action); return }
    void actWorkflow(action)
  }
  const openAmendment = () => { setError(''); setAmendmentReason(''); setAmendmentOpen(true) }
  const createAmendment = async () => {
    if (!selected || !workflow?.caseId || !amendmentReason.trim()) { setError('Nhập lý do mở amendment.'); return }
    setAmendmentOpening(true); setError('')
    try {
      await webCoreApi(`/module/02D45/maintain/MED/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ buttonName: 'BTN_OPEN_AMENDMENT', values: { CASE_ID: workflow?.caseId, REMARKS: amendmentReason.trim() } }) })
      const next = await webCoreApi<Workflow>(`/dms-docsvc/documents/${selected.id}/workflow?objectType=${encodeURIComponent(selected.objectType)}&objectRef=${encodeURIComponent(selected.objectRef)}`)
      await reloadDocuments(selected.id); setWorkflow(next); setAmendmentOpen(false); setTab('workflow')
    } catch (e) { setError(e instanceof Error ? e.message : 'Không thể mở amendment.') } finally { setAmendmentOpening(false) }
  }
  const cancelAmendment = async () => {
    if (!selected || !workflow?.caseId) return
    setAmendmentCancelling(true); setError('')
    try {
      await webCoreApi(`/module/02D45/maintain/MED/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ buttonName: 'BTN_CANCEL_AMENDMENT', values: { CASE_ID: workflow?.caseId, REMARKS: amendmentReason.trim() } }) })
      const next = await webCoreApi<Workflow>(`/dms-docsvc/documents/${selected.id}/workflow?objectType=${encodeURIComponent(selected.objectType)}&objectRef=${encodeURIComponent(selected.objectRef)}`)
      await reloadDocuments(undefined, selected); setWorkflow(next); setAmendmentCancelOpen(false); setAmendmentReason(''); setTab('workflow')
    } catch (e) { setError(e instanceof Error ? e.message : 'Không thể hủy amendment.') } finally { setAmendmentCancelling(false) }
  }

  return <AppContent title="DMS — Tài liệu giao dịch" breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'DMS' }]}><main className={styles.page}>
    {!selectedCaseId && <section className={styles.filters}>
      <label className={styles.field}><span>Tìm kiếm</span><div className={styles.search}><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Tên file, tham chiếu, mã giao dịch..." /><i className="bi bi-search" /></div></label>
      <label className={styles.field}><span>Phạm vi dữ liệu</span><input value="docsvc (PBBDMS)" readOnly /></label>
      <label className={styles.field}><span>Workflow</span><input value="Theo hồ sơ giao dịch" readOnly /></label>
      <button className={styles.primaryButton} disabled title="Upload phải gọi PBBDMS Document Service để giữ scan, WORM và audit."><i className="bi bi-upload" /> Tải qua Document Service</button>
      <button className={styles.primaryButton} onClick={openUpload} disabled={!activeCase} title={activeCase ? 'Attach a document to the selected case.' : 'Select a transaction case first.'}><i className="bi bi-upload" /> Tải tài liệu lên</button>
    </section>}
    {!selectedCaseId && <section className={styles.casePanel}>
      <header><div><h2>New document case</h2><p>Open a transaction case first, then attach and manage its documents.</p></div></header>
      <div className={styles.caseForm}>
        <label className={styles.field}><span>Case type</span><select value={caseForm.objectType} onChange={event => setCaseForm(current => ({ ...current, objectType: event.target.value }))}>{metadata.objectTypes.map(item => <option key={item.code} value={item.code}>{item.code}</option>)}</select></label>
        <label className={styles.field}><span>Contract reference</span><input value={caseForm.objectRef} onChange={event => setCaseForm(current => ({ ...current, objectRef: event.target.value }))} /></label>
        <label className={styles.field}><span>Product type *</span><select value={caseForm.productTypeCode} onChange={event => { const productTypeCode = event.target.value; setCaseForm(current => ({ ...current, productTypeCode, requestTypeCode: metadata.requestTypes.find(item => item.productTypeCode === productTypeCode)?.code || '' })) }}><option value="">Select...</option>{metadata.productTypes.map(item => <option key={item.code} value={item.code}>{item.description} ({item.code})</option>)}</select></label>
        <label className={styles.field}><span>Request type *</span><select value={caseForm.requestTypeCode} disabled={!caseForm.productTypeCode} onChange={event => setCaseForm(current => ({ ...current, requestTypeCode: event.target.value }))}><option value="">Select...</option>{availableRequestTypes.map(item => <option key={item.code} value={item.code}>{item.description} ({item.code})</option>)}</select></label>
        <label className={styles.field}><span>Branch</span><input value={caseForm.branchCode} onChange={event => setCaseForm(current => ({ ...current, branchCode: event.target.value }))} placeholder="HN001" /></label>
        <label className={styles.field}><span>Customer no.</span><input value={caseForm.customerNumber} onChange={event => setCaseForm(current => ({ ...current, customerNumber: event.target.value }))} /></label>
        <label className={styles.field}><span>Customer name</span><input value={caseForm.customerName} onChange={event => setCaseForm(current => ({ ...current, customerName: event.target.value }))} /></label>
        <button className={styles.primaryButton} disabled={creatingCase} onClick={() => void createCase()}>{creatingCase ? 'Opening...' : 'Open case'}</button>
      </div>
      <div className={styles.caseWorklistHeader}><h2>Worklist</h2><div>{[['ALL', 'All my cases'], ['DRAFT', 'Drafts'], ['TO_APPROVE', 'To approve'], ['RETURNED', 'Returned by HO'], ['AUTHORISED', 'Authorised']].map(([state, label]) => <button key={state} className={caseState === state ? styles.caseTabActive : ''} onClick={() => setCaseState(state)}>{label} <small>{state === 'ALL' ? cases.length : cases.filter(item => worklistBucket(item.state) === state).length}</small></button>)}</div></div>
      <div className={styles.caseSearch}><input value={caseSearch} onChange={event => setCaseSearch(event.target.value)} placeholder="Search contract, customer no. or name..." /><button onClick={() => setCaseSearch('')}>Clear</button></div>
      <div className={styles.caseTableWrap}><table className={styles.caseTable}><thead><tr><th>Contract</th><th>Case type</th><th>Customer</th><th>Branch</th><th>State</th><th>Maker</th><th>Documents</th><th>Last activity</th></tr></thead><tbody>{visibleCases.map(item => <tr key={item.caseId} className={activeCase?.caseId === item.caseId ? styles.caseSelected : ''} onClick={() => router.push(`/dms?caseId=${encodeURIComponent(item.caseId)}`)}><td><strong>{item.objectRef}</strong></td><td>{item.objectType}</td><td>{item.customerName || item.customerNumber}</td><td>{item.branchCode}</td><td><span className={`${styles.status} ${badge(item.state)}`}>{item.state}</span></td><td>{item.maker}</td><td>{item.activeDocumentCount}</td><td>{item.updatedAt}</td></tr>)}{!visibleCases.length && <tr><td colSpan={8} className={styles.caseEmpty}>No transaction cases found.</td></tr>}</tbody></table></div>
    </section>}
    {selectedCaseId && activeCase && <section className={styles.caseDetailHeading}><div><span>TRANSACTION CASE</span><h2>{activeCase.objectType} · {activeCase.objectRef}</h2><p>{activeCase.customerName || activeCase.customerNumber} · {activeCase.branchCode} · <b>{activeCase.state}</b></p></div><div className={styles.caseDetailActions}><button className={styles.primaryButton} onClick={openUpload}><i className="bi bi-plus-lg" /> Thêm tài liệu</button><button onClick={() => router.push('/dms')}><i className="bi bi-arrow-left" /> Quay lại worklist</button></div></section>}
    {error && <p className={styles.dmsError}>{error}</p>}
    {activeCase ? <section className={selected ? styles.workspace : `${styles.workspace} ${styles.workspaceCollapsed}`}><div className={styles.gridCard}>
      <DataGrid ref={gridRef} dataSource={filtered} keyExpr="id" height="100%" showBorders={false} showRowLines rowAlternationEnabled hoverStateEnabled focusedRowEnabled focusedRowKey={selected?.id} selectedRowKeys={selected ? [selected.id] : []} allowColumnReordering allowColumnResizing onToolbarPreparing={event => { event.toolbarOptions.items = event.toolbarOptions.items?.filter(item => item.name !== 'columnChooserButton') }} onOptionChanged={event => { if (event.fullName === 'filterValue') setHasGridFilter(Boolean(event.value)) }} onRowClick={e => { setSelected(e.data as DocumentRow); setTab('info') }} className={styles.grid} noDataText="Không có tài liệu docsvc"><Selection mode="single" /><Paging pageSize={pageSize} pageIndex={pageIndex} /><Pager visible={false} /><HeaderFilter visible /><FilterBuilderPopup visible={filterBuilderVisible} onHiding={() => setFilterBuilderVisible(false)} /><Scrolling mode="standard" showScrollbar="always" /><ColumnChooser enabled mode="select" />
        <Column dataField="name" caption="Tên tài liệu" width={260} cellRender={({ data }: { data: DocumentRow }) => <div className={styles.documentCell}><span className={styles.pdf}>PDF</span><div><strong>{data.name}</strong><small>{data.referenceText || data.id}</small></div></div>} />
        <Column dataField="objectRef" caption="Hồ sơ giao dịch" width={280} cellRender={({ data }: { data: DocumentRow }) => <div><strong>{data.objectRef || 'Chưa liên kết'}</strong></div>} /><Column dataField="objectType" caption="Case type" width={120} />
        <Column dataField="category" caption="Loại" width={175} /><Column dataField="documentType" caption="Document type" width={220} /><Column dataField="version" caption="Phiên bản" width={145} alignment="center" />
        <Column dataField="documentStatus" caption="Trạng thái file" width={150} cellRender={({ value }: { value: string }) => <span className={`${styles.status} ${badge(value)}`}>{value}</span>} />
        <Column dataField="workflowState" caption="Trạng thái hồ sơ" width={175} cellRender={({ value }: { value: string }) => <span className={`${styles.status} ${badge(value)}`}>{value}</span>} />
        <Column dataField="createdAt" caption="Tải lên" width={175} />
      </DataGrid><footer className={styles.gridFooter}><div><button type="button" onClick={() => setFilterBuilderVisible(true)}><i className="bi bi-funnel-fill" /> Tạo Bộ Lọc</button><button type="button" disabled={!hasGridFilter} onClick={() => { gridRef.current?.instance.clearFilter(); setHasGridFilter(false) }}><i className="bi bi-x-circle-fill" /> Xóa Bộ Lọc</button><button className={styles.columnChooser} type="button" title="Chọn cột hiển thị" onClick={() => gridRef.current?.instance.showColumnChooser()}><i className="bi bi-layout-three-columns" /></button></div><div><span>Trang {pageIndex + 1} trên {pageCount} ({filtered.length} bản ghi)</span><select aria-label="Số dòng mỗi trang" value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setPageIndex(0) }}>{[25, 50, 100, 200].map(value => <option key={value} value={value}>{value}</option>)}</select><button type="button" disabled={pageIndex === 0} onClick={() => setPageIndex(pageIndex - 1)} aria-label="Trang trước"><i className="bi bi-chevron-left" /></button><input className={styles.pageInput} type="number" min={1} max={pageCount} aria-label="Số trang" value={pageIndex + 1} onChange={event => { const nextPage = Number(event.target.value); if (Number.isFinite(nextPage) && nextPage >= 1 && nextPage <= pageCount) setPageIndex(nextPage - 1) }} /><span>/ {pageCount}</span><button type="button" disabled={pageIndex >= pageCount - 1} onClick={() => setPageIndex(pageIndex + 1)} aria-label="Trang sau"><i className="bi bi-chevron-right" /></button></div></footer>
    </div>{selected && <aside className={styles.detail}><div className={styles.detailHeader}><h2>Chi tiết tài liệu</h2><button onClick={() => setSelected(undefined)}><i className="bi bi-x-lg" /></button></div><div className={styles.detailTitle}><span className={`${styles.pdf} ${styles.largePdf}`}>PDF</span><div><h3>{selected.name}</h3><p>{selected.objectType} · {selected.objectRef || 'Chưa liên kết hồ sơ'}</p></div><span className={`${styles.status} ${badge(selected.workflowState)}`}>{selected.workflowState}</span></div><div className={styles.tabs}><button className={tab === 'info' ? styles.activeTab : ''} onClick={() => setTab('info')}>Thông tin</button><button className={tab === 'versions' ? styles.activeTab : ''} onClick={() => setTab('versions')}>Lịch sử phiên bản</button><button className={tab === 'workflow' ? styles.activeTab : ''} onClick={() => setTab('workflow')}>Workflow</button></div>
      {tab === 'info' && <div className={styles.info}><p className={styles.versionInfo}><span>Phiên bản</span><strong>{selected.version}</strong></p>{[['Loại tài liệu', selected.category], ['Kiểu tài liệu', selected.documentType], ['Chi nhánh', selected.branchCode], ['Người tải', selected.createdBy], ['Dung lượng', bytes(selected.fileSizeBytes)], ['Quét virus', selected.scanStatus], ['Legal hold', selected.legalHold ? 'Có' : 'Không']].map(([label, value]) => <p key={label}><span>{label}</span><strong>{value}</strong></p>)}<button className={styles.previewButton} onClick={() => void openPreview(selected)}><i className="bi bi-eye" /> Xem tài liệu</button>{workflow?.canEditDocuments && selected.documentStatus === 'ACTIVE' && <button className={styles.removeButton} onClick={openRemove}><i className="bi bi-trash" /> Xóa khỏi hồ sơ</button>}</div>}
      {tab === 'versions' && <div className={styles.timeline}>{versions.map(item => <div className={styles.timelineItem} key={item.id}><i className={item.id === selected.id ? styles.currentDot : styles.dot} /><div><strong>Phiên bản {item.version}</strong><p>{item.fileName} · {bytes(item.fileSizeBytes)}</p><small>{item.createdAt} · {item.createdBy}</small></div><button onClick={() => void openPreview(item)}>Xem</button></div>)}</div>}
      {tab === 'workflow' && <div className={styles.approval}><p><span>Trạng thái hồ sơ</span><span className={`${styles.status} ${badge(workflow?.state ?? 'NO_CASE')}`}>{workflow?.state ?? 'NO_CASE'}</span></p>{workflow?.amendmentNo ? <p className={styles.amendmentInfo}>Amendment #{workflow.amendmentNo}</p> : null}{workflow?.events.map((event, index) => <div className={styles.approvalStep} key={`${event.eventTime}-${index}`}><i>{index + 1}</i><div><strong>{event.action}</strong><small>{event.actor} · {event.eventTime}{event.remarks ? ` · ${event.remarks}` : ''}</small></div></div>)}{(!workflow || workflow.state === 'NO_CASE') && <p className={styles.approvalEmpty}>Tài liệu chưa được gắn với portal_case nên không có workflow để hiển thị.</p>}{workflow?.actions?.length ? <div className={styles.approvalActions}><span>{workflow.actions.some(action => action.allowed) ? 'Bạn có thể xử lý bước hiện tại.' : 'Bước tiếp theo của workflow.'}</span><div>{workflow.actions.map(action => <div className={styles.workflowAction} key={action.action}><button className={action.action === 'return' ? styles.rejectButton : action.action === 'authorise' ? styles.approveButton : styles.primaryButton} disabled={workflowActing || !action.allowed} onClick={() => requestWorkflowAction(action)}>{action.action === 'authorise' && <i className="bi bi-check-lg" />}{actionLabel(action.action)}</button>{!action.allowed && <small><i className="bi bi-lock" /> {action.blockedReason === 'ROLE' ? `Cần role ${action.requiredRole || 'workflow'}` : 'Không thỏa điều kiện four-eyes'}</small>}</div>)}</div></div> : null}{workflow?.canOpenAmendment && <div className={styles.amendmentActions}><span>Hồ sơ đã được duyệt. Mở cycle mới để thay đổi tài liệu và duyệt lại.</span><button className={styles.primaryButton} onClick={openAmendment}><i className="bi bi-arrow-repeat" /> Mở amendment</button></div>}{workflow?.canCancelAmendment && <div className={styles.amendmentActions}><span>Hủy cycle đang mở và khôi phục đúng snapshot đã duyệt gần nhất.</span><button className={styles.rejectConfirmButton} onClick={() => { setAmendmentReason(''); setAmendmentCancelOpen(true) }}>Hủy amendment</button></div>}</div>}
      {tab === 'workflow' && amendmentHistory.length > 1 && <section className={styles.cycleHistory}><h3>Lịch sử Amendment</h3>{amendmentHistory.map(cycle => <article key={cycle.caseId} className={styles.cycleCard}><header><div><strong>Amendment #{cycle.amendmentNo}</strong><small>{cycle.createdAt} · {cycle.openedBy || cycle.maker}</small></div><span className={`${styles.status} ${badge(cycle.state)}`}>{cycle.state}</span></header>{cycle.reason && <p className={styles.cycleReason}>Lý do: {cycle.reason}</p>}<p className={styles.cycleMeta}>{cycle.documentCount} file snapshot · {cycle.authorisedBy ? `Duyệt: ${cycle.authorisedBy}` : 'Đang xử lý'}</p><div className={styles.cycleEvents}>{cycle.events.map(event => <small key={`${cycle.caseId}-${event.eventTime}-${event.action}`}><b>{event.action}</b> · {event.actor} · {event.eventTime}{event.remarks ? ` · ${event.remarks}` : ''}</small>)}</div></article>)}</section>}
       {tab === 'versions' && workflow?.canEditDocuments && selected.documentStatus === 'ACTIVE' && <div className={styles.detailActions}><button className={styles.primaryButton} onClick={openVersion}><i className="bi bi-plus-lg" /> Create new version</button></div>}
    </aside>}</section> : <section className={styles.caseDetailEmpty}>Select or open a transaction case above to manage its documents.</section>}
    {preview && <div className={styles.backdrop} onMouseDown={closePreview}><section className={`${styles.dialog} ${styles.previewDialog}`} onMouseDown={e => e.stopPropagation()}><header><div><span>XEM TÀI LIỆU</span><h2>{preview.name}</h2><p>Nội dung được đọc từ object storage của docsvc.</p></div><button onClick={closePreview}><i className="bi bi-x-lg" /></button></header><iframe className={styles.previewFrame} src={preview.url} title={preview.name} /><footer><button className={styles.primaryButton} onClick={closePreview}>Đóng</button></footer></section></div>}
    {uploadOpen && <div className={styles.backdrop} onMouseDown={() => !uploading && setUploadOpen(false)}><section className={styles.dialog} onMouseDown={event => event.stopPropagation()}><header><div><span>NEW TRANSACTION DOCUMENT</span><h2>Upload document</h2><p>{activeCase ? `Attach a document to ${activeCase.objectType} · ${activeCase.objectRef}.` : 'Create a transaction case when it does not yet exist, then link the file to it.'}</p></div><button onClick={() => setUploadOpen(false)}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><div className={styles.formGrid}>
      {!activeCase && <><label className={styles.field}><span>Case type</span><select value={uploadForm.objectType} onChange={event => setUploadForm(current => ({ ...current, objectType: event.target.value }))}><option value="">Select type</option>{metadata.objectTypes.map(item => <option key={item.code} value={item.code}>{item.code}</option>)}</select></label>
      <label className={styles.field}><span>Case reference</span><input value={uploadForm.objectRef} onChange={event => setUploadForm(current => ({ ...current, objectRef: event.target.value }))} placeholder="Example: LC2026HN001" /></label>
      <label className={styles.field}><span>Branch</span><input value={uploadForm.branchCode} onChange={event => setUploadForm(current => ({ ...current, branchCode: event.target.value }))} placeholder="Example: HN001" /></label></>}
      <label className={styles.field}><span>Category</span><select value={uploadForm.categoryCode} onChange={event => setUploadForm(current => ({ ...current, categoryCode: event.target.value, typeCode: '' }))}>{metadata.categories.map(item => <option key={item.code} value={item.code}>{item.description}</option>)}</select></label>
      <label className={styles.field}><span>Document type</span><select value={uploadForm.typeCode} onChange={event => setUploadForm(current => ({ ...current, typeCode: event.target.value }))}><option value="">Select type</option>{availableTypes.map(item => <option key={item.code} value={item.code}>{item.description}</option>)}</select></label>
      <label className={styles.field}><span>Reference text</span><input value={uploadForm.referenceText} onChange={event => setUploadForm(current => ({ ...current, referenceText: event.target.value }))} /></label>
      {!activeCase && <><label className={styles.field}><span>Customer number</span><input value={uploadForm.customerNumber} onChange={event => setUploadForm(current => ({ ...current, customerNumber: event.target.value }))} /></label>
      <label className={styles.field}><span>Customer name</span><input value={uploadForm.customerName} onChange={event => setUploadForm(current => ({ ...current, customerName: event.target.value }))} /></label></>}
    </div><label className={styles.upload}><i className="bi bi-upload fs-4" /><strong>{uploadFile?.name || 'Choose upload file'}</strong><small>PDF, DOC, DOCX, XLS, XLSX — maximum 20 MB</small><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" onChange={event => setUploadFile(event.target.files?.[0])} /></label></div><footer><button onClick={() => setUploadOpen(false)} disabled={uploading}>Cancel</button><button className={styles.primaryButton} onClick={() => void upload()} disabled={uploading}>{uploading ? 'Uploading...' : 'Create and upload'}</button></footer></section></div>}
    {versionOpen && <div className={styles.backdrop} onMouseDown={() => !versionUploading && setVersionOpen(false)}><section className={styles.dialog} onMouseDown={event => event.stopPropagation()}><header><div><span>NEW DOCUMENT VERSION</span><h2>Create version {selected ? Number(selected.version) + 1 : ''}</h2><p>The previous version remains immutable and is marked SUPERSEDED after the clean file is accepted.</p></div><button onClick={() => setVersionOpen(false)}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><label className={styles.field}><span>Reference text</span><input value={versionReference} onChange={event => setVersionReference(event.target.value)} /></label><label className={styles.upload}><i className="bi bi-upload fs-4" /><strong>{versionFile?.name || 'Choose replacement file'}</strong><small>PDF, DOC, DOCX, XLS, XLSX — scanned by ClamAV before storage</small><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" onChange={event => setVersionFile(event.target.files?.[0])} /></label></div><footer><button onClick={() => setVersionOpen(false)} disabled={versionUploading}>Cancel</button><button className={styles.primaryButton} onClick={() => void createVersion()} disabled={versionUploading}>{versionUploading ? 'Uploading...' : 'Create version'}</button></footer></section></div>}
    {removeOpen && <div className={styles.backdrop} onMouseDown={() => !removing && setRemoveOpen(false)}><section className={`${styles.dialog} ${styles.rejectDialog}`} onMouseDown={event => event.stopPropagation()}><header><div><span>XÓA TÀI LIỆU</span><h2>Xóa khỏi hồ sơ</h2><p>File không bị xóa khỏi WORM storage; trạng thái sẽ chuyển sang SUPERSEDED và lý do được ghi audit.</p></div><button onClick={() => setRemoveOpen(false)} disabled={removing}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><label className={styles.field}><span>Lý do xóa <b>*</b></span><textarea autoFocus value={removalReason} onChange={event => setRemovalReason(event.target.value)} placeholder="Nhập lý do xóa tài liệu..." /></label><p className={styles.requiredHint}>Lý do là bắt buộc và không thể chỉnh sửa trong audit.</p></div><footer><button onClick={() => setRemoveOpen(false)} disabled={removing}>Hủy</button><button className={styles.rejectConfirmButton} disabled={removing || !removalReason.trim()} onClick={() => void removeDocument()}>{removing ? 'Đang xóa...' : 'Xác nhận xóa'}</button></footer></section></div>}
    {workflowAction && <div className={styles.backdrop} onMouseDown={() => !workflowActing && setWorkflowAction(undefined)}><section className={`${styles.dialog} ${styles.rejectDialog}`} onMouseDown={event => event.stopPropagation()}><header><div><span>WORKFLOW</span><h2>{actionLabel(workflowAction.action)}</h2><p>Nhập lý do để lưu cùng lịch sử xử lý hồ sơ.</p></div><button onClick={() => setWorkflowAction(undefined)} disabled={workflowActing}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><label className={styles.field}><span>Lý do trả về <b>*</b></span><textarea autoFocus value={workflowRemarks} onChange={event => setWorkflowRemarks(event.target.value)} placeholder="Nhập lý do trả về..." /></label><p className={styles.requiredHint}>Lý do là bắt buộc theo workflow definition.</p></div><footer><button onClick={() => setWorkflowAction(undefined)} disabled={workflowActing}>Hủy</button><button className={styles.rejectConfirmButton} disabled={workflowActing || !workflowRemarks.trim()} onClick={() => void actWorkflow(workflowAction, workflowRemarks)}>Xác nhận trả về</button></footer></section></div>}
    {amendmentOpen && <div className={styles.backdrop} onMouseDown={() => !amendmentOpening && setAmendmentOpen(false)}><section className={`${styles.dialog} ${styles.rejectDialog}`} onMouseDown={event => event.stopPropagation()}><header><div><span>AMENDMENT</span><h2>Mở cycle thay đổi mới</h2><p>Hồ sơ sẽ quay về DRAFT, mở khóa tài liệu để chỉnh sửa và phải duyệt lại.</p></div><button onClick={() => setAmendmentOpen(false)} disabled={amendmentOpening}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><label className={styles.field}><span>Lý do amendment <b>*</b></span><textarea autoFocus value={amendmentReason} onChange={event => setAmendmentReason(event.target.value)} placeholder="Ví dụ: Bổ sung/chỉnh sửa chứng từ..." /></label><p className={styles.requiredHint}>Lý do được lưu cố định trong lịch sử amendment.</p></div><footer><button onClick={() => setAmendmentOpen(false)} disabled={amendmentOpening}>Hủy</button><button className={styles.primaryButton} disabled={amendmentOpening || !amendmentReason.trim()} onClick={() => void createAmendment()}>{amendmentOpening ? 'Đang mở...' : 'Mở amendment'}</button></footer></section></div>}
    {amendmentCancelOpen && <div className={styles.backdrop} onMouseDown={() => !amendmentCancelling && setAmendmentCancelOpen(false)}><section className={`${styles.dialog} ${styles.rejectDialog}`} onMouseDown={event => event.stopPropagation()}><header><div><span>AMENDMENT</span><h2>Hủy cycle đang mở</h2><p>Thay đổi trong cycle này sẽ bị loại khỏi tập ACTIVE; snapshot đã duyệt gần nhất được khôi phục và khóa lại.</p></div><button onClick={() => setAmendmentCancelOpen(false)} disabled={amendmentCancelling}><i className="bi bi-x-lg" /></button></header><div className={styles.dialogBody}><label className={styles.field}><span>Lý do hủy</span><textarea autoFocus value={amendmentReason} onChange={event => setAmendmentReason(event.target.value)} placeholder="Lý do hủy amendment (không bắt buộc)..." /></label></div><footer><button onClick={() => setAmendmentCancelOpen(false)} disabled={amendmentCancelling}>Đóng</button><button className={styles.rejectConfirmButton} disabled={amendmentCancelling} onClick={() => void cancelAmendment()}>{amendmentCancelling ? 'Đang hủy...' : 'Xác nhận hủy amendment'}</button></footer></section></div>}
  </main></AppContent>
}
