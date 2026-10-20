<template>
  <view class="page-stack plans-view">
    <view class="row between plans-heading">
      <view>
        <text class="eyebrow">SMALL STEPS, BETTER DAYS</text>
        <text class="page-title">让健康，成为日常</text>
      </view>
      <button class="round-add" @click="navigate('/pages/plans/create')">
        <AppIcon name="plus" :size="24" color="#FFF" />
      </button>
    </view>

    <view class="week-card">
      <view class="row between">
        <text class="week-title">{{ formatDay(selectedDate) }}的安排</text>
        <picker mode="date" :value="selectedDate" @change="selectDate($event.detail.value)">
          <view class="calendar-link"
            ><AppIcon name="calendar" :size="15" /><text>选日期</text></view
          >
        </picker>
      </view>
      <view class="week-strip">
        <button
          v-for="day in days"
          :key="day.date"
          class="day-cell"
          :class="{ selected: selectedDate === day.date }"
          @click="selectDate(day.date)"
        >
          <text class="weekday">{{ day.label }}</text>
          <text class="day-number">{{ day.date.slice(-2) }}</text>
          <view class="day-dot" :class="{ filled: day.isToday }" />
        </button>
      </view>
    </view>

    <view class="progress-banner">
      <view class="progress-ring"
        ><text>{{ completeCount }}</text
        ><text class="ring-total">/ {{ tasks.length }}</text></view
      >
      <view class="progress-copy"
        ><text class="progress-title">{{
          completeCount ? '正在积累，属于你的节奏' : '每一个小行动，都算数'
        }}</text
        ><text class="progress-sub">完成计划后，可关联你实际记录的健康数据</text></view
      >
      <AppIcon name="leaf" :size="28" color="#86B8A5" />
    </view>

    <view class="row between section-heading">
      <text>今日任务</text>
      <text class="subtle-link" @click="navigate('/pages/records/index')"
        >记录档案 <text>↗</text></text
      >
    </view>
    <StateView
      v-if="!session.tokens"
      kind="auth"
      title="登录后查看你的健康日常"
      description="登录账号后，即可保存和管理自己的计划与记录。"
      action-text="登录 / 注册"
      @action="navigate('/pages/auth/index')"
    />
    <StateView v-else-if="loading" kind="loading" title="正在整理你的安排" />
    <StateView
      v-else-if="error"
      kind="error"
      title="暂时无法获取计划"
      :description="error"
      action-text="重新加载"
      @action="load"
    />
    <StateView
      v-else-if="!tasks.length"
      kind="empty"
      title="今天，留一点空间给自己"
      description="建立一个轻盈的小计划，从散步或规律作息开始。"
      action-text="创建计划"
      @action="navigate('/pages/plans/create')"
    />
    <view v-else class="task-list">
      <TaskCard
        v-for="task in tasks"
        :key="task.id"
        :task="task"
        @complete="complete(task)"
        @detail="openTask(task)"
        @skip="skip(task)"
      />
    </view>

    <view class="row between section-heading"
      ><text>我的计划</text><text class="muted">{{ plans.length }} 个</text></view
    >
    <view v-if="!plans.length && !loading" class="card gentle-empty"
      ><AppIcon name="calendar" :size="24" color="#8A9D95" /><text
        >你的第一个健康计划，从这里开始</text
      ></view
    >
    <view
      v-for="plan in plans"
      :key="plan.id"
      class="card plan-item"
      @click="navigate(`/pages/plans/detail?id=${plan.id}`)"
    >
      <view class="plan-symbol"><AppIcon name="calendar" :size="22" color="#1B6554" /></view>
      <view class="plan-copy"
        ><text class="plan-title">{{ plan.title }}</text
        ><text class="muted">{{ plan.startDate }} — {{ plan.endDate }}</text></view
      >
      <view class="plan-status"
        ><text class="pill" :class="{ draft: plan.status === 'DRAFT' }">{{
          statusLabels[plan.status]
        }}</text
        ><AppIcon name="arrow-right" :size="16" color="#8A9D95"
      /></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listPlans, listTasks, taskAction, type PlanView } from '@/api/plans'
import type { TaskView } from '@/api/types'
import { session } from '@/stores/session'
import { navigate, refreshApp, ui } from '@/stores/ui'
import { formatDay, today, weekDays } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const selectedDate = ref(today())
const tasks = ref<TaskView[]>([])
const plans = ref<PlanView[]>([])
const loading = ref(false)
const error = ref('')
const days = computed(() => weekDays(selectedDate.value))
const completeCount = computed(
  () => tasks.value.filter((task) => task.status === 'COMPLETED').length,
)
const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '进行中',
  PAUSED: '已暂停',
  ARCHIVED: '已归档',
}

async function load() {
  if (!session.tokens) {
    tasks.value = []
    plans.value = []
    loading.value = false
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [taskPage, planPage] = await Promise.all([
      listTasks(selectedDate.value, selectedDate.value),
      listPlans(),
    ])
    tasks.value = taskPage.items
    plans.value = planPage.items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '网络连接失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function selectDate(date: string) {
  selectedDate.value = date
  void load()
}

function openTask(task: TaskView) {
  navigate(`/pages/plans/task?id=${task.id}`)
}

async function complete(task: TaskView) {
  try {
    await taskAction(task, 'complete')
    toast('又完成了一件照顾自己的小事')
    refreshApp()
    await load()
  } catch (cause) {
    showError(cause)
  }
}

async function skip(task: TaskView) {
  try {
    await taskAction(task, 'skip', '今天暂时跳过')
    toast('已跳过，明天也可以重新开始')
    refreshApp()
    await load()
  } catch (cause) {
    showError(cause)
  }
}

watch(() => ui.refreshTick, load)
watch(() => session.tokens?.userId, load)
onMounted(load)
</script>

<style scoped>
.plans-heading {
  align-items: center;
  margin-top: 12px;
}
.eyebrow {
  display: block;
  font-size: 9px;
  letter-spacing: 1.7px;
  color: #8a9d95;
}
.page-title {
  display: block;
  font-size: 25px;
  margin-top: 7px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.round-add {
  width: 43px;
  height: 43px;
  min-width: 43px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #1b6554;
  border-radius: 50%;
}
.week-card {
  background: #fff;
  padding: 18px 14px 12px;
  border-radius: 14px;
  border: 1px solid #e9eeea;
}
.week-title {
  font-size: 14px;
  font-weight: 600;
  padding-left: 4px;
}
.calendar-link {
  display: flex;
  gap: 5px;
  align-items: center;
  font-size: 11px;
  color: #788c82;
}
.week-strip {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 5px;
  margin-top: 17px;
}
.day-cell {
  padding: 10px 0 5px;
  margin: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-radius: 14px;
  background: transparent;
  line-height: 1.5;
}
.weekday {
  font-size: 10px;
  color: #9aa89f;
}
.day-number {
  font-size: 17px;
  font-weight: 600;
  margin: 5px 0;
  color: #395044;
}
.day-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  margin-top: 2px;
}
.day-dot.filled {
  background: #6eae99;
}
.day-cell.selected {
  background: #1b6554;
  box-shadow: 0 5px 10px #1b65541a;
}
.day-cell.selected .weekday {
  color: #b9d6ca;
}
.day-cell.selected .day-number {
  color: #fff;
}
.day-cell.selected .day-dot.filled {
  background: #d4ebdf;
}
.progress-banner {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 18px 15px;
  background: #e7efe7;
  border-radius: 14px;
}
.progress-ring {
  min-width: 48px;
  height: 48px;
  border: 3px solid #b7d0be;
  border-top-color: #1b6554;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
}
.ring-total {
  font-size: 10px;
  margin-left: 2px;
  font-weight: 400;
  color: #7c9385;
}
.progress-copy {
  flex: 1;
}
.progress-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.progress-sub {
  display: block;
  margin-top: 5px;
  font-size: 10px;
  line-height: 1.6;
  color: #789082;
}
.task-list {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.plan-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}
.plan-symbol {
  width: 43px;
  height: 43px;
  background: #edf4ee;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}
.plan-copy {
  flex: 1;
  min-width: 0;
}
.plan-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 7px;
}
.plan-copy .muted {
  font-size: 10px;
}
.plan-status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.plan-status .pill {
  font-size: 10px;
  padding: 4px 7px;
}
.draft {
  background: #f6efe1;
  color: #9d7a36;
}
.gentle-empty {
  display: flex;
  gap: 12px;
  color: #819386;
  font-size: 12px;
  align-items: center;
}
</style>
