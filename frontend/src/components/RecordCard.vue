<script setup lang="ts">
import { computed } from 'vue'
import type { RecordView } from '@/api/types'
import { formatDay, formatDuration, formatTime } from '@/utils/time'

const props = defineProps<{ record: RecordView }>()
defineEmits<{ (event: 'click', record: RecordView): void }>()
const kind = computed(
  () =>
    ({
      SLEEP: ['moon', '睡眠', 'lavender'],
      ACTIVITY: ['activity', '活动', 'sage'],
      MEAL: ['utensils', '饮食', 'peach'],
      ENERGY: ['sun', '精力', 'sand'],
    })[props.record.recordType],
)
const summary = computed(() => {
  const detail = props.record.detail as Record<string, unknown>
  if (props.record.recordType === 'SLEEP') {
    const minutes = Number(
      detail.intervalMinutes ||
        (props.record.endedAt
          ? (Date.parse(props.record.endedAt) - Date.parse(props.record.startedAt)) / 60000
          : 0),
    )
    return minutes > 0 ? formatDuration(minutes) : '待补充时间'
  }
  if (props.record.recordType === 'ACTIVITY')
    return `${detail.activityName || ({ WALK: '散步', RUN: '跑步', CYCLING: '骑行', STRETCH: '拉伸' } as Record<string, string>)[String(detail.activityType)] || '日常活动'} · ${detail.actualDurationMinutes || '—'} 分钟`
  if (props.record.recordType === 'MEAL') return String(detail.description || '记录一餐的日常')
  return `${detail.energyLabel || '主观精力'} · ${detail.energyLevel || '—'} / 5`
})
</script>

<template>
  <view class="record-card" @click="$emit('click', record)">
    <view class="record-symbol" :class="kind[2]"><AppIcon :name="kind[0]" :size="20" /></view>
    <view class="record-copy"
      ><view class="record-top"
        ><text class="record-kind">{{ kind[1] }}</text
        ><text v-if="record.sourceType === 'AI_CONFIRMED'" class="origin-tag">AI 核对后记录</text
        ><text v-if="record.deletedAt" class="origin-tag">已删除</text></view
      ><text class="record-summary">{{ summary }}</text></view
    >
    <view class="record-time"
      ><text>{{ formatDay(record.recordLocalDate) }}</text
      ><text>{{ formatTime(record.startedAt) }}</text></view
    >
  </view>
</template>

<style scoped>
.record-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
}
.record-symbol {
  width: 41px;
  height: 43px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.lavender {
  background: #eeeaf6;
}
.sage {
  background: #e6f0e8;
}
.peach {
  background: #f8eee3;
}
.sand {
  background: #f6f1d8;
}
.record-copy {
  flex: 1;
  min-width: 0;
}
.record-top {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.record-kind {
  font-size: 12px;
  color: var(--muted);
}
.record-summary {
  display: block;
  margin-top: 6px;
  font-size: 14px;
  font-weight: 550;
  color: var(--ink);
  line-height: 1.6;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.record-time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
  font-size: 10px;
  color: var(--muted);
  flex-shrink: 0;
}
.origin-tag {
  font-size: 9px;
  color: var(--primary);
  background: var(--mint);
  border-radius: 4px;
  padding: 2px 4px;
}
</style>
