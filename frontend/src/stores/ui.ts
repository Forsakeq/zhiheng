import { reactive } from 'vue'

export type TabKey = 'today' | 'plans' | 'assistant' | 'trends' | 'profile'

export const ui = reactive({
  activeTab: 'today' as TabKey,
  refreshTick: 0,
  online: true,
  theme: 'SYSTEM' as 'LIGHT' | 'DARK' | 'SYSTEM',
  reduceMotion: false,
})

export function navigate(url: string) {
  uni.navigateTo({ url })
}

export function goTab(tab: TabKey) {
  ui.activeTab = tab
  const pages = getCurrentPages()
  if (pages[pages.length - 1]?.route !== 'pages/index/index') {
    uni.reLaunch({ url: `/pages/index/index?tab=${tab}` })
  }
}

export function back() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else goTab('today')
}

export function refreshApp() {
  ui.refreshTick += 1
}
