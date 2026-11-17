'use client'

import { useEffect, useMemo, useState } from 'react'
import { AppContent, Card, Toast } from '@adminlte/react'
import Chart, { ArgumentAxis, CommonSeriesSettings, Legend as ChartLegend, Series, SeriesTemplate, Tooltip as ChartTooltip, ValueAxis } from 'devextreme-react/chart'
import PieChart, { Label, Legend as PieLegend, Series as PieSeries, Tooltip as PieTooltip } from 'devextreme-react/pie-chart'
import TreeMap, { Colorizer, Tooltip as TreeMapTooltip } from 'devextreme-react/tree-map'

type Widget = { id: string; type: string }
type Layout = { schemaVersion: number; widgets: Widget[] }
type Permission = { actions?: string[]; isPrivilegedAccount?: boolean }
type DashboardUi = { moduleId: string; moduleName?: string; language?: Record<string, Record<string, string | undefined> | undefined>; permission?: Permission; dashboardLayout: Layout }
type Product = { name: string; quantity: string; price: number; discontinued: boolean; unitsInStock: number }
type SupportIssue = { employee: string; productName: string; resolvedTime: number; issueType: string }

const widgetTypes: Record<string, string[]> = { D001: ['pie', 'treemap', 'bar'], D002: ['filter', 'stackedBar'], D003: ['empty'] }
const nameOf = (ui: DashboardUi | undefined) => ui?.language?.vi?.['Module$Name'] ?? ui?.language?.en?.['Module$Name'] ?? ui?.moduleName ?? 'Dashboard'
const editable = (permission: Permission | undefined) => permission?.isPrivilegedAccount === true || permission?.actions?.some(action => action.toLowerCase() === 'edit') === true

export function RuntimeDashboardModule({ moduleId }: { moduleId: string }) {
  const id = moduleId.trim().toUpperCase()
  const [ui, setUi] = useState<DashboardUi>(); const [rows, setRows] = useState<Product[] | SupportIssue[]>([]); const [layout, setLayout] = useState<Layout>()
  const [designerOpen, setDesignerOpen] = useState(false); const [selectedProduct, setSelectedProduct] = useState(''); const [error, setError] = useState<string>(); const [notice, setNotice] = useState<string>(); const [saving, setSaving] = useState(false)

  useEffect(() => {
    let active = true
    setUi(undefined); setRows([]); setLayout(undefined); setError(undefined); setDesignerOpen(false)
    void Promise.all([fetch(`/api/webcore-api/dashboard/${encodeURIComponent(id)}/ui`, { credentials: 'include' }), fetch(`/api/webcore-api/dashboard/${encodeURIComponent(id)}/data`, { credentials: 'include' })])
      .then(async ([uiResponse, dataResponse]) => { if (!uiResponse.ok || !dataResponse.ok) throw new Error('Unable to load dashboard data or configuration.'); return [await uiResponse.json() as DashboardUi, await dataResponse.json() as { rows: Product[] | SupportIssue[] }] as const })
      .then(([nextUi, nextData]) => { if (active) { setUi(nextUi); setLayout(nextUi.dashboardLayout); setRows(nextData.rows) } })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : 'Unable to load dashboard.') })
    return () => { active = false }
  }, [id])

  const products = rows as Product[]; const supportIssues = rows as SupportIssue[]
  const quantityCounts = useMemo(() => Object.entries(products.reduce<Record<string, number>>((counts, product) => { counts[product.quantity] = (counts[product.quantity] ?? 0) + 1; return counts }, {})).map(([quantity, count]) => ({ quantity, count })), [products])
  const supportProducts = useMemo(() => [...new Set(supportIssues.map(row => row.productName))], [supportIssues])
  const filteredIssues = useMemo(() => selectedProduct ? supportIssues.filter(row => row.productName === selectedProduct) : supportIssues, [selectedProduct, supportIssues])
  const add = (type: string) => setLayout(current => current ? { ...current, widgets: [...current.widgets, { id: `${type}-${crypto.randomUUID()}`, type }] } : current)
  const remove = (index: number) => setLayout(current => current ? { ...current, widgets: current.widgets.filter((_, item) => item !== index) } : current)
  const move = (index: number, direction: -1 | 1) => setLayout(current => { if (!current || index + direction < 0 || index + direction >= current.widgets.length) return current; const widgets = [...current.widgets]; [widgets[index], widgets[index + direction]] = [widgets[index + direction], widgets[index]]; return { ...current, widgets } })
  const save = async () => {
    if (!layout) return; setSaving(true)
    try { const response = await fetch(`/api/webcore-api/dashboard/${encodeURIComponent(id)}/layout`, { method: 'PUT', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dashboardLayout: layout }) }); if (!response.ok) throw new Error('Unable to save dashboard layout.'); const result = await response.json() as { dashboardLayout: Layout }; setLayout(result.dashboardLayout); setDesignerOpen(false); setNotice('Đã lưu bố cục dashboard.') }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Unable to save dashboard layout.') } finally { setSaving(false) }
  }
  const widget = (item: Widget) => {
    if (item.type === 'pie') return <div className="col-12 col-xl-6" key={item.id}><Card title="Discontinued (Count)" className="h-100"><PieChart dataSource={quantityCounts} palette="Soft Pastel" type="doughnut" height={380}><PieSeries argumentField="quantity" valueField="count"><Label visible position="columns" customizeText={(point: any) => `${point.argumentText}: ${point.valueText}`} /></PieSeries><PieLegend visible={false} /><PieTooltip enabled customizeTooltip={(point: any) => ({ text: `${point.argumentText}: ${point.valueText}` })} /></PieChart></Card></div>
    if (item.type === 'treemap') return <div className="col-12 col-xl-6" key={item.id}><Card title="Products" className="h-100"><TreeMap dataSource={products} valueField="price" labelField="name" height={380}><Colorizer palette="Soft Pastel" /><TreeMapTooltip enabled customizeTooltip={(point: any) => ({ text: `${point.node.label()}: $${point.value}` })} /></TreeMap></Card></div>
    if (item.type === 'bar') return <div className="col-12" key={item.id}><Card title="Unit Price by Product"><Chart dataSource={products} palette="Soft Pastel" height={360}><ArgumentAxis label={{ overlappingBehavior: 'rotate', rotationAngle: 45 }} /><ValueAxis /><Series argumentField="name" valueField="price" type="bar" name="Unit price" /><ChartLegend visible={false} /><ChartTooltip enabled customizeTooltip={(point: any) => ({ text: `$${point.valueText}` })} /></Chart></Card></div>
    if (item.type === 'filter') return <div className="col-12 col-lg-3" key={item.id}><Card title="Platform"><select className="form-select" value={selectedProduct} onChange={event => setSelectedProduct(event.target.value)}><option value="">All products</option>{supportProducts.map(product => <option key={product} value={product}>{product}</option>)}</select></Card></div>
    if (item.type === 'stackedBar') return <div className="col-12 col-lg-9" key={item.id}><Card title="Resolved Time by Employee"><Chart dataSource={filteredIssues} palette="Soft Pastel" height={360}><CommonSeriesSettings argumentField="employee" valueField="resolvedTime" type="stackedBar" /><SeriesTemplate nameField="issueType" /><ArgumentAxis label={{ overlappingBehavior: 'rotate', rotationAngle: 45 }} /><ValueAxis /><ChartTooltip enabled /><ChartLegend verticalAlignment="bottom" horizontalAlignment="center" /></Chart></Card></div>
    return <div className="col-12" key={item.id}><Card title="Blank Dashboard"><div className="text-muted py-5 text-center">Thêm widget trong chế độ thiết kế để bắt đầu.</div></Card></div>
  }
  const title = nameOf(ui)
  return <AppContent title={title} breadcrumbs={[{ label: 'Home', href: '/' }, { label: title }]}>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {ui && layout && <>{editable(ui.permission) && <div className="mb-3"><button className="btn btn-outline-primary" onClick={() => setDesignerOpen(value => !value)}><i className="bi bi-sliders me-1" />{designerOpen ? 'Đóng thiết kế' : 'Thiết kế dashboard'}</button></div>}
      {designerOpen && <Card title="Thiết kế dashboard" className="mb-3"><div className="d-flex flex-wrap gap-2 mb-3">{(widgetTypes[id] ?? []).map(type => <button key={type} className="btn btn-sm btn-primary" onClick={() => add(type)}>Thêm {type}</button>)}</div><div className="list-group mb-3">{layout.widgets.map((item, index) => <div key={item.id} className="list-group-item d-flex align-items-center justify-content-between"><span>{index + 1}. {item.type}</span><span className="btn-group"><button className="btn btn-sm btn-outline-secondary" disabled={index === 0} onClick={() => move(index, -1)}>↑</button><button className="btn btn-sm btn-outline-secondary" disabled={index === layout.widgets.length - 1} onClick={() => move(index, 1)}>↓</button><button className="btn btn-sm btn-outline-danger" onClick={() => remove(index)}>Xóa</button></span></div>)}</div><button className="btn btn-success" onClick={() => void save()} disabled={saving}>{saving ? 'Đang lưu...' : 'Lưu bố cục'}</button></Card>}
      <div className="row g-3">{layout.widgets.map(widget)}</div></>}
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}><Toast show={Boolean(notice)} onClose={() => setNotice(undefined)} theme="success" title="Dashboard">{notice ?? ''}</Toast></div>
  </AppContent>
}
