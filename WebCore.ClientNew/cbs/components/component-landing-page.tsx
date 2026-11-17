'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { selectedComponentStorageKey } from '@/components/app-auth-gate'
import { landingRecentChangedEvent, getRecentModules, togglePinnedModule, type RecentModule } from '@/lib/landing-recent'
import { activeUserEmailStorageKey, getCurrentUser } from '@/lib/webcore-auth'
import { webCoreApi } from '@/lib/webcore-api'
import { readStoredLanguage, webCoreLanguageChangedEvent } from '@/lib/language'

type MenuItem = {
  menuId: number
  componentCode: string
  menuKey: string
  href: string | null
  icon: string | null
}
type User = { email?: string; displayName?: string; name?: string; userName?: string }
type MenuDisplay = { text: string; icon: string }
type LanguageDictionary = Record<string, Record<string, string>>

const announcements = [
  { icon: 'bi-gear', tone: 'warning', title: 'Kế hoạch bảo trì hệ thống', message: 'Theo dõi thông báo vận hành trước khi thực hiện các thao tác dữ liệu lớn hoặc batch job.', meta: 'Vận hành' },
  { icon: 'bi-arrow-repeat', tone: 'success', title: 'Cập nhật chức năng', message: 'Các thay đổi về module, dashboard, report và quyền truy cập sẽ được tổng hợp tại khu vực này.', meta: 'Release' },
  { icon: 'bi-info-circle', tone: 'primary', title: 'Thông tin sử dụng workspace', message: 'Thông báo chung dành cho toàn hệ thống; thông báo cần xử lý sẽ hiển thị tại mục Notifications.', meta: 'Hệ thống' },
]

function languageKey(item: MenuItem) {
  return `APP_MENU$${item.componentCode}.${item.menuKey}`
}

function localizedText(item: MenuItem, dictionary: Record<string, string>) {
  const value = dictionary[languageKey(item)]
  return typeof value === 'string' && value.trim() ? value.trim() : languageKey(item)
}

function displayIcon(icon?: string | null) {
  return icon?.startsWith('bi ') ? icon : 'bi bi-window-stack'
}

function recentModuleReference(href: string) {
  const legacyMatch = href.match(/^\/legacy-search\/([^/?#]+)/i)
  return legacyMatch?.[1] || href.split('/').pop() || href
}

export function ComponentLandingPage() {
  const router = useRouter()
  const [component, setComponent] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('User')
  const [language, setLanguage] = useState('')
  const [menu, setMenu] = useState<Map<string, MenuDisplay>>(() => new Map())
  const [recent, setRecent] = useState<RecentModule[]>([])

  const refreshContext = useCallback(() => {
    const nextComponent = window.localStorage.getItem(selectedComponentStorageKey)?.trim().toUpperCase() || ''
    const nextEmail = window.sessionStorage.getItem(activeUserEmailStorageKey)?.trim() || ''
    setComponent(nextComponent)
    setEmail(nextEmail)
    setLanguage(readStoredLanguage() ?? '')
    setRecent(getRecentModules(nextEmail, nextComponent))
  }, [])

  useEffect(() => {
    refreshContext()
    window.addEventListener(landingRecentChangedEvent, refreshContext)
    window.addEventListener(webCoreLanguageChangedEvent, refreshContext)
    window.addEventListener('storage', refreshContext)
    return () => {
      window.removeEventListener(landingRecentChangedEvent, refreshContext)
      window.removeEventListener(webCoreLanguageChangedEvent, refreshContext)
      window.removeEventListener('storage', refreshContext)
    }
  }, [refreshContext])

  useEffect(() => {
    let active = true
    void getCurrentUser().then(async response => {
      if (!response.ok) return
      const user = await response.json() as User
      if (!active) return
      const nextEmail = user.email?.trim() || ''
      setEmail(nextEmail)
      setDisplayName(user.displayName?.trim() || user.name?.trim() || user.userName?.trim() || nextEmail || 'User')
      if (nextEmail) {
        window.sessionStorage.setItem(activeUserEmailStorageKey, nextEmail)
        setRecent(getRecentModules(nextEmail, component))
      }
    }).catch(() => undefined)
    return () => { active = false }
  }, [component])

  useEffect(() => {
    let active = true
    if (!component) { setMenu(new Map()); return () => { active = false } }
    const languageId = language.trim().toUpperCase()
    const dictionaryRequest: Promise<LanguageDictionary> = languageId
      ? webCoreApi<LanguageDictionary>(`/language/menu/${encodeURIComponent(component)}`, {
        headers: { 'Accept-Language': languageId },
      }).catch((): LanguageDictionary => ({}))
      : Promise.resolve({})
    void Promise.all([
      webCoreApi<MenuItem[]>(`/menu/app/${encodeURIComponent(component)}`),
      dictionaryRequest,
    ])
      .then(([items, payload]) => {
        if (!active) return
        const dictionary = payload[languageId] ?? {}
        setMenu(new Map(items.filter(item => item.href).map(item => [item.href as string, {
          text: localizedText(item, dictionary), icon: displayIcon(item.icon),
        }])))
      })
      .catch(() => { if (active) setMenu(new Map()) })
    return () => { active = false }
  }, [component, language])

  const pinned = useMemo(() => recent.filter(item => item.pinnedAt), [recent])
  const open = (href: string) => router.push(href)
  const togglePin = (event: React.MouseEvent<HTMLButtonElement>, href: string) => {
    event.stopPropagation()
    if (email && component) togglePinnedModule(email, component, href)
  }
  const display = (item: RecentModule) => menu.get(item.href) ?? { text: item.href.split('/').pop() || item.href, icon: 'bi bi-window-stack' }
  const renderItem = (item: RecentModule, index?: number, pinnedItem = false) => {
    const info = display(item)
    return <div
      className="landing-module-item"
      key={item.href}
      onClick={() => open(item.href)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          open(item.href)
        }
      }}
      role="button"
      tabIndex={0}
    >
      {typeof index === 'number' && <span className="landing-module-rank">{index + 1}</span>}
      <i className={`${info.icon} landing-module-icon`} aria-hidden="true" />
      <span className="landing-module-copy"><strong>{info.text}</strong><small>{recentModuleReference(item.href)}</small></span>
      <span className="landing-module-count">{item.visitCount}</span>
      <span className="landing-pin" onClick={event => event.stopPropagation()}>
        <button aria-label={pinnedItem ? `Bỏ ghim ${info.text}` : `Ghim ${info.text}`} className="btn btn-link p-0" onClick={event => togglePin(event, item.href)} type="button">
          <i className={`bi ${pinnedItem ? 'bi-pin-angle-fill' : 'bi-pin-angle'}`} aria-hidden="true" />
        </button>
      </span>
    </div>
  }

  return <main className="landing-page">
    <header className="landing-page-header">
      <div><h1>Xin chào, {displayName}</h1><p>Tổng quan component hiện tại</p></div>
      {component && <span className="landing-component-chip">{component}</span>}
    </header>
    <div className="landing-top-grid">
      <section className="landing-panel"><h2>Truy cập gần đây</h2>
        {recent.length ? <div className="landing-module-list">{recent.map((item, index) => renderItem(item, index))}</div> : <div className="landing-empty">Chưa có lịch sử truy cập module.</div>}
      </section>
      <section className="landing-panel"><h2>Đã ghim</h2>
        {pinned.length ? <div className="landing-module-list">{pinned.map(item => renderItem(item, undefined, true))}</div> : <div className="landing-empty">Chưa có module được ghim.</div>}
      </section>
    </div>
    <section className="landing-panel landing-announcements"><div><h2>Thông báo chung</h2><p>Thông báo chung từ hệ thống</p></div>
      {announcements.map(item => <article className="landing-announcement" key={item.title}>
        <span className={`landing-announcement-icon text-${item.tone}`}><i className={`bi ${item.icon}`} /></span>
        <div><strong>{item.title}</strong><p>{item.message}</p></div><small>{item.meta}</small>
      </article>)}
    </section>
  </main>
}
