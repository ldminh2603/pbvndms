'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { webCoreApi } from '@/lib/webcore-api'
import { selectedComponentStorageKey } from '@/components/app-auth-gate'
import { webCoreLanguageChangedEvent, webCoreLanguageStorageKey } from '@/components/language-selector'

type LocalizedText = { name?: string; description?: string }
type BackendComponent = { code?: string; icon?: string; type?: string; language?: Record<string, LocalizedText | undefined> }
type BackendComponentType = { value?: string; name?: string; position?: number; language?: Record<string, LocalizedText | undefined> }
type Module = { code: string; name: string; description: string; icon: string; type: string }
type ComponentGroup = { value: string; name: string; position: number; modules: Module[] }

const fallbackIcon = 'bi-file-earmark-spreadsheet'
const iconNames: Record<string, string> = {
  database: 'bi-database', folder: 'bi-folder', spreadsheet: 'bi-file-earmark-spreadsheet',
  users: 'bi-people', settings: 'bi-gear', landmark: 'bi-bank', gauge: 'bi-speedometer2',
  lightbulb: 'bi-lightbulb', 'file-spreadsheet': 'bi-file-earmark-spreadsheet',
}

function iconClass(rawIcon?: string) {
  const value = rawIcon?.trim()
  if (!value) return fallbackIcon
  try {
    const parsed = JSON.parse(value) as { library?: string; name?: string; icon?: string }
    const name = (parsed.name ?? parsed.icon ?? '').trim().toLowerCase()
    if (parsed.library === 'dx' || parsed.library === 'devextreme') return `dx-icon dx-icon-${name || 'file'}`
    return iconNames[name] ?? fallbackIcon
  } catch {
    return iconNames[value.toLowerCase()] ?? (value.startsWith('bi-') ? value : fallbackIcon)
  }
}

function mapModules(items: BackendComponent[], language: string): Module[] {
  return items.map(item => {
    const localized = item.language?.[language]
    const fallback = Object.values(item.language ?? {}).find(value => value?.name?.trim() || value?.description?.trim())
    return {
      code: item.code?.trim() ?? '',
      name: localized?.name?.trim() || fallback?.name?.trim() || item.code?.trim() || '',
      description: localized?.description?.trim() || fallback?.description?.trim() || '',
      icon: item.icon?.trim() ?? '',
      type: item.type?.trim().toUpperCase() ?? '',
    }
  }).filter(item => item.code)
    .sort((left, right) => left.name.localeCompare(right.name, language, { sensitivity: 'base', numeric: true }))
}

function mapComponentGroups(types: BackendComponentType[], modules: Module[], language: string): ComponentGroup[] {
  return types.map(type => {
    const value = type.value?.trim().toUpperCase() ?? ''
    const localized = value ? type.language?.[language] : undefined
    const fallback = Object.values(type.language ?? {}).find(item => item?.name?.trim())
    return {
      value,
      name: localized?.name?.trim() || fallback?.name?.trim() || type.name?.trim() || value,
      position: Number(type.position) || 0,
      modules: modules.filter(module => module.type === value),
    }
  }).filter(group => group.value && group.modules.length > 0)
    .sort((left, right) => left.position - right.position || left.value.localeCompare(right.value))
}

export default function ComponentPage() {
  const router = useRouter()
  const [language, setLanguage] = useState('VN')
  const [modules, setModules] = useState<Module[]>([])
  const [componentTypes, setComponentTypes] = useState<BackendComponentType[]>([])
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const syncLanguage = () => setLanguage((window.localStorage.getItem(webCoreLanguageStorageKey) || 'VN').trim().toUpperCase())
    syncLanguage()
    window.addEventListener(webCoreLanguageChangedEvent, syncLanguage)
    return () => window.removeEventListener(webCoreLanguageChangedEvent, syncLanguage)
  }, [])

  useEffect(() => {
    let active = true
    setLoading(true)
    setError(undefined)
    void Promise.all([
      webCoreApi<BackendComponent[]>('/component/list'),
      webCoreApi<BackendComponentType[]>('/component/types'),
    ])
      .then(([componentResult, typeResult]) => {
        if (!active) return
        setModules(mapModules(componentResult, language))
        setComponentTypes(typeResult)
      })
      .catch(() => { if (active) setError('Không thể tải danh sách module.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [language])

  const componentGroups = useMemo(
    () => mapComponentGroups(componentTypes, modules, language),
    [componentTypes, modules, language],
  )
  const selectModule = useCallback((code: string) => {
    window.localStorage.setItem(selectedComponentStorageKey, code)
    router.replace('/')
  }, [router])

  const renderGroup = (group: ComponentGroup) => group.modules.length > 0 && (
    <section key={group.value} className="component-section">
      <h1 className="component-section-title">{group.name}</h1>
      <div className="component-grid">
        {group.modules.map(item => <button key={item.code} type="button" className="component-tile" onClick={() => selectModule(item.code)}>
          <i className={`${iconClass(item.icon)} component-tile-icon`} aria-hidden="true" />
          <span className="component-tile-name">{item.name}</span>
          {item.description && <span className="component-tile-description">{item.description}</span>}
        </button>)}
      </div>
    </section>
  )

  return <main className="component-page">
    {loading && <p className="component-status">Đang tải module...</p>}
    {error && <div className="alert alert-danger component-status" role="alert">{error}</div>}
    {!loading && !error && <>
      {componentGroups.map(renderGroup)}
      {modules.length === 0 && <p className="component-status text-body-secondary">Tài khoản này chưa được gán module.</p>}
    </>}
  </main>
}
