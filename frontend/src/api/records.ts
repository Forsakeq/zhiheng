import { request } from './client'
import type { Id, MutationResult, PageResult, RecordView } from './types'

export type RecordType = 'SLEEP' | 'ACTIVITY' | 'MEAL' | 'ENERGY'

export interface RecordDetail {
  detailType: RecordType
  restedLevel?: number
  sleepKind?: 'MAIN' | 'NAP'
  interruptionNote?: string
  intervalMinutes?: number
  overlapWarning?: boolean
  activityType?: 'WALK' | 'RUN' | 'CYCLING' | 'STRETCH' | 'SPORT' | 'OTHER'
  actualDurationMinutes?: number
  intensity?: 'EASY' | 'MODERATE' | 'HARD'
  activityName?: string
  distanceMeters?: number
  locationLabel?: string
  mealType?: 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'OTHER'
  description?: string
  vegetablePresent?: boolean
  fruitPresent?: boolean
  proteinFoodPresent?: boolean
  wholeGrainPresent?: boolean
  satietyLevel?: number
  mealContext?: 'CANTEEN' | 'HOME' | 'TAKEAWAY' | 'RESTAURANT' | 'OTHER'
  energyLevel?: number
  energyLabel?: string
  contextCode?: 'WAKE' | 'STUDY' | 'ACTIVITY' | 'EVENING' | 'OTHER'
}

export interface ExtendedRecordView extends RecordView {
  recordType: RecordType
  rowVersion: Id
  clientRecordId: string
  startedAt: string
  endedAt: string | null
  recordLocalDate: string
  recordTimeZone: string
  utcOffsetMinutes: number
  detail: RecordDetail
  notes: string | null
  tagIds: Id[]
  sourceType: string
  qualityStatus: string
  deletedAt: string | null
}

export interface RecordInput {
  clientRecordId?: string
  recordType?: RecordType
  expectedRowVersion?: Id
  startedAt: string
  endedAt?: string | null
  recordTimeZone: string
  recordLocalDate: string
  utcOffsetMinutes: number
  notes?: string
  tagIds: Id[]
  detail: RecordDetail
}

export interface RecordTag {
  id: Id
  rowVersion: Id
  name: string
  color: string
  sortOrder: number
}

export interface RecordQuery {
  from?: string
  to?: string
  recordType?: RecordType
  tagId?: Id
  includeDeleted?: boolean
  sort?: string
  cursor?: string
  limit?: number
}

export const recordTypeMeta: Record<
  RecordType,
  { label: string; icon: string; color: string; unit: string }
> = {
  SLEEP: { label: '睡眠', icon: 'moon', color: '#6975A3', unit: '小时' },
  ACTIVITY: { label: '活动', icon: 'activity', color: '#1B6554', unit: '分钟' },
  MEAL: { label: '饮食', icon: 'utensils', color: '#B48339', unit: '餐' },
  ENERGY: { label: '精力', icon: 'sun', color: '#B0755A', unit: '/ 5' },
}

export function listRecords(query: RecordQuery = {}) {
  return request<PageResult<ExtendedRecordView>>('/api/v1/records', {
    query: { ...query, limit: query.limit || 30 },
  })
}

export function getRecord(id: Id) {
  return request<ExtendedRecordView>(`/api/v1/records/${id}`)
}

export function createRecord(input: RecordInput, idempotencyKey?: string) {
  return request<MutationResult>('/api/v1/records', {
    method: 'POST',
    data: input,
    mutation: true,
    epoch: true,
    idempotencyKey,
  })
}

export function replaceRecord(id: Id, input: RecordInput, idempotencyKey?: string) {
  const { clientRecordId, recordType, ...data } = input
  return request<MutationResult>(`/api/v1/records/${id}`, {
    method: 'PUT',
    data,
    mutation: true,
    epoch: true,
    idempotencyKey,
  })
}

export function deleteRecord(id: Id, expectedRowVersion: Id) {
  return request<MutationResult>(`/api/v1/records/${id}`, {
    method: 'DELETE',
    data: { expectedRowVersion },
    mutation: true,
    epoch: true,
  })
}

export function restoreRecord(id: Id, expectedRowVersion: Id) {
  return request<MutationResult>(`/api/v1/records/${id}/restore`, {
    method: 'POST',
    data: { expectedRowVersion },
    mutation: true,
    epoch: true,
  })
}

export function listRecordTags() {
  return request<PageResult<RecordTag>>('/api/v1/record-tags', { query: { limit: 100 } })
}

export function createRecordTag(name: string, color = 'TEAL') {
  return request<MutationResult>('/api/v1/record-tags', {
    method: 'POST',
    data: { name, color, sortOrder: 0 },
    mutation: true,
    epoch: true,
  })
}

export function getCoverage(from: string, to: string) {
  return request<{
    totalDays: number
    items: {
      recordType: RecordType
      validDays: number
      validRecords: number
      coverageRatio: number
      missingDays: string[]
    }[]
  }>('/api/v1/records/coverage', { query: { from, to } })
}
