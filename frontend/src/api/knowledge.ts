import { request } from './client'
import type { Id, PageResult } from './types'

export type KnowledgeCategory = 'ROUTINE' | 'ACTIVITY' | 'MEAL_HABIT' | 'RECORDING' | 'APP_HELP'
export interface KnowledgeSource {
  id: Id
  title: string
  organization: string
  url: string
  sourceKind: string
  publishedOn: string | null
  accessedOn: string | null
  verificationStatus: string
  verificationNote: string | null
  usageBasis: string
  rightsNote: string
}
export interface KnowledgeArticle {
  id: Id
  activeVersionId: Id
  title: string
  summary: string
  category: KnowledgeCategory
  tags: string[]
  publishedAt: string
}
export interface KnowledgeDetail extends KnowledgeArticle {
  versionId: Id
  versionNo: string
  bodyMarkdown: string
  applicablePopulation: string
  limitations: string
  contentDate: string
  rightsNote: string
  retired: boolean
  retiredReason: string | null
  sources: KnowledgeSource[]
}
export const getArticles = (
  options: { q?: string; category?: KnowledgeCategory; cursor?: string } = {},
) =>
  request<PageResult<KnowledgeArticle>>('/api/v1/knowledge/articles', {
    query: { ...options, limit: 20 },
  })
export const getArticle = (id: Id, versionId?: Id) =>
  request<KnowledgeDetail>(`/api/v1/knowledge/articles/${id}`, { query: { versionId } })
export const getSource = (id: Id) => request<KnowledgeSource>(`/api/v1/knowledge/sources/${id}`)
