import { request } from './client'
import type { MeView, MutationResult, PageResult, UserGoalView } from './types'
import type { Policy } from './account'

export interface Consent {
  id: string
  rowVersion: string
  purpose: string
  status: string
  policyVersion: string
  decidedAt?: string
}
export interface GoalDefinition {
  id: string
  code: string
  title: string
  category: string
  description: string
  allowedUnits: string[]
  minValue: number | null
  maxValue: number | null
  defaultValue: number | null
  enabled: boolean
}
export interface Availability {
  id: string
  rowVersion: string
  weekday: number
  kind: string
  startTime: string
  endTime: string
  endDayOffset: number
  timeZone: string
  effectiveFrom: string
  effectiveTo?: string
  label?: string
}
export interface ExceptionSlot {
  kind: string
  startTime: string
  endTime: string
  endDayOffset: number
  label?: string
}
export interface DayException {
  id: string
  rowVersion: string
  date: string
  timeZone: string
  mode: string
  slots: ExceptionSlot[]
  reason?: string
}
const write = <T>(
  path: string,
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  data: unknown,
  epoch = true,
) => request<T>(path, { method, data, mutation: true, epoch })
export const getMe = () => request<MeView>('/api/v1/me')
export const patchProfile = (data: Record<string, unknown>) =>
  write<MutationResult>('/api/v1/me/profile', 'PATCH', data)
export const patchPreferences = (data: Record<string, unknown>) =>
  write<MutationResult>('/api/v1/me/preferences', 'PATCH', data)
export const getConsents = () =>
  request<{ items: Consent[]; currentPolicyVersions: Policy[] }>('/api/v1/me/consents')
export const changeConsent = (
  purpose: string,
  expectedRowVersion: string,
  decision: 'GRANT' | 'WITHDRAW',
  policyVersion: string,
) =>
  write<MutationResult>(
    `/api/v1/me/consents/${purpose}`,
    'PUT',
    { expectedRowVersion, decision, policyVersion, deleteExisting: false },
    false,
  )
export const getGoalDefinitions = () =>
  request<{ items: GoalDefinition[] }>('/api/v1/goal-definitions')
export const getGoals = () =>
  request<PageResult<UserGoalView>>('/api/v1/me/goals', { query: { limit: 100 } })
export const createGoal = (data: Record<string, unknown>) =>
  write<MutationResult>('/api/v1/me/goals', 'POST', data)
export const patchGoal = (id: string, data: Record<string, unknown>) =>
  write<MutationResult>(`/api/v1/me/goals/${id}`, 'PATCH', data)
export const closeGoal = (id: string, expectedRowVersion: string, closedOn: string) =>
  write<MutationResult>(`/api/v1/me/goals/${id}/close`, 'POST', { expectedRowVersion, closedOn })
export const getAvailability = () =>
  request<PageResult<Availability>>('/api/v1/me/availability-rules', { query: { limit: 100 } })
export const createAvailability = (data: Record<string, unknown>) =>
  write<MutationResult>('/api/v1/me/availability-rules', 'POST', data)
export const deleteAvailability = (id: string, expectedRowVersion: string) =>
  write<MutationResult>(`/api/v1/me/availability-rules/${id}`, 'DELETE', { expectedRowVersion })
export const getExceptions = (from: string, to: string) =>
  request<{ items: DayException[] }>('/api/v1/me/availability-exceptions', { query: { from, to } })
export const putException = (date: string, data: Record<string, unknown>) =>
  write<MutationResult>(`/api/v1/me/availability-exceptions/${date}`, 'PUT', data)
export const deleteException = (date: string, expectedRowVersion: string) =>
  write<MutationResult>(`/api/v1/me/availability-exceptions/${date}`, 'DELETE', {
    expectedRowVersion,
  })
