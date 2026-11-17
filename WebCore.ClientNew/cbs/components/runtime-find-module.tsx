'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'next/navigation'
import DataGrid, { Column, ColumnChooser, Editing, FilterPanel, FilterRow, Form as EditingForm, Pager, Paging, Popup, Scrolling, Selection, Sorting } from 'devextreme-react/data-grid'
import Button from 'devextreme-react/button'
import DropDownButton from 'devextreme-react/drop-down-button'
import LoadPanel from 'devextreme-react/load-panel'
import { AppContent, Toast } from '@adminlte/react'
import { webCoreApi } from '@/lib/webcore-api'
import { webCoreLanguageStorageKey } from '@/components/language-selector'

type Row = Record<string, unknown>
type SearchField = { paramName: string; label: string; editorType?: string; required?: boolean; defaultValue?: unknown; Lookup?: { dataSource?: Array<{ value: unknown; text?: string }> } }
type ColumnConfig = { dataField: string; dataType?: string; format?: string; width?: number; alignment?: string; permission?: { add?: string; edit?: string } }
type ToolbarButton = { icon?: string; type?: string; onClick?: string; textKey?: string; text?: string; textResolver?: string; stylingMode?: string; _OperationAction?: string; _enableOnSelected?: boolean; _showConfirm?: boolean }
type ToolbarDropDown = { icon?: string; textKey?: string; text?: string; stylingMode?: string; items?: ToolbarButton[] }
type UiResponse = {
  moduleId: string
  moduleName: string
  configUi?: {
    SearchPanel?: { Fields?: SearchField[]; colCount?: number }
    Toolbar?: Array<{ Button?: ToolbarButton; DropDownButton?: ToolbarDropDown; div?: { className?: string } }>
    GridConfig?: { Columns?: ColumnConfig[]; keyExpr?: string | string[]; Editing?: { allowAdding?: boolean; allowUpdating?: boolean; allowDeleting?: boolean; Popup?: { width?: number; height?: number | string; maxHeight?: number | string }; Form?: { labelMode?: string; labelLocation?: string } }; Paging?: { defaultPageSize?: number }; Pager?: { allowedPageSizes?: number[] } }
  }
  language?: Record<string, Record<string, string>>
  permission?: { actions?: string[] }
}
type DataResponse = { data?: Row[]; totalCount?: number }
type ToastState = { message: string; theme: 'danger' | 'warning' }

const isMissing = (value: unknown) => value == null || (typeof value === 'string' && !value.trim())

export function RuntimeFindModule() {
  const { moduleId = '' } = useParams<{ moduleId: string }>()
  const [ui, setUi] = useState<UiResponse>()
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [rows, setRows] = useState<Row[]>([])
  const [selected, setSelected] = useState<Row>()
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState<ToastState>()
  const [filterPanelVisible, setFilterPanelVisible] = useState(false)
  const [editingMode, setEditingMode] = useState<'add' | 'edit'>('edit')
  const gridRef = useRef<DataGrid<Row, any>>(null)
  const language = typeof window === 'undefined' ? 'VN' : (window.localStorage.getItem(webCoreLanguageStorageKey) || 'VN').toUpperCase()
  const labels = ui?.language?.[language] ?? ui?.language?.VN ?? ui?.language?.VI ?? {}
  const fields = ui?.configUi?.SearchPanel?.Fields ?? []
  const columns = ui?.configUi?.GridConfig?.Columns ?? []
  const keyExpr = ui?.configUi?.GridConfig?.keyExpr
  const keyField = Array.isArray(keyExpr) ? keyExpr[0] : keyExpr ?? 'id'
  const title = labels['Module$Name'] ?? ui?.moduleName ?? moduleId
  const caption = (field: string) => labels[`Column$${field}.text`] ?? field
  const showToast = (message: string, theme: ToastState['theme'] = 'danger') => setToast({ message, theme })
  // JDBC labels from PostgreSQL/Oracle can have a different case from the
  // metadata field name (for example `name` versus `Name`).  DevExtreme uses
  // exact keys, so normalize once before displaying or editing the row.
  const normalizeRow = (row: Row): Row => {
    const normalized = { ...row }
    columns.forEach(column => {
      const sourceKey = Object.keys(row).find(key => key.toLowerCase() === column.dataField.toLowerCase())
      if (sourceKey) normalized[column.dataField] = row[sourceKey]
    })
    return normalized
  }
  const canWrite = (action: string) => ui?.permission?.actions?.some(value => value.toLowerCase() === action.toLowerCase()) ?? false
  const buttonText = useCallback((button: ToolbarButton | ToolbarDropDown) => {
    if ('textResolver' in button && button.textResolver === 'filterPanelLabel') return filterPanelVisible ? 'Ẩn bộ lọc' : 'Hiện bộ lọc'
    const fallback: Record<string, string> = { Export: 'Xuất', Export_Excel: 'Xuất Excel', Export_PDF: 'Xuất PDF' }
    return button.textKey ? labels[`Toolbar$${button.textKey}.text`] ?? button.text ?? fallback[button.textKey] ?? button.textKey : button.text ?? ''
  }, [filterPanelVisible, labels])

  const loadUi = useCallback(async () => {
    if (!moduleId) return
    setLoading(true)
    try {
      const result = await webCoreApi<UiResponse>(`/module/${encodeURIComponent(moduleId)}/ui`)
      setUi(result)
      setValues(Object.fromEntries((result.configUi?.SearchPanel?.Fields ?? []).map(field => [field.paramName, field.defaultValue ?? ''])))
    } catch (cause) { showToast(cause instanceof Error ? cause.message : 'Cannot load module.') } finally { setLoading(false) }
  }, [moduleId])

  useEffect(() => {
    setUi(undefined); setRows([]); setSelected(undefined); setSearched(false); void loadUi()
  }, [loadUi])

  const search = useCallback(async () => {
    const required = fields.find(field => field.required !== false && isMissing(values[field.paramName]))
    if (required) { showToast(`${labels[`SearchPanel$${required.label}.text`] ?? required.label} is required.`, 'warning'); return }
    setLoading(true)
    try {
      const result = await webCoreApi<DataResponse>(`/module/${encodeURIComponent(moduleId)}/data/view`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ skip: 0, take: 500, requireTotalCount: true, requireGroupCount: false, parameters: values }) })
      setRows((result.data ?? []).map(normalizeRow)); setSelected(undefined); setSearched(true)
    } catch (cause) { setRows([]); showToast(cause instanceof Error ? cause.message : 'Cannot load search results.') } finally { setLoading(false) }
  }, [columns, fields, labels, moduleId, values])

  const write = async (method: 'POST' | 'PUT' | 'DELETE', suffix: string, body: Record<string, unknown>): Promise<boolean> => {
    setLoading(true)
    try {
      await webCoreApi(`/find/${encodeURIComponent(moduleId)}/${suffix}`, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      await search()
      return false
    } catch (cause) {
      showToast(cause instanceof Error ? cause.message : 'Cannot save record.')
      return true
    } finally { setLoading(false) }
  }

  const runOperation = async (button: ToolbarButton) => {
    if (!button._OperationAction) return
    if (button._showConfirm && !window.confirm(`Run ${buttonText(button)} for the selected record?`)) return
    setLoading(true)
    try {
      const selectedRows = selected ? [selected] : []
      const selectedKeys = selected ? [selected[keyField]] : []
      const result = await webCoreApi<{ result?: unknown }>(`/module/${encodeURIComponent(moduleId)}/operation`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operationKey: button._OperationAction, operationAction: button._OperationAction, selectedRowKeys: selectedKeys, selectedRowsData: selectedRows, parameters: values }) })
      window.alert(typeof result.result === 'string' ? result.result : JSON.stringify(result.result ?? 'Completed'))
      await search()
    } catch (cause) { showToast(cause instanceof Error ? cause.message : 'Operation failed.') } finally { setLoading(false) }
  }

  const loadExportRows = async () => {
    const allRows: Row[] = []
    const pageSize = 5000
    for (let skip = 0; ; skip += pageSize) {
      const result = await webCoreApi<DataResponse>(`/module/${encodeURIComponent(moduleId)}/data/view`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ skip, take: pageSize, requireTotalCount: true, requireGroupCount: false, parameters: values }),
      })
      const page = (result.data ?? []).map(normalizeRow)
      allRows.push(...page)
      if (page.length < pageSize || (typeof result.totalCount === 'number' && allRows.length >= result.totalCount)) return allRows
    }
  }

  const fileName = () => `${String(title || moduleId || 'export').replace(/[\\/:*?"<>|]+/g, '_')}_${new Date().toISOString().replace(/[:.]/g, '-')}`
  const excelValue = (value: unknown) => typeof value === 'boolean' ? (value ? 'TRUE' : 'FALSE') : value ?? ''

  const exportToXlsx = async () => {
    setLoading(true)
    try {
      const [exportRows, excel, fileSaver] = await Promise.all([loadExportRows(), import('devextreme-exceljs-fork/dist/dx-exceljs-fork.min.js'), import('file-saver-es')])
      const workbook = new excel.Workbook()
      const worksheet = workbook.addWorksheet('Main sheet')
      const header = columns.map(column => caption(column.dataField))
      worksheet.addRow(header)
      exportRows.forEach(row => worksheet.addRow(columns.map(column => excelValue(row[column.dataField]))))
      worksheet.getRow(1).font = { bold: true }
      worksheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: Math.max(header.length, 1) } }
      columns.forEach((column, index) => {
        const longest = Math.max(header[index]?.length ?? 8, ...exportRows.slice(0, 250).map(row => String(excelValue(row[column.dataField])).length))
        worksheet.getColumn(index + 1).width = Math.min(Math.max(longest + 2, 10), 48)
      })
      const buffer = await workbook.xlsx.writeBuffer()
      fileSaver.saveAs(new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `${fileName()}.xlsx`)
    } catch (cause) { showToast(cause instanceof Error ? cause.message : 'Cannot export Excel.') } finally { setLoading(false) }
  }

  const exportToPdf = async () => {
    setLoading(true)
    try {
      const [exportRows, pdf, autoTable] = await Promise.all([loadExportRows(), import('jspdf'), import('jspdf-autotable')])
      const document = new pdf.jsPDF({ orientation: 'landscape' })
      autoTable.default(document, { head: [columns.map(column => caption(column.dataField))], body: exportRows.map(row => columns.map(column => String(excelValue(row[column.dataField])))), styles: { fontSize: 7 } })
      document.save(`${fileName()}.pdf`)
    } catch (cause) { showToast(cause instanceof Error ? cause.message : 'Cannot export PDF.') } finally { setLoading(false) }
  }

  const executeToolbarAction = (button: ToolbarButton) => {
    const grid = gridRef.current?.instance
    if (button.onClick === 'onAddClick') grid?.addRow()
    else if (button.onClick === 'onEditClick' && selected) grid?.editRow(grid.getRowIndexByKey(selected[keyField]))
    else if (button.onClick === 'onDeleteClick' && selected) grid?.deleteRow(grid.getRowIndexByKey(selected[keyField]))
    else if (button.onClick === 'refresh') void search()
    else if (button.onClick === 'toggleFilterPanel') setFilterPanelVisible(current => !current)
    else if (button.onClick === 'showColumnChooser') grid?.showColumnChooser()
    else if (button.onClick === 'exportToXSLX') void exportToXlsx()
    else if (button.onClick === 'exportToPDF') void exportToPdf()
    else if (button._OperationAction) void runOperation(button)
  }

  const isToolbarDisabled = (button: ToolbarButton) => {
    const writeAction = button.onClick === 'onAddClick' ? 'Add' : button.onClick === 'onEditClick' ? 'Edit' : button.onClick === 'onDeleteClick' ? 'Delete' : undefined
    return loading || (!!writeAction && !canWrite(writeAction)) || ((button.onClick === 'onEditClick' || button.onClick === 'onDeleteClick' || button._enableOnSelected) && !selected) || !searched
  }

  const searchControls = useMemo(() => fields.map(field => {
    const label = labels[`SearchPanel$${field.label}.text`] ?? field.label
    const value = values[field.paramName]; const lookupItems = field.Lookup?.dataSource
    return <div className="col-sm-6 col-lg-3" key={field.paramName}><label className="form-label" htmlFor={field.paramName}>{label}{field.required !== false ? ' *' : ''}</label>
      {lookupItems ? <select id={field.paramName} className="form-select" value={String(value ?? '')} onChange={event => setValues(current => ({ ...current, [field.paramName]: event.target.value }))}><option value="" />{lookupItems.map(item => <option key={String(item.value)} value={String(item.value)}>{item.text ?? String(item.value)}</option>)}</select> : <input id={field.paramName} className="form-control" type={field.editorType === 'dxDateBox' ? 'date' : 'text'} value={String(value ?? '')} onChange={event => setValues(current => ({ ...current, [field.paramName]: event.target.value }))} />}
    </div>
  }), [fields, labels, values])

  const toolbarItems = ui?.configUi?.Toolbar ?? []
  const pageSize = ui?.configUi?.GridConfig?.Paging?.defaultPageSize ?? 25
  const allowedPageSizes = ui?.configUi?.GridConfig?.Pager?.allowedPageSizes ?? [25, 50, 100, 200, 500]

  return <AppContent>
    <section className="runtime-find-page-toolbar"><h2 className="runtime-find-page-title">{title}</h2><div className="runtime-find-toolbar-actions">{toolbarItems.map((item, index) => {
      if (item.div) return <span className="runtime-find-toolbar-separator" key={`page-divider-${index}`} />
      if (item.DropDownButton) { const dropdown = item.DropDownButton; return <DropDownButton key={`page-dropdown-${index}`} icon={dropdown.icon} text={buttonText(dropdown)} stylingMode={dropdown.stylingMode === 'contained' ? 'contained' : 'text'} items={(dropdown.items ?? []).map(button => ({ ...button, text: buttonText(button), disabled: isToolbarDisabled(button) }))} displayExpr="text" keyExpr="textKey" dropDownOptions={{ minWidth: 160, wrapperAttr: { class: 'runtime-find-export-menu' } }} disabled={loading || !searched} onItemClick={event => executeToolbarAction(event.itemData as ToolbarButton)} /> }
      if (!item.Button) return null
      const button = item.Button
      return <Button key={`page-button-${index}`} icon={button.icon} text={buttonText(button)} type={button.type === 'default' ? 'default' : undefined} stylingMode={button.stylingMode === 'contained' ? 'contained' : 'text'} disabled={isToolbarDisabled(button)} onClick={() => executeToolbarAction(button)} />
    })}</div></section>
    <section className="card runtime-find-search-card mb-3"><div className="card-header"><h3 className="card-title mb-0">{title}</h3></div><div className="card-body runtime-find-search-body"><div className="row g-3 runtime-find-search-fields">{searchControls}</div><div className="runtime-find-search-actions"><button className="btn btn-primary" disabled={loading} onClick={() => void search()}>{labels['SearchPanel$Search.text'] ?? 'Search'}</button></div></div></section>
    <section className="card runtime-find-card"><div className="card-header runtime-find-toolbar"><h3 className="card-title mb-0">{title}</h3><div className="runtime-find-toolbar-actions">{toolbarItems.map((item, index) => {
      if (item.div) return <span className="runtime-find-toolbar-separator" key={`divider-${index}`} />
      if (item.DropDownButton) { const dropdown = item.DropDownButton; return <DropDownButton key={`dropdown-${index}`} icon={dropdown.icon} text={buttonText(dropdown)} stylingMode={dropdown.stylingMode === 'contained' ? 'contained' : 'text'} items={(dropdown.items ?? []).map(button => ({ ...button, text: buttonText(button), disabled: isToolbarDisabled(button) }))} displayExpr="text" keyExpr="textKey" dropDownOptions={{ minWidth: 160, wrapperAttr: { class: 'runtime-find-export-menu' } }} disabled={loading || !searched} onItemClick={event => executeToolbarAction(event.itemData as ToolbarButton)} /> }
      if (!item.Button) return null
      const button = item.Button
      return <Button key={`button-${index}`} icon={button.icon} text={buttonText(button)} type={button.type === 'default' ? 'default' : undefined} stylingMode={button.stylingMode === 'contained' ? 'contained' : 'text'} disabled={isToolbarDisabled(button)} onClick={() => executeToolbarAction(button)} />
    })}</div></div><div className="card-body p-2 runtime-find-card-body">
      {!searched ? <div className="runtime-find-empty-state">Nhập điều kiện tìm kiếm và nhấn Tìm kiếm để hiển thị dữ liệu.</div> : <DataGrid ref={gridRef} dataSource={rows} keyExpr={keyField} height="100%" showBorders hoverStateEnabled rowAlternationEnabled columnAutoWidth wordWrapEnabled={false} onSelectionChanged={event => setSelected(event.selectedRowsData[0])} onEditingStart={() => setEditingMode('edit')} onInitNewRow={event => { setEditingMode('add'); columns.filter(column => column.dataType === 'boolean').forEach(column => { event.data[column.dataField] = false }) }} onRowInserting={event => { event.cancel = write('POST', 'add', event.data) }} onRowUpdating={event => { event.cancel = write('PUT', 'update', { key: event.key, values: { ...event.oldData, ...event.newData } }) }} onRowRemoving={event => { event.cancel = write('DELETE', 'delete', { key: event.key }) }}><Selection mode="single" /><Sorting mode="multiple" /><FilterRow visible applyFilter="auto" /><FilterPanel visible={filterPanelVisible} /><ColumnChooser enabled mode="select" /><Paging defaultPageSize={pageSize} /><Pager visible showInfo showPageSizeSelector allowedPageSizes={allowedPageSizes} displayMode="compact" /><Editing mode="popup" allowAdding={!!ui?.configUi?.GridConfig?.Editing?.allowAdding && canWrite('Add')} allowUpdating={!!ui?.configUi?.GridConfig?.Editing?.allowUpdating && canWrite('Edit')} allowDeleting={!!ui?.configUi?.GridConfig?.Editing?.allowDeleting && canWrite('Delete')}><Popup showTitle title={editingMode === 'add' ? labels['Popup$Add$Title.text'] ?? 'Thêm sản phẩm' : labels['Popup$Edit$Title.text'] ?? 'Sửa sản phẩm'} width={ui?.configUi?.GridConfig?.Editing?.Popup?.width ?? 700} height={ui?.configUi?.GridConfig?.Editing?.Popup?.height ?? 'auto'} maxHeight={ui?.configUi?.GridConfig?.Editing?.Popup?.maxHeight ?? '80vh'} /><EditingForm colCount={2} labelMode={ui?.configUi?.GridConfig?.Editing?.Form?.labelMode ?? 'outside'} labelLocation={ui?.configUi?.GridConfig?.Editing?.Form?.labelLocation ?? 'left'} /></Editing><Scrolling mode="virtual" columnRenderingMode="standard" useNative={false} />{columns.map(column => <Column key={column.dataField} dataField={column.dataField} caption={caption(column.dataField)} dataType={column.dataType === 'datetime' ? 'datetime' : column.dataType === 'boolean' ? 'boolean' : undefined} format={column.format} width={column.width} alignment={column.alignment} allowEditing={column.permission?.edit === 'rw'} />)}</DataGrid>}
    </div></section><LoadPanel visible={loading} shading />
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}>
      <Toast
        autohideMs={6000}
        onClose={() => setToast(undefined)}
        show={Boolean(toast)}
        theme={toast?.theme}
        title={toast?.theme === 'warning' ? 'Cảnh báo' : 'Lỗi'}
      >
        {toast?.message ?? ''}
      </Toast>
    </div>
  </AppContent>
}
