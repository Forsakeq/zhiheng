import { request } from '@/api/client'
import type { ClientConfiguration, MeView } from '@/api/types'
import { clientPlatform, session, setMe } from './session'
import { ui } from './ui'

export async function refreshConfiguration(): Promise<ClientConfiguration> {
  const configuration = await request<ClientConfiguration>('/api/v1/configuration', {
    auth: false,
    query: { platform: clientPlatform(), appVersion: '0.1.0' },
  })
  session.configuration = configuration
  return configuration
}

export async function refreshSession(): Promise<void> {
  if (!session.tokens) return
  const [meResult, configResult] = await Promise.allSettled([
    request<MeView>('/api/v1/me'),
    refreshConfiguration(),
  ])
  if (meResult.status === 'fulfilled') {
    setMe(meResult.value)
    ui.theme = meResult.value.preferences.theme
    ui.reduceMotion = meResult.value.preferences.reduceMotion
  } else throw meResult.reason
  if (configResult.status === 'rejected') session.configuration = null
}

export function featureEnabled(code: string): boolean {
  return (
    session.configuration?.features.some((feature) => feature.code === code && feature.enabled) ||
    false
  )
}
