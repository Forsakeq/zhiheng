<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="记录详情" subtitle="每一条记录，都是了解自己的线索" back />
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后查看你的健康日常"
        description="登录账号后，即可保存和管理自己的计划与记录。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在打开记录" />
      <StateView
        v-else-if="error"
        kind="error"
        title="暂时无法打开记录"
        :description="error"
        action-text="重新加载"
        @action="load"
      />
      <template v-else-if="record">
        <view class="record-hero"
          ><view class="hero-icon" :style="{ background: `${meta.color}12` }"
            ><AppIcon :name="meta.icon" :size="35" :color="meta.color" /></view
          ><text class="eyebrow">A MOMENT OF YOUR DAY</text
          ><text class="hero-title">{{ meta.label }}记录</text
          ><text class="hero-date"
            >{{ formatDay(record.recordLocalDate) }} · {{ formatTime(record.startedAt) }}</text
          ><text v-if="record.deletedAt" class="deleted-badge">这条记录已删除</text></view
        >
        <view class="card detail-card"
          ><view v-for="item in detailRows" :key="item.label" class="detail-row"
            ><text class="detail-label">{{ item.label }}</text
            ><text class="detail-value">{{ item.value }}</text></view
          ></view
        >
        <view v-if="record.detail.overlapWarning" class="warning-box"
          ><AppIcon name="info" :size="17" color="#A7894B" /><text
            >这段睡眠与其他记录重叠。统计会按覆盖区间去重，避免重复计算。</text
          ></view
        >
        <view v-if="record.notes" class="card note-card"
          ><text class="section-title">当时的随记</text
          ><text class="note-text">{{ record.notes }}</text></view
        >
        <view v-if="record.attachments?.length" class="card note-card"
          ><text class="section-title">附件</text
          ><text class="muted attachment-note"
            >{{ record.attachments.length }} 个附件已保留。当前移动版暂不提供附件预览与编辑。</text
          ></view
        >
        <view class="record-provenance"
          ><AppIcon name="shield" :size="15" color="#8BA190" /><view
            ><text>{{
              record.sourceType === 'MANUAL'
                ? '由你手动记录'
                : record.sourceType === 'AI_CONFIRMED'
                  ? 'AI 整理，经你确认后记录'
                  : '已标记来源的健康记录'
            }}</text
            ><text>记录时区 {{ record.recordTimeZone }} · 版本 {{ record.rowVersion }}</text></view
          ></view
        >
        <template v-if="!record.deletedAt"
          ><AppButton block icon="edit" @click="navigate(`/pages/records/edit?id=${record.id}`)"
            >编辑这条记录</AppButton
          ><AppButton variant="ghost" icon="trash" :loading="working" block @click="remove"
            >删除记录</AppButton
          ></template
        >
        <template v-else
          ><text class="recovery-hint"
            >误删了？在允许的恢复期内可以找回这条记录，恢复后会重新参与统计。</text
          ><AppButton block icon="refresh" :loading="working" @click="restore"
            >恢复这条记录</AppButton
          ></template
        >
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  deleteRecord,
  getRecord,
  listRecords,
  recordTypeMeta,
  restoreRecord,
  type ExtendedRecordView,
} from '@/api/records'
import { session } from '@/stores/session'
import { navigate, refreshApp } from '@/stores/ui'
import { formatDay, formatDuration, formatTime } from '@/utils/time'
import { confirm, showError, toast } from '@/utils/feedback'

const id = ref('')
const deletedDate = ref('')
const record = ref<ExtendedRecordView | null>(null)
const loading = ref(true)
const working = ref(false)
const error = ref('')
const meta = computed(() => recordTypeMeta[record.value?.recordType || 'ACTIVITY'])
const activityNames: Record<string, string> = {
  WALK: '散步',
  RUN: '跑步',
  CYCLING: '骑行',
  STRETCH: '拉伸',
  SPORT: '运动',
  OTHER: '其他',
}
const mealNames: Record<string, string> = {
  BREAKFAST: '早餐',
  LUNCH: '午餐',
  DINNER: '晚餐',
  SNACK: '加餐',
  OTHER: '其他',
}
const contextNames: Record<string, string> = {
  WAKE: '起床后',
  STUDY: '学习 / 工作',
  ACTIVITY: '活动时',
  EVENING: '晚间',
  OTHER: '其他',
  CANTEEN: '食堂',
  HOME: '家中',
  TAKEAWAY: '外卖',
  RESTAURANT: '餐厅',
}
const detailRows = computed(() => {
  if (!record.value) return []
  const row = record.value
  const detail = row.detail
  const result = [
    {
      label: '发生时间',
      value: `${formatTime(row.startedAt)}${row.endedAt ? ` — ${formatTime(row.endedAt)}` : ''}`,
    },
  ]
  if (row.recordType === 'SLEEP') {
    const minutes =
      detail.intervalMinutes ||
      Math.round((Date.parse(row.endedAt || row.startedAt) - Date.parse(row.startedAt)) / 60000)
    result.push(
      { label: '睡眠时长', value: formatDuration(minutes) },
      { label: '睡眠类型', value: detail.sleepKind === 'NAP' ? '小睡' : '主睡眠' },
    )
    if (detail.restedLevel) result.push({ label: '休息感受', value: `${detail.restedLevel} / 5` })
    if (detail.interruptionNote) result.push({ label: '睡眠感受', value: detail.interruptionNote })
  } else if (row.recordType === 'ACTIVITY') {
    result.push(
      { label: '活动类型', value: activityNames[detail.activityType || 'OTHER'] },
      { label: '实际活动时长', value: formatDuration(detail.actualDurationMinutes || 0) },
    )
    if (detail.activityName) result.push({ label: '活动名称', value: detail.activityName })
    if (detail.intensity)
      result.push({
        label: '活动强度',
        value: { EASY: '轻松', MODERATE: '适中', HARD: '较高' }[detail.intensity],
      })
    if (detail.distanceMeters != null)
      result.push({ label: '活动距离', value: `${detail.distanceMeters} 米` })
    if (detail.locationLabel) result.push({ label: '地点', value: detail.locationLabel })
  } else if (row.recordType === 'MEAL') {
    result.push(
      { label: '餐次', value: mealNames[detail.mealType || 'OTHER'] },
      { label: '吃了什么', value: detail.description || '未填写' },
    )
    if (detail.mealContext)
      result.push({ label: '用餐场景', value: contextNames[detail.mealContext] })
    if (detail.satietyLevel) result.push({ label: '饱腹感', value: `${detail.satietyLevel} / 5` })
    const foods = [
      detail.vegetablePresent ? '蔬菜' : '',
      detail.fruitPresent ? '水果' : '',
      detail.proteinFoodPresent ? '蛋白质食物' : '',
      detail.wholeGrainPresent ? '全谷物' : '',
    ].filter(Boolean)
    if (foods.length) result.push({ label: '食物搭配', value: foods.join(' · ') })
  } else {
    result.push({ label: '精力值', value: `${detail.energyLevel || '—'} / 5` })
    if (detail.contextCode)
      result.push({ label: '当时情境', value: contextNames[detail.contextCode] })
    if (detail.description) result.push({ label: '感受', value: detail.description })
  }
  return result
})

async function load() {
  if (!session.tokens || !id.value) {
    record.value = null
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    let result: ExtendedRecordView | undefined
    if (deletedDate.value) {
      let cursor: string | undefined
      do {
        const page = await listRecords({
          from: deletedDate.value,
          to: deletedDate.value,
          includeDeleted: true,
          cursor,
          limit: 100,
        })
        result = page.items.find((item) => item.id === id.value)
        cursor = !result && page.hasMore ? page.nextCursor || undefined : undefined
      } while (cursor)
    } else result = await getRecord(id.value)
    if (!result) throw new Error('记录不存在或已不可访问。')
    record.value = result
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '记录加载失败'
  } finally {
    loading.value = false
  }
}

async function remove() {
  if (
    !record.value ||
    working.value ||
    !(await confirm('删除这条记录？', '删除后不再参与健康趋势分析，在允许的恢复期内可以恢复。'))
  )
    return
  working.value = true
  try {
    const result = await deleteRecord(id.value, record.value.rowVersion)
    record.value.deletedAt = new Date().toISOString()
    record.value.rowVersion = result.rowVersion
    deletedDate.value = record.value.recordLocalDate
    refreshApp()
    toast('记录已删除')
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

async function restore() {
  if (!record.value || working.value) return
  working.value = true
  try {
    await restoreRecord(id.value, record.value.rowVersion)
    deletedDate.value = ''
    refreshApp()
    toast('这条记录已恢复')
    await load()
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

onLoad((options) => {
  id.value = options?.id || ''
  deletedDate.value = options?.deleted === '1' ? options?.date || '' : ''
})
onShow(() => {
  void load()
})
</script>

<style scoped>
.record-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 17px 0 12px;
}
.hero-icon {
  width: 78px;
  height: 78px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 26px;
  margin-bottom: 19px;
}
.eyebrow {
  font-size: 9px;
  letter-spacing: 1.8px;
  color: #96a89a;
}
.hero-title {
  font-size: 28px;
  margin-top: 9px;
  font-weight: 600;
}
.hero-date {
  font-size: 12px;
  margin-top: 10px;
  color: #8a9d90;
}
.detail-card {
  padding: 3px 18px;
}
.detail-row {
  padding: 17px 0;
  display: flex;
  align-items: flex-start;
  gap: 18px;
  border-bottom: 1px solid #eff2ed;
}
.detail-row:last-child {
  border: 0;
}
.detail-label {
  min-width: 83px;
  font-size: 12px;
  color: #8d9e92;
  line-height: 1.7;
}
.detail-value {
  flex: 1;
  font-size: 13px;
  color: #3c5747;
  text-align: right;
  line-height: 1.7;
  word-break: break-word;
}
.section-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}
.note-text {
  font-size: 13px;
  line-height: 1.9;
  color: #728477;
  white-space: pre-wrap;
}
.record-provenance {
  display: flex;
  gap: 8px;
  padding: 3px 4px;
}
.record-provenance text {
  display: block;
  color: #95a59a;
  font-size: 10px;
  line-height: 1.9;
}
.warning-box {
  background: #faf3e4;
  border-radius: 14px;
  padding: 14px;
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: #9d814a;
  line-height: 1.8;
}
.deleted-badge {
  background: #f1e5dc;
  color: #a0785f;
  font-size: 11px;
  border-radius: 20px;
  padding: 6px 12px;
  margin-top: 13px;
}
.recovery-hint {
  font-size: 12px;
  color: #8c9c90;
  line-height: 1.8;
}
.attachment-note {
  font-size: 12px;
  line-height: 1.8;
}
</style>
