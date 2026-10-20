<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="这一项小计划" subtitle="按照自己的节奏，一点点完成" back />
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后查看你的健康日常"
        description="登录账号后，即可保存和管理自己的计划与记录。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在打开任务" />
      <StateView
        v-else-if="error"
        kind="error"
        title="无法获取任务详情"
        :description="error"
        action-text="重新加载"
        @action="load"
      />
      <template v-else-if="task">
        <view class="task-hero"
          ><view class="task-symbol"
            ><AppIcon
              :name="task.status === 'COMPLETED' ? 'check-circle' : 'leaf'"
              :size="37"
              color="#39775D" /></view
          ><text class="task-status">{{ statusLabels[task.status] }}</text
          ><text class="task-title">{{ task.title }}</text
          ><text class="task-date"
            >{{ formatDay(task.taskLocalDate) }} · {{ formatTime(task.scheduledStartAt) }} —
            {{ formatTime(task.scheduledEndAt) }}</text
          ></view
        >
        <view class="task-metrics"
          ><view
            ><text class="metric-value">{{ task.plannedDurationMinutes }}<text> 分钟</text></text
            ><text class="metric-label">计划投入</text></view
          ><view class="metric-line" /><view
            ><text class="metric-value fixed-value">{{
              task.isFixed ? '固定安排' : '灵活安排'
            }}</text
            ><text class="metric-label">{{
              task.isFixed ? '修改需要再次确认' : '给生活留一点余地'
            }}</text></view
          ></view
        >
        <view v-if="task.instructions" class="card"
          ><text class="section-title">给自己的小提示</text
          ><text class="instructions">{{ task.instructions }}</text></view
        >
        <view v-if="task.status === 'PENDING'" class="action-section"
          ><AppButton :loading="working" icon="check" block @click="act('complete')"
            >我完成了</AppButton
          ><AppButton variant="secondary" block @click="reasonSheet = true"
            >今天先跳过</AppButton
          ></view
        >
        <AppButton
          v-if="['COMPLETED', 'SKIPPED'].includes(task.status)"
          variant="secondary"
          icon="refresh"
          :loading="working"
          block
          @click="act('reopen')"
          >重新打开这项任务</AppButton
        >
        <view class="card record-link-card"
          ><view class="row between"
            ><text class="section-title">实际健康记录</text
            ><button class="text-button" @click="openLinks">
              {{ task.linkedRecordIds.length ? '管理关联' : '关联记录' }}
            </button></view
          ><text class="link-explanation"
            >完成任务仅代表计划执行。关联实际记录后，才能查看真正发生的睡眠、活动或饮食。</text
          ><view v-if="linkedRecords.length" class="linked-list"
            ><view
              v-for="record in linkedRecords"
              :key="record.id"
              class="linked-record"
              @click="navigate(`/pages/records/detail?id=${record.id}`)"
              ><view class="linked-icon"
                ><AppIcon :name="recordTypeMeta[record.recordType].icon" :size="17" /></view
              ><text
                >{{ recordTypeMeta[record.recordType].label }} ·
                {{ formatTime(record.startedAt) }}</text
              ><AppIcon name="arrow-right" :size="14" color="#8D9E92" /></view></view
          ><AppButton
            variant="ghost"
            size="small"
            icon="plus"
            @click="navigate('/pages/records/edit')"
            >新建一条实际记录</AppButton
          ></view
        >
        <view class="card history-card"
          ><text class="section-title">执行轨迹</text
          ><view v-if="!events.length" class="history-empty">还没有执行记录，从第一步开始。</view
          ><view v-for="event in events" :key="event.id" class="history-event"
            ><view class="event-dot" /><view
              ><text class="event-title">{{ eventTitle(event) }}</text
              ><text v-if="event.reasonText" class="event-reason">{{ event.reasonText }}</text
              ><text class="event-time">{{ formatEventDate(event.receivedAt) }}</text></view
            ></view
          ><text v-if="eventsError" class="event-time"
            >执行轨迹暂时无法加载，可重新打开此页重试。</text
          ></view
        >
        <text class="timezone-note"
          >计划时区 {{ task.taskTimeZone }} · 任务版本 {{ task.rowVersion }}</text
        >
      </template>
    </view>

    <BottomSheet v-model="reasonSheet" title="今天先放一放" subtitle="你的感受，也值得被记录。"
      ><view class="sheet-body">
        <textarea
          v-model="skipReason"
          class="reason-input"
          placeholder="是什么让你想先跳过？（选填）"
          :maxlength="300"
        /><AppButton :loading="working" block @click="act('skip')">确认跳过</AppButton></view
      ></BottomSheet
    >
    <BottomSheet
      v-model="linksSheet"
      title="关联实际记录"
      subtitle="选择已经发生的健康记录，最多 20 条。"
      ><view class="sheet-body"
        ><StateView v-if="linksLoading" kind="loading" title="正在读取记录" /><template v-else
          ><view v-if="!candidateRecords.length" class="empty-candidates"
            >这一天还没有健康记录，先创建一条吧。</view
          ><view
            v-for="record in candidateRecords"
            :key="record.id"
            class="candidate-record"
            @click="toggleLink(record.id)"
            ><view class="candidate-check" :class="{ selected: selectedLinks.includes(record.id) }"
              ><AppIcon
                v-if="selectedLinks.includes(record.id)"
                name="check"
                :size="13"
                color="#FFF" /></view
            ><view
              ><text>{{ recordTypeMeta[record.recordType].label }}记录</text
              ><text class="candidate-time"
                >{{ record.recordLocalDate }} · {{ formatTime(record.startedAt) }}</text
              ></view
            ></view
          ><text v-if="unlistedLinks" class="unlisted-links"
            >另有 {{ unlistedLinks }} 条日期范围外的已有记录关联，保存时会保留。</text
          ><AppButton block :loading="working" @click="saveLinks"
            >保存关联（{{ selectedLinks.length }}）</AppButton
          ></template
        ></view
      ></BottomSheet
    >
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  getTask,
  linkTaskRecords,
  listTaskEvents,
  taskAction,
  type TaskDetail,
  type TaskEvent,
} from '@/api/plans'
import { getRecord, listRecords, recordTypeMeta } from '@/api/records'
import type { RecordView } from '@/api/types'
import { session } from '@/stores/session'
import { navigate, refreshApp } from '@/stores/ui'
import { dayOffset, formatDay, formatTime } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const id = ref('')
const task = ref<TaskDetail | null>(null)
const events = ref<TaskEvent[]>([])
const linkedRecords = ref<RecordView[]>([])
const loading = ref(false)
const error = ref('')
const eventsError = ref(false)
const working = ref(false)
const reasonSheet = ref(false)
const linksSheet = ref(false)
const linksLoading = ref(false)
const skipReason = ref('')
const selectedLinks = ref<string[]>([])
const candidateRecords = ref<RecordView[]>([])
const unlistedLinks = computed(
  () =>
    selectedLinks.value.filter(
      (link) => !candidateRecords.value.some((record) => record.id === link),
    ).length,
)
const statusLabels: Record<string, string> = {
  DRAFT: '待生效',
  PENDING: '等待你的行动',
  COMPLETED: '已经完成，真好',
  SKIPPED: '已跳过，给自己一点空间',
  CANCELLED: '已取消',
}

async function load() {
  if (!session.tokens || !id.value) {
    task.value = null
    linkedRecords.value = []
    events.value = []
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    task.value = await getTask(id.value)
    linkedRecords.value = []
    const linkResults = await Promise.allSettled(
      task.value.linkedRecordIds.map((link) => getRecord(link)),
    )
    linkedRecords.value = linkResults
      .filter(
        (result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof getRecord>>> =>
          result.status === 'fulfilled',
      )
      .map((result) => result.value)
    try {
      events.value = (await listTaskEvents(id.value)).items
      eventsError.value = false
    } catch {
      eventsError.value = true
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '任务加载失败'
  } finally {
    loading.value = false
  }
}

async function act(action: 'complete' | 'skip' | 'reopen') {
  if (!task.value || working.value) return
  working.value = true
  try {
    await taskAction(
      task.value,
      action,
      action === 'skip' ? skipReason.value : action === 'reopen' ? '想重新完成这项任务' : '',
    )
    reasonSheet.value = false
    refreshApp()
    toast(
      action === 'complete'
        ? '为自己完成的这一小步，开心一下'
        : action === 'skip'
          ? '已记录你的选择'
          : '任务已重新打开',
    )
    await load()
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

async function openLinks() {
  if (!task.value) return
  selectedLinks.value = [...task.value.linkedRecordIds]
  linksSheet.value = true
  linksLoading.value = true
  try {
    const date = task.value.taskLocalDate
    candidateRecords.value = (
      await listRecords({ from: dayOffset(-1, date), to: dayOffset(1, date), limit: 100 })
    ).items
  } catch (cause) {
    showError(cause)
    linksSheet.value = false
  } finally {
    linksLoading.value = false
  }
}

function toggleLink(link: string) {
  if (selectedLinks.value.includes(link))
    selectedLinks.value = selectedLinks.value.filter((item) => item !== link)
  else if (selectedLinks.value.length < 20) selectedLinks.value.push(link)
  else toast('最多关联 20 条记录')
}

async function saveLinks() {
  if (!task.value || working.value) return
  working.value = true
  try {
    await linkTaskRecords(id.value, task.value.rowVersion, selectedLinks.value)
    linksSheet.value = false
    refreshApp()
    toast('实际记录已关联')
    await load()
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

function eventTitle(event: TaskEvent) {
  return (
    (
      {
        COMPLETED: '完成了这项任务',
        SKIPPED: '今天先跳过',
        PENDING: '任务等待执行',
        CANCELLED: '任务已取消',
      } as Record<string, string>
    )[event.toStatus] || '任务状态已更新'
  )
}
function formatEventDate(iso: string) {
  return `${iso.slice(0, 10)} · ${formatTime(iso)}`
}
onLoad((options) => {
  id.value = options?.id || ''
})
onShow(() => {
  void load()
})
</script>

<style scoped>
.task-hero {
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 12px 8px 15px;
}
.task-symbol {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 83px;
  height: 83px;
  border-radius: 50%;
  background: #e4eee1;
  margin-bottom: 17px;
}
.task-status {
  color: #7e9b86;
  font-size: 11px;
}
.task-title {
  font-size: 26px;
  font-weight: 600;
  text-align: center;
  margin-top: 10px;
  line-height: 1.5;
}
.task-date {
  font-size: 12px;
  color: #8ea094;
  margin-top: 12px;
}
.task-metrics {
  display: grid;
  grid-template-columns: 1fr 1px 1fr;
  align-items: center;
  background: #fff;
  border: 1px solid #e9eee6;
  padding: 22px 0;
  border-radius: 14px;
  text-align: center;
}
.metric-value {
  display: block;
  color: #38614a;
  font-size: 25px;
  font-weight: 600;
}
.metric-value text {
  font-size: 11px;
  font-weight: 400;
}
.fixed-value {
  font-size: 17px;
  line-height: 34px;
}
.metric-label {
  display: block;
  margin-top: 6px;
  font-size: 10px;
  color: #98a79c;
}
.metric-line {
  height: 34px;
  background: #e8ede4;
}
.section-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
}
.instructions {
  display: block;
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.9;
  color: #7e9080;
}
.action-section {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.text-button {
  padding: 0;
  background: transparent;
  color: #527b61;
  font-size: 11px;
  margin: 0;
  line-height: 1.5;
}
.link-explanation {
  color: #95a396;
  font-size: 11px;
  line-height: 1.9;
  display: block;
  margin: 12px 0;
}
.linked-list {
  margin: 14px 0;
}
.linked-record {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 0;
  border-top: 1px solid #f0f3ec;
  color: #6c8772;
  font-size: 12px;
}
.linked-record text {
  flex: 1;
}
.linked-icon {
  background: #edf4e9;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.history-event {
  display: flex;
  gap: 12px;
  padding-top: 19px;
}
.event-dot {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: #8cb59a;
  flex-shrink: 0;
}
.event-title {
  display: block;
  font-size: 12px;
  color: #587660;
}
.event-time {
  display: block;
  font-size: 10px;
  color: #a0aea0;
  margin-top: 7px;
}
.event-reason {
  display: block;
  font-size: 11px;
  line-height: 1.7;
  color: #8a9c8b;
  margin-top: 5px;
}
.history-empty {
  font-size: 12px;
  color: #9aac9a;
  padding-top: 16px;
}
.timezone-note {
  text-align: center;
  font-size: 10px;
  color: #a0afa1;
}
.sheet-body {
  display: flex;
  flex-direction: column;
  gap: 15px;
  padding: 8px 0;
}
.reason-input {
  width: 100%;
  box-sizing: border-box;
  height: 100px;
  padding: 12px;
  background: #f1f5ee;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.8;
}
.candidate-record {
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #eef2ea;
  padding: 12px 0;
  font-size: 13px;
  color: #526e59;
}
.candidate-check {
  width: 20px;
  height: 20px;
  border: 1px solid #cad7c7;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.candidate-check.selected {
  background: #1b6554;
  border-color: #1b6554;
}
.candidate-time {
  display: block;
  font-size: 10px;
  color: #97a793;
  margin-top: 5px;
}
.empty-candidates,
.unlisted-links {
  color: #92a28e;
  font-size: 12px;
  line-height: 1.8;
}
</style>
