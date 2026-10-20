import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthTokens } from './types'

interface PendingRequest {
  url: string
  method: string
  data: unknown
  header: Record<string, string>
  success: (response: { statusCode: number; data: unknown }) => void
  fail: () => void
}

const tokens = (overrides: Partial<AuthTokens> = {}): AuthTokens => ({
  accessToken: 'access-before-refresh',
  accessExpiresAt: '2026-10-19T12:30:00Z',
  refreshToken: 'refresh-before-rotation',
  refreshExpiresAt: '2026-11-19T12:00:00Z',
  sessionId: '101',
  sessionFamilyId: 'family-one',
  generation: '1',
  audience: 'MOBILE',
  installationId: '201',
  userId: '301',
  datasetEpoch: 'epoch-one',
  securityVersion: '1',
  tokenType: 'Bearer',
  ...overrides,
})

let pending: PendingRequest[]
let storage: Map<string, string>
let persist: ReturnType<typeof vi.fn>
let client: typeof import('./client')
let account: typeof import('@/stores/session')

function succeed(call: PendingRequest, data: unknown) {
  call.success({ statusCode: 200, data: { code: 'OK', data } })
}

function rejectRequest(call: PendingRequest, statusCode: number, code: string) {
  call.success({ statusCode, data: { code, message: '服务端返回的错误说明' } })
}

beforeEach(async () => {
  vi.resetModules()
  pending = []
  storage = new Map()
  persist = vi.fn((key: string, value: string) => storage.set(key, value))
  vi.stubGlobal('uni', {
    getStorageSync: vi.fn((key: string) => storage.get(key)),
    setStorageSync: persist,
    request: vi.fn((options: PendingRequest) => {
      // Snapshot request headers as a transport would. Retrying may update the
      // caller's shared header object after the first request has already left.
      pending.push({ ...options, header: { ...options.header } })
    }),
  })
  account = await import('@/stores/session')
  client = await import('./client')
  account.applyTokens(tokens())
})

describe('mobile API transport', () => {
  it('sends a PATCH mutation using the allowed POST override without losing version guards', async () => {
    const body = { nickname: '小知', expectedRowVersion: '7' }
    const result = client.request('/api/v1/me/profile', {
      method: 'PATCH',
      mutation: true,
      idempotencyKey: 'profile-save-one',
      epoch: true,
      data: body,
    })

    expect(pending).toHaveLength(1)
    expect(pending[0]).toMatchObject({
      url: '/api/v1/me/profile',
      method: 'POST',
      data: body,
      header: {
        Authorization: 'Bearer access-before-refresh',
        'X-HTTP-Method-Override': 'PATCH',
        'Idempotency-Key': 'profile-save-one',
        'X-Data-Epoch': 'epoch-one',
      },
    })
    succeed(pending[0], { rowVersion: '8' })
    await expect(result).resolves.toEqual({ rowVersion: '8' })
  })

  it('rotates an expired refresh token once for concurrent writes and retains each original idempotency key', async () => {
    const first = client.request('/api/v1/records', {
      method: 'POST',
      mutation: true,
      epoch: true,
      data: { clientRecordId: 'device-record-one' },
    })
    const second = client.request('/api/v1/me/profile', {
      method: 'PATCH',
      mutation: true,
      epoch: true,
      data: { expectedRowVersion: '2' },
    })
    const originalKeys = pending.map((call) => call.header['Idempotency-Key'])
    expect(new Set(originalKeys).size).toBe(2)
    expect(originalKeys.every((key) => typeof key === 'string' && key.length > 0)).toBe(true)

    rejectRequest(pending[0], 401, 'TOKEN_EXPIRED')
    rejectRequest(pending[1], 401, 'TOKEN_EXPIRED')
    await vi.waitFor(() => expect(pending).toHaveLength(3))
    const refresh = pending[2]
    expect(refresh.url).toBe('/api/v1/auth/refresh')
    expect(refresh.header.Authorization).toBeUndefined()
    expect(refresh.data).toMatchObject({
      refreshToken: 'refresh-before-rotation',
      clientAudience: 'MOBILE',
      installationKey: expect.any(String),
    })

    succeed(
      refresh,
      tokens({
        accessToken: 'access-after-refresh',
        refreshToken: 'refresh-after-rotation',
        sessionId: '102',
        generation: '2',
      }),
    )
    await vi.waitFor(() => expect(pending).toHaveLength(5))
    expect(pending.filter((call) => call.url.endsWith('/auth/refresh'))).toHaveLength(1)

    const retriedRecord = pending.slice(3).find((call) => call.url === '/api/v1/records')!
    const retriedProfile = pending.slice(3).find((call) => call.url === '/api/v1/me/profile')!
    expect(retriedRecord.header['Idempotency-Key']).toBe(originalKeys[0])
    expect(retriedProfile.header['Idempotency-Key']).toBe(originalKeys[1])
    expect(retriedRecord.header.Authorization).toBe('Bearer access-after-refresh')
    expect(retriedProfile.header).toMatchObject({
      Authorization: 'Bearer access-after-refresh',
      'X-HTTP-Method-Override': 'PATCH',
      'X-Data-Epoch': 'epoch-one',
    })
    succeed(retriedRecord, { id: 'record-one' })
    succeed(retriedProfile, { rowVersion: '3' })
    await expect(Promise.all([first, second])).resolves.toEqual([
      { id: 'record-one' },
      { rowVersion: '3' },
    ])
  })

  it('rejects a late result after switching accounts rather than exposing the previous account data', async () => {
    const result = client.request('/api/v1/home')
    account.applyTokens(tokens({ userId: 'another-user', sessionId: 'another-session' }))
    succeed(pending[0], { privateNote: 'previous account data' })

    await expect(result).rejects.toMatchObject({ status: 409, code: 'SESSION_CHANGED' })
    expect(account.session.tokens?.userId).toBe('another-user')
  })

  it('rejects an old response after signing into the same account and installation with a new session family', async () => {
    const result = client.request('/api/v1/home')
    account.clearSession()
    account.applyTokens(tokens({ sessionId: 'new-session', sessionFamilyId: 'new-login-family' }))
    succeed(pending[0], { privateNote: 'outdated signed-in session' })

    await expect(result).rejects.toMatchObject({ status: 409, code: 'SESSION_CHANGED' })
    expect(account.session.tokens?.sessionFamilyId).toBe('new-login-family')
  })

  it('does not overwrite a new login for the same account with the previous login refresh result', async () => {
    const result = client.request('/api/v1/home')
    const rejection = expect(result).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    rejectRequest(pending[0], 401, 'TOKEN_EXPIRED')
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    account.clearSession()
    account.applyTokens(
      tokens({
        sessionId: 'new-session',
        sessionFamilyId: 'new-login-family',
        accessToken: 'new-login-access',
      }),
    )
    succeed(
      pending[1],
      tokens({ sessionId: 'rotated-old-session', accessToken: 'old-login-access' }),
    )

    await rejection
    expect(account.session.tokens?.accessToken).toBe('new-login-access')
    expect(account.session.tokens?.sessionFamilyId).toBe('new-login-family')
    expect(pending).toHaveLength(2)
  })

  it('does not clear a new login for the same account when its previous refresh token is rejected', async () => {
    const result = client.request('/api/v1/home')
    const rejection = expect(result).rejects.toMatchObject({ status: 401, code: 'REFRESH_REVOKED' })
    rejectRequest(pending[0], 401, 'TOKEN_EXPIRED')
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    account.clearSession()
    account.applyTokens(
      tokens({
        sessionId: 'new-session',
        sessionFamilyId: 'new-login-family',
        accessToken: 'new-login-access',
      }),
    )
    rejectRequest(pending[1], 401, 'REFRESH_REVOKED')

    await rejection
    expect(account.session.tokens?.accessToken).toBe('new-login-access')
    expect(account.session.tokens?.sessionFamilyId).toBe('new-login-family')
    expect(pending).toHaveLength(2)
  })

  it('does not apply a late refresh response or clear the newly signed-in account', async () => {
    const result = client.request('/api/v1/home')
    const rejection = expect(result).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    rejectRequest(pending[0], 401, 'TOKEN_EXPIRED')
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    account.applyTokens(
      tokens({
        userId: 'another-user',
        sessionId: 'another-session',
        accessToken: 'new-user-token',
      }),
    )
    succeed(pending[1], tokens({ accessToken: 'old-user-refreshed-token' }))

    await rejection
    expect(pending).toHaveLength(2)
    expect(account.session.tokens?.accessToken).toBe('new-user-token')
    expect(account.session.tokens?.userId).toBe('another-user')
  })

  it('clears an invalid current refresh session without retrying the original write', async () => {
    const result = client.request('/api/v1/records', { method: 'POST', mutation: true })
    const rejection = expect(result).rejects.toMatchObject({ status: 401, code: 'REFRESH_REVOKED' })
    rejectRequest(pending[0], 401, 'TOKEN_EXPIRED')
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    rejectRequest(pending[1], 401, 'REFRESH_REVOKED')

    await rejection
    expect(account.session.tokens).toBeNull()
    expect(account.session.me).toBeNull()
    expect(pending).toHaveLength(2)
  })

  it('returns a network failure without inventing success or retrying a write', async () => {
    const result = client.request('/api/v1/records', { method: 'POST', mutation: true })
    pending[0].fail()

    await expect(result).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
    expect(pending).toHaveLength(1)
    expect(account.session.tokens?.userId).toBe('301')
    expect(account.session.tokens?.accessToken).toBe('access-before-refresh')
  })

  it('retains server validation fields and request ID so forms can report the actual failed input', async () => {
    const result = client.request('/api/v1/records', { method: 'POST', mutation: true })
    pending[0].success({
      statusCode: 422,
      data: {
        code: 'VALIDATION_FAILED',
        message: '请检查记录时间',
        requestId: 'request-from-server',
        error: { fieldErrors: [{ field: 'endedAt', message: '结束时间不能早于开始时间' }] },
      },
    })

    await expect(result).rejects.toMatchObject({
      status: 422,
      code: 'VALIDATION_FAILED',
      message: '请检查记录时间',
      requestId: 'request-from-server',
      fieldErrors: [{ field: 'endedAt', message: '结束时间不能早于开始时间' }],
    })
    expect(pending).toHaveLength(1)
  })

  it('blocks protected network operations before login', async () => {
    account.clearSession()
    await expect(client.request('/api/v1/records')).rejects.toMatchObject({ code: 'AUTH_REQUIRED' })
    expect(pending).toHaveLength(0)
  })

  it('allows public configuration before login and safely encodes repeated query values', async () => {
    account.clearSession()
    const result = client.request('/api/v1/configuration', {
      auth: false,
      query: { platform: 'ANDROID', category: ['作息 计划', 'A&B'], omitted: null, count: 0 },
    })
    expect(pending[0].url).toBe(
      '/api/v1/configuration?platform=ANDROID&category=%E4%BD%9C%E6%81%AF%20%E8%AE%A1%E5%88%92&category=A%26B&count=0',
    )
    expect(pending[0].header.Authorization).toBeUndefined()
    succeed(pending[0], { features: [] })
    await expect(result).resolves.toEqual({ features: [] })
  })
})

describe('session and service selection', () => {
  it('keeps credentials in memory while persisting only nonsecret server and installation settings', () => {
    expect(persist).not.toHaveBeenCalled()
    account.setApiBaseUrl(' https://demo.example.test/api/ ')
    expect(account.session.tokens).toBeNull()
    expect(storage.get('zhiheng.apiBaseUrl')).toBe('https://demo.example.test/api')

    account.applyTokens(tokens({ accessToken: 'secret-access', refreshToken: 'secret-refresh' }))
    const firstInstallation = account.installationKey()
    expect(account.installationKey()).toBe(firstInstallation)
    account.clearSession()
    expect(account.session.tokens).toBeNull()
    expect(account.installationKey()).toBe(firstInstallation)
    expect(JSON.stringify([...storage])).not.toContain('secret-access')
    expect(JSON.stringify([...storage])).not.toContain('secret-refresh')

    account.setApiBaseUrl('https://other.example.test')
    expect(account.installationKey()).not.toBe(firstInstallation)
  })

  it.each([
    'javascript:alert(1)',
    'https://user:password@example.test',
    'https://example.test?token=secret',
    'https://example.test#secret',
  ])('rejects an unsafe service URL without clearing the existing session: %s', (url) => {
    expect(() => account.setApiBaseUrl(url)).toThrow()
    expect(account.session.tokens?.accessToken).toBe('access-before-refresh')
    expect(persist).not.toHaveBeenCalled()
  })

  it('rejects an in-flight public response after changing its destination service', async () => {
    const result = client.request('/api/v1/configuration', { auth: false })
    account.setApiBaseUrl('https://other.example.test')
    succeed(pending[0], { oldServicePolicy: 'do not show this on the new server' })

    await expect(result).rejects.toMatchObject({ status: 409, code: 'SESSION_CHANGED' })
  })
})
