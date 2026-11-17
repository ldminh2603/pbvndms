'use client'

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import Button from 'devextreme-react/button'
import CheckBox from 'devextreme-react/check-box'
import DateBox from 'devextreme-react/date-box'
import DataGrid, { Column, FilterBuilderPopup, FilterRow, HeaderFilter, Lookup, Scrolling, Selection, Sorting } from 'devextreme-react/data-grid'
import type DxDataGrid from 'devextreme-react/data-grid'
import type { SelectionChangedEvent } from 'devextreme/ui/data_grid'
import DropDownBox from 'devextreme-react/drop-down-box'
import type DxDropDownBox from 'devextreme-react/drop-down-box'
import type { ValueChangedEvent } from 'devextreme/ui/drop_down_box'
import LoadPanel from 'devextreme-react/load-panel'
import NumberBox from 'devextreme-react/number-box'
import Popup from 'devextreme-react/popup'
import SelectBox from 'devextreme-react/select-box'
import TextBox from 'devextreme-react/text-box'
import TextArea from 'devextreme-react/text-area'
import { AppContent, Toast } from '@adminlte/react'
import { webCoreApi, webCoreApiResponse } from '@/lib/webcore-api'
import { createClientId } from '@/lib/client-id'
import { webCoreLanguageChangedEvent, webCoreLanguageStorageKey } from '@/components/language-selector'
import { useSidebarReady } from '@/lib/sidebar-readiness'

type Row = Record<string, unknown>
type LookupItem = { value: string; text?: string; codeValueName?: string }
type Field = {
  fieldID?: string
  fieldName?: string
  parameterName?: string
  label?: string
  fieldType?: string
  controlType?: string
  fieldFormat?: string
  defaultValue?: unknown
  fixedWidth?: number
  listSource?: string
  columnIndex?: number
  nullable?: string
  readOnlyOnAdd?: string
  readOnlyOnEdit?: string
  readOnlyOnView?: string
  listSourceItems?: LookupItem[]
}
type FormInfo = {
  moduleID?: string
  subModule?: string
  moduleType?: string
  uiType?: string
  title?: string
  moduleName?: string
  columnFields?: Field[]
  parameterFields?: Field[]
  commonFields?: Field[]
  searchFacetFields?: Field[]
  buttons?: ModuleButton[]
}
type ModuleButton = {
  buttonID?: string
  buttonName?: string
  caption?: string
  icon?: string
  showOnToolbar?: string
  beginGroup?: string
  callModuleID?: string
  callSubModule?: string
  showConfirm?: string
  btnParams?: string
}
type Localization = {
  Title?: string
  Fields?: Record<string, string>
  Buttons?: Record<string, string>
  Icons?: Record<string, string>
  Lookups?: Record<string, Record<string, string>>
  Colors?: Record<string, Record<string, string>>
  Texts?: Record<string, string>
}
type SearchResponse = {
  data?: Row[]
  totalCount?: number
  permission?: {
    actions?: string[]
    operations?: string[]
    isPrivilegedAccount?: boolean
  }
  facets?: Array<{
    field?: string
    allCount?: number
    items?: Array<{ value?: string; count?: number }>
  }>
}
type FileConfig = { allowedExtensions: string[]; maxFileSizeMb?: number }
type MaintainPopup = { button: ModuleButton; form: FormInfo; values: Row; localization?: Localization; dynamicItems?: Record<string, LookupItem[]>; dynamicFileConfigs?: Record<string, FileConfig> }
type PendingConfirmation = { button: ModuleButton; row: Row }
type GridFilter = string | Function | unknown[]
type FixedRightPanel = { moduleID: string; values: Row; btnParams?: string }
type DmsDocument = { id: string; name: string; referenceText: string; category: string; documentType: string; version: string; documentStatus: string; scanStatus: string; workflowState: string; fileSizeBytes: number; mimeType: string; createdBy: string; branchCode: string; legalHold: boolean; objectType: string; objectRef: string; createdAt: string }
type DmsVersion = { id: string; version: string; fileName: string; fileSizeBytes: number; documentStatus: string; createdBy: string; createdAt: string }
type DmsWorkflowAction = { action: string; requiresRemarks: boolean; requiredRole?: string; allowed: boolean; blockedReason: string }
type DmsWorkflow = { caseId?: string; state: string; canEditDocuments?: boolean; canOpenAmendment?: boolean; canCancelAmendment?: boolean; events: Array<{ action: string; actor: string; remarks: string; eventTime: string }>; actions?: DmsWorkflowAction[] }
type DmsAmendment = { caseId: string; amendmentNo: number; state: string; reason: string; openedBy: string; maker: string; authorisedBy: string; createdAt: string; documentCount: number; events: Array<{ action: string; actor: string; remarks: string; eventTime: string }> }
type ModulePermission = { actions?: string[]; operations?: string[]; isPrivilegedAccount?: boolean }

// Keep the no-filter value referentially stable. DevExtreme treats a fresh []
// as a new filter and resets virtual scrolling after a row is selected.
const emptyGridFilter: unknown[] = []

// JSON.stringify omits undefined and the runtime binder cannot retain null in
// its input list. Send blank values instead; the JDBC binding layer converts
// blank text to SQL NULL for nullable Store parameters.
const storeValues = (values: Row, fields: Field[]): Row => {
  const result = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, value ?? ''])) as Row
  // A DateBox with no value can omit its property entirely. Materialize every
  // form field so the generic Store binder receives '' and binds SQL NULL.
  fields.forEach(field => {
    const name = fieldName(field)
    if (name && rowValue(result, name) == null) result[name] = ''
  })
  return result
}

type ApiRecord = Record<string, unknown>

const apiValue = (record: ApiRecord, name: string) => {
  const key = Object.keys(record).find(candidate => candidate.toLowerCase() === name.toLowerCase())
  return key ? record[key] : undefined
}

const apiString = (record: ApiRecord, name: string) => {
  const value = apiValue(record, name)
  return typeof value === 'string' ? value : undefined
}

const normalizeField = (value: unknown): Field => {
  const record = (value ?? {}) as ApiRecord
  const listSourceItems = apiValue(record, 'listSourceItems')

  return {
    fieldID: apiString(record, 'fieldID'),
    fieldName: apiString(record, 'fieldName'),
    parameterName: apiString(record, 'parameterName'),
    label: apiString(record, 'label'),
    fieldType: apiString(record, 'fieldType'),
    controlType: apiString(record, 'controlType'),
    fieldFormat: apiString(record, 'fieldFormat'),
    defaultValue: apiValue(record, 'defaultValue'),
    fixedWidth: Number(apiValue(record, 'fixedWidth')) || undefined,
    listSource: apiString(record, 'listSource'),
    columnIndex: Number(apiValue(record, 'columnIndex')) || undefined,
    nullable: apiString(record, 'nullable'),
    readOnlyOnAdd: apiString(record, 'readOnlyOnAdd'),
    readOnlyOnEdit: apiString(record, 'readOnlyOnEdit'),
    readOnlyOnView: apiString(record, 'readOnlyOnView'),
    listSourceItems: Array.isArray(listSourceItems)
      ? listSourceItems.map(item => {
          const itemRecord = item as ApiRecord
          return {
            value: String(apiValue(itemRecord, 'value') ?? ''),
            text: apiString(itemRecord, 'text'),
            codeValueName: apiString(itemRecord, 'codeValueName'),
          }
        })
      : [],
  }
}

const normalizeLookupItems = (value: unknown): LookupItem[] => Array.isArray(value)
  ? value.map(item => {
      const record = item as ApiRecord
      return {
        value: String(apiValue(record, 'value') ?? ''),
        text: apiString(record, 'text'),
        codeValueName: apiString(record, 'codeValueName'),
      }
    }).filter(item => item.value)
  : []

const normalizeButton = (value: unknown): ModuleButton => {
  const record = (value ?? {}) as ApiRecord
  return {
    buttonID: apiString(record, 'buttonID'),
    buttonName: apiString(record, 'buttonName'),
    caption: apiString(record, 'caption'),
    icon: apiString(record, 'icon'),
    showOnToolbar: apiString(record, 'showOnToolbar'),
    beginGroup: apiString(record, 'beginGroup'),
    callModuleID: apiString(record, 'callModuleID'),
    callSubModule: apiString(record, 'callSubModule'),
    showConfirm: apiString(record, 'showConfirm'),
    btnParams: apiString(record, 'btnParams'),
  }
}

const normalizeFormInfo = (value: unknown): FormInfo => {
  const record = (value ?? {}) as ApiRecord
  const rawColumns = apiValue(record, 'columnFields')
  const rawParameterFields = apiValue(record, 'parameterFields')
  const rawCommonFields = apiValue(record, 'commonFields')
  const rawSearchFacetFields = apiValue(record, 'searchFacetFields')
  const rawButtons = apiValue(record, 'buttons')

  return {
    moduleID: apiString(record, 'moduleID'),
    subModule: apiString(record, 'subModule'),
    moduleType: apiString(record, 'moduleType'),
    uiType: apiString(record, 'uiType'),
    title: apiString(record, 'title'),
    moduleName: apiString(record, 'moduleName'),
    columnFields: Array.isArray(rawColumns) ? rawColumns.map(normalizeField) : [],
    parameterFields: Array.isArray(rawParameterFields) ? rawParameterFields.map(normalizeField) : [],
    commonFields: Array.isArray(rawCommonFields) ? rawCommonFields.map(normalizeField) : [],
    searchFacetFields: Array.isArray(rawSearchFacetFields) ? rawSearchFacetFields.map(normalizeField) : [],
    buttons: Array.isArray(rawButtons) ? rawButtons.map(normalizeButton) : [],
  }
}

const normalizeLocalization = (value: unknown): Localization | undefined => {
  if (!value || typeof value !== 'object') return undefined
  const record = value as ApiRecord
  const fields = apiValue(record, 'fields')
  const buttons = apiValue(record, 'buttons')
  const icons = apiValue(record, 'icons')
  const lookups = apiValue(record, 'lookups')
  const colors = apiValue(record, 'colors')
  const texts = apiValue(record, 'texts')

  return {
    Title: apiString(record, 'title'),
    Fields: fields && typeof fields === 'object' ? fields as Record<string, string> : undefined,
    Buttons: buttons && typeof buttons === 'object' ? buttons as Record<string, string> : undefined,
    Icons: icons && typeof icons === 'object' ? icons as Record<string, string> : undefined,
    Lookups: lookups && typeof lookups === 'object' ? lookups as Record<string, Record<string, string>> : undefined,
    Colors: colors && typeof colors === 'object' ? colors as Record<string, Record<string, string>> : undefined,
    Texts: texts && typeof texts === 'object' ? texts as Record<string, string> : undefined,
  }
}

const uiText = (localization: Localization | undefined, key: string, fallback: string) => {
  const matchingKey = Object.keys(localization?.Texts ?? {}).find(candidate => candidate.toLowerCase() === key.toLowerCase())
  return matchingKey && localization?.Texts?.[matchingKey] ? localization.Texts[matchingKey] : fallback
}

const fieldName = (field: Field) => field.fieldName ?? field.parameterName ?? ''
const localizedFieldLabel = (field: Field, localization?: Localization) => {
  const fields = localization?.Fields
  for (const key of [field.fieldID, field.fieldName, field.parameterName]) {
    if (!key) continue
    const matchingKey = Object.keys(fields ?? {}).find(candidate => candidate.toLowerCase() === key.toLowerCase())
    if (matchingKey && fields?.[matchingKey]) return fields[matchingKey]
  }
  return undefined
}

const lookupTexts = (field: Field, localization?: Localization) => {
  const lookups = localization?.Lookups
  for (const key of [field.fieldID, field.fieldName, field.parameterName]) {
    if (!key) continue
    const matched = Object.keys(lookups ?? {}).find(candidate => candidate.toLowerCase() === key.toLowerCase())
    if (matched && lookups?.[matched]) return lookups[matched]
  }
  return undefined
}

const lookupItems = (field: Field, localization?: Localization) => {
  const texts = lookupTexts(field, localization)
  const metadataItems = field.listSourceItems ?? []
  if (metadataItems.length > 0) return metadataItems.map(item => {
    const localizedKey = Object.keys(texts ?? {}).find(key => key.toLowerCase() === item.value.toLowerCase() || key.toLowerCase() === item.codeValueName?.toLowerCase())
    return { ...item, text: localizedKey ? texts?.[localizedKey] : item.text ?? item.value }
  })
  return Object.entries(texts ?? {}).map(([value, text]) => ({ value, text }))
}

const label = (field: Field, localization?: Localization) =>
  localizedFieldLabel(field, localization) ?? field.label ?? fieldName(field).toUpperCase()
const isDate = (field: Field) => field.controlType?.trim().toUpperCase() === 'CDT'
// PostgreSQL DATE is a calendar day, not a UTC timestamp.  Passing a Date
// through JSON.stringify() turns local midnight into a UTC instant and shifts
// the calendar day for timezones east/west of UTC.  Keep CDT values as local
// YYYY-MM-DD strings at the API boundary and materialize them as local Dates
// only for DevExtreme widgets.
const dateOnlyString = (value: unknown): string | undefined => {
  if (value == null || value === '') return undefined
  const formatLocalDate = (date: Date) => {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }
  if (value instanceof Date && !Number.isNaN(value.getTime())) return formatLocalDate(value)
  const text = String(value).trim()
  // A plain PostgreSQL DATE has no timezone and must remain the exact date.
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text
  // DevExtreme may emit an ISO UTC/offset string instead of Date. Convert its
  // instant to the browser's local calendar date; taking text.substring(0,10)
  // here would reproduce the one-day UTC shift.
  if (/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:?\d{2})$/i.test(text)) {
    const instant = new Date(text)
    if (!Number.isNaN(instant.getTime())) return formatLocalDate(instant)
  }
  const matched = text.match(/^(\d{4}-\d{2}-\d{2})/)
  return matched?.[1]
}
const localCalendarDate = (value: unknown): Date | undefined => {
  const date = dateOnlyString(value)
  if (!date) return undefined
  const [year, month, day] = date.split('-').map(Number)
  return new Date(year, month - 1, day)
}
const isBoolean = (field: Field) => /^(BOOL|BIT)$/i.test(field.fieldType?.trim() ?? '')
const isCheckbox = (field: Field) => ['CBI', 'CCK'].includes(field.controlType?.trim().toUpperCase() ?? '')
const checkboxValue = (value: unknown) => value === true || ['true', 'y', 'yes', '1'].includes(String(value ?? '').trim().toLowerCase())
const isPassword = (field: Field) => field.controlType?.trim().toUpperCase() === 'CPW'
const isFileUpload = (field: Field) => field.controlType?.trim().toUpperCase() === 'CFU'
const isTextArea = (field: Field) => field.controlType?.trim().toUpperCase() === 'CTA'
// A source beginning with ':' is a static DEFCODE list (for example
// :DMS.OBJECT_TYPE). Field references exist only inside a routine argument list.
const listSourceReferences = (field: Field) => {
  const source = field.listSource?.trim() ?? ''
  if (source.startsWith(':')) return []
  const argumentsText = source.match(/\((.*)\)$/)?.[1] ?? ''
  return Array.from(argumentsText.matchAll(/:([A-Za-z_][A-Za-z0-9_]*)/g), match => match[1])
}
const referencesField = (dependent: Field, source: Field) => {
  const names = [source.fieldID, source.fieldName, source.parameterName].filter((value): value is string => Boolean(value)).map(value => value.toLowerCase())
  return listSourceReferences(dependent).some(reference => names.includes(reference.toLowerCase()))
}
const isDependentCcb = (field: Field) => isCcb(field) && listSourceReferences(field).length > 0
const isCcb = (field: Field) => field.controlType?.trim().toUpperCase() === 'CCB'
const isCcc = (field: Field) => field.controlType?.trim().toUpperCase() === 'CCC'
const cccValues = (value: unknown): string[] => {
  // A malformed historic jsonb value can contain literal null entries. Do not
  // turn those into the text "null", and never send them back to PostgreSQL.
  if (Array.isArray(value)) return value.filter(item => item != null).map(item => String(item).trim()).filter(Boolean)
  // PostgreSQL jsonb may arrive as a JDBC wrapper serialized with a `value`
  // property. Unwrap it before parsing the JSON array.
  if (value && typeof value === 'object') {
    const nested = apiValue(value as ApiRecord, 'value')
    if (nested !== value) return cccValues(nested)
  }
  if (typeof value !== 'string' || !value.trim()) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter(item => item != null).map(item => String(item).trim()).filter(Boolean) : []
  } catch { return [] }
}
const sameCccValues = (left: unknown, right: unknown) => {
  const normalize = (value: unknown) => cccValues(value).map(item => item.toLowerCase()).sort().join('|')
  return normalize(left) === normalize(right)
}
// Always serialize CCC fields at commit time. This also repairs values returned
// by older JDBC/jsonb responses, even when the user does not touch the editor.
const normalizeCccValuesForStore = (values: Row, fields: Field[]): Row => {
  const normalized = { ...values }
  fields.filter(isCcc).forEach(field => {
    const name = fieldName(field)
    if (name) normalized[name] = JSON.stringify(cccValues(rowValue(normalized, name)))
  })
  return normalized
}

type CccEditorProps = {
  value: unknown
  items: LookupItem[]
  readOnly: boolean
  placeholder: string
  onChange: (value: string) => void
}

/** Mirrors DevExtreme's DropDownBox-with-embedded-DataGrid multiple-selection pattern. */
const CccEditor = ({ value, items, readOnly, placeholder, onChange }: CccEditorProps) => {
  const selectionRef = useRef<string[]>(cccValues(value))
  const dropDownRef = useRef<DxDropDownBox | null>(null)
  const dataGridRef = useRef<DxDataGrid<LookupItem> | null>(null)

  useEffect(() => {
    const next = cccValues(value)
    if (!sameCccValues(selectionRef.current, next)) {
      selectionRef.current = next
      dropDownRef.current?.instance.option('value', next)
      dataGridRef.current?.instance.option('selectedRowKeys', next)
    }
    // Persist the normalized initial selection into the draft too. Without
    // this, an untouched DropDownBox can leave an old JDBC jsonb wrapper in
    // the commit payload even though its display already shows selected rows.
    onChange(JSON.stringify(next))
  }, [onChange, value])

  const setSelection = useCallback((nextValue: unknown) => {
    const next = cccValues(nextValue)
    if (sameCccValues(selectionRef.current, next)) return
    selectionRef.current = next
    // Update DevExtreme widgets imperatively. No React render is triggered
    // while the nested DataGrid is open, preventing overlay flicker.
    dropDownRef.current?.instance.option('value', next)
    onChange(JSON.stringify(next))
  }, [onChange])

  const syncDataGridSelection = useCallback((event: ValueChangedEvent) => {
    // In DevExtreme 22.2 this event echoes every DataGrid selection. Let the
    // grid own normal selection; only the clear button produces null here.
    if (event.value == null) {
      dataGridRef.current?.instance.clearSelection()
      setSelection([])
    }
  }, [setSelection])

  const dataGridOnSelectionChanged = useCallback((event: SelectionChangedEvent) => {
    setSelection(event.selectedRowKeys.length ? event.selectedRowKeys : [])
  }, [setSelection])

  const dataGridRender = useCallback(() => (
    <DataGrid
      ref={dataGridRef}
      dataSource={items}
      keyExpr="value"
      height={Math.min(300, Math.max(140, items.length * 42 + 58))}
      showBorders
      hoverStateEnabled
      defaultSelectedRowKeys={selectionRef.current}
      onSelectionChanged={dataGridOnSelectionChanged}
    >
      <Selection mode="multiple" showCheckBoxesMode="always" />
      <Scrolling mode="virtual" />
      <Column dataField="value" caption="Value" width={160} />
      <Column dataField="text" caption="Text" />
    </DataGrid>
  ), [dataGridOnSelectionChanged, items])

  return (
    <DropDownBox
      ref={dropDownRef}
      value={selectionRef.current}
      valueExpr="value"
      displayExpr="text"
      dataSource={items}
      deferRendering={false}
      readOnly={readOnly}
      showClearButton={!readOnly}
      placeholder={placeholder}
      displayValueFormatter={(selectedValues: string | LookupItem[]) => {
        const values = Array.isArray(selectedValues) ? selectedValues.map(item => typeof item === 'string' ? item : item.value) : cccValues(selectedValues)
        return values.map(selectedValue => items.find(item => item.value === selectedValue)?.text ?? selectedValue).join(', ')
      }}
      onValueChanged={syncDataGridSelection}
      contentRender={dataGridRender}
    />
  )
}

/** Legacy CFU binary contract: [filename byte length][UTF-8 filename][file bytes]. */
const legacyFilePayload = async (file: File) => {
  const fileName = new TextEncoder().encode(file.name)
  if (fileName.length > 255) throw new Error('CFU filename must not exceed 255 UTF-8 bytes.')
  const bytes = new Uint8Array(1 + fileName.length + file.size)
  bytes[0] = fileName.length
  bytes.set(fileName, 1)
  bytes.set(new Uint8Array(await file.arrayBuffer()), 1 + fileName.length)
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  return btoa(binary)
}

const legacyFileName = (payload: unknown) => {
  if (typeof payload !== 'string' || !payload) return ''
  try {
    const binary = atob(payload)
    const length = binary.charCodeAt(0)
    return new TextDecoder().decode(Uint8Array.from(binary.slice(1, length + 1), char => char.charCodeAt(0)))
  } catch { return '' }
}
const fileConfigFromResponse = (value: unknown): FileConfig => {
  const record = value && typeof value === 'object' ? value as ApiRecord : {}
  const rawExtensions = apiValue(record, 'allowed_extensions')
  const parsed = Array.isArray(rawExtensions) ? rawExtensions : typeof rawExtensions === 'string'
    ? (() => { try { return JSON.parse(rawExtensions) } catch { return rawExtensions.split(',') } })()
    : []
  const allowedExtensions = Array.isArray(parsed)
    ? parsed.map(item => String(item ?? '').trim().replace(/^\./, '').toLowerCase()).filter(Boolean)
    : []
  const maximum = Number(apiValue(record, 'max_file_size_mb'))
  return { allowedExtensions, maxFileSizeMb: Number.isFinite(maximum) && maximum > 0 ? maximum : undefined }
}
const fileValidationError = (file: File, config: FileConfig | undefined) => {
  const extension = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase() : ''
  if (config?.allowedExtensions?.length && !config.allowedExtensions.includes(extension)) {
    return `File type .${extension || '?'} is not allowed.`
  }
  if (config?.maxFileSizeMb && file.size > config.maxFileSizeMb * 1024 * 1024) {
    return `File size exceeds the configured ${config.maxFileSizeMb} MB limit.`
  }
  return undefined
}
const isNumeric = (field: Field) => /^(N|I|D|INT|NUMBER|NUMERIC|DECIMAL|INTEGER)$/i.test(field.fieldType?.trim() ?? '')
const fieldModePermission = (field: Field, subModule?: string) => {
  switch (subModule?.trim().toUpperCase()) {
    case 'MAD': return field.readOnlyOnAdd?.trim().toUpperCase() ?? 'RW'
    case 'MED': return field.readOnlyOnEdit?.trim().toUpperCase() ?? 'RW'
    case 'MVW': return field.readOnlyOnView?.trim().toUpperCase() ?? 'RO'
    default: return 'RW'
  }
}
const fieldHidden = (field: Field, subModule?: string) => fieldModePermission(field, subModule) === 'HI'
const fieldReadOnly = (field: Field, subModule?: string) => fieldModePermission(field, subModule) !== 'RW'
const rowValue = (row: Row, name: string) => {
  const key = Object.keys(row).find(candidate => candidate.toLowerCase() === name.toLowerCase())
  return key ? row[key] : undefined
}
const writeRowValue = (row: Row, name: string, value: unknown): Row => {
  const key = Object.keys(row).find(candidate => candidate.toLowerCase() === name.toLowerCase()) ?? name
  return { ...row, [key]: value }
}
const defaultValues = (fields: Field[]) => fields.reduce<Row>((values, field) => {
  const name = fieldName(field)
  return name && field.defaultValue != null
    ? writeRowValue(values, name, field.defaultValue)
    : values
}, {})

const localizedButtonValue = (values: Record<string, string> | undefined, button: ModuleButton) => {
  for (const key of [button.buttonID, button.buttonName]) {
    if (!key) continue
    const matched = Object.keys(values ?? {}).find(candidate => candidate.toLowerCase() === key.toLowerCase())
    if (matched && values?.[matched]?.trim()) return values[matched].trim()
  }
  return undefined
}

const buttonCaption = (button: ModuleButton, localization?: Localization) =>
  localizedButtonValue(localization?.Buttons, button)
  ?? button.caption?.trim()
  ?? button.buttonName?.trim()
  ?? ''

const buttonAction = (button: ModuleButton) => {
  const name = button.buttonName?.trim().toUpperCase()
  if (name === 'BTN_ADD') return 'add'
  if (name === 'BTN_EDIT') return 'edit'
  if (name === 'BTN_DELETE') return 'delete'
  return 'view'
}

const buttonOperation = (button: ModuleButton) => {
  const name = button.buttonName?.trim().toUpperCase() ?? ''
  return ['BTN_VIEW', 'BTN_ADD', 'BTN_EDIT', 'BTN_DELETE', 'BTN_DETAIL', 'BTN_BACK', 'BTN_REFRESH'].includes(name) ? undefined : name
}

const navigationParameters = (mapping: string | undefined, row: Row): URLSearchParams => {
  const parameters = new URLSearchParams()
  for (const statement of (mapping ?? '').split(';')) {
    const matched = statement.trim().match(/^\$target\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\$row\.([A-Za-z_][A-Za-z0-9_]*)$/i)
    if (!matched) continue
    const value = rowValue(row, matched[2])
    if (value != null && String(value).trim()) parameters.set(matched[1], String(value))
  }
  return parameters
}

/**
 * BTN_PARAMS targetHCD is a value already supplied to the current Type-03
 * module through an HCD/URL parameter.  It is deliberately independent of a
 * selected grid row, so an Add button can pass its parent context to a form.
 */
const maintainParameters = (mapping: string | undefined, row: Row, hcd: URLSearchParams): Row => {
  let values: Row = {}
  for (const statement of (mapping ?? '').split(';')) {
    const hcdMapping = statement.trim().match(/^\$targetHCD\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\$row\.([A-Za-z_][A-Za-z0-9_]*)$/i)
    if (hcdMapping) {
      const value = urlParameterValue(hcd, hcdMapping[1])
      if (value) values = writeRowValue(values, hcdMapping[1], value)
      continue
    }
    const rowMapping = statement.trim().match(/^\$target\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\$row\.([A-Za-z_][A-Za-z0-9_]*)$/i)
    if (!rowMapping) continue
    const value = rowValue(row, rowMapping[2])
    if (value != null && String(value).trim()) values = writeRowValue(values, rowMapping[1], value)
  }
  return values
}

const urlParameterValue = (parameters: URLSearchParams, name: string) => {
  for (const [key, value] of parameters.entries()) if (key.toLowerCase() === name.toLowerCase()) return value.trim()
  return ''
}

const maximumDisplayedCharacters = 200
const truncateCellText = (value: string) => value.length > maximumDisplayedCharacters
  ? `${value.slice(0, maximumDisplayedCharacters)}…`
  : value

const configuredColor = (field: Field, value: unknown, localization?: Localization) => {
  const fieldKey = Object.keys(localization?.Colors ?? {}).find(key => key.toLowerCase() === fieldName(field).toLowerCase())
  const values = fieldKey ? localization?.Colors?.[fieldKey] : undefined
  const valueKey = Object.keys(values ?? {}).find(key => key.toLowerCase() === String(value ?? '').toLowerCase())
  const rule = valueKey ? values?.[valueKey] : undefined
  const matched = rule?.match(/^\{\s*color\s*:\s*(#[0-9a-f]{3,8})\s*;\s*background(?:-color)?\s*:\s*(#[0-9a-f]{3,8})\s*;\s*\}$/i)
  return matched ? { color: matched[1], backgroundColor: matched[2] } : undefined
}

const defaultButtonIcons: Record<string, string> = {
  BTN_VIEW: 'VIEW',
  BTN_ADD: 'ADD',
  BTN_EDIT: 'EDIT',
  BTN_DELETE: 'DELETE',
  BTN_BACK: 'EXIT',
  BTN_ACTIVE: 'APPROVE',
  BTN_ACTIVATE: 'APPROVE',
  BTN_DEACTIVE: 'REFUSE',
  BTN_DEACTIVATE: 'REFUSE',
  BTN_CANCEL: 'CLOSE',
  BTN_PNND: 'GROUP_ROLE',
  BTN_GROUP_ROLE: 'GROUP_ROLE',
  BTN_PERMISSION: 'GROUP_ROLE',
}

const buttonIcon = (button: ModuleButton, localization?: Localization) => {
  const configured = button.icon?.trim()
  let iconCode = (localizedButtonValue(localization?.Icons, button) ?? configured ?? '').trim()

  // The Spring fallback for an absent Icon is BTNNAME itself. Do not request a
  // non-existent BTN_*.png; a genuine DEFLANG icon code wins over the fallback.
  const isButtonNameFallback = !configured
    && [button.buttonID, button.buttonName].some(value => value?.trim().toLowerCase() === iconCode.toLowerCase())
  if (isButtonNameFallback) iconCode = ''
  if (!iconCode) iconCode = defaultButtonIcons[(button.buttonName ?? '').trim().toUpperCase()] ?? ''
  if (!iconCode) return undefined

  const fileName = /\.[a-z0-9]+$/i.test(iconCode) ? iconCode : `${iconCode}.png`
  return `/assets/winform-theme/24x24/${encodeURIComponent(fileName)}`
}

const fileSize = (value: number) => value < 1024 * 1024 ? `${Math.max(1, Math.round(value / 1024))} KB` : `${(value / (1024 * 1024)).toFixed(1)} MB`
const allows = (permission: ModulePermission | undefined, action: string) => permission?.isPrivilegedAccount === true || permission?.actions?.some(value => value.toLowerCase() === action.toLowerCase()) === true
// Fixed modules have no form metadata. This registry is the static-module equivalent
// of a renderer registration; it deliberately contains only modules implemented here.
const fixedRightPanelModules = new Set(['02D45|MED'])

type DmsWorkflowButton = { buttonName: string; allowed: boolean; blockedReason: string; requiredRole: string; remarksRequired: boolean }
function DmsWorkflowDetail({ document, workflow, buttons, amendments, label, onClose, onInfo, onVersions, onAction, submitting, dialog, errorMessage, onErrorClose }: { document: DmsDocument; workflow?: DmsWorkflow; buttons: DmsWorkflowButton[]; amendments: DmsAmendment[]; label: (name: string) => string; onClose: () => void; onInfo: () => void; onVersions: () => void; onAction: (name: string, required: boolean) => void; submitting: boolean; dialog: ReactNode; errorMessage?: string; onErrorClose: () => void }) {
  return <><aside className="legacy-dms-detail" aria-label="Chi tiết tài liệu"><header><h2>Chi tiết tài liệu</h2><button type="button" onClick={onClose} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><section className="legacy-dms-detail-title"><span className="legacy-dms-detail-pdf">{document.mimeType?.includes('pdf') ? 'PDF' : 'FILE'}</span><div><strong>{document.name}</strong><small>{document.objectType} · {document.objectRef || 'Chưa liên kết hồ sơ'}</small></div><em>{workflow?.state ?? document.workflowState}</em></section><nav className="legacy-dms-detail-tabs"><button onClick={onInfo}>Thông tin</button><button onClick={onVersions}>Lịch sử phiên bản</button><button className="active">Workflow</button></nav><section className="legacy-dms-workflow"><p>Trạng thái hồ sơ: <strong>{workflow?.state ?? document.workflowState}</strong></p>{workflow?.events?.map((event, index) => <div key={`${event.eventTime}-${index}`}><b>{index + 1}</b><span><strong>{event.action}</strong><small>{event.actor} · {event.eventTime}{event.remarks ? ` · ${event.remarks}` : ''}</small></span></div>)}{buttons.length > 0 && <div className="legacy-dms-workflow-action"><span>{buttons.some(button => button.allowed) ? 'Bạn có thể xử lý bước hiện tại.' : 'Bước tiếp theo của workflow.'}</span><div>{buttons.map(button => <div className="legacy-dms-policy-action" key={button.buttonName}><button type="button" className={`legacy-dms-submit-control ${button.buttonName === 'BTN_RETURN' || button.buttonName === 'BTN_CANCEL_AMENDMENT' ? 'danger' : ''}`} disabled={submitting || !button.allowed} onClick={() => onAction(button.buttonName, button.remarksRequired)}>{label(button.buttonName)}</button>{!button.allowed && <small><i className="bi bi-lock" /> {button.blockedReason === 'ROLE' ? `Cần role ${button.requiredRole || 'workflow'}` : 'Không thỏa điều kiện four-eyes'}</small>}</div>)}</div></div>}{amendments.length > 1 && <section className="legacy-dms-amendment-history"><h3>Lịch sử Amendment</h3>{amendments.map(cycle => <article key={cycle.caseId}><header><div><strong>Amendment #{cycle.amendmentNo}</strong><small>{cycle.createdAt} · {cycle.openedBy || cycle.maker}</small></div><em>{cycle.state}</em></header>{cycle.reason && <p>Lý do: {cycle.reason}</p>}<small>{cycle.documentCount} file snapshot · {cycle.authorisedBy ? `Duyệt: ${cycle.authorisedBy}` : 'Đang xử lý'}</small><div>{cycle.events.map(event => <small key={`${cycle.caseId}-${event.eventTime}-${event.action}`}><b>{event.action}</b> · {event.actor} · {event.eventTime}{event.remarks ? ` · ${event.remarks}` : ''}</small>)}</div></article>)}</section>}</section></aside>{errorMessage && <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}><Toast show autohideMs={7000} onClose={onErrorClose} theme="danger" title="Workflow">{errorMessage}</Toast></div>}{dialog}</>
}

function DmsDocumentRightPanel({ documentID, onClose, onDocumentUpdated, permission }: { documentID: string; onClose: () => void; onDocumentUpdated: () => void; permission?: ModulePermission }) {
  const [document, setDocument] = useState<DmsDocument>(); const [versions, setVersions] = useState<DmsVersion[]>([]); const [workflow, setWorkflow] = useState<DmsWorkflow>(); const [amendments, setAmendments] = useState<DmsAmendment[]>([]); const [tab, setTab] = useState<'info' | 'versions' | 'workflow'>('info'); const [error, setError] = useState<string>(); const [preview, setPreview] = useState<{ url: string; name: string }>(); const [versionOpen, setVersionOpen] = useState(false); const [versionFile, setVersionFile] = useState<File>(); const [versionReference, setVersionReference] = useState(''); const [versionIdempotencyKey, setVersionIdempotencyKey] = useState(''); const [versionSaving, setVersionSaving] = useState(false); const [removeOpen, setRemoveOpen] = useState(false); const [removalReason, setRemovalReason] = useState(''); const [removing, setRemoving] = useState(false); const [workflowSubmitting, setWorkflowSubmitting] = useState(false); const [workflowPrompt, setWorkflowPrompt] = useState<string>(); const [workflowRemarks, setWorkflowRemarks] = useState(''); const [workflowError, setWorkflowError] = useState<string>(); const [workflowLocalization, setWorkflowLocalization] = useState<Localization>()
  useEffect(() => { let active = true; setDocument(undefined); setVersions([]); setWorkflow(undefined); setError(undefined); setWorkflowError(undefined); setTab('info')
    void webCoreApi<DmsDocument>(`/dms-docsvc/documents/${encodeURIComponent(documentID)}`).then(item => { if (!active) return undefined; setDocument(item); void webCoreApi<DmsAmendment[]>(`/dms-docsvc/documents/${encodeURIComponent(item.id)}/amendments/history`).then(rows => { if (active) setAmendments(rows) }).catch(() => { if (active) setAmendments([]) }); return Promise.all([webCoreApi<DmsVersion[]>(`/dms-docsvc/documents/${encodeURIComponent(item.id)}/versions`), webCoreApi<DmsWorkflow>(`/dms-docsvc/documents/${encodeURIComponent(item.id)}/workflow?objectType=${encodeURIComponent(item.objectType)}&objectRef=${encodeURIComponent(item.objectRef)}`)]) }).then(result => { if (active && result) { setVersions(result[0]); setWorkflow(result[1]) } }).catch(cause => { if (active) setError(cause instanceof Error ? cause.message : 'Không thể tải chi tiết tài liệu.') }); return () => { active = false } }, [documentID])
  useEffect(() => { let active = true; void webCoreApi<unknown>('/module/02D45/MED/localization').then(result => { if (active) setWorkflowLocalization(normalizeLocalization(result)) }).catch(() => { if (active) setWorkflowLocalization(undefined) }); return () => { active = false } }, [])
  const openPreview = async (id: string, name: string) => { try { const previewByName = /\.(pdf|png|jpe?g|gif|webp)$/i.test(name); const response = await webCoreApiResponse(`/dms-docsvc/documents/${encodeURIComponent(id)}/content?access=${previewByName ? 'PREVIEW' : 'DOWNLOAD'}`); if (!response.ok) throw new Error(`Không thể mở file (${response.status}).`); const blob = await response.blob(); const previewable = blob.type === 'application/pdf' || blob.type.startsWith('image/'); if (!previewable) { const url = URL.createObjectURL(blob); const link = window.document.createElement('a'); link.href = url; link.download = name; window.document.body.appendChild(link); link.click(); link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 0); return } setPreview(current => { if (current) URL.revokeObjectURL(current.url); return { url: URL.createObjectURL(blob), name } }) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Không thể mở hoặc tải tài liệu.') } }
  const closePreview = () => setPreview(current => { if (current) URL.revokeObjectURL(current.url); return undefined })
  const createVersion = async () => { if (!document || !versionFile) { setError('Chọn file cho phiên bản mới.'); return } const idempotencyKey = versionIdempotencyKey || createClientId(); if (!versionIdempotencyKey) setVersionIdempotencyKey(idempotencyKey); setVersionSaving(true); setError(undefined); try { const payload = new FormData(); payload.set('referenceText', versionReference.trim()); payload.set('file', versionFile); const next = await webCoreApi<DmsDocument>(`/dms-docsvc/documents/${encodeURIComponent(document.id)}/versions`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: payload }); setDocument(next); setVersions(await webCoreApi<DmsVersion[]>(`/dms-docsvc/documents/${encodeURIComponent(next.id)}/versions`)); onDocumentUpdated(); setVersionOpen(false); setVersionFile(undefined); setVersionReference(''); setVersionIdempotencyKey(''); setTab('versions') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Không thể tạo phiên bản mới.') } finally { setVersionSaving(false) } }
  const removeDocument = async () => { if (!document || !removalReason.trim()) { setError('Nhập lý do xóa tài liệu.'); return } setRemoving(true); setError(undefined); try { await webCoreApi(`/dms-docsvc/documents/${encodeURIComponent(document.id)}/remove?reason=${encodeURIComponent(removalReason.trim())}`, { method: 'POST' }); onDocumentUpdated(); setRemoveOpen(false); onClose() } catch (cause) { setError(cause instanceof Error ? cause.message : 'Không thể xóa tài liệu khỏi hồ sơ.') } finally { setRemoving(false) } }
  const workflowLabel = (buttonName: string) => workflowLocalization?.Buttons?.[buttonName] || buttonName.replace(/^BTN_/, '').replace(/_/g, ' ')
  const commitWorkflowButton = async (buttonName: string, remarks = '') => { if (!document || !workflow?.caseId) return; setWorkflowSubmitting(true); setWorkflowError(undefined); try { await webCoreApi(`/module/02D45/maintain/MED/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ buttonName, values: { CASE_ID: workflow.caseId, REMARKS: remarks } }) }); const next = await webCoreApi<DmsWorkflow>(`/dms-docsvc/documents/${encodeURIComponent(document.id)}/workflow?objectType=${encodeURIComponent(document.objectType)}&objectRef=${encodeURIComponent(document.objectRef)}`); setWorkflow(next); setDocument(current => current ? { ...current, workflowState: next.state } : current); onDocumentUpdated(); setWorkflowPrompt(undefined); setWorkflowRemarks('') } catch (cause) { setWorkflowError(cause instanceof Error ? cause.message : 'Không thể thực hiện workflow.') } finally { setWorkflowSubmitting(false) } }
  const workflowButtons: DmsWorkflowButton[] = [...(workflow?.actions ?? []).map(action => ({ buttonName: `BTN_${action.action.toUpperCase()}`, allowed: action.allowed, blockedReason: action.blockedReason, requiredRole: action.requiredRole ?? '', remarksRequired: action.requiresRemarks })), ...(workflow?.canOpenAmendment ? [{ buttonName: 'BTN_OPEN_AMENDMENT', allowed: true, blockedReason: '', requiredRole: '', remarksRequired: true }] : []), ...(workflow?.canCancelAmendment ? [{ buttonName: 'BTN_CANCEL_AMENDMENT', allowed: true, blockedReason: '', requiredRole: '', remarksRequired: false }] : [])]
  const requestWorkflowButton = (buttonName: string, remarksRequired: boolean) => { if (remarksRequired) { setWorkflowPrompt(buttonName); setWorkflowRemarks(''); return } void commitWorkflowButton(buttonName) }
  const workflowDialog = workflowPrompt && <div className="legacy-dms-workflow-modal-backdrop" role="presentation"><section className="legacy-dms-workflow-modal" role="dialog" aria-modal="true" aria-labelledby="workflow-dialog-title"><header><div><small>WORKFLOW</small><h2 id="workflow-dialog-title">{workflowLabel(workflowPrompt)}</h2><p>Nhập lý do để lưu cùng lịch sử xử lý hồ sơ.</p></div><button type="button" onClick={() => setWorkflowPrompt(undefined)} disabled={workflowSubmitting} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><div><label>Lý do trả về *<textarea value={workflowRemarks} onChange={event => setWorkflowRemarks(event.target.value)} placeholder="Nhập lý do trả về..." autoFocus /></label>{!workflowRemarks.trim() && <small>Lý do là bắt buộc theo workflow definition.</small>}</div><footer><button type="button" onClick={() => setWorkflowPrompt(undefined)} disabled={workflowSubmitting}>Hủy</button><button type="button" onClick={() => void commitWorkflowButton(workflowPrompt, workflowRemarks.trim())} disabled={workflowSubmitting || !workflowRemarks.trim()}>{workflowSubmitting ? 'Đang xử lý...' : 'Xác nhận trả về'}</button></footer></section></div>
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview.url) }, [preview])
  const canEdit = allows(permission, 'Edit')
  const canDelete = allows(permission, 'Delete')
  const submitAction = workflowButtons.find(button => button.buttonName === 'BTN_SUBMIT')
  const submitForControl = () => commitWorkflowButton('BTN_SUBMIT')
  if (tab === 'workflow' && document) return <DmsWorkflowDetail document={document} workflow={workflow} buttons={workflowButtons} amendments={amendments} label={workflowLabel} onClose={onClose} onInfo={() => setTab('info')} onVersions={() => setTab('versions')} onAction={requestWorkflowButton} submitting={workflowSubmitting} dialog={workflowDialog} errorMessage={workflowError} onErrorClose={() => setWorkflowError(undefined)} />
  return <>
    <aside className="legacy-dms-detail" aria-label="Chi tiết tài liệu">
      <header><h2>Chi tiết tài liệu</h2><button type="button" onClick={onClose} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header>
      {error && <p className="legacy-dms-detail-error">{error}</p>}
      {!document && !error && <p className="legacy-dms-detail-loading">Đang tải chi tiết tài liệu...</p>}
      {document && <>
        <section className="legacy-dms-detail-title"><span className="legacy-dms-detail-pdf">{document.mimeType?.includes('pdf') ? 'PDF' : 'FILE'}</span><div><strong>{document.name}</strong><small>{document.objectType} · {document.objectRef || 'Chưa liên kết hồ sơ'}</small></div><em>{document.workflowState}</em></section>
        <nav className="legacy-dms-detail-tabs"><button className={tab === 'info' ? 'active' : ''} onClick={() => setTab('info')}>Thông tin</button><button className={tab === 'versions' ? 'active' : ''} onClick={() => setTab('versions')}>Lịch sử phiên bản</button><button className={tab === 'workflow' ? 'active' : ''} onClick={() => setTab('workflow')}>Workflow</button></nav>
        {tab === 'info' && <section className="legacy-dms-detail-info">{[['Phiên bản', document.version], ['Loại tài liệu', document.category], ['Kiểu tài liệu', document.documentType], ['Chi nhánh', document.branchCode], ['Người tải', document.createdBy], ['Dung lượng', fileSize(document.fileSizeBytes)], ['Quét virus', document.scanStatus], ['Legal hold', document.legalHold ? 'Có' : 'Không'], ['Tham chiếu', document.referenceText || '—']].map(([name, value]) => <p key={name}><span>{name}</span><strong>{value}</strong></p>)}<button type="button" className="legacy-dms-preview" onClick={() => void openPreview(document.id, document.name)}><i className="bi bi-eye" /> Xem tài liệu</button>{document.documentStatus === 'ACTIVE' && workflow?.canEditDocuments && canDelete && <button type="button" className="legacy-dms-remove-document" onClick={() => { setRemovalReason(''); setRemoveOpen(true) }}><i className="bi bi-trash" /> Xóa khỏi hồ sơ</button>}</section>}
        {tab === 'versions' && <section className="legacy-dms-timeline">{versions.map(item => <div key={item.id}><span><strong>Phiên bản {item.version}</strong><small>{item.fileName} · {fileSize(item.fileSizeBytes)}<br />{item.createdAt} · {item.createdBy}</small></span><button type="button" onClick={() => void openPreview(item.id, item.fileName)}>Xem</button></div>)}{document.documentStatus === 'ACTIVE' && workflow?.canEditDocuments && canEdit && <button type="button" className="legacy-dms-create-version" onClick={() => { setVersionFile(undefined); setVersionReference(''); setVersionOpen(true) }}><i className="bi bi-plus-lg" /> Tạo phiên bản mới</button>}</section>}
        {tab === 'workflow' && <section className="legacy-dms-workflow"><p>Trạng thái hồ sơ: <strong>{workflow?.state ?? document.workflowState}</strong></p>{workflow?.events?.map((event, index) => <div key={`${event.eventTime}-${index}`}><b>{index + 1}</b><span><strong>{event.action}</strong><small>{event.actor} · {event.eventTime}{event.remarks ? ` · ${event.remarks}` : ''}</small></span></div>) || <small>Chưa có lịch sử workflow.</small>}{submitAction && <div className="legacy-dms-workflow-action"><span>{submitAction.allowed ? 'Bạn có thể xử lý bước hiện tại.' : 'Bước tiếp theo của workflow.'}</span><button type="button" className="legacy-dms-submit-control" disabled={workflowSubmitting || !submitAction.allowed} title={!submitAction.allowed ? submitAction.blockedReason || 'Không đủ điều kiện gửi kiểm soát.' : undefined} onClick={() => void submitForControl()}><i className="bi bi-send" /> {workflowSubmitting ? 'Đang gửi...' : 'Gửi kiểm soát'}</button></div>}</section>}
      </>}
    </aside>
    {preview && <div className="legacy-dms-preview-backdrop" onMouseDown={closePreview}><section className="legacy-dms-preview-dialog" onMouseDown={event => event.stopPropagation()}><header><div><span>XEM TÀI LIỆU</span><h2>{preview.name}</h2><p>Nội dung được đọc từ object storage của docsvc.</p></div><button type="button" onClick={closePreview} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><iframe src={preview.url} title={preview.name} /><footer><button type="button" onClick={closePreview}>Đóng</button></footer></section></div>}
    {versionOpen && <div className="legacy-dms-preview-backdrop" onMouseDown={() => !versionSaving && setVersionOpen(false)}><section className="legacy-dms-version-dialog" onMouseDown={event => event.stopPropagation()}><header><div><span>NEW DOCUMENT VERSION</span><h2>Create version {Number(document?.version ?? 0) + 1}</h2><p>The previous version remains immutable and is marked SUPERSEDED after the clean file is accepted.</p></div><button type="button" onClick={() => setVersionOpen(false)} disabled={versionSaving}><i className="bi bi-x-lg" /></button></header><div className="legacy-dms-version-body"><label>Reference text<input value={versionReference} onChange={event => setVersionReference(event.target.value)} /></label><label className="legacy-dms-version-upload"><i className="bi bi-upload" /><strong>{versionFile?.name || 'Choose replacement file'}</strong><small>PDF, DOC, DOCX, XLS, XLSX — scanned by ClamAV before storage</small><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx" onChange={event => setVersionFile(event.target.files?.[0])} /></label></div><footer><button type="button" onClick={() => setVersionOpen(false)} disabled={versionSaving}>Cancel</button><button type="button" onClick={() => void createVersion()} disabled={versionSaving || !versionFile}>{versionSaving ? 'Creating...' : 'Create version'}</button></footer></section></div>}
    {removeOpen && <div className="legacy-dms-workflow-modal-backdrop" role="presentation"><section className="legacy-dms-workflow-modal" role="dialog" aria-modal="true" aria-labelledby="remove-document-title"><header><div><small>XÓA TÀI LIỆU</small><h2 id="remove-document-title">Xóa khỏi hồ sơ</h2><p>Tài liệu không bị xóa vật lý; hệ thống lưu vết lý do và chuyển trạng thái theo quy tắc DMS.</p></div><button type="button" onClick={() => setRemoveOpen(false)} disabled={removing} aria-label="Đóng"><i className="bi bi-x-lg" /></button></header><div><label>Lý do xóa *<textarea value={removalReason} onChange={event => setRemovalReason(event.target.value)} placeholder="Nhập lý do xóa tài liệu..." autoFocus /></label>{!removalReason.trim() && <small>Lý do xóa là bắt buộc.</small>}</div><footer><button type="button" onClick={() => setRemoveOpen(false)} disabled={removing}>Hủy</button><button type="button" className="legacy-dms-remove-confirm" onClick={() => void removeDocument()} disabled={removing || !removalReason.trim()}>{removing ? 'Đang xóa...' : 'Xác nhận xóa'}</button></footer></section></div>}
  </>
}

export function LegacySearch() {
  const params = useParams<{ modId: string; subMod: string }>()
  const router = useRouter()
  const searchParams = useSearchParams()
  const sidebarReady = useSidebarReady()
  const modId = params.modId ?? ''
  const subMod = params.subMod ?? ''
  const [form, setForm] = useState<FormInfo>()
  const [columns, setColumns] = useState<Field[]>([])
  const [localization, setLocalization] = useState<Localization>()
  const [rows, setRows] = useState<Row[]>([])
  const [totalCount, setTotalCount] = useState(0)
  const [allowedActions, setAllowedActions] = useState<string[]>([])
  const [allowedOperations, setAllowedOperations] = useState<string[]>([])
  const [isPrivilegedAccount, setIsPrivilegedAccount] = useState(false)
  const [dmsPermission, setDmsPermission] = useState<ModulePermission>()
  const [page, setPage] = useState(0)
  const [pageInput, setPageInput] = useState('1')
  const [pageSize, setPageSize] = useState(25)
  const [filterExpression, setFilterExpression] = useState<GridFilter | null>(null)
  const [selectedFacetValue, setSelectedFacetValue] = useState<string>()
  const selectedFacetValueRef = useRef<string | undefined>(undefined)
  const facetFieldNameRef = useRef('')
  const parameterFieldsRef = useRef<Field[]>([])
  const latestSearchRequestRef = useRef(0)
  const [facets, setFacets] = useState<SearchResponse['facets']>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [errorToast, setErrorToast] = useState<string>()
  const [successToast, setSuccessToast] = useState<string>()
  const [notice, setNotice] = useState<{ tone: 'success' | 'warning'; text: string }>()
  const [selectedRow, setSelectedRow] = useState<Row>()
  const [rightPanel, setRightPanel] = useState<FixedRightPanel>()
  const [popup, setPopup] = useState<MaintainPopup>()
  const [confirmation, setConfirmation] = useState<PendingConfirmation>()
  const [popupSaving, setPopupSaving] = useState(false)
  const [popupMaximized, setPopupMaximized] = useState(false)
  const cccDraftValuesRef = useRef<Record<string, string>>({})
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [filterBuilderVisible, setFilterBuilderVisible] = useState(false)
  const autoOpenedRightPanelRef = useRef('')
  const autoOpenedFormRef = useRef('')
  const activeNotificationNavigationRef = useRef('')
  // Wait until the browser preference is read.  Starting with a default value
  // caused a VN request and the saved-language request to race each other.
  const [language, setLanguage] = useState<string>()

  useEffect(() => {
    const syncLanguage = () => setLanguage((window.localStorage.getItem(webCoreLanguageStorageKey) || 'VN').trim().toUpperCase())
    syncLanguage()
    window.addEventListener(webCoreLanguageChangedEvent, syncLanguage)
    return () => window.removeEventListener(webCoreLanguageChangedEvent, syncLanguage)
  }, [])

  useEffect(() => {
    let active = true
    setDmsPermission(undefined)
    if (modId.trim().toUpperCase() !== '03L45') return () => { active = false }
    void webCoreApi<ModulePermission>('/module/02D45/permission')
      .then(response => { if (active) setDmsPermission(response) })
      .catch(() => { if (active) setDmsPermission({ actions: [] }) })
    return () => { active = false }
  }, [modId])

  useEffect(() => {
    selectedFacetValueRef.current = selectedFacetValue
  }, [selectedFacetValue])

  const autoOpenKey = `${modId}|${subMod}|${searchParams.toString()}`
  const notificationNavigation = searchParams.has('_notification')
  useEffect(() => {
    activeNotificationNavigationRef.current = notificationNavigation ? autoOpenKey : ''
    if (!notificationNavigation) return
    // A notification may be opened while another case detail panel is mounted.
    // Clear its selection first, then let the notification-specific effect
    // below resolve CASE_ID and open the replacement panel.
    setSelectedRow(undefined)
    setRightPanel(undefined)
  }, [autoOpenKey, notificationNavigation])

  useEffect(() => {
    if (notificationNavigation && !sidebarReady) {
      setNotice({ tone: 'warning', text: 'Đang mở hồ sơ…' })
    }
  }, [notificationNavigation, sidebarReady])

  useEffect(() => {
    if (autoOpenedRightPanelRef.current === autoOpenKey || rows.length === 0 || !form) return
    // Notification navigation has its own effect below. It resolves the row
    // from CASE_ID before opening the declared detail action; this generic
    // effect would otherwise reuse the detail panel's previous selected row.
    if (notificationNavigation) return
    const button = (form.buttons ?? [])
      .filter(item => fixedRightPanelModules.has(`${item.callModuleID?.trim().toUpperCase()}|${item.callSubModule?.trim().toUpperCase()}`)
        && (item.callModuleID?.trim().toUpperCase() !== '02D45' || allows(dmsPermission, 'View')))
      .sort((left, right) => Number(left.buttonID ?? Number.MAX_SAFE_INTEGER) - Number(right.buttonID ?? Number.MAX_SAFE_INTEGER))[0]
    if (!button) return
    const row = selectedRow ?? rows[0]
    const parameters = navigationParameters(button.btnParams, row)
    if (!parameters.size) return
    autoOpenedRightPanelRef.current = autoOpenKey
    setSelectedRow(row)
    setNotice(undefined)
    setRightPanel({ moduleID: button.callModuleID!.trim().toUpperCase(), values: { ...row, ...Object.fromEntries(parameters.entries()) }, btnParams: button.btnParams })
  }, [autoOpenKey, dmsPermission, form, notificationNavigation, rows, selectedRow, sidebarReady])

  // A fixed right panel remains open while users browse the grid. Re-evaluate
  // its declared $target = $row mapping on every selection change.
  useEffect(() => {
    if (!selectedRow) return
    setRightPanel(current => current
      ? { ...current, values: { ...selectedRow, ...Object.fromEntries(navigationParameters(current.btnParams, selectedRow).entries()) } }
      : current)
  }, [selectedRow])

  const loadRows = useCallback(async (nextPage: number, nextPageSize: number, expression: unknown, facetValue: string | null = null) => {
    const requestId = ++latestSearchRequestRef.current
    const parameterFields = parameterFieldsRef.current
    const parameters = parameterFields.reduce<Row>((values, field) => {
      const name = field.parameterName ?? fieldName(field)
      if (name) values[name] = urlParameterValue(searchParams, name)
      return values
    }, {})
    const hasMissingRequiredParameter = parameterFields.some(field => {
      const name = field.parameterName ?? fieldName(field)
      return field.nullable?.trim().toUpperCase() === 'N' && !urlParameterValue(searchParams, name)
    })
    if (hasMissingRequiredParameter) {
      setRows([])
      setTotalCount(0)
      setFacets([])
      setAllowedActions([])
      setAllowedOperations([])
      setIsPrivilegedAccount(false)
      setError(undefined)
      setLoading(false)
      return
    }
    setLoading(true)
    setError(undefined)
    try {
      const requestedFacetValue = facetValue === null ? selectedFacetValueRef.current : facetValue
      const result = await webCoreApi<SearchResponse>(`/legacy-search/${encodeURIComponent(modId)}/${encodeURIComponent(subMod)}/data`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parameters, filter: expression ? { expression } : undefined, facet: requestedFacetValue && facetFieldNameRef.current ? { field: facetFieldNameRef.current, value: requestedFacetValue } : undefined, skip: nextPage * nextPageSize, take: nextPageSize }),
      })
      // Tab clicks can overlap. A slower response for the previous tab must
      // never overwrite the grid that has already moved to a newer tab.
      if (requestId !== latestSearchRequestRef.current) return
      setRows(result.data ?? [])
      setTotalCount(result.totalCount ?? 0)
      setFacets(result.facets ?? [])
      setAllowedActions((result.permission?.actions ?? []).map(action => action.trim().toLowerCase()))
      setAllowedOperations((result.permission?.operations ?? []).map(operation => operation.trim().toUpperCase()))
      setIsPrivilegedAccount(result.permission?.isPrivilegedAccount === true)
    } catch (cause) {
      if (requestId !== latestSearchRequestRef.current) return
      setRows([])
      setTotalCount(0)
      setFacets([])
      setAllowedActions([])
      setAllowedOperations([])
      setIsPrivilegedAccount(false)
      setError(cause instanceof Error ? cause.message : 'Cannot load search data.')
    } finally {
      if (requestId === latestSearchRequestRef.current) setLoading(false)
    }
  }, [modId, searchParams, subMod])

  useEffect(() => {
    let active = true
    parameterFieldsRef.current = []
    setPage(0)
    setFilterExpression(null)
    setSelectedFacetValue(undefined)
    selectedFacetValueRef.current = undefined
    facetFieldNameRef.current = ''
    setColumns([])
    setSelectedRow(undefined)
    setRightPanel(undefined)
    autoOpenedRightPanelRef.current = ''
    setNotice(undefined)
    setLoading(true)
    void webCoreApi<unknown>(`/module/${encodeURIComponent(modId)}/form-info/${encodeURIComponent(subMod)}`).then(formResponse => {
      if (!active) return
      const nextForm = normalizeFormInfo(formResponse)
      setForm(nextForm)
      parameterFieldsRef.current = nextForm.parameterFields ?? []
      setColumns((nextForm.columnFields ?? []).map((field, index) => ({ field, index }))
        .sort((left, right) => (left.field.columnIndex ?? Number.MAX_SAFE_INTEGER) - (right.field.columnIndex ?? Number.MAX_SAFE_INTEGER) || left.index - right.index)
        .map(item => item.field))
      facetFieldNameRef.current = fieldName(nextForm.searchFacetFields?.[0] ?? {})
      return loadRows(0, pageSize, null)
    }).catch(cause => {
      if (!active) return
      setError(cause instanceof Error ? cause.message : 'Cannot load module metadata.')
      setLoading(false)
    })
    return () => { active = false }
  }, [loadRows, modId, pageSize, subMod])

  useEffect(() => {
    if (!language) return

    let active = true
    const localizationPath = `/module/${encodeURIComponent(modId)}/${encodeURIComponent(subMod)}/localization`

    const requestLocalization = (requestedLanguage: string) => webCoreApi<unknown>(localizationPath, {
      headers: { 'Accept-Language': requestedLanguage },
    })

    void (async () => {
      try {
        // MY/EN are selectable on the client.  If the server has not yet been
        // configured for that language, retain usable Vietnamese captions.
        const response = await requestLocalization(language).catch(async error => {
          if (language === 'VN') throw error
          return requestLocalization('VN')
        })
        if (active) setLocalization(normalizeLocalization(response))
      } catch {
        // Do not overwrite a previously successful localization with an error.
        if (active) setLocalization(current => current)
      }
    })()

    return () => { active = false }
  }, [language, modId, subMod])

  useEffect(() => {
    if (error) setErrorToast(error)
  }, [error])

  useEffect(() => {
    const syncSidebar = () => setSidebarCollapsed(document.body.classList.contains('sidebar-collapse'))
    syncSidebar()
    const observer = new MutationObserver(syncSidebar)
    observer.observe(document.body, { attributes: true, attributeFilter: ['class'] })
    return () => observer.disconnect()
  }, [])

  const pageCount = Math.max(1, Math.ceil(totalCount / pageSize))
  const screenTitle = localization?.Title ?? form?.title ?? form?.moduleName ?? 'Legacy Search'
  const configuredButtons = form?.buttons ?? []
  // Older metadata rows (including imported legacy modules) do not always
  // carry SHOWONTOOLBAR. In that case all visible DEFMODBTN records belong on
  // the search toolbar; when the flag is present, keep its explicit intent.
  const buttonsMarkedForToolbar = configuredButtons.filter(button => button.showOnToolbar?.trim().toUpperCase() === 'Y')
  const toolbarButtons = (buttonsMarkedForToolbar.length > 0 ? buttonsMarkedForToolbar : configuredButtons)
    .filter(button => {
      const fixedPanelKey = `${button.callModuleID?.trim().toUpperCase()}|${button.callSubModule?.trim().toUpperCase()}`
      if (fixedRightPanelModules.has(fixedPanelKey) && button.callModuleID?.trim().toUpperCase() === '02D45' && !allows(dmsPermission, 'View')) return false
      if (isPrivilegedAccount) return true
      const operation = buttonOperation(button)
      return operation
        ? allowedOperations.includes(operation)
        : allowedActions.includes(buttonAction(button))
    })
  const hasActiveFilter = filterExpression != null
  const primaryFacetField = form?.searchFacetFields?.[0]
  const primaryFacet = facets?.find(facet => facet.field?.toLowerCase() === fieldName(primaryFacetField ?? {}).toLowerCase())
  const facetCount = (value?: string) => value == null
    ? primaryFacet?.allCount ?? 0
    : primaryFacet?.items?.find(item => item.value === value)?.count ?? 0
  const changePage = (nextPage: number) => {
    const normalized = Math.max(0, Math.min(pageCount - 1, nextPage))
    setPage(normalized)
    void loadRows(normalized, pageSize, filterExpression)
  }
  const submitPageInput = () => {
    const requested = Number.parseInt(pageInput, 10)
    const nextPage = Number.isFinite(requested) ? requested - 1 : page
    const normalized = Math.max(0, Math.min(pageCount - 1, nextPage))
    setPageInput(String(normalized + 1))
    if (normalized !== page) changePage(normalized)
  }

  useEffect(() => setPageInput(String(page + 1)), [page])

  const openAssignedForm = async (button: ModuleButton, row = selectedRow, notificationKey?: string) => {
    if (button.buttonName?.trim().toUpperCase() === 'BTN_REFRESH') {
      setError(undefined)
      setNotice(undefined)
      await loadRows(page, pageSize, filterExpression)
      return
    }

    const targetModule = button.callModuleID?.trim()
    const targetSubModule = button.callSubModule?.trim().toUpperCase()
    if (!targetModule || !targetSubModule) {
      setError('Nút chưa được cấu hình CallModuleID/CallSubModule.')
      return
    }

    setError(undefined)
    setNotice(undefined)
    try {
      const fixedPanelKey = `${targetModule.toUpperCase()}|${targetSubModule}`
      if (fixedRightPanelModules.has(fixedPanelKey)) {
        if (targetModule.toUpperCase() === '02D45' && !allows(dmsPermission, 'View')) {
          setNotice({ tone: 'warning', text: 'Bạn không có quyền 02D45/View để xem chi tiết tài liệu.' })
          return
        }
        if (!row) {
          setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
          return
        }
        const parameters = navigationParameters(button.btnParams, row)
        if (!parameters.size) {
          setError('Nút panel phải chưa có BTN_PARAMS hợp lệ.')
          return
        }
        if (notificationKey && activeNotificationNavigationRef.current !== notificationKey) return
        setRightPanel({ moduleID: targetModule.toUpperCase(), values: { ...row, ...Object.fromEntries(parameters.entries()) }, btnParams: button.btnParams })
        return
      }
      const targetDefinition = normalizeFormInfo(await webCoreApi<unknown>(`/module/${encodeURIComponent(targetModule)}/definition/${encodeURIComponent(targetSubModule)}`))
      if (notificationKey && activeNotificationNavigationRef.current !== notificationKey) return
      if (targetDefinition.moduleType?.trim() === '00' && targetDefinition.uiType?.trim().toUpperCase() === 'R') {
        if (!row) {
          setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
          return
        }
        const parameters = navigationParameters(button.btnParams, row)
        if (!parameters.size) {
          setError('Nút panel phải chưa có BTN_PARAMS hợp lệ.')
          return
        }
        setRightPanel({ moduleID: targetModule.toUpperCase(), values: { ...row, ...Object.fromEntries(parameters.entries()) }, btnParams: button.btnParams })
        return
      }
      const [formResponse, localizationResponse] = await Promise.all([
        webCoreApi<unknown>(`/module/${encodeURIComponent(targetModule)}/form-info/${encodeURIComponent(targetSubModule)}`),
        webCoreApi<unknown>(`/module/${encodeURIComponent(targetModule)}/${encodeURIComponent(targetSubModule)}/localization`, {
          headers: { 'Accept-Language': language ?? 'VN' },
        }),
      ])
      const targetForm = normalizeFormInfo(formResponse)
      const targetLocalization = normalizeLocalization(localizationResponse)
      const moduleType = targetForm.moduleType?.trim()

      if (moduleType === '03') {
        const isBack = button.buttonName?.trim().toUpperCase() === 'BTN_BACK'
        if (!row && !isBack) {
          setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
          return
        }
        const parameters = navigationParameters(button.btnParams, row ?? {})
        if (!parameters.size && !isBack) {
          setError('Nút điều hướng chưa có BTN_PARAMS hợp lệ.')
          return
        }
        const query = parameters.toString()
        router.push(`/legacy-search/${encodeURIComponent(targetModule)}/${encodeURIComponent(targetSubModule)}${query ? `?${query}` : ''}`)
        return
      }

      if (moduleType === '02') {
        const startsNewRecord = targetSubModule === 'MAD'
        if (!startsNewRecord && !row) {
          setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
          return
        }
        const fields = targetForm.commonFields?.length ? targetForm.commonFields : targetForm.columnFields ?? []
        setPopupMaximized(false)
        cccDraftValuesRef.current = {}
        const selectedValues = Object.fromEntries(Object.entries(row ?? {}).map(([key, value]) => [key, value ?? '']))
        const initialValues = startsNewRecord ? defaultValues(fields) : selectedValues
        const nextPopup = { button, form: targetForm, values: { ...initialValues, ...maintainParameters(button.btnParams, row ?? {}, searchParams) }, localization: targetLocalization }
        setPopup(nextPopup)
        refreshDynamicCcbItems(nextPopup, nextPopup.values)
        return
      }

      if (moduleType !== '01') {
        setError(`Module ${targetModule}/${targetSubModule} có ModType ${moduleType || '(trống)'} chưa được hỗ trợ.`)
        return
      }
      if (!row) {
        setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
        return
      }
      if (button.showConfirm?.trim().toUpperCase() === 'Y') {
        setConfirmation({ button, row: { ...row } })
        return
      }

      const buttonName = button.buttonName?.trim()
      if (!buttonName) throw new Error('Nút chưa được cấu hình BTNNAME.')
      const response = await webCoreApi<{ message?: unknown }>(
        `/legacy-search/${encodeURIComponent(modId)}/${encodeURIComponent(subMod)}/button/${encodeURIComponent(buttonName)}/execute`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values: row }) },
      )
      const message = typeof response.message === 'string' && response.message.trim() ? response.message.trim() : `${buttonCaption(button, localization)} thành công.`
      setNotice({ tone: 'success', text: message })
      setPage(0)
      await loadRows(0, pageSize, filterExpression)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể thực hiện thao tác.')
    }
  }

  // A notification can point at any Legacy Search module with URL parameters.
  // Use that module's own metadata to select a matching row and invoke its
  // declared view/detail action; no module ID or form is hard-coded here.
  useEffect(() => {
    if (autoOpenedFormRef.current === autoOpenKey || !form || rows.length === 0) return
    if (notificationNavigation && !sidebarReady) return

    const routeParameters = Array.from(searchParams.entries()).filter(([name]) =>
      (form.parameterFields ?? []).some(field => fieldName(field).toLowerCase() === name.toLowerCase())
      || rows.some(row => rowValue(row, name) !== undefined),
    )
    if (routeParameters.length === 0) return

    const viewButton = (form.buttons ?? []).find(button =>
      ['BTN_VIEW', 'BTN_DETAIL'].includes(button.buttonName?.trim().toUpperCase() ?? ''),
    )
    if (!viewButton) return

    const operation = buttonOperation(viewButton)
    const permitted = isPrivilegedAccount
      || (operation ? allowedOperations.includes(operation) : allowedActions.includes(buttonAction(viewButton)))
    if (!permitted) return

    const matchingRow = rows.find(candidate => {
      let matched = false
      for (const [name, value] of routeParameters) {
        const actual = rowValue(candidate, name)
        if (actual === undefined) continue
        matched = true
        if (String(actual) !== value) return false
      }
      return matched
    })
    // During notification navigation the previous grid can still contain one
    // row while the new CASE_ID request is in flight. Never use that stale row
    // as a fallback; wait until the new grid returns an exact match.
    const row = matchingRow ?? (notificationNavigation ? undefined : rows.length === 1 ? rows[0] : undefined)
    if (!row) return

    autoOpenedFormRef.current = autoOpenKey
    setSelectedRow(row)
    void openAssignedForm(viewButton, row, notificationNavigation ? autoOpenKey : undefined)
  }, [allowedActions, allowedOperations, autoOpenKey, form, isPrivilegedAccount, notificationNavigation, rows, searchParams, sidebarReady])

  const savePopup = async () => {
    if (!popup) return
    const targetModule = popup.form.moduleID?.trim()
    const targetSubModule = popup.form.subModule?.trim().toUpperCase()
    if (!targetModule || (targetSubModule !== 'MAD' && targetSubModule !== 'MED')) {
      setNotice({ tone: 'warning', text: 'Form hiện tại không có thao tác lưu.' })
      return
    }
    setPopupSaving(true)
    setError(undefined)
    try {
      const fields = popup.form.commonFields?.length ? popup.form.commonFields : popup.form.columnFields ?? []
      // Row data comes from PostgreSQL with lowercase keys while DEFMODFLD
      // commonly uses uppercase names. A spread would retain both
      // `allowed_extensions` and `ALLOWED_EXTENSIONS`; the case-insensitive
      // lookup then reads the old key first. Replace the existing key instead.
      const valuesWithDrafts = Object.entries(cccDraftValuesRef.current)
        .reduce((current, [name, value]) => writeRowValue(current, name, value), { ...popup.values })
      const values = normalizeCccValuesForStore(valuesWithDrafts, fields)
      const response = await webCoreApi<{ message?: unknown }>(
        `/legacy-search/${encodeURIComponent(modId)}/${encodeURIComponent(subMod)}/button/${encodeURIComponent(popup.button.buttonName ?? '')}/maintain/commit`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ values: storeValues(values, fields) }),
        },
      )
      setPopup(undefined)
      cccDraftValuesRef.current = {}
      setPopupMaximized(false)
      const successMessage = typeof response.message === 'string' && response.message.trim()
        ? response.message.trim()
        : 'Đã lưu dữ liệu.'
      setNotice({ tone: 'success', text: successMessage })
      setSuccessToast(successMessage)
      setPage(0)
      await loadRows(0, pageSize, filterExpression)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể lưu dữ liệu.')
    } finally {
      setPopupSaving(false)
    }
  }

  const executeToolbarButton = async (button: ModuleButton, row = selectedRow) => {
    const buttonName = button.buttonName?.trim()
    if (!buttonName) {
      setNotice({ tone: 'warning', text: 'Nút chưa được cấu hình BTNNAME.' })
      return
    }
    if (!row) {
      setNotice({ tone: 'warning', text: 'Hãy chọn một bản ghi trước khi thực hiện.' })
      return
    }

    setError(undefined)
    setNotice(undefined)
    try {
      const response = await webCoreApi<{ message?: unknown }>(
        `/legacy-search/${encodeURIComponent(modId)}/${encodeURIComponent(subMod)}/button/${encodeURIComponent(buttonName)}/execute`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ values: row }),
        },
      )
      const message = typeof response.message === 'string' && response.message.trim()
        ? response.message.trim()
        : `${buttonCaption(button, localization)} thành công.`
      setNotice({ tone: 'success', text: message })
      setPage(0)
      await loadRows(0, pageSize, filterExpression)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể thực hiện thao tác.')
    }
  }

  // DEFMODFLD.COLUMN_INDEX is the configured form order as well as the grid
  // order. Preserve the source order only for legacy fields without an index.
  const popupFields = (popup?.form.commonFields?.length ? popup.form.commonFields : popup?.form.columnFields ?? [])
    .filter(field => !fieldHidden(field, popup?.form.subModule))
    .map((field, index) => ({ field, index }))
    .sort((left, right) => (left.field.columnIndex ?? Number.MAX_SAFE_INTEGER) - (right.field.columnIndex ?? Number.MAX_SAFE_INTEGER) || left.index - right.index)
    .map(item => item.field)
  const popupReadOnly = popup?.form.subModule?.trim().toUpperCase() === 'MVW'
  const closePopup = () => {
    if (popupSaving) return
    setPopup(undefined)
    cccDraftValuesRef.current = {}
    setPopupMaximized(false)
  }
  const popupMaximizedBounds = (() => {
    if (typeof window === 'undefined') return { width: 760, height: 520, host: undefined as HTMLElement | undefined }
    const host = sidebarCollapsed ? undefined : document.querySelector<HTMLElement>('.app-content') ?? undefined
    const rect = host?.getBoundingClientRect()
    return {
      width: sidebarCollapsed ? window.innerWidth : Math.max(720, Math.floor((rect?.width ?? window.innerWidth) - 16)),
      height: sidebarCollapsed ? window.innerHeight : Math.max(420, Math.floor((rect?.height ?? window.innerHeight) - 16)),
      host,
    }
  })()
  const popupUsesMaximizedBounds = popupMaximized || sidebarCollapsed
  const refreshDynamicCcbItems = (currentPopup: MaintainPopup, values: Row, changedField?: Field) => {
    const fields = currentPopup.form.commonFields?.length ? currentPopup.form.commonFields : currentPopup.form.columnFields ?? []
    const dynamicFields = fields.filter(field => isDependentCcb(field) && (!changedField || referencesField(field, changedField)))
    const dynamicFileFields = fields.filter(field => isFileUpload(field) && listSourceReferences(field).length > 0 && (!changedField || referencesField(field, changedField)))
    const targetModule = currentPopup.form.moduleID?.trim()
    const targetSubModule = currentPopup.form.subModule?.trim()
    if ((!dynamicFields.length && !dynamicFileFields.length) || !targetModule || !targetSubModule) return
    void Promise.all(dynamicFields.map(async field => {
      const fieldId = field.fieldID?.trim()
      if (!fieldId) return [fieldId, []] as const
      const response = await webCoreApi<unknown>(
        `/module/${encodeURIComponent(targetModule)}/form-info/${encodeURIComponent(targetSubModule)}/ccb/${encodeURIComponent(fieldId)}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(values) },
      )
      return [fieldId, normalizeLookupItems(response)] as const
    })).then(async results => {
      const fileResults = await Promise.all(dynamicFileFields.map(async field => {
        const fieldId = field.fieldID?.trim()
        if (!fieldId) return [fieldId, { allowedExtensions: [] }] as const
        const response = await webCoreApi<unknown>(
          `/module/${encodeURIComponent(targetModule)}/form-info/${encodeURIComponent(targetSubModule)}/file-config/${encodeURIComponent(fieldId)}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(values) },
        )
        return [fieldId, fileConfigFromResponse(response)] as const
      }))
      setPopup(active => active === currentPopup
        ? { ...active, dynamicItems: { ...active.dynamicItems, ...Object.fromEntries(results) }, dynamicFileConfigs: { ...active.dynamicFileConfigs, ...Object.fromEntries(fileResults) } }
        : active)
    }).catch(cause => setError(cause instanceof Error ? cause.message : 'Cannot load dependent list.'))
  }
  const updatePopupValue = (field: Field, value: unknown) => {
    const name = fieldName(field)
    if (!name || !popup) return
    // Store the date-only representation instead of Date.toJSON() / UTC.
    // This prevents selecting 2026-10-30 in UTC+7 from being committed as
    // 2026-10-29 by the server-side DATE binder.
    if (isDate(field)) value = dateOnlyString(value) ?? ''
    const currentValue = rowValue(popup.values, name)
    // DropDownBox re-emits its selected rows while its content is rendered.
    // Do not turn that notification into a state update when the stored JSON
    // selection has not changed; otherwise React enters a render loop.
    if (isCcc(field) ? sameCccValues(currentValue, value) : currentValue === value) return
    const fields = popup.form.commonFields?.length ? popup.form.commonFields : popup.form.columnFields ?? []
    const initialValues = writeRowValue(popup.values, name, value)
    const nextValues = fields.filter(candidate => referencesField(candidate, field))
      .reduce((values, candidate) => writeRowValue(values, fieldName(candidate), ''), initialValues)
    const next = { ...popup, values: nextValues }
    setPopup(next)
    refreshDynamicCcbItems(next, next.values, field)
  }
  const columnWidth = (field: Field) => {
    const name = fieldName(field)
    const longestValue = rows.reduce((longest, row) => Math.max(longest, String(rowValue(row, name) ?? '').length), 0)
    const visibleCharacters = Math.min(maximumDisplayedCharacters, Math.max(label(field, localization).length, longestValue))
    const configuredWidth = field.fixedWidth ?? 0
    return Math.max(160, configuredWidth, Math.ceil(visibleCharacters * 8.5) + 36)
  }

  return (
    <AppContent title={screenTitle} breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Legacy Search' }]}>
      <div className="legacy-search-page">
        {error && (
          <div className="alert alert-danger legacy-search-alert d-flex align-items-center" role="alert">
            <span>{error}</span>
            <button type="button" className="btn-close ms-auto" aria-label="Đóng thông báo lỗi" onClick={() => setError(undefined)} />
          </div>
        )}
        {notice && (
          <div className={`alert alert-${notice.tone} legacy-search-alert d-flex align-items-center`} role="alert">
            <span>{notice.text}</span>
            <button type="button" className="btn-close ms-auto" aria-label="Đóng thông báo" onClick={() => setNotice(undefined)} />
          </div>
        )}
        <div className="card legacy-search-card">
        <div className="card-header">
          {toolbarButtons.length > 0 && (
            <div className="card-tools d-flex flex-wrap gap-1">
              {toolbarButtons.map(button => (
                <Button
                  key={button.buttonID ?? button.buttonName}
                  text={buttonCaption(button, localization)}
                  icon={buttonIcon(button, localization)}
                  stylingMode="text"
                  disabled={loading}
                  onClick={() => void openAssignedForm(button)}
                />
              ))}
            </div>
          )}
        </div>
        <div className="card-body p-2 legacy-search-card-body">
          {primaryFacetField && (
            <nav className="legacy-search-facets" aria-label={`${label(primaryFacetField, localization)} tabs`}>
              <button type="button" className={`legacy-search-facet${selectedFacetValue == null ? ' active' : ''}`} disabled={loading} onClick={() => {
                setSelectedFacetValue(undefined)
                setPage(0)
                void loadRows(0, pageSize, filterExpression, '')
              }}><span>All</span><small>{facetCount()}</small></button>
              {lookupItems(primaryFacetField, localization).map(item => (
                <button key={item.value} type="button" className={`legacy-search-facet${selectedFacetValue === item.value ? ' active' : ''}`} disabled={loading} onClick={() => {
                  setSelectedFacetValue(item.value)
                  setPage(0)
                  void loadRows(0, pageSize, filterExpression, item.value)
                }}><span>{item.text ?? item.value}</span><small>{facetCount(item.value)}</small></button>
              ))}
            </nav>
          )}
          <div className="legacy-search-grid-with-panel">
          <div className="legacy-search-grid-shell">
          <div className="legacy-search-grid">
            <DataGrid
            dataSource={rows}
            showBorders
            height="100%"
            filterValue={filterExpression ?? emptyGridFilter}
            filterSyncEnabled
            columnAutoWidth={false}
            columnHidingEnabled={false}
            cellHintEnabled
            allowColumnReordering
            allowColumnResizing
            wordWrapEnabled={false}
            hoverStateEnabled
            rowAlternationEnabled
            onSelectionChanged={event => setSelectedRow(event.selectedRowsData[0] as Row | undefined)}
            onRowClick={event => {
              if (event.rowType !== 'data') return
              event.component.element().querySelectorAll('.legacy-search-grid__selected-row')
                .forEach(row => row.classList.remove('legacy-search-grid__selected-row'))
              event.rowElement?.classList.add('legacy-search-grid__selected-row')
              setSelectedRow(event.data as Row)
            }}
            onFilterValueChange={value => {
              const nextFilter: GridFilter | null = Array.isArray(value) && value.length === 0 ? null : value ?? null
              setFilterExpression(nextFilter)
              setPage(0)
              void loadRows(0, pageSize, nextFilter)
            }}
            noDataText={loading ? '' : 'Không có dữ liệu'}
          >
            <Selection mode="single" />
            <Sorting mode="multiple" />
            <FilterRow visible />
            <HeaderFilter visible />
            <FilterBuilderPopup visible={filterBuilderVisible} onHiding={() => setFilterBuilderVisible(false)} />
            <Scrolling mode="virtual" columnRenderingMode="standard" useNative={false} />
            {columns.map((field, index) => {
              const items = lookupItems(field, localization)
              const checkbox = isCheckbox(field) || isBoolean(field)
              return (
                <Column
                  key={`${field.fieldID || 'field'}-${fieldName(field)}-${index}`}
                  name={`${field.fieldID || 'field'}-${fieldName(field)}-${index}`}
                  dataField={fieldName(field).toLowerCase()}
                  caption={label(field, localization)}
                  width={columnWidth(field)}
                  allowHiding={false}
                  dataType={checkbox ? 'boolean' : isDate(field) ? 'date' : undefined}
                  calculateCellValue={isDate(field) ? (row: Row) => localCalendarDate(rowValue(row, fieldName(field))) : undefined}
                  format={isDate(field) ? field.fieldFormat ?? 'dd/MM/yyyy' : undefined}
                  customizeText={(event: { valueText?: string }) => truncateCellText(event.valueText ?? '')}
                  cellRender={({ value, text }: { value?: unknown; text?: string }) => {
                    if (checkbox) return <CheckBox value={checkboxValue(value)} readOnly />
                    if (isCcc(field)) {
                      const content = cccValues(value).map(selectedValue => items.find(item => item.value === selectedValue)?.text ?? selectedValue).join(', ')
                      return <span title={content}>{truncateCellText(content)}</span>
                    }
                    const content = truncateCellText(text ?? String(value ?? ''))
                    const style = configuredColor(field, value, localization)
                    return style
                      ? <span className="legacy-search-code-badge" style={style} title={text ?? String(value ?? '')}>{content}</span>
                      : <span title={text ?? String(value ?? '')}>{content}</span>
                  }}
                >
                  {items.length > 0 && !isCcc(field) && <Lookup dataSource={items} valueExpr="value" displayExpr="text" />}
                </Column>
              )
            })}
            </DataGrid>
          </div>
          <div className="card-footer legacy-search-card-footer">
              <div className="d-flex gap-2">
                <Button icon="filter" text={uiText(localization, 'CREATE_FILTER', 'Create Filter')} stylingMode="text" onClick={() => setFilterBuilderVisible(true)} />
                <Button icon="clear" text={uiText(localization, 'CLEAR_FILTER', 'Clear Filter')} stylingMode="text" disabled={!hasActiveFilter || loading} onClick={() => { setFilterExpression(null); setPage(0); void loadRows(0, pageSize, null) }} />
              </div>
              <div className="d-flex align-items-center gap-2 legacy-search-pager">
                <SelectBox width={90} items={[10, 25, 50, 100]} value={pageSize} onValueChanged={event => { const nextSize = Number(event.value); setPageSize(nextSize); setPage(0); void loadRows(0, nextSize, filterExpression) }} />
                <input aria-label="Trang hiện tại" className="form-control form-control-sm legacy-search-page-input" disabled={loading} max={pageCount} min={1} onBlur={submitPageInput} onChange={event => setPageInput(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.currentTarget.blur() } }} type="number" value={pageInput} />
                <span>/ {pageCount} ({totalCount})</span>
                <Button icon="chevronleft" stylingMode="text" disabled={page === 0 || loading} onClick={() => changePage(page - 1)} />
                <Button icon="chevronright" stylingMode="text" disabled={page + 1 >= pageCount || loading} onClick={() => changePage(page + 1)} />
              </div>
            </div>
          </div>
          {rightPanel?.moduleID === '02D45' && allows(dmsPermission, 'View') && typeof rowValue(rightPanel.values, 'DOCUMENT_ID') === 'string' && <DmsDocumentRightPanel key={`${String(rowValue(rightPanel.values, 'DOCUMENT_ID'))}|${autoOpenKey}`} documentID={String(rowValue(rightPanel.values, 'DOCUMENT_ID'))} permission={dmsPermission} onClose={() => setRightPanel(undefined)} onDocumentUpdated={() => void loadRows(page, pageSize, filterExpression)} />}
          </div>
        </div>
        </div>
      </div>
      <Popup
        visible={Boolean(popup)}
        onHiding={closePopup}
        showCloseButton={!popupSaving}
        showTitle
        title={popup?.localization?.Title ?? popup?.form.title ?? popup?.form.moduleName ?? 'Maintain'}
        width={popupUsesMaximizedBounds ? popupMaximizedBounds.width : 760}
        height={popupUsesMaximizedBounds ? popupMaximizedBounds.height : 'auto'}
        maxHeight={popupUsesMaximizedBounds ? popupMaximizedBounds.height : '85vh'}
        position={popupUsesMaximizedBounds && popupMaximizedBounds.host ? { of: popupMaximizedBounds.host, my: 'center', at: 'center' } : undefined}
        wrapperAttr={{ class: `legacy-search-maintain-popup${popupUsesMaximizedBounds ? ' legacy-search-maintain-popup--maximized' : ''}${sidebarCollapsed ? ' legacy-search-maintain-popup--viewport' : ''}` }}
        toolbarItems={[{
          widget: 'dxButton',
          toolbar: 'top',
          location: 'after',
          options: {
            icon: popupUsesMaximizedBounds ? 'collapse' : 'fullscreen',
            stylingMode: 'text',
            hint: popupUsesMaximizedBounds ? 'Thu nhỏ' : 'Phóng to',
            onClick: () => setPopupMaximized(value => !value),
          },
        }]}
      >
        <div className="container-fluid py-2 legacy-search-popup-content">
          <div className="row g-3 legacy-search-popup-fields">
            {popupFields.map((field, index) => {
              const name = fieldName(field)
              const value = name && popup ? rowValue(popup.values, name) : undefined
              const dynamicItems = popup?.dynamicItems?.[field.fieldID ?? fieldName(field)]
              const items = dynamicItems ?? lookupItems(field, popup?.localization)
              const uploadConfig = popup?.dynamicFileConfigs?.[field.fieldID ?? fieldName(field)]
              const booleanItems = [{ value: true, text: 'true' }, { value: false, text: 'false' }]
              const readOnly = popupReadOnly || fieldReadOnly(field, popup?.form.subModule)
              const dependencySelected = (!isDependentCcb(field) && !isFileUpload(field)) || listSourceReferences(field).every(reference => {
                const source = (popup?.form.commonFields?.length ? popup.form.commonFields : popup?.form.columnFields ?? [])
                  .find(candidate => [candidate.fieldID, candidate.fieldName, candidate.parameterName].some(name => name?.toLowerCase() === reference.toLowerCase()))
                const sourceValue = source && popup ? rowValue(popup.values, fieldName(source)) : undefined
                return sourceValue != null && String(sourceValue).trim() !== ''
              })
              return (
                <div className={isFileUpload(field) || isTextArea(field) ? 'col-12' : 'col-md-6'} key={`${field.fieldID ?? name}-${index}`}>
                  <label className="form-label">{label(field, popup?.localization)}</label>
                  {isFileUpload(field) ? (
                    <label className={`legacy-cfu-dropzone${readOnly || !dependencySelected ? ' legacy-cfu-dropzone--disabled' : ''}`} onDragOver={event => event.preventDefault()} onDrop={event => {
                      event.preventDefault()
                      const file = event.dataTransfer.files?.[0]
                      if (!file || readOnly || !dependencySelected) return
                      const validationError = fileValidationError(file, uploadConfig)
                      if (validationError) { setError(validationError); return }
                      void legacyFilePayload(file).then(payload => updatePopupValue(field, payload)).catch(cause => setError(cause instanceof Error ? cause.message : 'Cannot read selected file.'))
                    }}>
                      <input type="file" className="visually-hidden" disabled={readOnly || !dependencySelected} accept={uploadConfig?.allowedExtensions.map(extension => `.${extension}`).join(',') || undefined}
                        onChange={event => {
                          const file = event.currentTarget.files?.[0]
                          if (!file) return
                          const validationError = fileValidationError(file, uploadConfig)
                          if (validationError) { setError(validationError); event.currentTarget.value = ''; return }
                          void legacyFilePayload(file).then(payload => updatePopupValue(field, payload)).catch(cause => setError(cause instanceof Error ? cause.message : 'Cannot read selected file.'))
                        }} />
                      <span className="legacy-cfu-dropzone__icon" aria-hidden="true">⇧</span>
                      <strong>{legacyFileName(value) || 'Choose upload file'}</strong>
                      <small>{uploadConfig?.allowedExtensions.map(extension => extension.toUpperCase()).join(', ') || 'Choose a file'}{uploadConfig?.maxFileSizeMb ? ` · Max ${uploadConfig.maxFileSizeMb} MB` : ''}</small>
                    </label>
                  ) : isCheckbox(field) ? (
                    <CheckBox value={checkboxValue(value)} readOnly={readOnly} onValueChanged={event => updatePopupValue(field, Boolean(event.value))} />
                  ) : isBoolean(field) ? (
                    <SelectBox value={value} items={booleanItems} valueExpr="value" displayExpr="text" readOnly={readOnly} onValueChanged={event => updatePopupValue(field, event.value)} />
                  ) : isCcb(field) ? (
                    <SelectBox value={value} items={items} valueExpr="value" displayExpr="text" readOnly={readOnly || !dependencySelected} placeholder={dependencySelected ? 'Select...' : 'Select category first'} onValueChanged={event => updatePopupValue(field, event.value)} />
                  ) : isCcc(field) ? (
                    <CccEditor
                      value={value}
                      items={items}
                      readOnly={readOnly}
                      placeholder="Select..."
                      onChange={nextValue => { cccDraftValuesRef.current[fieldName(field)] = nextValue }}
                    />
                  ) : isDate(field) ? (
                    <DateBox value={localCalendarDate(value)} type="date" displayFormat={field.fieldFormat ?? 'dd/MM/yyyy'} readOnly={readOnly} onValueChanged={event => updatePopupValue(field, event.value)} />
                  ) : isNumeric(field) ? (
                    <NumberBox value={value == null || value === '' ? undefined : Number(value)} readOnly={readOnly} onValueChanged={event => updatePopupValue(field, event.value)} />
                  ) : isTextArea(field) ? (
                    <TextArea value={value == null ? '' : String(value)} readOnly={readOnly} autoResizeEnabled minHeight={120} onValueChanged={event => updatePopupValue(field, event.value)} />
                  ) : (
                    <TextBox value={value == null ? '' : String(value)} mode={isPassword(field) ? 'password' : 'text'} readOnly={readOnly} onValueChanged={event => updatePopupValue(field, event.value)} />
                  )}
                </div>
              )
            })}
          </div>
          <div className="d-flex justify-content-end gap-2 pt-3 legacy-search-popup-actions">
            <Button text={popupReadOnly ? 'Đóng' : 'Hủy'} stylingMode="outlined" disabled={popupSaving} onClick={closePopup} />
            {!popupReadOnly && <Button text="Lưu" type="default" disabled={popupSaving} onClick={() => void savePopup()} />}
          </div>
        </div>
      </Popup>
      <Popup
        visible={Boolean(confirmation)}
        onHiding={() => setConfirmation(undefined)}
        showCloseButton
        showTitle
        title="Confirm action"
        width={560}
        height="auto"
      >
        <div className="p-4">
          <div className="d-flex gap-3 align-items-start">
            <span aria-hidden="true" style={{ color: '#f0ad00', fontSize: 34, lineHeight: 1 }}>⚠</span>
            <div>
              <div className="fs-5 mb-2">Are you sure you want to {confirmation ? buttonCaption(confirmation.button, localization) : 'continue'}?</div>
              <div className="text-muted">This action changes the selected user and may not be reversible.</div>
            </div>
          </div>
          <div className="d-flex justify-content-end gap-2 pt-4">
            <Button text="Cancel" stylingMode="outlined" onClick={() => setConfirmation(undefined)} />
            <Button
              text={confirmation ? buttonCaption(confirmation.button, localization) : 'Confirm'}
              type="danger"
              onClick={() => {
                const action = confirmation
                setConfirmation(undefined)
                if (action) void executeToolbarButton(action.button, action.row)
              }}
            />
          </div>
        </div>
      </Popup>
      <LoadPanel visible={loading} shading />
      <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}>
        <Toast show={Boolean(successToast)} autohideMs={6000} onClose={() => setSuccessToast(undefined)} theme="success" title={screenTitle}>
          {successToast ?? ''}
        </Toast>
        <Toast show={Boolean(errorToast)} autohideMs={6000} onClose={() => setErrorToast(undefined)} theme="danger" title="Lỗi">
          {errorToast ?? ''}
        </Toast>
      </div>
    </AppContent>
  )
}
