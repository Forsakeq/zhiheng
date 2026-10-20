<script setup lang="ts">
withDefaults(
  defineProps<{
    kind?: 'empty' | 'error' | 'loading' | 'auth'
    title?: string
    description?: string
    actionText?: string
  }>(),
  {
    kind: 'empty',
    title: '从一个小小的开始出发',
    description: '',
    actionText: '',
  },
)
defineEmits<{ (event: 'action'): void }>()
</script>

<template>
  <view class="state-view" :aria-busy="kind === 'loading'">
    <view class="state-symbol" :class="{ 'state-loading': kind === 'loading' }"
      ><AppIcon
        :name="
          kind === 'auth'
            ? 'lock'
            : kind === 'error'
              ? 'cloud'
              : kind === 'loading'
                ? 'refresh'
                : 'leaf'
        "
        :size="28"
        color="#50806A"
    /></view>
    <text class="state-title">{{ title }}</text>
    <text v-if="description" class="state-description">{{ description }}</text>
    <AppButton
      v-if="actionText && kind !== 'loading'"
      variant="secondary"
      size="small"
      @click="$emit('action')"
      >{{ actionText }}</AppButton
    >
  </view>
</template>

<style scoped>
.state-view {
  padding: 38px 23px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.state-symbol {
  width: 62px;
  height: 62px;
  background: var(--mint);
  border-radius: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 2px;
}
.state-title {
  color: var(--ink);
  font-weight: 600;
  font-size: 16px;
  line-height: 1.6;
}
.state-description {
  color: var(--muted);
  font-size: 13px;
  max-width: 290px;
  line-height: 1.8;
  margin-bottom: 5px;
}
.state-loading {
  animation: state-breathe 1.8s ease-in-out infinite;
}
@keyframes state-breathe {
  50% {
    opacity: 0.45;
    transform: scale(0.95);
  }
}
</style>
