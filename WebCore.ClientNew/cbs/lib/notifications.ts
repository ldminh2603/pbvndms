import { webCoreApi } from '@/lib/webcore-api'

export type NotificationType = 'info' | 'success' | 'warning' | 'action'
export type NotificationCategory = 'system' | 'personal' | 'workflow'

export type NotificationItem = {
  id: string
  type: NotificationType
  category: NotificationCategory
  severity: 'low' | 'normal' | 'high' | 'critical'
  title: string
  message?: string | null
  actionUrl?: string | null
  createdAt: string
  readAt?: string | null
  dismissedAt?: string | null
}

export type RecentNotifications = {
  items: NotificationItem[]
  unreadCount: number
}

type ReadResult = { id: string; readAt: string }
type DismissResult = { id: string; dismissedAt: string }
type ReadAllResult = { readAt: string; updatedCount: number }

export function getRecentNotifications(limit = 10) {
  return webCoreApi<RecentNotifications>(`/notification/recent?limit=${limit}`)
}

export function markNotificationRead(id: string) {
  return webCoreApi<ReadResult>(`/notification/${encodeURIComponent(id)}/read`, { method: 'POST' })
}

export function markAllNotificationsRead(scope: 'all' | NotificationCategory = 'all') {
  return webCoreApi<ReadAllResult>('/notification/read-all', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scope }),
  })
}

export function dismissNotification(id: string) {
  return webCoreApi<DismissResult>(`/notification/${encodeURIComponent(id)}/dismiss`, { method: 'POST' })
}
