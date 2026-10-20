import { request } from './client'
import type { AuthTokens, ClientConfiguration, PageResult } from './types'
import { clientPlatform, installationKey } from '@/stores/session'
import { deviceTimeZone } from '@/utils/time'

export interface Policy {
  purpose: string
  version: string
  title: string
  documentUrl: string | null
}
export interface Verification {
  verificationId: string
  expiresAt: string
  resendAfterSeconds: number
  maskedDestination: string
}
export interface ServicePolicyDocument {
  purpose: 'SERVICE'
  version: string
  title: string
  sections: { heading: string; content: string }[]
}

export const LOCAL_SERVICE_POLICY_PATH = '/api/v1/development/policies/service'

export const getLocalServicePolicy = () =>
  request<ServicePolicyDocument>(LOCAL_SERVICE_POLICY_PATH, { auth: false })
export interface SessionView {
  id: string
  clientName: string
  current: boolean
  status: string
  lastSeenAt: string
  clientAudience: string
}

export const getConfiguration = () =>
  request<ClientConfiguration>('/api/v1/configuration', {
    auth: false,
    query: { platform: clientPlatform(), appVersion: '0.1.0' },
  })
export const sendVerification = (
  account: string,
  purpose: 'REGISTER' | 'RESET_PASSWORD' | 'REAUTH',
) =>
  request<Verification>('/api/v1/auth/verifications', {
    method: 'POST',
    auth: false,
    data: { destination: account.trim(), channel: 'EMAIL', purpose, clientAudience: 'MOBILE' },
  })
export const login = (account: string, password: string) =>
  request<AuthTokens>('/api/v1/auth/login', {
    method: 'POST',
    auth: false,
    data: {
      account: account.trim(),
      password,
      installationKey: installationKey(),
      clientName: '知衡手机 App',
      clientAudience: 'MOBILE',
      platform: clientPlatform(),
    },
  })
export const register = (input: {
  account: string
  password: string
  verificationId: string
  verificationCode: string
  policyVersion: string
  nickname?: string
}) =>
  request<AuthTokens>('/api/v1/auth/register', {
    method: 'POST',
    auth: false,
    data: {
      ...input,
      account: input.account.trim(),
      adultConfirmed: true,
      termsAccepted: true,
      installationKey: installationKey(),
      timeZone: deviceTimeZone(),
      clientAudience: 'MOBILE',
      platform: clientPlatform(),
    },
  })
export const resetPassword = (
  account: string,
  newPassword: string,
  verificationId: string,
  verificationCode: string,
) =>
  request<{ passwordChanged: boolean; allSessionsRevoked: boolean; loginRequired: boolean }>(
    '/api/v1/auth/password/reset',
    {
      method: 'POST',
      auth: false,
      data: {
        account: account.trim(),
        newPassword,
        verificationId,
        verificationCode,
        clientAudience: 'MOBILE',
      },
    },
  )
export const logout = (scope: 'CURRENT' | 'ALL' = 'CURRENT') =>
  request<void>('/api/v1/auth/logout', { method: 'POST', data: { scope } })
export const getSessions = () =>
  request<PageResult<SessionView>>('/api/v1/auth/sessions', { query: { limit: 100 } })
export const revokeSession = (id: string) =>
  request<void>(`/api/v1/auth/sessions/${id}`, { method: 'DELETE' })
