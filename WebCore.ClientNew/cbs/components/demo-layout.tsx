'use client'

import { DashboardLayout, NavMessages } from '@adminlte/react'
import type { DashboardLayoutProps } from '@adminlte/react'
import { useEffect, useRef, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { loadMenuItems } from '@/lib/menu'
import { withBase } from '@/lib/base'
import { NavLink } from '@/components/nav-link'
import { LanguageSelector } from '@/components/language-selector'
import { AdminToolsMenu } from '@/components/admin-tools-menu'
import { NotificationBell } from '@/components/notification-bell'
import { ComponentSwitcher } from '@/components/component-switcher'
import {
  activeUserEmailStorageKey,
  authSessionStorageKey,
  getCurrentUser,
  signOut,
} from '@/lib/webcore-auth'
import { selectedComponentStorageKey } from '@/components/app-auth-gate'
import { recordRecentModule } from '@/lib/landing-recent'
import { readStoredLanguage, webCoreLanguageChangedEvent } from '@/lib/language'
import { SidebarReadyContext } from '@/lib/sidebar-readiness'

const sampleMessages = [
  { from: 'Brad Diesel', image: withBase('/assets/img/user1-128x128.jpg'), text: 'Call me whenever you can...', time: '4 Hours Ago', star: 'danger' as const },
  { from: 'John Pierce', image: withBase('/assets/img/user8-128x128.jpg'), text: 'I got your message bro', time: '4 Hours Ago', star: 'secondary' as const },
  { from: 'Nora Silvester', image: withBase('/assets/img/user3-128x128.jpg'), text: 'The subject goes here', time: '4 Hours Ago', star: 'warning' as const },
]

const TopbarStart = (
  <>
    <li className="nav-item d-none d-md-block">
      <a href={withBase('/')} className="nav-link">
        <i className="bi bi-grid-1x2 me-1" aria-hidden="true"></i>
        Live preview
      </a>
    </li>
    <li className="nav-item d-none d-md-block">
      <a href={withBase('/docs/introduction')} className="nav-link">
        <i className="bi bi-book me-1" aria-hidden="true"></i>
        Documentation
      </a>
    </li>
  </>
)

export type DemoLayoutProps = Partial<DashboardLayoutProps> & { children: React.ReactNode }

type CurrentUser = { displayName?: string; email?: string; avatarUrl?: string }

const defaultAvatar = withBase('/assets/img/avatar/avatar.png')
const avatarSource = (value?: string) => value?.startsWith('/') ? withBase(value) : value || defaultAvatar

/**
 * Demo-wide layout wrapper: applies the shared brand, signed-in user, topbar
 * links and dropdowns, then forwards any layout flags (fixedHeader, sidebarMini,
 * dir, …) to the library DashboardLayout.
 */
export function DemoLayout({ children, ...props }: DemoLayoutProps) {
  const router = useRouter()
  const pathname = usePathname()
  const [menuItems, setMenuItems] = useState<DashboardLayoutProps['menuItems']>([])
  const [component, setComponent] = useState<string>()
  const [language, setLanguage] = useState('')
  const [user, setUser] = useState<CurrentUser>()
  const [sidebarReady, setSidebarReady] = useState(false)
  const previousComponent = useRef<string | undefined>(undefined)

  useEffect(() => {
    const syncContext = () => {
      setComponent(window.localStorage.getItem(selectedComponentStorageKey)?.trim() || undefined)
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
    void getCurrentUser().then(async response => {
      if (!response.ok || !active) return
      const currentUser = await response.json() as CurrentUser
      if (active) setUser(currentUser)
    }).catch(() => undefined)
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    setSidebarReady(false)

    // Never blank the sidebar while navigating inside one component or while
    // changing language. Clear it only when the active component changes, so
    // users never see the previous component's menu during the new API load.
    if (previousComponent.current !== undefined && previousComponent.current !== component) {
      setMenuItems([])
    }
    previousComponent.current = component

    void loadMenuItems(component, language)
      .then(items => { if (active) { setMenuItems(items); setSidebarReady(true) } })
      .catch(() => { if (active) { setMenuItems([]); setSidebarReady(true) } })
    return () => { active = false }
  }, [component, language])

  useEffect(() => {
    recordRecentModule(
      window.sessionStorage.getItem(activeUserEmailStorageKey) || undefined,
      component,
      pathname,
    )
  }, [component, pathname])

  const handleSignOut = async () => {
    try {
      await signOut()
    } finally {
      window.localStorage.removeItem(authSessionStorageKey)
      window.localStorage.removeItem(selectedComponentStorageKey)
      window.sessionStorage.removeItem(activeUserEmailStorageKey)
      router.replace('/login')
      router.refresh()
    }
  }

  return (
    <SidebarReadyContext.Provider value={sidebarReady}>
      <DashboardLayout
      menuItems={menuItems}
      linkComponent={NavLink}
      docsHref={withBase('/docs')}
      logo={withBase('/assets/img/pbbank.webp')}
      logoHref={withBase('/')}
      colorModeToggle
      initialColorMode="light"
      user={{
        name: user?.displayName?.trim() || user?.email?.trim() || 'User',
        image: avatarSource(user?.avatarUrl),
      }}
      topbarStart={TopbarStart}
      topbarEnd={
        <>
          <NavMessages messages={sampleMessages} badgeColor="danger" />
          <NotificationBell userEmail={user?.email} />
          <LanguageSelector />
        </>
      }
      topbarBeforeUser={<><ComponentSwitcher /><AdminToolsMenu /></>}
      onSignOut={handleSignOut}
      {...props}
      >
        {children}
      </DashboardLayout>
    </SidebarReadyContext.Provider>
  )
}
