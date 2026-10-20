<script setup lang="ts">
withDefaults(defineProps<{ modelValue: boolean; title: string; subtitle?: string }>(), {
  subtitle: '',
})
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>()
</script>

<template>
  <view v-if="modelValue" class="sheet-backdrop" @click.self="emit('update:modelValue', false)">
    <view class="sheet-panel" role="dialog" aria-modal="true" :aria-label="title">
      <view class="sheet-handle" />
      <view class="sheet-heading">
        <view
          ><text class="sheet-title">{{ title }}</text
          ><text v-if="subtitle" class="sheet-subtitle">{{ subtitle }}</text></view
        >
        <button class="icon-button" aria-label="关闭" @click="emit('update:modelValue', false)">
          <AppIcon name="close" />
        </button>
      </view>
      <scroll-view class="sheet-content" scroll-y><slot /></scroll-view>
      <view v-if="$slots.footer" class="sheet-footer"><slot name="footer" /></view>
    </view>
  </view>
</template>

<style scoped>
.sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(14, 39, 30, 0.38);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  backdrop-filter: blur(3px);
}
.sheet-panel {
  width: 100%;
  max-width: 480px;
  max-height: 89vh;
  padding: 10px 22px calc(25px + env(safe-area-inset-bottom));
  box-sizing: border-box;
  border-radius: 25px 25px 0 0;
  background: var(--surface);
  animation: sheet-enter 220ms ease-out;
}
.sheet-handle {
  margin: 0 auto 20px;
  width: 36px;
  height: 4px;
  border-radius: 4px;
  background: var(--border);
}
.sheet-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}
.sheet-title {
  display: block;
  font-size: 21px;
  color: var(--ink);
  font-weight: 650;
}
.sheet-subtitle {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-top: 7px;
  line-height: 1.7;
}
.sheet-content {
  max-height: 61vh;
}
.sheet-footer {
  padding-top: 18px;
}
@keyframes sheet-enter {
  from {
    transform: translateY(20px);
    opacity: 0.5;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}
</style>
