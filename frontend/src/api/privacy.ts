import { request } from './client'
import type { PageResult } from './types'
export type PrivacyType = 'EXPORT' | 'DELETE_RECORDS' | 'ERASE_HEALTH_DATA' | 'DELETE_ACCOUNT'
export interface PrivacyRequest {
  id: string
  requestId: string
  rowVersion: string
  type: PrivacyType
  status: string
  scopeSummary: { moduleCounts: Record<string, number>; preservesPlanExecutionHistory?: boolean }
  previewDataSeq: string
  datasetEpoch: string
  previewExpiresAt: string
  progress: Record<string, unknown>
  errorCode?: string
  completedAt?: string
  manifestDigest?: string
  receiptToken?: string
  tokenUnavailable?: boolean
}
export interface Reauth {
  method: 'PASSWORD' | 'VERIFICATION'
  password?: string
  verificationId?: string
  verificationCode?: string
}
export interface PrivateDownload {
  url: string
  expiresAt: string
  fileName: string
  mediaType: string
  sizeBytes: number
}
export const previewPrivacy = (
  type: PrivacyType,
  scope: Record<string, unknown>,
  format = 'JSON',
) =>
  request<PrivacyRequest>('/api/v1/privacy/requests/preview', {
    method: 'POST',
    data: { type, scope, format },
    mutation: true,
    epoch: true,
  })
export const confirmPrivacy = (preview: PrivacyRequest, reauth: Reauth) =>
  request<PrivacyRequest>(`/api/v1/privacy/requests/${preview.id}/confirm`, {
    method: 'POST',
    mutation: true,
    epoch: true,
    data: {
      expectedRowVersion: preview.rowVersion,
      manifestDigest: preview.manifestDigest,
      expectedDataSeq: preview.previewDataSeq,
      userConfirmed: true,
      reauth,
    },
  })
export const getPrivacyRequest = (id: string) =>
  request<PrivacyRequest>(`/api/v1/privacy/requests/${id}`)
export const listPrivacyRequests = () =>
  request<PageResult<PrivacyRequest>>('/api/v1/privacy/requests', { query: { limit: 50 } })
export const cancelPrivacy = (item: PrivacyRequest) =>
  request(`/api/v1/privacy/requests/${item.id}/cancel`, {
    method: 'POST',
    data: { expectedRowVersion: item.rowVersion },
    mutation: true,
    epoch: true,
  })
export const privacyReceipt = (receiptToken: string) =>
  request<{
    requestId: string
    type: PrivacyType
    status: string
    errorCode?: string
    progress: Record<string, unknown>
  }>('/api/v1/privacy/receipts/status', { method: 'POST', auth: false, data: { receiptToken } })
export const getExportDownload = (id: string, reauth: Reauth) =>
  request<PrivateDownload>(`/api/v1/privacy/requests/${id}/download`, {
    method: 'POST',
    data: { reauth, disposition: 'ATTACHMENT' },
  })
