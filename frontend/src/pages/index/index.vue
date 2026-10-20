<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { watch } from 'vue'
import HomeView from '@/components/views/HomeView.vue'
import PlansView from '@/components/views/PlansView.vue'
import AssistantView from '@/components/views/AssistantView.vue'
import TrendsView from '@/components/views/TrendsView.vue'
import ProfileView from '@/components/views/ProfileView.vue'
import { session } from '@/stores/session'
import { ui } from '@/stores/ui'
import type { TabKey } from '@/stores/ui'

const tabs: { key: TabKey; label: string; icon: string }[] = [
  { key: 'today', label: '今日', icon: 'home' },
  { key: 'plans', label: '计划', icon: 'calendar' },
  { key: 'assistant', label: '助手', icon: 'sparkles' },
  { key: 'trends', label: '趋势', icon: 'chart' },
  { key: 'profile', label: '我的', icon: 'user' },
]

onLoad((query) => {
  const tab = tabs.find((item) => item.key === query?.tab)
  if (tab) ui.activeTab = tab.key
})
onShow(() => {
  ui.refreshTick += 1
})
watch(
  () => session.tokens?.sessionFamilyId || '',
  () => {
    ui.refreshTick += 1
  },
)

function select(tab: TabKey) {
  ui.activeTab = tab
  uni.pageScrollTo({ scrollTop: 0, duration: 0 })
}
</script>

<template>
  <view class="app-root" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <view class="app-status-space" />
    <view v-if="!ui.online" class="offline-banner"
      ><AppIcon name="cloud" :size="14" /><text>当前离线，已加载内容仅保留在本次会话</text></view
    >
    <view class="tab-content" :key="`${ui.activeTab}-${ui.refreshTick}`">
      <HomeView v-if="ui.activeTab === 'today'" />
      <PlansView v-else-if="ui.activeTab === 'plans'" />
      <AssistantView v-else-if="ui.activeTab === 'assistant'" />
      <TrendsView v-else-if="ui.activeTab === 'trends'" />
      <ProfileView v-else />
    </view>
    <view class="nav-position"
      ><view class="tab-bar" role="navigation" aria-label="主导航"
        ><button
          v-for="tab in tabs"
          :key="tab.key"
          class="tab-item"
          :class="{ active: ui.activeTab === tab.key, assistant: tab.key === 'assistant' }"
          :aria-label="tab.label"
          @click="select(tab.key)"
        >
          <view class="tab-symbol"
            ><AppIcon
              :name="tab.icon"
              :size="tab.key === 'assistant' ? 23 : 21"
              :color="ui.activeTab === tab.key ? '#28684F' : '#94A294'" /></view
          ><text class="tab-label">{{ tab.label }}</text
          ><view class="active-point" /></button></view
    ></view>
  </view>
</template>

<style scoped lang="scss">
.app-status-space {
  height: env(safe-area-inset-top);
  min-height: 11px;
}
.offline-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 9px 16px;
  gap: 8px;
  font-size: 11px;
  color: var(--muted);
}
.tab-content {
  padding-top: 8px;
  animation: tab-enter 220ms ease-out;
}
.nav-position {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 100;
  padding: 0 12px calc(12px + env(safe-area-inset-bottom));
  pointer-events: none;
  display: flex;
  justify-content: center;
}
.tab-bar {
  pointer-events: auto;
  width: 100%;
  max-width: 456px;
  display: flex;
  align-items: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 21px;
  box-shadow: 0 5px 30px rgba(33, 59, 40, 0.09);
  padding: 9px 8px 7px;
}
.tab-item {
  flex: 1;
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 0;
  margin: 0;
  line-height: 1.4;
  color: #94a294;
  position: relative;
}
.tab-symbol {
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tab-label {
  display: block;
  margin-top: 4px;
  font-size: 9px;
  letter-spacing: 0.3px;
}
.tab-item.active {
  color: #28684f;
  font-weight: 600;
}
.active-point {
  width: 3px;
  height: 3px;
  margin-top: 4px;
  background: transparent;
  border-radius: 50%;
}
.active .active-point {
  background: #699477;
}
.assistant .tab-symbol {
  background: #edf3e8;
  width: 38px;
  height: 32px;
  border-radius: 11px;
  margin-top: -6px;
}
.assistant .tab-label {
  margin-top: 4px;
}
@keyframes tab-enter {
  from {
    opacity: 0.6;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
