import { request } from './client'
import type { Id, MutationResult, PageResult, TaskView } from './types'

export interface PlanView {
  id: Id
  rowVersion: Id
  title: string
  planKind: 'DAY' | 'WEEK' | 'CUSTOM'
  timeZone: string
  startDate: string
  endDate: string
  status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED'
  activeVersionId?: Id | null
  goalIds: Id[]
  updatedAt?: string
}

export interface TaskSnapshot {
  draftTaskKey: string
  taskId?: Id | null
  expectedTaskRowVersion?: Id | null
  title: string
  taskType: 'ROUTINE' | 'ACTIVITY' | 'MEAL_HABIT' | 'RECORDING' | 'CUSTOM'
  scheduledStartAt: string
  scheduledEndAt: string
  plannedDurationMinutes: number
  taskTimeZone: string
  isFixed: boolean
  priority: number
  sortOrder: number
  instructions?: string | null
  reminder?: {
    enabled: boolean
    offsetMinutes: number
    channel: 'IN_APP' | 'LOCAL_SYSTEM'
    timeZone: string
    quietPolicy: 'SUPPRESS' | 'NEXT_ALLOWED'
  } | null
  ruleId?: Id | null
  occurrenceKey?: string | null
}

export interface PlanVersion {
  id: Id
  rowVersion: Id
  planId: Id
  versionNo: Id
  status: 'DRAFT' | 'SEALED' | 'DISCARDED'
  changeReason: string
  sourceType: string
  createdAt: string
  sealedAt?: string | null
  isActive: boolean
  taskCount: number
}

export interface PlanVersionDetail extends PlanVersion {
  baseActiveVersionId?: Id | null
  tasks: TaskSnapshot[]
  effectiveFrom?: string
}

export interface PlanDetail extends PlanView {
  activeVersion?: PlanVersionDetail | null
  draftVersions: PlanVersion[]
}

export interface TaskDetail extends TaskView {
  rowVersion: Id
  planId: Id
  scheduledStartAt: string
  scheduledEndAt: string
  plannedDurationMinutes: number
  isFixed: boolean
  taskType: string
  instructions?: string | null
  linkedRecordIds: Id[]
  reminderRules: Record<string, unknown>[]
  lastEvent?: TaskEvent | null
}

export interface TaskEvent {
  id: Id
  eventType: string
  fromStatus?: string
  toStatus: string
  reasonCode?: string
  reasonText?: string
  receivedAt: string
}

export interface PlanScope {
  expectedRowVersion: Id
  expectedActiveVersionId?: Id | null
  effectiveFrom: string
  affectedTaskIds: Id[]
  allowFixedTaskChanges: boolean
  fixedChangeReason?: string
  expectedTasks: { taskId: Id; expectedRowVersion: Id }[]
}

export interface PlanPreview {
  validation: {
    valid: boolean
    checkedDataSeq: Id
    checkedRowVersion: Id
    checkedAt: string
    expiresAt: string
    issues: {
      code: string
      severity: string
      message: string
      fieldPath: string
      suggestedAction?: string
    }[]
  }
  changes: {
    action: string
    taskId?: Id
    after?: TaskSnapshot
    before?: TaskSnapshot
    fixedTaskChange: boolean
  }[]
  affectedTaskIds: Id[]
  unchangedHistoricalTaskIds: Id[]
  reminderChanges: { action: string; channel: string; fireAt?: string; reason: string }[]
  effectiveFrom: string
  canApply: boolean
}

export function listPlans(query: { status?: string; cursor?: string; limit?: number } = {}) {
  return request<PageResult<PlanView>>('/api/v1/plans', { query: { limit: 30, ...query } })
}

export function getPlan(id: Id) {
  return request<PlanDetail>(`/api/v1/plans/${id}`)
}

export function createPlan(
  input: {
    title: string
    planKind: string
    timeZone: string
    startDate: string
    endDate: string
    goalIds: Id[]
  },
  idempotencyKey?: string,
) {
  return request<MutationResult & { planVersionId: Id }>('/api/v1/plans', {
    method: 'POST',
    data: input,
    mutation: true,
    epoch: true,
    idempotencyKey,
  })
}

export function listVersions(planId: Id) {
  return request<PageResult<PlanVersion>>(`/api/v1/plans/${planId}/versions`, {
    query: { limit: 100 },
  })
}

export function getVersion(planId: Id, versionId: Id) {
  return request<PlanVersionDetail>(`/api/v1/plans/${planId}/versions/${versionId}`)
}

export function createVersion(
  planId: Id,
  input: {
    expectedPlanRowVersion: Id
    expectedActiveVersionId?: Id | null
    changeReason: string
    copyFromVersionId?: Id
    tasks?: TaskSnapshot[]
  },
) {
  return request<MutationResult>(`/api/v1/plans/${planId}/versions`, {
    method: 'POST',
    data: input,
    mutation: true,
    epoch: true,
  })
}

export function replaceVersion(
  planId: Id,
  versionId: Id,
  input: {
    expectedRowVersion: Id
    expectedActiveVersionId?: Id | null
    changeReason: string
    tasks: TaskSnapshot[]
  },
  idempotencyKey?: string,
) {
  return request<MutationResult>(`/api/v1/plans/${planId}/versions/${versionId}`, {
    method: 'PUT',
    data: input,
    mutation: true,
    epoch: true,
    idempotencyKey,
  })
}

export function previewVersion(planId: Id, versionId: Id, scope: PlanScope) {
  return request<PlanPreview>(`/api/v1/plans/${planId}/versions/${versionId}/preview`, {
    method: 'POST',
    data: scope,
  })
}

export function activateVersion(planId: Id, versionId: Id, scope: PlanScope, expectedDataSeq: Id) {
  return request<MutationResult>(`/api/v1/plans/${planId}/versions/${versionId}/activate`, {
    method: 'POST',
    data: { ...scope, expectedDataSeq, userConfirmed: true },
    mutation: true,
    epoch: true,
  })
}

export function discardVersion(planId: Id, versionId: Id, expectedRowVersion: Id) {
  return request<MutationResult>(`/api/v1/plans/${planId}/versions/${versionId}/discard`, {
    method: 'POST',
    data: { expectedRowVersion },
    mutation: true,
    epoch: true,
  })
}

export function archivePlan(plan: PlanView, tasks: TaskView[], reason: string) {
  return request<MutationResult>(`/api/v1/plans/${plan.id}/archive`, {
    method: 'POST',
    mutation: true,
    epoch: true,
    data: {
      expectedRowVersion: plan.rowVersion,
      expectedActiveVersionId: plan.activeVersionId ?? null,
      effectiveFrom: new Date().toISOString(),
      affectedTasks: tasks.map((task) => ({
        taskId: task.id,
        expectedRowVersion: task.rowVersion,
      })),
      userConfirmed: true,
      allowFixedTaskChanges: false,
      reason,
    },
  })
}

export function listTasks(
  from: string,
  to: string,
  options: { planId?: Id; cursor?: string; limit?: number; timeZone?: string } = {},
) {
  return request<PageResult<TaskView>>('/api/v1/tasks', {
    query: { from, to, limit: 100, ...options },
  })
}

export function getTask(id: Id) {
  return request<TaskDetail>(`/api/v1/tasks/${id}`)
}

export function taskAction(task: TaskView, action: 'complete' | 'skip' | 'reopen', reason = '') {
  const data =
    action === 'reopen'
      ? { expectedRowVersion: task.rowVersion, reason: reason || '重新安排这项任务' }
      : {
          expectedRowVersion: task.rowVersion,
          performedAt: new Date().toISOString(),
          reasonCode: action === 'complete' ? 'DONE' : 'OTHER',
          reasonText: reason || undefined,
        }
  return request<MutationResult>(`/api/v1/tasks/${task.id}/${action}`, {
    method: 'POST',
    data,
    mutation: true,
    epoch: true,
  })
}

export function linkTaskRecords(id: Id, expectedRowVersion: Id, recordIds: Id[]) {
  return request<MutationResult>(`/api/v1/tasks/${id}/record-links`, {
    method: 'PUT',
    data: { expectedRowVersion, recordIds },
    mutation: true,
    epoch: true,
  })
}

export function listTaskEvents(id: Id) {
  return request<PageResult<TaskEvent>>(`/api/v1/tasks/${id}/events`, { query: { limit: 50 } })
}
