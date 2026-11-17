'use client'

import { useEffect, useState, type ReactNode } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import {
  authSessionStorageKey,
  clearClientAuthenticationState,
  getCurrentUser,
  webCoreAuthenticationExpiredEvent,
} from '@/lib/webcore-auth'

const publicPaths = new Set(['/login', '/lockscreen'])
export const selectedComponentStorageKey = 'selected_component'
const defaultInactivityTimeoutSeconds = 5 * 60

type CurrentUser = {
  isPrivilegedAccount?: boolean
  IsPrivilegedAccount?: boolean
  components?: string[]
  Components?: string[]
  component?: string[]
  Component?: string[]
  lockScreenTimeoutSeconds?: unknown
  LockScreenTimeoutSeconds?: unknown
}

function lockScreenTimeoutSeconds(user: CurrentUser | undefined) {
  const value = Number(user?.lockScreenTimeoutSeconds ?? user?.LockScreenTimeoutSeconds)
  return Number.isInteger(value) && value > 0 && value <= 86_400 ? value : defaultInactivityTimeoutSeconds
}

function getUserComponents(user: CurrentUser) {
  return (user.components ?? user.Components ?? user.component ?? user.Component ?? [])
    .filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
}

function canAccessWithoutComponent(pathname: string, isPrivilegedAccount: boolean) {
  return pathname === '/component'
    || (isPrivilegedAccount && (pathname.startsWith('/admin-tools') || pathname.startsWith('/admin/')))
}

export function AppAuthGate({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const [isAuthorized, setIsAuthorized] = useState(false)
  const [inactivityTimeoutSeconds, setInactivityTimeoutSeconds] = useState(defaultInactivityTimeoutSeconds)
  const isPublicPath = publicPaths.has(pathname)

  useEffect(() => {
    let active = true

    if (isPublicPath) {
      setIsAuthorized(true)
      return () => {
        active = false
      }
    }

    if (window.localStorage.getItem(authSessionStorageKey) !== '1') {
      setIsAuthorized(false)
      router.replace('/login')
      return () => {
        active = false
      }
    }

    void getCurrentUser()
      .then(async response => {
        if (!active) return

        if (response.ok) {
          const user = await response.json() as CurrentUser
          if (!active) return

          setInactivityTimeoutSeconds(lockScreenTimeoutSeconds(user))

          const components = getUserComponents(user)
          const isPrivilegedAccount = user.isPrivilegedAccount === true || user.IsPrivilegedAccount === true
          const selectedComponent = window.localStorage.getItem(selectedComponentStorageKey) || undefined
          const mustSelectComponent = (isPrivilegedAccount || components.length > 1)
            && !selectedComponent
            && !canAccessWithoutComponent(pathname, isPrivilegedAccount)

          if (components.length === 1 && !isPrivilegedAccount) {
            window.localStorage.setItem(selectedComponentStorageKey, components[0])
          } else if (!isPrivilegedAccount && selectedComponent && !components.includes(selectedComponent)) {
            window.localStorage.removeItem(selectedComponentStorageKey)
          }

          if (mustSelectComponent) {
            router.replace('/component')
            return
          }

          if (pathname === '/component' && components.length === 1 && !isPrivilegedAccount) {
            router.replace('/')
            return
          }

          window.localStorage.setItem(authSessionStorageKey, '1')
          // Keep the dashboard mounted while a sibling route is verified. This
          // lets Next App Router replace only the page body instead of remounting
          // the sidebar/topbar on every menu click.
          setIsAuthorized(true)
          return
        }

        setIsAuthorized(false)
        window.localStorage.removeItem(authSessionStorageKey)
        router.replace('/login')
      })
      .catch(() => {
        if (!active) return

        setIsAuthorized(false)
        window.localStorage.removeItem(authSessionStorageKey)
        router.replace('/login')
      })

    return () => {
      active = false
    }
  }, [isPublicPath, pathname, router])

  useEffect(() => {
    if (isPublicPath) return

    const lockExpiredSession = () => {
      clearClientAuthenticationState()
      setIsAuthorized(false)
      const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(`/lockscreen?returnTo=${encodeURIComponent(returnTo)}`)
    }

    window.addEventListener(webCoreAuthenticationExpiredEvent, lockExpiredSession)
    return () => window.removeEventListener(webCoreAuthenticationExpiredEvent, lockExpiredSession)
  }, [isPublicPath, router])

  useEffect(() => {
    if (isPublicPath) return

    let timeoutId: number

    const resetInactivityTimer = () => {
      window.clearTimeout(timeoutId)
      timeoutId = window.setTimeout(
        () => {
          // usePathname() intentionally excludes query/hash. Preserve the
          // complete in-app location so every metadata-driven parameter stays
          // available after the lock screen replaces the route.
          const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`
          router.replace(`/lockscreen?returnTo=${encodeURIComponent(returnTo)}`)
        },
        inactivityTimeoutSeconds * 1000,
      )
    }

    const activityEvents = ['pointerdown', 'keydown', 'scroll', 'touchstart']
    activityEvents.forEach(event => window.addEventListener(event, resetInactivityTimer, { passive: true }))
    resetInactivityTimer()

    return () => {
      window.clearTimeout(timeoutId)
      activityEvents.forEach(event => window.removeEventListener(event, resetInactivityTimer))
    }
  }, [inactivityTimeoutSeconds, isPublicPath, pathname, router])

  return isPublicPath || isAuthorized ? children : null
}
