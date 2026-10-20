import { request } from './client'
import type { Id, MutationResult, PageResult } from './types'

export type AiKind = 'CHAT' | 'RECORD_EXTRACT' | 'PLAN_GENERATE' | 'PLAN_ADJUST' | 'WEEKLY_REVIEW'
export interface ConversationView {
  id: Id
  rowVersion: string
  title: string
  scene: 'GENERAL' | 'PLAN' | 'RECORD' | 'REVIEW'
  status: 'ACTIVE' | 'ARCHIVED'
  lastMessageAt: string | null
  createdAt: string
}
export interface CitationView {
  id: Id
  label: string
  articleId: Id
  articleVersionId: Id
  sourceId: Id
  sourceTitle: string
  sourceUrl: string
  locator?: string
  unavailable: boolean
}
export interface MessageView {
  id: Id
  conversationId: Id
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  contentType: 'TEXT' | 'QUESTION' | 'DRAFT_CARD' | 'SOURCE_CARD' | 'ERROR'
  runId: Id | null
  draftId: Id | null
  citations: CitationView[]
  createdAt: string
  final: boolean
}
export interface RunView {
  id: Id
  rowVersion: string
  kind: AiKind
  status: 'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  conversationId: Id | null
  draftId: Id | null
  resultMessageId: Id | null
  weeklyReviewId: Id | null
  progressStage: string
  createdAt: string
  expiresAt: string
  cancelRequested: boolean
  error: { code: string; message: string; missingFields: string[] } | null
  retryable: boolean
}
export interface AcceptedRun {
  mutation: MutationResult
  runId: Id
  messageId?: Id
  conversationId: Id | null
  status: string
  pollAfterSeconds: number
}
export interface ValidationReport {
  draftId?: Id
  revisionNo?: string
  valid?: boolean
  issues?: { code?: string; field?: string; message?: string }[]
  checkedDataSeq?: string
  expiresAt?: string
}
export interface DraftView {
  id: Id
  rowVersion: string
  runId: Id
  kind: 'PLAN_CREATE' | 'PLAN_ADJUST' | 'RECORD_EXTRACT'
  status: string
  revisionNo: string
  payloadSchemaVersion: '1'
  payload: Record<string, unknown>
  missingFields: string[]
  questions: string[]
  base: Record<string, unknown>
  validation: ValidationReport
  appliedResourceId?: Id
  appliedResourceType?: string
  expiresAt: string
}
export interface PlanGenerateInput {
  goalIds: Id[]
  from: string
  to: string
  timeZone: string
  dailyTaskLimit: number
  requestText: string
  expectedDataSeq: string
  planId?: Id
  expectedActiveVersionId?: Id
}
export interface PlanAdjustInput {
  planId: Id
  expectedActiveVersionId: Id
  expectedDataSeq: string
  effectiveFrom: string
  affectedTasks: { taskId: Id; expectedRowVersion: string }[]
  requestText: string
  allowFixedTaskChanges?: boolean
  fixedChangeReason?: string
}
export interface RunCreate {
  kind: AiKind
  conversationId?: Id
  input: Record<string, unknown> | PlanGenerateInput | PlanAdjustInput
  clientRequestId: string
}
export interface DraftApply {
  expectedRowVersion: string
  expectedDraftRevisionNo: string
  expectedDataSeq: string
  userConfirmed: true
  expectedActiveVersionId?: Id
  effectiveFrom?: string
  affectedTasks?: { taskId: Id; expectedRowVersion: string }[]
  allowFixedTaskChanges?: boolean
  fixedChangeReason?: string
  clientRecordId?: string
}
const base = '/api/v1/ai'
const mutation = { method: 'POST', mutation: true, epoch: true } as const

export const getConversations = (cursor?: string) =>
  request<PageResult<ConversationView>>(`${base}/conversations`, { query: { cursor, limit: 20 } })
export const createConversation = (title: string, scene: ConversationView['scene'] = 'GENERAL') =>
  request<MutationResult>(`${base}/conversations`, { ...mutation, data: { title, scene } })
export const getConversation = (id: Id) => request<ConversationView>(`${base}/conversations/${id}`)
export const archiveConversation = (id: Id, expectedRowVersion: string) =>
  request<MutationResult>(`${base}/conversations/${id}/archive`, {
    ...mutation,
    data: { expectedRowVersion },
  })
export const getMessages = (id: Id, cursor?: string) =>
  request<PageResult<MessageView>>(`${base}/conversations/${id}/messages`, {
    query: { cursor, limit: 50 },
  })
export const sendMessage = (id: Id, text: string, clientMessageId: string) =>
  request<AcceptedRun>(`${base}/conversations/${id}/messages`, {
    ...mutation,
    data: {
      text,
      clientMessageId,
      contextTaskIds: [],
      contextRecordIds: [],
      allowKnowledgeSearch: true,
    },
  })
export const createRun = (data: RunCreate) =>
  request<AcceptedRun>(`${base}/runs`, { ...mutation, data })
export const getRun = (id: Id) => request<RunView>(`${base}/runs/${id}`)
export const cancelRun = (id: Id, expectedRowVersion: string) =>
  request<MutationResult>(`${base}/runs/${id}/cancel`, {
    ...mutation,
    data: { expectedRowVersion, reason: '用户在 App 中取消' },
  })
export const retryRun = (id: Id, expectedRowVersion: string) =>
  request<AcceptedRun>(`${base}/runs/${id}/retry`, {
    ...mutation,
    data: { expectedRowVersion, reuseInput: true },
  })
export const getDraft = (id: Id) => request<DraftView>(`${base}/drafts/${id}`)
export const reviseDraft = (draft: DraftView, payload: Record<string, unknown>) =>
  request<MutationResult>(`${base}/drafts/${draft.id}/revisions`, {
    method: 'PUT',
    mutation: true,
    epoch: true,
    data: {
      expectedRowVersion: draft.rowVersion,
      expectedDraftRevisionNo: draft.revisionNo,
      payloadSchemaVersion: '1',
      payload,
      editReason: '用户核对并修订草稿',
    },
  })
export const validateDraft = (draft: DraftView, expectedDataSeq: string) =>
  request<ValidationReport>(`${base}/drafts/${draft.id}/validate`, {
    ...mutation,
    data: {
      expectedRowVersion: draft.rowVersion,
      expectedDraftRevisionNo: draft.revisionNo,
      expectedDataSeq,
    },
  })
export const applyDraft = (id: Id, data: DraftApply) =>
  request<MutationResult>(`${base}/drafts/${id}/apply`, { ...mutation, data })
export const cancelDraft = (draft: DraftView) =>
  request<MutationResult>(`${base}/drafts/${draft.id}/cancel`, {
    ...mutation,
    data: {
      expectedRowVersion: draft.rowVersion,
      expectedDraftRevisionNo: draft.revisionNo,
      reason: '用户取消此草稿',
    },
  })
