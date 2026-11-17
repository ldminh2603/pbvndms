export type RecentModule = {
  href: string
  visitCount: number
  lastVisitedAt: string
  pinnedAt?: string
}

export const landingRecentChangedEvent = 'webcore-landing-recent-changed'
const storagePrefix = 'webcore_landing_recent'
const maxRecentModules = 10

function storageKey(userEmail: string, component: string) {
  return `${storagePrefix}_${encodeURIComponent(userEmail.trim().toLowerCase())}_${encodeURIComponent(component.trim().toUpperCase())}`
}

function notify() {
  window.dispatchEvent(new Event(landingRecentChangedEvent))
}

export function getRecentModules(userEmail?: string, component?: string) {
  if (!userEmail || !component || typeof window === 'undefined') return [] as RecentModule[]
  try {
    const value = JSON.parse(window.localStorage.getItem(storageKey(userEmail, component)) || '[]') as unknown
    if (!Array.isArray(value)) return []
    return value
      .filter((item): item is RecentModule => Boolean(item) && typeof item === 'object'
        && typeof (item as RecentModule).href === 'string'
        && typeof (item as RecentModule).visitCount === 'number'
        && typeof (item as RecentModule).lastVisitedAt === 'string')
      .sort((left, right) => Date.parse(right.lastVisitedAt) - Date.parse(left.lastVisitedAt))
      .slice(0, maxRecentModules)
  } catch {
    return []
  }
}

function saveRecentModules(userEmail: string, component: string, items: RecentModule[]) {
  window.localStorage.setItem(storageKey(userEmail, component), JSON.stringify(items.slice(0, maxRecentModules)))
  notify()
}

export function recordRecentModule(userEmail?: string, component?: string, href?: string) {
  if (!userEmail || !component || !href || href === '/' || href === '/component' || typeof window === 'undefined') return
  const current = getRecentModules(userEmail, component)
  const existing = current.find(item => item.href === href)
  const next: RecentModule = {
    href,
    visitCount: (existing?.visitCount ?? 0) + 1,
    lastVisitedAt: new Date().toISOString(),
    pinnedAt: existing?.pinnedAt,
  }
  saveRecentModules(userEmail, component, [next, ...current.filter(item => item.href !== href)])
}

export function togglePinnedModule(userEmail: string, component: string, href: string) {
  const current = getRecentModules(userEmail, component)
  const next = current.map(item => item.href === href
    ? { ...item, pinnedAt: item.pinnedAt ? undefined : new Date().toISOString() }
    : item)
  saveRecentModules(userEmail, component, next)
}
