'use client'

import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { useRouter } from 'next/navigation'
import { Toast } from '@adminlte/react'
import { withBase } from '@/lib/base'
import {
  dismissNotification,
  getRecentNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationCategory,
  type NotificationItem,
} from '@/lib/notifications'

type NotificationTab = 'all' | 'system' | 'personal'
type NotificationPush = { type: 'notification'; item: NotificationItem }

const iconByType: Record<NotificationItem['type'], string> = {
  info: 'bi-info-circle-fill',
  success: 'bi-check-circle-fill',
  warning: 'bi-exclamation-triangle-fill',
  action: 'bi-bell-fill',
}

const text = {
  title: 'Th\u00f4ng b\u00e1o',
  markAllRead: '\u0110\u00c1NH D\u1ea4U \u0110\u00c3 \u0110\u1eccC',
  all: 'T\u1ea5t c\u1ea3',
  system: 'H\u1ec7 th\u1ed1ng',
  personal: 'C\u00e1 nh\u00e2n',
  loading: '\u0110ang t\u1ea3i th\u00f4ng b\u00e1o...',
  empty: 'Kh\u00f4ng c\u00f3 th\u00f4ng b\u00e1o m\u1edbi',
  view: 'VIEW',
  dismiss: 'B\u1ecf th\u00f4ng b\u00e1o',
}

function relativeTime(value: string) {
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return ''
  const minutes = Math.floor(Math.max(0, Date.now() - timestamp) / 60_000)
  if (minutes < 1) return 'v\u1eeba xong'
  if (minutes < 60) return `${minutes} ph\u00fat tr\u01b0\u1edbc`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} gi\u1edd tr\u01b0\u1edbc`
  return `${Math.floor(hours / 24)} ng\u00e0y tr\u01b0\u1edbc`
}

function countUnread(items: NotificationItem[]) {
  return items.filter(item => !item.readAt && !item.dismissedAt).length
}

// A notification may target the route that is already on screen. Give that
// navigation a unique, transport-only query value so Next re-runs the page
// state/effects; module parameters remain unchanged.
function notificationNavigationUrl(actionUrl: string, notificationId: string) {
  const target = new URL(withBase(actionUrl), window.location.origin)
  target.searchParams.set('_notification', `${notificationId}-${Date.now()}`)
  return `${target.pathname}${target.search}${target.hash}`
}

export function NotificationBell({ userEmail }: { userEmail?: string }) {
  const router = useRouter()
  const [items, setItems] = useState<NotificationItem[]>([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [activeTab, setActiveTab] = useState<NotificationTab>('all')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()
  const [realtimeToast, setRealtimeToast] = useState<NotificationItem>()
  const toggleRef = useRef<HTMLButtonElement>(null)

  const closeMenu = useCallback(() => {
    if (toggleRef.current?.getAttribute('aria-expanded') === 'true') {
      toggleRef.current.click()
    }
  }, [])

  const refresh = useCallback(async () => {
    if (!userEmail) {
      setItems([])
      setUnreadCount(0)
      return
    }
    setLoading(true)
    setError(undefined)
    try {
      const response = await getRecentNotifications(10)
      setItems(response.items.filter(item => !item.dismissedAt))
      setUnreadCount(response.unreadCount)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Kh\u00f4ng th\u1ec3 t\u1ea3i th\u00f4ng b\u00e1o.')
    } finally {
      setLoading(false)
    }
  }, [userEmail])

  useEffect(() => { void refresh() }, [refresh])

  // Offline notifications are loaded from DB once. New items arrive by WebSocket.
  useEffect(() => {
    if (!userEmail) return
    let socket: WebSocket | undefined
    let reconnect: number | undefined
    let stopped = false
    // Local development normally proxies HTTP through Next on :3001 while the
    // Spring API runs on :51130. Outside localhost, use the same browser
    // origin so HTTP pages use ws and HTTPS pages use wss through the ingress.
    const configuredEndpoint = process.env.NEXT_PUBLIC_WEBCORE_WS_URL?.trim()
    const sameOriginEndpoint = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/notification/ws`
    const endpoint = window.location.hostname === 'localhost'
      ? configuredEndpoint || `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://localhost:51130/api/notification/ws`
      : sameOriginEndpoint
    if (!endpoint) return
    const connect = () => {
      socket = new WebSocket(endpoint)
      socket.onmessage = event => {
        try {
          const push = JSON.parse(String(event.data)) as NotificationPush
          if (push.type !== 'notification' || !push.item) return
          setItems(previous => [push.item, ...previous.filter(item => item.id !== push.item.id)].slice(0, 10))
          setUnreadCount(previous => push.item.readAt || push.item.dismissedAt ? previous : previous + 1)
          setRealtimeToast(push.item)
        } catch { /* Ignore malformed realtime payloads; refresh after reconnect remains authoritative. */ }
      }
      socket.onclose = () => {
        if (!stopped) reconnect = window.setTimeout(() => { void refresh(); connect() }, 3_000)
      }
    }
    connect()
    return () => { stopped = true; if (reconnect) window.clearTimeout(reconnect); socket?.close() }
  }, [refresh, userEmail])

  const visibleItems = useMemo(() => items.filter(item => activeTab === 'all' || item.category === activeTab), [activeTab, items])

  const markRead = useCallback(async (id: string) => {
    const result = await markNotificationRead(id)
    setItems(previous => {
      const next = previous.map(item => item.id === id ? { ...item, readAt: item.readAt ?? result.readAt } : item)
      setUnreadCount(countUnread(next))
      return next
    })
  }, [])

  const onItemClick = useCallback(async (item: NotificationItem) => {
    try {
      await markRead(item.id)
      if (item.actionUrl) {
        closeMenu()
        router.push(notificationNavigationUrl(item.actionUrl, item.id))
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Kh\u00f4ng th\u1ec3 c\u1eadp nh\u1eadt th\u00f4ng b\u00e1o.')
    }
  }, [closeMenu, markRead, router])

  const dismiss = useCallback(async (event: MouseEvent, id: string) => {
    event.preventDefault()
    event.stopPropagation()
    try {
      await dismissNotification(id)
      setItems(previous => {
        const next = previous.filter(item => item.id !== id)
        setUnreadCount(countUnread(next))
        return next
      })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Kh\u00f4ng th\u1ec3 b\u1ecf th\u00f4ng b\u00e1o.')
    }
  }, [])

  const view = useCallback((event: MouseEvent, item: NotificationItem) => {
    event.preventDefault()
    event.stopPropagation()
    void onItemClick(item)
  }, [onItemClick])

  const markAllRead = useCallback(async () => {
    try {
      const result = await markAllNotificationsRead('all')
      setItems(previous => previous.map(item => ({ ...item, readAt: item.readAt ?? result.readAt })))
      setUnreadCount(0)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Kh\u00f4ng th\u1ec3 \u0111\u00e1nh d\u1ea5u \u0111\u00e3 \u0111\u1ecdc.')
    }
  }, [])

  return (
    <>
    <li className="nav-item dropdown notification-bell">
      <button ref={toggleRef} type="button" className="nav-link notification-bell-toggle" data-bs-toggle="dropdown" data-bs-auto-close="outside" aria-expanded="false" aria-label={text.title} onClick={() => { void refresh() }}>
        <i className="bi bi-bell-fill" aria-hidden="true"></i>
        {unreadCount > 0 && <span className="navbar-badge badge text-bg-danger">{unreadCount > 99 ? '99+' : unreadCount}</span>}
      </button>
      <div className="dropdown-menu dropdown-menu-end notification-bell-menu">
        <div className="notification-bell-header">
          <strong>{text.title}</strong>
          <button type="button" className="btn btn-link notification-bell-read-all" disabled={unreadCount === 0} onClick={() => { void markAllRead() }}>{text.markAllRead}</button>
        </div>
        <div className="notification-bell-tabs" role="tablist">
          {([['all', text.all], ['system', text.system], ['personal', text.personal]] as const).map(([key, label]) => (
            <button key={key} type="button" className={activeTab === key ? 'active' : ''} onClick={() => setActiveTab(key)} role="tab" aria-selected={activeTab === key}>{label}</button>
          ))}
        </div>
        <div className="notification-bell-list">
          {loading && <div className="notification-bell-empty">{text.loading}</div>}
          {!loading && error && <div className="notification-bell-empty text-danger">{error}</div>}
          {!loading && !error && visibleItems.length === 0 && <div className="notification-bell-empty">{text.empty}</div>}
          {!loading && !error && visibleItems.map(item => (
            <div key={item.id} className={`notification-bell-item ${item.readAt ? 'read' : 'unread'}`} role="button" tabIndex={0} onClick={() => { void onItemClick(item) }} onKeyDown={event => { if (event.key === 'Enter') { void onItemClick(item) } }}>
              <span className="notification-bell-icon-wrap">
                <span className={`notification-bell-icon type-${item.type}`}><i className={`bi ${iconByType[item.type]}`}></i></span>
                {!item.readAt && <span className="notification-bell-unread-dot" />}
              </span>
              <span className="notification-bell-copy"><strong>{item.title}</strong>{item.message && <span>{item.message}</span>}<small>{relativeTime(item.createdAt)}</small>{item.actionUrl && <span className="notification-bell-actions"><button type="button" onClick={event => view(event, item)}>{text.view}</button></span>}</span>
              <button type="button" className="notification-bell-dismiss" aria-label={text.dismiss} onClick={event => { void dismiss(event, item.id) }}><i className="bi bi-x-lg"></i></button>
            </div>
          ))}
        </div>
      </div>
    </li>
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}>
      <Toast show={Boolean(realtimeToast)} autohideMs={7000} onClose={() => setRealtimeToast(undefined)} onClick={() => { if (realtimeToast) { void onItemClick(realtimeToast); setRealtimeToast(undefined) } }} theme="info" title={realtimeToast?.title ?? text.title}>
        {realtimeToast?.message ?? ''}
      </Toast>
    </div>
    </>
  )
}
