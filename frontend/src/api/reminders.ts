import { request } from './client'
import type { MutationResult, PageResult } from './types'
export interface Reminder {
  id: string
  rowVersion: string
  scope: string
  enabled: boolean
  channel: string
  localTime?: string
  timeZone: string
  installationId?: string
  capabilityState: string
}
export interface Notification {
  id: string
  rowVersion: string
  title: string
  body: string
  category: string
  targetType: string
  targetId: string | null
  readAt: string | null
  createdAt: string
}
export const getReminders = () =>
  request<PageResult<Reminder>>('/api/v1/reminder-rules', { query: { limit: 100 } })
export const createReminder = (data: Record<string, unknown>) =>
  request<MutationResult>('/api/v1/reminder-rules', {
    method: 'POST',
    data,
    mutation: true,
    epoch: true,
  })
export const toggleReminder = (item: Reminder) =>
  request<MutationResult>(`/api/v1/reminder-rules/${item.id}`, {
    method: 'PATCH',
    data: { expectedRowVersion: item.rowVersion, enabled: !item.enabled },
    mutation: true,
    epoch: true,
  })
export const deleteReminder = (item: Reminder) =>
  request(`/api/v1/reminder-rules/${item.id}`, {
    method: 'DELETE',
    data: { expectedRowVersion: item.rowVersion },
    mutation: true,
    epoch: true,
  })
export const getNotifications = () =>
  request<PageResult<Notification>>('/api/v1/notifications', { query: { limit: 100 } })
export const readNotifications = (items: Notification[], read = true) =>
  request('/api/v1/notifications/read-state', {
    method: 'PUT',
    mutation: true,
    epoch: true,
    data: {
      items: items.map((item) => ({ id: item.id, expectedRowVersion: item.rowVersion })),
      read,
    },
  })
export const deleteNotification = (item: Notification) =>
  request(`/api/v1/notifications/${item.id}`, {
    method: 'DELETE',
    mutation: true,
    epoch: true,
    data: { expectedRowVersion: item.rowVersion },
  })
