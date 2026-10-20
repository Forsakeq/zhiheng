<script setup lang="ts">
import { back as goBack } from '@/stores/ui'
withDefaults(defineProps<{ title: string; subtitle?: string; back?: boolean }>(), {
  subtitle: '',
  back: false,
})
</script>

<template>
  <view class="app-header">
    <view class="header-left">
      <button v-if="back" class="icon-button back-button" aria-label="返回上一页" @click="goBack">
        <AppIcon name="arrow-left" :size="21" />
      </button>
      <view>
        <text class="header-title">{{ title }}</text>
        <text v-if="subtitle" class="header-subtitle">{{ subtitle }}</text>
      </view>
    </view>
    <view class="header-right"><slot name="right" /></view>
  </view>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 22px 20px 18px;
  padding-top: calc(22px + env(safe-area-inset-top));
}
.header-left {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.header-title {
  display: block;
  color: var(--ink);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.8px;
  line-height: 1.35;
}
.header-subtitle {
  display: block;
  color: var(--muted);
  font-size: 12px;
  margin-top: 5px;
  line-height: 1.5;
}
.header-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 8px;
}
.back-button {
  background: var(--surface);
  width: 42px;
  height: 42px;
}
</style>
