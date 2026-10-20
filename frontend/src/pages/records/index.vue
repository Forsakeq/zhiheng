<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="健康记录" subtitle="记录真实的日常，了解自己的节奏" back>
      <template #right
        ><button class="add-button" @click="navigate('/pages/records/edit')">
          <AppIcon name="plus" :size="21" color="#1B6554" /></button
      ></template>
    </AppHeader>
    <view class="page-stack">
      <view class="record-intro"
        ><view
          ><text class="eyebrow">YOUR EVERYDAY, REMEMBERED</text
          ><text class="intro-title">身体的每一个信号，<br />都值得被好好看见。</text></view
        ><view class="intro-art"><AppIcon name="leaf" :size="47" color="#5C9478" /></view
      ></view>
      <scroll-view scroll-x class="filter-scroll"
        ><view class="filter-line"
          ><button
            v-for="filter in filters"
            :key="filter.value"
            class="filter-chip"
            :class="{ active: recordType === filter.value }"
            @click="setType(filter.value)"
          >
            {{ filter.label }}
          </button></view
        ></scroll-view
      >
      <view class="range-row"
        ><picker mode="date" :value="from" @change="changeRange('from', $event.detail.value)"
          ><view class="date-box"
            ><AppIcon name="calendar" :size="14" /><text>{{ from }}</text></view
          ></picker
        ><text class="range-dash">至</text
        ><picker mode="date" :value="to" @change="changeRange('to', $event.detail.value)"
          ><view class="date-box"
            ><text>{{ to }}</text
            ><AppIcon name="chevron-down" :size="13" /></view></picker
      ></view>
      <view class="row between"
        ><text class="muted record-count"
          >{{ records.length }} 条记录{{ hasMore ? '，还有更多' : '' }}</text
        ><view class="deleted-toggle" @click="toggleDeleted"
          ><view class="mini-check" :class="{ checked: includeDeleted }"
            ><AppIcon v-if="includeDeleted" name="check" :size="10" color="#FFF" /></view
          ><text>包含最近删除</text></view
        ></view
      >
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后查看你的健康日常"
        description="登录账号后，即可保存和管理自己的计划与记录。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading && !records.length" kind="loading" title="正在读取健康记录" />
      <StateView
        v-else-if="error"
        kind="error"
        title="记录暂时没有加载出来"
        :description="error"
        action-text="再试一次"
        @action="load(false)"
      />
      <StateView
        v-else-if="!records.length"
        kind="empty"
        title="从今天的一次记录开始"
        description="睡眠、活动、饮食与精力，慢慢拼出你的健康日常。"
        action-text="添加记录"
        @action="navigate('/pages/records/edit')"
      />
      <view v-else class="record-groups"
        ><view v-for="group in groups" :key="group.date" class="record-group"
          ><view class="group-label"
            ><text>{{ formatDay(group.date) }}</text
            ><text>{{ group.records.length }} 条</text></view
          ><view
            v-for="record in group.records"
            :key="record.id"
            class="record-wrap"
            :class="{ deleted: record.deletedAt }"
            ><RecordCard :record="record" @click="openRecord(record)" /><text
              v-if="record.deletedAt"
              class="deleted-label"
              >已删除 · 可在详情中恢复</text
            ></view
          ></view
        ></view
      >
      <AppButton
        v-if="hasMore && !error"
        variant="ghost"
        :loading="loading"
        block
        @click="load(true)"
        >加载更多记录</AppButton
      >
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listRecords, type RecordType } from '@/api/records'
import type { RecordView } from '@/api/types'
import { session } from '@/stores/session'
import { navigate, ui } from '@/stores/ui'
import { dayOffset, formatDay, today } from '@/utils/time'
import { toast } from '@/utils/feedback'

const from = ref(dayOffset(-29))
const to = ref(today())
const recordType = ref<RecordType | ''>('')
const includeDeleted = ref(false)
const records = ref<RecordView[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const filters: { value: RecordType | ''; label: string }[] = [
  { value: '', label: '全部记录' },
  { value: 'SLEEP', label: '睡眠' },
  { value: 'ACTIVITY', label: '活动' },
  { value: 'MEAL', label: '饮食' },
  { value: 'ENERGY', label: '精力' },
]
const groups = computed(() => {
  const grouped = new Map<string, RecordView[]>()
  records.value.forEach((record) => {
    const date = record.recordLocalDate
    grouped.set(date, [...(grouped.get(date) || []), record])
  })
  return [...grouped.entries()]
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([date, items]) => ({ date, records: items }))
})

async function load(append = false) {
  if (!session.tokens) {
    records.value = []
    nextCursor.value = null
    hasMore.value = false
    loading.value = false
    error.value = ''
    return
  }
  if (loading.value) return
  if (from.value > to.value) {
    toast('开始日期不能晚于结束日期')
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await listRecords({
      from: from.value,
      to: to.value,
      recordType: recordType.value || undefined,
      includeDeleted: includeDeleted.value,
      cursor: append ? nextCursor.value || undefined : undefined,
    })
    records.value = append ? [...records.value, ...result.items] : result.items
    nextCursor.value = result.nextCursor || null
    hasMore.value = result.hasMore
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '请检查网络后重试'
  } finally {
    loading.value = false
  }
}

function setType(type: RecordType | '') {
  recordType.value = type
  void load()
}
function changeRange(key: 'from' | 'to', value: string) {
  ;(key === 'from' ? from : to).value = value
  void load()
}
function toggleDeleted() {
  includeDeleted.value = !includeDeleted.value
  void load()
}
function openRecord(record: RecordView) {
  navigate(
    `/pages/records/detail?id=${record.id}${record.deletedAt ? `&deleted=1&date=${record.recordLocalDate}` : ''}`,
  )
}
watch(
  () => ui.refreshTick,
  () => load(),
)
onShow(() => load())
</script>

<style scoped>
.add-button {
  background: #e8f0e8;
  width: 37px;
  height: 37px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  padding: 0;
}
.record-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 0 12px;
}
.eyebrow {
  font-size: 9px;
  letter-spacing: 1.5px;
  color: #92a299;
  display: block;
}
.intro-title {
  font-size: 24px;
  line-height: 1.55;
  font-weight: 600;
  display: block;
  margin-top: 10px;
  letter-spacing: -0.5px;
}
.intro-art {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  background: #e4ede2;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: rotate(-15deg);
}
.filter-scroll {
  width: 100%;
  white-space: nowrap;
}
.filter-line {
  display: flex;
  gap: 8px;
}
.filter-chip {
  font-size: 12px;
  padding: 9px 16px;
  border-radius: 40px;
  line-height: 1.4;
  margin: 0;
  background: #eaefeb;
  color: #76897e;
  flex-shrink: 0;
}
.filter-chip.active {
  background: #1b6554;
  color: #fff;
}
.range-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.date-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 12px;
  background: #fff;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid #e6ece7;
}
.range-dash {
  color: #9aaa9f;
  font-size: 11px;
}
.deleted-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #8a9a90;
}
.mini-check {
  width: 13px;
  height: 13px;
  border: 1px solid #b9c8bd;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mini-check.checked {
  background: #1b6554;
  border-color: #1b6554;
}
.record-count {
  font-size: 11px;
}
.record-groups {
  display: flex;
  flex-direction: column;
  gap: 23px;
}
.record-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.group-label {
  display: flex;
  justify-content: space-between;
  padding: 0 2px;
  color: #829289;
  font-size: 11px;
}
.deleted {
  opacity: 0.65;
}
.deleted-label {
  color: #9e7966;
  font-size: 10px;
  margin: 5px 12px 0;
  display: block;
}
</style>
