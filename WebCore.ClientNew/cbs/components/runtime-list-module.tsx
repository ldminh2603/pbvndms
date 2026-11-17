'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams } from 'next/navigation'
import Button from 'devextreme-react/button'
import DataGrid, { Column, FilterRow, Scrolling, Selection, Sorting } from 'devextreme-react/data-grid'
import LoadPanel from 'devextreme-react/load-panel'
import SelectBox from 'devextreme-react/select-box'
import Form from 'devextreme-react/form'
import Popup from 'devextreme-react/popup'
import { AppContent } from '@adminlte/react'
import { webCoreApi } from '@/lib/webcore-api'
import { webCoreLanguageStorageKey } from '@/components/language-selector'

type Row = Record<string, unknown>
type LookupConfig = { dataSource?: Row[]; dataSourceEdit?: Row[]; valueExpr?: string; displayExpr?: string }
type ColumnConfig = { dataField: string; dataType?: string; format?: string; width?: number; alignment?: string; colSpan?: number; permission?: { add?: string; edit?: string }; EditorType?: string; EditorOptions?: Record<string, unknown>; Lookup?: LookupConfig; RequiredRule?: boolean | { trim?: boolean } }
type ToolbarButton = { icon?: string; type?: string; onClick?: string; textKey?: string; text?: string; stylingMode?: string }
type EditingConfig = { Popup?: { width?: number | string; height?: number | string; maxHeight?: number | string }; Form?: { colCount?: number; labelLocation?: 'left' | 'right' | 'top'; labelMode?: 'outside' | 'static' | 'floating' | 'hidden' } }
type UiResponse = { moduleId: string; moduleName: string; configUi?: { Toolbar?: Array<{ Button?: ToolbarButton }>; GridConfig?: { Columns?: ColumnConfig[]; keyExpr?: string | string[]; Paging?: { defaultPageSize?: number }; Pager?: { allowedPageSizes?: number[] }; Editing?: EditingConfig } }; language?: Record<string, Record<string, string>>; permission?: { actions?: string[] } }
type DataResponse = { data?: Row[]; totalCount?: number }
type EditorState = { mode: 'add' | 'edit'; row: Row }

const canWrite = (permission: UiResponse['permission'], action: string) => permission?.actions?.some(value => value.toLowerCase() === action.toLowerCase()) ?? false
const text = (value: unknown) => value == null ? '' : String(value)

// The database chooses EditorType. This registry only maps DevExtreme's public
// editor name to its client module so the editor is registered before Form renders.
const editorModuleLoaders: Record<string, () => Promise<unknown>> = {
  dxtextbox: () => import('devextreme-react/text-box'),
  dxtextarea: () => import('devextreme-react/text-area'),
  dxcheckbox: () => import('devextreme-react/check-box'),
  dxdatebox: () => import('devextreme-react/date-box'),
  dxnumberbox: () => import('devextreme-react/number-box'),
  dxselectbox: () => import('devextreme-react/select-box'),
  dxtagbox: () => import('devextreme-react/tag-box'),
  dxcolorbox: () => import('devextreme-react/color-box'),
  dxswitch: () => import('devextreme-react/switch'),
}

const resolveEditorType = (column: ColumnConfig) => {
  if (typeof column.EditorType === 'string' && column.EditorType.trim()) return column.EditorType.trim()
  if (column.Lookup) return 'dxSelectBox'
  if (column.dataType === 'boolean') return 'dxCheckBox'
  if (column.dataType === 'date' || column.dataType === 'datetime') return 'dxDateBox'
  return undefined
}

export function RuntimeListModule() {
  const { moduleId = '' } = useParams<{ moduleId: string }>()
  const [ui, setUi] = useState<UiResponse>()
  const [rows, setRows] = useState<Row[]>([])
  const [totalCount, setTotalCount] = useState(0)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [selected, setSelected] = useState<Row>()
  const [editor, setEditor] = useState<EditorState>()
  const [editorError, setEditorError] = useState<string>()
  const [editorModulesReady, setEditorModulesReady] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>()
  const language = typeof window === 'undefined' ? 'VN' : (window.localStorage.getItem(webCoreLanguageStorageKey) || 'VN').toUpperCase()
  const labels = ui?.language?.[language] ?? ui?.language?.VN ?? ui?.language?.VI ?? {}
  const caption = (field: string) => labels[`Column$${field}.text`] ?? field.toUpperCase()
  const title = labels['Module$Name'] ?? ui?.moduleName ?? moduleId
  const columns = ui?.configUi?.GridConfig?.Columns ?? []
  const configuredKey = ui?.configUi?.GridConfig?.keyExpr
  const keyExpr = configuredKey ?? 'id'
  const keyField = Array.isArray(keyExpr) ? keyExpr[0] : keyExpr
  const rowKey = (row: Row) => Array.isArray(keyExpr)
    ? Object.fromEntries(keyExpr.map(field => [field, row[field]]))
    : row[keyExpr]
  const pageSizes = ui?.configUi?.GridConfig?.Pager?.allowedPageSizes ?? [25, 50, 100, 200, 500]
  const pageCount = Math.max(1, Math.ceil(totalCount / pageSize))
  const toolbarButtons = (ui?.configUi?.Toolbar ?? []).flatMap(item => item.Button ? [item.Button] : [])
  const editorColumns = useMemo(
    () => editor ? columns.filter(column => (editor.mode === 'add' ? column.permission?.add : column.permission?.edit) !== 'di') : [],
    [columns, editor?.mode],
  )
  const isEditorReadOnly = (column: ColumnConfig) => (editor?.mode === 'add' ? column.permission?.add : column.permission?.edit) !== 'rw'
  const editingConfig = ui?.configUi?.GridConfig?.Editing
  const editingForm = editingConfig?.Form
  const editingPopup = editingConfig?.Popup

  const requiredEditorTypes = useMemo(() => {
    return Array.from(new Set(editorColumns.map(resolveEditorType).filter((value): value is string => Boolean(value))))
  }, [editorColumns])

  useEffect(() => {
    if (!editor) {
      setEditorModulesReady(false)
      return
    }

    const unsupportedTypes = requiredEditorTypes.filter(type => !editorModuleLoaders[type.toLowerCase()])
    if (unsupportedTypes.length) {
      setEditorModulesReady(false)
      setEditorError(`Unsupported EditorType in module metadata: ${unsupportedTypes.join(', ')}`)
      return
    }

    let active = true
    setEditorModulesReady(false)
    void Promise.all(requiredEditorTypes.map(type => editorModuleLoaders[type.toLowerCase()]()))
      .then(() => { if (active) setEditorModulesReady(true) })
      .catch(() => { if (active) setEditorError('Cannot load the configured form editor.') })

    return () => { active = false }
  }, [editor?.mode, requiredEditorTypes])

  const loadUi = useCallback(async () => {
    if (!moduleId) return
    setError(undefined)
    try {
      const nextUi = await webCoreApi<UiResponse>(`/module/${encodeURIComponent(moduleId)}/ui`)
      setUi(nextUi)
      const defaultPageSize = nextUi.configUi?.GridConfig?.Paging?.defaultPageSize
      if (typeof defaultPageSize === 'number' && defaultPageSize > 0) setPageSize(defaultPageSize)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Cannot load module.')
    }
  }, [moduleId])

  const loadData = useCallback(async (nextPage = page, nextPageSize = pageSize) => {
    if (!moduleId) return
    setLoading(true)
    setError(undefined)
    try {
      const nextData = await webCoreApi<DataResponse>(`/module/${encodeURIComponent(moduleId)}/data/view`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ skip: nextPage * nextPageSize, take: nextPageSize, requireTotalCount: true, requireGroupCount: false }),
      })
      setRows(nextData.data ?? [])
      setTotalCount(nextData.totalCount ?? 0)
      setSelected(undefined)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Cannot load module data.')
    } finally { setLoading(false) }
  }, [moduleId, page, pageSize])

  // Only a module change loads its UI metadata. Refresh and CRUD reload rows.
  useEffect(() => {
    setUi(undefined)
    setRows([])
    setTotalCount(0)
    setPage(0)
    setSelected(undefined)
    void loadUi()
    void loadData()
  }, [moduleId])

  const save = async () => {
    if (!editor) return
    const requiredColumn = editorColumns.find(column => column.RequiredRule && !isEditorReadOnly(column) && !text(editor.row[column.dataField]).trim())
    if (requiredColumn) {
      setEditorError(`${caption(requiredColumn.dataField)} is required.`)
      return
    }
    const action = editor.mode === 'add' ? 'Add' : 'Edit'
    setSaving(true)
    try {
      const listPath = `/list/${encodeURIComponent(moduleId)}/${editor.mode === 'add' ? 'add' : 'update'}`
      await webCoreApi(listPath, { method: editor.mode === 'add' ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(editor.mode === 'edit' ? { key: rowKey(editor.row), values: editor.row } : editor.row) })
      setEditor(undefined)
      await loadData(page, pageSize)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Cannot save record.') } finally { setSaving(false) }
  }

  const remove = async () => {
    if (!selected || !window.confirm(`Xóa bản ghi đã chọn?`)) return
    setSaving(true)
    try {
      await webCoreApi(`/list/${encodeURIComponent(moduleId)}/delete`, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key: rowKey(selected) }) })
      await loadData(page, pageSize)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Cannot delete record.') } finally { setSaving(false) }
  }

  const toolbarText = (button: ToolbarButton) => button.textKey
    ? labels[`Toolbar$${button.textKey}.text`] ?? button.text ?? button.textKey
    : button.text ?? ''
  const isToolbarVisible = (button: ToolbarButton) => {
    const requiredAction = button.onClick === 'onAddClick' ? 'Add'
      : button.onClick === 'onEditClick' ? 'Edit'
        : button.onClick === 'onDeleteClick' ? 'Delete' : undefined
    return !requiredAction || canWrite(ui?.permission, requiredAction)
  }
  const isToolbarDisabled = (button: ToolbarButton) => !selected && (button.onClick === 'onEditClick' || button.onClick === 'onDeleteClick')
  const executeToolbarClick = (button: ToolbarButton) => {
    if (button.onClick === 'onAddClick') { setEditorError(undefined); setEditor({ mode: 'add', row: {} }) }
    else if (button.onClick === 'onEditClick' && selected) { setEditorError(undefined); setEditor({ mode: 'edit', row: { ...selected } }) }
    else if (button.onClick === 'onDeleteClick') void remove()
    else if (button.onClick === 'refresh') void loadData()
  }

  const formItems = editorColumns.map(column => {
    const editorType = resolveEditorType(column)
    const readOnly = isEditorReadOnly(column)
    const lookup = column.Lookup
    return {
      dataField: column.dataField,
      ...(editorType ? { editorType } : {}),
      editorOptions: {
        ...(column.EditorOptions ?? {}),
        ...(lookup?.dataSourceEdit ?? lookup?.dataSource ? { dataSource: lookup.dataSourceEdit ?? lookup.dataSource } : {}),
        ...(lookup?.valueExpr ? { valueExpr: lookup.valueExpr } : {}),
        ...(lookup?.displayExpr ? { displayExpr: lookup.displayExpr } : {}),
        disabled: readOnly,
        readOnly,
      },
      label: { text: `${caption(column.dataField)}:`, showColon: false },
      ...(typeof column.colSpan === 'number' ? { colSpan: column.colSpan } : {}),
      ...(column.RequiredRule && !readOnly ? { validationRules: [{ type: 'required', trim: typeof column.RequiredRule === 'object' ? column.RequiredRule.trim !== false : true }] } : {}),
    }
  })

  return <AppContent title={title} breadcrumbs={[{ label: 'Home', href: '/' }, { label: title }]}>
    {error && <div className="alert alert-danger">{error}</div>}
    <div className="card runtime-list-card">
      <div className="card-header d-flex align-items-center gap-2">
        <h3 className="card-title">{title}</h3>
        <div className="ms-auto d-flex gap-1">
          {toolbarButtons.filter(isToolbarVisible).map((button, index) => <Button
            key={`${button.onClick ?? 'button'}-${index}`}
            icon={button.icon}
            text={toolbarText(button)}
            type={button.type === 'default' ? 'default' : undefined}
            stylingMode={button.stylingMode === 'contained' ? 'contained' : 'text'}
            disabled={isToolbarDisabled(button)}
            onClick={() => executeToolbarClick(button)}
          />)}
        </div>
      </div>
      <div className="card-body p-2 runtime-module-grid runtime-list-card-body"><DataGrid dataSource={rows} keyExpr={keyExpr} height="100%" showBorders hoverStateEnabled rowAlternationEnabled columnAutoWidth onSelectionChanged={event => setSelected(event.selectedRowsData[0])}>
        <Selection mode="single" /><Sorting mode="multiple" /><FilterRow visible />
        <Scrolling mode="virtual" columnRenderingMode="standard" useNative={false} />
        {columns.map(column => <Column key={column.dataField} dataField={column.dataField} caption={caption(column.dataField)} dataType={column.dataType === 'datetime' ? 'datetime' : column.dataType === 'boolean' ? 'boolean' : undefined} format={column.format} width={column.width} alignment={column.alignment} />)}
      </DataGrid></div>
      <div className="card-footer runtime-list-card-footer">
        <SelectBox className="runtime-list-page-size" width={90} items={pageSizes} value={pageSize} onValueChanged={event => {
          const nextSize = Number(event.value)
          setPage(0)
          setPageSize(nextSize)
          void loadData(0, nextSize)
        }} />
        <div className="d-flex align-items-center gap-2 runtime-list-pager">
          <span>Trang {page + 1} trên {pageCount} ({totalCount} bản ghi)</span>
          <Button icon="chevronleft" stylingMode="text" disabled={page === 0 || loading} onClick={() => { const next = page - 1; setPage(next); void loadData(next, pageSize) }} />
          <input
            className="form-control form-control-sm text-center runtime-list-page-input"
            type="number"
            min={1}
            max={pageCount}
            value={page + 1}
            aria-label="Page number"
            onChange={event => {
              const next = Number(event.target.value) - 1
              if (Number.isInteger(next) && next >= 0 && next < pageCount) {
                setPage(next)
                void loadData(next, pageSize)
              }
            }}
          />
          <span>/ {pageCount}</span>
          <Button icon="chevronright" stylingMode="text" disabled={page + 1 >= pageCount || loading} onClick={() => { const next = page + 1; setPage(next); void loadData(next, pageSize) }} />
        </div>
      </div>
    </div>
    {editor && <Popup
      visible
      title={editor.mode === 'add' ? (labels['Popup$Add$Title.text'] ?? 'Add') : (labels['Popup$Edit$Title.text'] ?? 'Edit')}
      width={editingPopup?.width ?? 900}
      height={editingPopup?.height ?? 'auto'}
      maxHeight={editingPopup?.maxHeight ?? '80vh'}
      showCloseButton
      dragEnabled
      hideOnOutsideClick={false}
      onHiding={() => setEditor(undefined)}
      contentRender={() => <div className="runtime-module-popup-content">
        {editorError && <div className="alert alert-danger py-2">{editorError}</div>}
        {editorModulesReady && <Form
          formData={editor.row}
          items={formItems}
          colCount={editingForm?.colCount ?? 2}
          labelLocation={editingForm?.labelLocation ?? 'left'}
          labelMode={editingForm?.labelMode ?? 'outside'}
          onFieldDataChanged={event => {
            if (!event.dataField) return
            setEditorError(undefined)
            setEditor(current => current && { ...current, row: { ...current.row, [event.dataField as string]: event.value } })
          }}
        />}
        <div className="d-flex justify-content-end gap-2 border-top pt-3 mt-3"><Button text="Cancel" onClick={() => setEditor(undefined)} /><Button text="Save" type="default" disabled={saving || !editorModulesReady} onClick={() => void save()} /></div>
      </div>}
    />}
    <LoadPanel visible={loading || saving} container=".runtime-module-grid" position={{ of: '.runtime-module-grid' }} shading />
  </AppContent>
}
