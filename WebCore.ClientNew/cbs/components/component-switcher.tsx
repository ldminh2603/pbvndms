'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { selectedComponentStorageKey } from '@/components/app-auth-gate'
import { readStoredLanguage, webCoreLanguageChangedEvent } from '@/lib/language'
import { webCoreApi } from '@/lib/webcore-api'

type LocalizedText = { name?: string; description?: string }
type BackendComponent = {
  code?: string
  language?: Record<string, LocalizedText | undefined>
}

type ComponentDetails = {
  name: string
  description: string
}

const noComponent: ComponentDetails = {
  name: 'Chưa chọn component',
  description: 'Chọn component để tải menu và quyền tương ứng.',
}

function getComponentDetails(items: BackendComponent[], code: string, language: string): ComponentDetails {
  const item = items.find(value => value.code?.trim().toLowerCase() === code.toLowerCase())
  if (!item) return { name: code, description: '' }

  const localized = item.language?.[language]
  const fallback = Object.values(item.language ?? {}).find(value => value?.name?.trim() || value?.description?.trim())
  return {
    name: localized?.name?.trim() || fallback?.name?.trim() || item.code?.trim() || code,
    description: localized?.description?.trim() || fallback?.description?.trim() || '',
  }
}

/** Shows the active component and returns the user to the component selector. */
export function ComponentSwitcher() {
  const router = useRouter()
  const [componentCode, setComponentCode] = useState('')
  const [language, setLanguage] = useState('')
  const [details, setDetails] = useState<ComponentDetails>(noComponent)

  useEffect(() => {
    const syncContext = () => {
      setComponentCode(window.localStorage.getItem(selectedComponentStorageKey)?.trim() || '')
      setLanguage(readStoredLanguage() ?? '')
    }

    syncContext()
    window.addEventListener(webCoreLanguageChangedEvent, syncContext)
    window.addEventListener('storage', syncContext)
    return () => {
      window.removeEventListener(webCoreLanguageChangedEvent, syncContext)
      window.removeEventListener('storage', syncContext)
    }
  }, [])

  useEffect(() => {
    let active = true
    if (!componentCode) {
      setDetails(noComponent)
      return () => { active = false }
    }

    void webCoreApi<BackendComponent[]>('/component/list')
      .then(items => {
        if (active) setDetails(getComponentDetails(items, componentCode, language))
      })
      .catch(() => {
        if (active) setDetails({ name: componentCode, description: '' })
      })
    return () => { active = false }
  }, [componentCode, language])

  const changeComponent = () => {
    window.localStorage.removeItem(selectedComponentStorageKey)
    router.replace('/component')
  }

  return (
    <li className="nav-item dropdown">
      <button
        aria-label="Thay đổi component"
        className="nav-link border-0 bg-transparent"
        data-bs-auto-close="outside"
        data-bs-toggle="dropdown"
        title="Thay đổi component"
        type="button"
      >
        <i className="bi bi-grid-3x3-gap-fill" aria-hidden="true" />
      </button>

      <div className="dropdown-menu dropdown-menu-end component-switcher-menu shadow">
        <div className="component-switcher-current">
          <div className="fw-semibold">{details.name}</div>
          {details.description && <small className="text-body-secondary d-block mt-1">{details.description}</small>}
        </div>
        <div className="p-3 pt-2">
          <button className="btn btn-primary w-100" onClick={changeComponent} type="button">
            <i className="bi bi-arrow-left me-2" aria-hidden="true" />
            THAY ĐỔI COMPONENT
          </button>
        </div>
      </div>
    </li>
  )
}
