<script setup lang="ts">
import { computed } from 'vue'
import type { TaskView } from '@/api/types'
import { formatTime } from '@/utils/time'

const props = defineProps<{ task: TaskView }>()
defineEmits<{
  (event: 'complete', task: TaskView): void
  (event: 'detail', task: TaskView): void
  (event: 'skip', task: TaskView): void
}>()
const finished = computed(() => props.task.status === 'COMPLETED')
const meta = computed(
  () =>
    ({
      ACTIVITY: ['activity', '轻活动'],
      MEAL_HABIT: ['utensils', '饮食习惯'],
      ROUTINE: ['moon', '生活节奏'],
      RECORDING: ['edit', '生活记录'],
    })[props.task.taskType] || ['leaf', '自定义'],
)
const status = computed(
  () =>
    ({
      COMPLETED: '已完成',
      SKIPPED: '已跳过',
      CANCELLED: '已取消',
      DRAFT: '草稿',
      PENDING: '待进行',
    })[props.task.status],
)
</script>

<template>
  <view class="task-card" :class="{ finished }" @click="$emit('detail', task)">
    <button
      class="task-toggle"
      :class="{ checked: finished }"
      :disabled="task.status !== 'PENDING'"
      :aria-label="`${finished ? '已完成' : '标记完成'}：${task.title}`"
      @click.stop="$emit('complete', task)"
    >
      <AppIcon v-if="finished" name="check" :size="16" color="#FFFFFF" />
    </button>
    <view class="task-main">
      <text class="task-name">{{ task.title }}</text>
      <view class="task-meta"
        ><text>{{ formatTime(task.scheduledStartAt) }}</text
        ><text class="meta-dot">·</text><text>{{ task.plannedDurationMinutes }} 分钟</text
        ><text v-if="task.isFixed" class="fixed-label">固定</text></view
      >
    </view>
    <view class="task-tail"
      ><text v-if="task.status !== 'PENDING'" class="task-status">{{ status }}</text
      ><view v-else class="task-kind"><AppIcon :name="meta[0]" :size="16" /></view
      ><AppIcon name="chevron-right" :size="15" color="#A1B2A9"
    /></view>
  </view>
</template>

<style scoped>
.task-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 17px 0;
  color: var(--ink);
}
.task-toggle {
  width: 23px;
  height: 23px;
  min-width: 23px;
  padding: 0;
  margin: 0;
  border-radius: 8px;
  border: 1.5px solid #c5d8cb;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
}
.task-toggle::after {
  border: 0;
}
.task-toggle.checked {
  background: #4b8c72;
  border-color: #4b8c72;
}
.task-main {
  min-width: 0;
  flex: 1;
}
.task-name {
  display: block;
  font-size: 14px;
  line-height: 1.6;
  font-weight: 550;
}
.task-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  color: var(--muted);
  margin-top: 5px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.meta-dot {
  color: #a9b6ae;
}
.fixed-label {
  color: var(--muted);
  padding: 1px 5px;
  border: 1px solid var(--border);
  border-radius: 4px;
  margin-left: 4px;
  font-size: 9px;
}
.task-tail {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}
.task-status {
  color: var(--muted);
  font-size: 10px;
}
.task-kind {
  background: var(--soft);
  width: 29px;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
}
.finished .task-name {
  color: var(--muted);
}
</style>
