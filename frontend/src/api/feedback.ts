import { request } from './client'
import type { PageResult, MutationResult } from './types'
export interface Feedback {
  id: string
  rowVersion: string
  category: string
  title: string
  description: string
  status: string
  reply: string | null
  createdAt: string
  fileIds: string[]
}
export const listFeedback = () =>
  request<PageResult<Feedback>>('/api/v1/feedback', { query: { limit: 50 } })
export const createFeedback = (data: {
  category: string
  title: string
  description: string
  contact?: string
}) =>
  request<MutationResult>('/api/v1/feedback', {
    method: 'POST',
    mutation: true,
    epoch: true,
    data: { ...data, fileIds: [], includeDiagnostics: false },
  })
