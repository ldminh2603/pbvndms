import type { MenuNode } from '@adminlte/react'
import { withBase } from './base'
import { webCoreApi } from './webcore-api'

type AppMenuItem = {
  menuId: number
  componentCode: string
  menuKey: string
  parentMenuId: number | null
  nodeType: 'root' | 'header' | 'group' | 'item'
  text: string | null
  textI18n: string | Record<string, string> | null
  href: string | null
  target: '_self' | '_blank' | null
  icon: string | null
  position: number
}

type LanguageDictionary = Record<string, Record<string, string>>

/** Namespace prevents app-menu labels from colliding with legacy DEFLANG keys. */
function languageKey(item: AppMenuItem) {
  return `APP_MENU$${item.componentCode}.${item.menuKey}`
}

function localizedIcon(item: AppMenuItem, dictionary: Record<string, string>) {
  const icon = dictionary[`${languageKey(item)}.Icon`]?.trim()
  return icon || undefined
}

function localizedText(item: AppMenuItem, dictionary: Record<string, string>) {
  const metadataText = dictionary[languageKey(item)]
  if (typeof metadataText === 'string' && metadataText.trim()) return metadataText.trim()
  // Do not fall back to core.app_menu text/text_i18n. This makes missing DEFLANG data visible.
  return languageKey(item)
}

function menuHref(href: string, text: string) {
  if (href === '/' && text.trim().toLowerCase() === 'dashboard v1') return withBase('/index1')
  return href.startsWith('/') ? withBase(href) : href
}

function toMenuNodes(items: AppMenuItem[], dictionary: Record<string, string>): MenuNode[] {
  const ordered = [...items].sort((left, right) => left.position - right.position || left.menuId - right.menuId)
  const rootIds = new Set(ordered.filter(item => item.nodeType === 'root').map(item => item.menuId))
  const children = new Map<number, AppMenuItem[]>()
  ordered.forEach(item => {
    if (item.parentMenuId !== null) children.set(item.parentMenuId, [...(children.get(item.parentMenuId) ?? []), item])
  })

  const build = (item: AppMenuItem, lineage: Set<number>): MenuNode | undefined => {
    if (item.nodeType === 'root' || lineage.has(item.menuId)) return undefined
    const text = localizedText(item, dictionary)
    if (item.nodeType === 'header') return { type: 'header', text }
    if (item.nodeType === 'item' && item.href) {
      return { type: 'item', text, href: menuHref(item.href, text), icon: localizedIcon(item, dictionary), target: item.target ?? undefined }
    }
    if (item.nodeType !== 'group') return undefined
    const next = new Set(lineage)
    next.add(item.menuId)
    return {
      type: 'group', text, icon: localizedIcon(item, dictionary),
      children: (children.get(item.menuId) ?? []).map(child => build(child, next)).filter((node): node is MenuNode => Boolean(node)),
    }
  }

  return ordered.filter(item => item.parentMenuId !== null && rootIds.has(item.parentMenuId))
    .map(item => build(item, new Set()))
    .filter((node): node is MenuNode => Boolean(node))
}

/** Sidebar source of truth: core.app_menu only. */
export async function loadMenuItems(component: string | undefined, language: string): Promise<MenuNode[]> {
  const normalizedComponent = component?.trim().toUpperCase()
  if (!normalizedComponent) return []
  const languageId = language.trim().toUpperCase()
  const [items, languagePayload] = await Promise.all([
    webCoreApi<AppMenuItem[]>(`/menu/app/${encodeURIComponent(normalizedComponent)}`),
    languageId
      ? webCoreApi<LanguageDictionary>(
        `/language/menu/${encodeURIComponent(normalizedComponent)}`,
        { headers: { 'Accept-Language': languageId } },
      ).catch((): LanguageDictionary => ({}))
      : Promise.resolve<LanguageDictionary>({}),
  ])
  return toMenuNodes(items, languagePayload[languageId] ?? {})
}
