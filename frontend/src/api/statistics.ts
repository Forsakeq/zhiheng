import { request } from './client'
import type { Id, MutationResult, PageResult } from './types'

export type MetricCode =
  | 'SLEEP_INTERVAL_MINUTES'
  | 'ACTIVITY_MINUTES'
  | 'ENERGY_LEVEL'
  | 'TASK_COMPLETION'
export interface MetricPoint {
  date: string
  value: number | null
  sampleCount: number
  valid: boolean
  coverageState: 'VALID' | 'MISSING' | 'CONFLICT'
  numerator?: number
  denominator?: number
  sourceRecordIds: Id[]
  sourceTaskIds: Id[]
}
export interface MetricSeries {
  definition: {
    code: MetricCode
    version: string
    label: string
    unit: 'MINUTES' | 'LEVEL' | 'RATIO'
    aggregation: string
    missingPolicy: string
  }
  points: MetricPoint[]
  validDays: number
  totalDays: number
  average: number | null
}
export interface TrendsView {
  from: string
  to: string
  displayTimeZone: string
  businessDatePolicy: string
  series: MetricSeries[]
  dataAsOfSeq: string
  computedAt: string
  stale: boolean
}
export interface WeeklyReview {
  id: Id
  rowVersion: string
  weekStart: string
  weekEnd: string
  status: string
  summary: string | null
  highlights: unknown[]
  adjustmentSuggestions: unknown[]
  coverage: Record<string, unknown>
  sourceDataSeq: string
  metricDefinitionVersion: string
  stale: boolean
  generatedAt: string | null
  aiRunId: Id | null
}
export interface ReviewEvidence {
  id: Id
  claimKey: string
  claimText: string
  evidenceType: string
  evidenceId: Id
  sourceRowVersion: string
  sourceDate: string
  value: number | null
  unit: string
}
export const getTrends = (from: string, to: string, metrics: MetricCode[], timeZone: string) =>
  request<TrendsView>('/api/v1/statistics/trends', {
    query: { from, to, metrics: metrics.join(','), timeZone },
  })
export const getDayStatistics = (date: string) =>
  request<Record<string, unknown>>(`/api/v1/statistics/days/${date}`)
export const rebuildStatistics = (from: string, to: string) =>
  request<MutationResult>('/api/v1/statistics/rebuild', {
    method: 'POST',
    mutation: true,
    epoch: true,
    data: { from, to, reason: '用户在 App 中重新计算' },
  })
export const getWeeklyReviews = (cursor?: string) =>
  request<PageResult<WeeklyReview>>('/api/v1/weekly-reviews', { query: { cursor, limit: 20 } })
export const createWeeklyReview = (weekStart: string, expectedDataSeq: string) =>
  request<{
    jobId: Id
    jobType: string
    status: string
    pollPath: string
    pollAfterSeconds: number
    mutation: MutationResult
  }>('/api/v1/weekly-reviews', {
    method: 'POST',
    mutation: true,
    epoch: true,
    data: { weekStart, expectedDataSeq, focus: ['作息规律', '执行节奏'], forceNewRevision: false },
  })
export const getWeeklyReview = (id: Id) => request<WeeklyReview>(`/api/v1/weekly-reviews/${id}`)
export const getReviewEvidence = (id: Id, cursor?: string) =>
  request<PageResult<ReviewEvidence>>(`/api/v1/weekly-reviews/${id}/evidence`, {
    query: { cursor, limit: 50 },
  })
