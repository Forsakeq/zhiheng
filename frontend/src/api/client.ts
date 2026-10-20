import type { AuthTokens } from './types'
import { applyTokens, clearSession, installationKey, session } from '@/stores/session'
import { uuid } from '@/utils/time'

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  data?: unknown
  query?: Record<string, unknown>
  auth?: boolean
  mutation?: boolean
  idempotencyKey?: string
  epoch?: boolean
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fieldErrors: { field: string; message: string }[] = [],
    public requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function apiBaseUrl(): string {
  if (session.apiBaseUrl) return session.apiBaseUrl
  // #ifdef H5
  return ''
  // #endif
  // #ifndef H5
  return 'http://127.0.0.1:8080'
  // #endif
}

function identity(): string {
  return [
    session.tokens?.userId,
    session.tokens?.installationId,
    session.tokens?.sessionFamilyId,
    apiBaseUrl(),
  ].join('|')
}

function queryString(query?: Record<string, unknown>): string {
  const values: string[] = []
  Object.entries(query || {}).forEach(([name, value]) => {
    if (value === undefined || value === null || value === '') return
    const list = Array.isArray(value) ? value : [value]
    list.forEach((item) =>
      values.push(`${encodeURIComponent(name)}=${encodeURIComponent(String(item))}`),
    )
  })
  return values.length ? `?${values.join('&')}` : ''
}

function transport<T>(
  url: string,
  options: RequestOptions,
  headers: Record<string, string>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url,
      // Classic uni-app request does not expose PATCH on App/H5. The backend validates
      // this narrowly-scoped override before dispatching to its original PATCH handler.
      method: options.method === 'PATCH' ? 'POST' : options.method || 'GET',
      data: options.data as UniApp.RequestOptions['data'],
      header: headers,
      timeout: 25000,
      success(response) {
        const envelope = response.data as {
          code?: string
          message?: string
          data?: T
          requestId?: string
          error?: { fieldErrors?: { field: string; message: string }[] }
        }
        if (response.statusCode >= 200 && response.statusCode < 300 && envelope?.code === 'OK') {
          resolve(envelope.data as T)
        } else {
          reject(
            new ApiError(
              response.statusCode,
              envelope?.code || 'SERVICE_ERROR',
              envelope?.message || '服务暂时不可用，请稍后重试',
              envelope?.error?.fieldErrors || [],
              envelope?.requestId,
            ),
          )
        }
      },
      fail() {
        reject(new ApiError(0, 'NETWORK_ERROR', '暂时连接不上服务，请检查网络或服务地址'))
      },
    })
  })
}

let refreshFlight: { owner: string; promise: Promise<void> } | null = null

async function refreshTokens(owner: string): Promise<void> {
  if (refreshFlight?.owner === owner) return refreshFlight.promise
  const token = session.tokens?.refreshToken
  const baseUrl = apiBaseUrl()
  if (!token || identity() !== owner) throw new ApiError(401, 'AUTH_REQUIRED', '请重新登录')

  const promise = transport<AuthTokens>(
    `${baseUrl}/api/v1/auth/refresh`,
    {
      method: 'POST',
      data: { refreshToken: token, installationKey: installationKey(), clientAudience: 'MOBILE' },
    },
    { 'Content-Type': 'application/json' },
  )
    .then((tokens) => {
      if (identity() !== owner) throw new ApiError(401, 'SESSION_CHANGED', '当前账号已切换')
      applyTokens(tokens)
    })
    .catch((error: unknown) => {
      if (identity() === owner && error instanceof ApiError && error.status === 401) clearSession()
      throw error
    })
    .finally(() => {
      if (refreshFlight?.promise === promise) refreshFlight = null
    })
  refreshFlight = { owner, promise }
  return promise
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const authenticated = options.auth !== false
  if (authenticated && !session.tokens) {
    throw new ApiError(401, 'AUTH_REQUIRED', '登录后就能保存和同步你的生活记录')
  }
  const owner = identity()
  const baseUrl = apiBaseUrl()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.method === 'PATCH') headers['X-HTTP-Method-Override'] = 'PATCH'
  if (options.mutation) headers['Idempotency-Key'] = options.idempotencyKey || uuid()
  if (options.epoch) {
    const epoch = session.me?.datasetEpoch || session.tokens?.datasetEpoch
    if (!epoch) throw new ApiError(409, 'DATA_EPOCH_REQUIRED', '请先刷新个人信息后再操作')
    headers['X-Data-Epoch'] = epoch
  }
  const url = `${baseUrl}${path}${queryString(options.query)}`
  async function send(): Promise<T> {
    if (authenticated) headers.Authorization = `Bearer ${session.tokens?.accessToken || ''}`
    return transport<T>(url, options, headers)
  }
  let result: T
  try {
    result = await send()
  } catch (error) {
    if (
      authenticated &&
      error instanceof ApiError &&
      error.status === 401 &&
      identity() === owner
    ) {
      await refreshTokens(owner)
      result = await send()
    } else throw error
  }
  if ((authenticated && identity() !== owner) || apiBaseUrl() !== baseUrl) {
    throw new ApiError(409, 'SESSION_CHANGED', '账号或服务已切换，请重新打开此页面')
  }
  return result
}
