import { reactive } from 'vue'
import type { AuthTokens, ClientConfiguration, MeView } from '@/api/types'
import { uuid } from '@/utils/time'

function stored(key: string): string {
  try {
    return String(uni.getStorageSync(key) || '')
  } catch {
    return ''
  }
}

export const session = reactive({
  tokens: null as AuthTokens | null,
  me: null as MeView | null,
  configuration: null as ClientConfiguration | null,
  apiBaseUrl: stored('zhiheng.apiBaseUrl') || import.meta.env.VITE_API_BASE_URL || '',
  revision: 0,
})

export function applyTokens(tokens: AuthTokens) {
  session.tokens = tokens
  session.revision += 1
}

export function setMe(value: MeView) {
  session.me = value
  if (session.tokens) session.tokens.datasetEpoch = value.datasetEpoch
}

export function clearSession() {
  session.tokens = null
  session.me = null
  session.revision += 1
}

export function setApiBaseUrl(value: string) {
  const normalized = value.trim().replace(/\/+$/, '')
  if (
    normalized &&
    (!/^https?:\/\/[^\s/?#]+(?::\d+)?(?:\/[^\s?#]*)?$/.test(normalized) || normalized.includes('@'))
  ) {
    throw new Error('请输入完整的 http:// 或 https:// 服务地址')
  }
  if (normalized !== session.apiBaseUrl) {
    clearSession()
    session.configuration = null
    session.apiBaseUrl = normalized
    uni.setStorageSync('zhiheng.apiBaseUrl', normalized)
  }
}

export function installationKey(): string {
  const key = `zhiheng.installation.${clientPlatform()}.${session.apiBaseUrl || 'proxy'}`
  let value = stored(key)
  if (!value) {
    value = uuid()
    uni.setStorageSync(key, value)
  }
  return value
}

export function clientPlatform(): 'ANDROID' | 'HARMONYOS' | 'IOS' {
  // #ifdef H5
  return import.meta.env.VITE_PREVIEW_PLATFORM || 'ANDROID'
  // #endif
  // #ifndef H5
  const system = uni.getSystemInfoSync()
  const name = `${system.platform} ${system.system}`.toLowerCase()
  if (name.includes('harmony')) return 'HARMONYOS'
  return name.includes('ios') ? 'IOS' : 'ANDROID'
  // #endif
}
