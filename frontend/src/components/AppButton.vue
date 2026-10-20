<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
    size?: 'normal' | 'small'
    loading?: boolean
    disabled?: boolean
    icon?: string
    block?: boolean
  }>(),
  { variant: 'primary', size: 'normal', loading: false, disabled: false, icon: '', block: false },
)

defineEmits<{ (event: 'click', nativeEvent: Event): void }>()
</script>

<template>
  <button
    class="app-button"
    :class="[variant, size, { block, 'is-loading': loading }]"
    :disabled="disabled || loading"
    hover-class="button-pressed"
    @click="$emit('click', $event)"
  >
    <view v-if="loading" class="button-spinner" />
    <AppIcon
      v-else-if="icon"
      :name="icon"
      :size="17"
      :color="variant === 'primary' || variant === 'danger' ? '#FFFFFF' : '#246653'"
    />
    <text><slot /></text>
  </button>
</template>

<style scoped lang="scss">
.app-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 48px;
  padding: 0 19px;
  border: 0;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.35;
  margin: 0;
  transition:
    transform 180ms,
    background 180ms;
  &::after {
    border: 0;
  }
}
.primary {
  background: var(--primary);
  color: #fff;
}
.secondary {
  background: var(--mint);
  color: var(--primary);
}
.ghost {
  background: transparent;
  color: var(--primary);
}
.danger {
  background: #b8524e;
  color: #fff;
}
.small {
  min-height: 37px;
  padding: 0 13px;
  font-size: 12px;
  border-radius: 10px;
}
.block {
  width: 100%;
}
.app-button[disabled] {
  opacity: 0.47;
  color: inherit;
}
.primary[disabled],
.danger[disabled] {
  color: #fff;
}
.button-pressed {
  transform: scale(0.975);
  opacity: 0.85;
}
.button-spinner {
  width: 15px;
  height: 15px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: button-spin 0.8s linear infinite;
}
@keyframes button-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
