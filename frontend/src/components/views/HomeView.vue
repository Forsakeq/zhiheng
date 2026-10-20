<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request } from '@/api/client'
import type { HomeView, RecordType, RecordView, TaskView } from '@/api/types'
import { listTasks, taskAction } from '@/api/plans'
import { refreshSession } from '@/stores/app'
import { session } from '@/stores/session'
import { goTab, navigate } from '@/stores/ui'
import { formatDay, formatTime, today } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const serverHome = ref<HomeView | null>(null)
const serverTasks = ref<TaskView[]>([])
const loading = ref(false)
const error = ref('')
const completing = ref<string | null>(null)
const showQuickRecord = ref(false)
const nickname = computed(() => session.me?.profile.nickname || '朋友')
const tasks = computed(() => serverTasks.value)
const completed = computed(() => tasks.value.filter((task) => task.status === 'COMPLETED').length)
const denominator = computed(
  () =>
    tasks.value.filter((task) => ['PENDING', 'COMPLETED', 'SKIPPED'].includes(task.status)).length,
)
const progress = computed(() => (denominator.value ? completed.value / denominator.value : 0))
const nextTask = computed(() => tasks.value.find((task) => task.status === 'PENDING'))
const recentRecords = computed(() => serverHome.value?.recentRecords.slice(0, 3) || [])
const dayLabel = computed(() => {
  const date = new Date()
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 · 星期${['日', '一', '二', '三', '四', '五', '六'][date.getDay()]}`
})
const recordTypes: { type: RecordType; title: string; note: string; icon: string; tone: string }[] =
  [
    { type: 'SLEEP', title: '记睡眠', note: '留住昨夜的休息', icon: 'moon', tone: 'lavender' },
    { type: 'ACTIVITY', title: '记活动', note: '每一步，都算数', icon: 'activity', tone: 'sage' },
    { type: 'MEAL', title: '记饮食', note: '好好吃一顿饭', icon: 'utensils', tone: 'peach' },
    { type: 'ENERGY', title: '记精力', note: '听听自己的感受', icon: 'sun', tone: 'sand' },
  ]

async function load() {
  if (!session.tokens) return
  loading.value = true
  error.value = ''
  try {
    await refreshSession()
    const [home, taskPage] = await Promise.all([
      request<HomeView>('/api/v1/home', { query: { date: today() } }),
      listTasks(today(), today()),
    ])
    serverHome.value = home
    serverTasks.value = taskPage.items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '今天的内容还没有加载完成'
  } finally {
    loading.value = false
  }
}

function openRecord(type: RecordType) {
  showQuickRecord.value = false
  if (!session.tokens) return navigate('/pages/auth/index')
  navigate(`/pages/records/edit?type=${type}`)
}

function recordDetail(record: RecordView) {
  navigate(`/pages/records/detail?id=${record.id}&date=${record.recordLocalDate}`)
}

async function complete(task: TaskView) {
  if (completing.value || task.status !== 'PENDING') return
  completing.value = task.id
  try {
    await taskAction(task, 'complete')
    await load()
    toast('完成一件小事，也是在照顾自己')
  } catch (cause) {
    showError(cause)
  } finally {
    completing.value = null
  }
}

onMounted(load)
</script>

<template>
  <view class="home-view page-stack">
    <view class="home-top">
      <view
        ><text class="eyebrow brand-word">ZHIHENG / 知衡</text
        ><text class="greeting"
          >{{ nickname }}，今天也慢慢来 <text class="greeting-sun">✺</text></text
        ><text class="home-date">{{ dayLabel }}</text></view
      >
      <button class="home-avatar" aria-label="打开个人主页" @click="goTab('profile')">
        <text>{{ nickname.slice(0, 1) }}</text
        ><view class="avatar-dot" />
      </button>
    </view>

    <view class="companion-card">
      <view class="hero-grid" /><view class="hero-orbit orbit-outer" /><view
        class="hero-orbit orbit-inner"
      />
      <view class="hero-flower"><view /><view /><view /><view /></view>
      <view class="hero-label"
        ><AppIcon name="sparkles" :size="13" color="#C5DEBB" /><text>你的日常节奏助手</text></view
      >
      <view class="hero-headline"><text>把今天，</text><text>过成喜欢的样子。</text></view>
      <text class="hero-description">一份轻盈的计划，一点刚好的陪伴。</text>
      <button class="hero-button" @click="goTab('assistant')">
        <text>和知衡聊聊</text><AppIcon name="arrow-right" :size="17" color="#244C3B" />
      </button>
      <view class="hero-memo"><view class="memo-dot" /><text>从一件小事开始就很好</text></view>
    </view>

    <StateView
      v-if="!session.tokens"
      kind="auth"
      title="从这里，记录属于你的日常"
      description="登录后可以把计划和生活记录保存到自己的账号。"
      action-text="连接我的账号"
      @action="navigate('/pages/auth/index')"
    />
    <StateView v-else-if="loading" kind="loading" title="正在整理今天的日常" />
    <StateView
      v-else-if="error"
      kind="error"
      title="今天的内容还没加载好"
      :description="error"
      action-text="重新连接"
      @action="load"
    />
    <template v-else>
      <view class="day-progress card">
        <view
          class="progress-ring"
          :style="{ background: `conic-gradient(#76A589 ${progress * 360}deg, var(--soft) 0deg)` }"
          ><view class="ring-center"
            ><text
              >{{ completed }}<text class="ring-denominator">/{{ denominator }}</text></text
            ></view
          ></view
        >
        <view class="progress-copy"
          ><text class="progress-title">{{
            denominator && completed === denominator
              ? '今天的小事，都认真完成了'
              : completed
                ? '正在靠近自己的节奏'
                : '给今天留一个小小的开始'
          }}</text
          ><text class="muted">{{
            denominator
              ? `已完成 ${completed} 项，共 ${denominator} 项安排`
              : '还没有安排，轻松一点也没关系'
          }}</text></view
        >
        <button class="icon-button" aria-label="查看今日计划" @click="goTab('plans')">
          <AppIcon name="chevron-right" :size="17" />
        </button>
      </view>

      <view v-if="nextTask" class="next-card card">
        <view class="between"
          ><text class="eyebrow">NEXT SMALL STEP</text
          ><text class="time-pill"
            >{{ formatTime(nextTask.scheduledStartAt) }} ·
            {{ nextTask.plannedDurationMinutes }} 分钟</text
          ></view
        >
        <view class="next-body"
          ><view class="next-icon"
            ><AppIcon
              :name="nextTask.taskType === 'ACTIVITY' ? 'activity' : 'moon'"
              :size="26"
              color="#5B8469" /></view
          ><view
            ><text class="next-title">{{ nextTask.title }}</text
            ><text class="next-note">给自己一点专注在当下的时间</text></view
          ></view
        >
        <view class="next-actions"
          ><AppButton size="small" :loading="completing === nextTask.id" @click="complete(nextTask)"
            >标记完成</AppButton
          ><button class="subtle-link" @click="navigate(`/pages/plans/task?id=${nextTask.id}`)">
            看看安排<AppIcon name="arrow-right" :size="14" /></button
        ></view>
      </view>

      <view class="quick-section"
        ><view class="section-heading"
          ><text>留一点生活的痕迹</text><text class="section-small">轻松记录，不必完美</text></view
        ><view class="quick-grid"
          ><button
            v-for="item in recordTypes"
            :key="item.type"
            class="quick-item"
            @click="openRecord(item.type)"
          >
            <view class="quick-symbol" :class="item.tone"
              ><AppIcon :name="item.icon" :size="20" /></view
            ><view
              ><text class="quick-title">{{ item.title }}</text
              ><text class="quick-note">{{ item.note }}</text></view
            ><AppIcon class="quick-plus" name="plus" :size="13" color="#A4B0A6" /></button></view
      ></view>

      <view
        ><view class="section-heading"
          ><text>今日节奏</text
          ><button class="subtle-link" @click="goTab('plans')">
            全部安排 <AppIcon name="arrow-right" :size="13" /></button></view
        ><view v-if="tasks.length" class="task-preview card"
          ><TaskCard
            v-for="task in tasks.slice(0, 3)"
            :key="task.id"
            :task="task"
            @complete="complete"
            @detail="navigate(`/pages/plans/task?id=${task.id}`)" /></view
        ><view v-else class="card"
          ><StateView
            title="一张空白的日程，也有无限可能"
            description="先放进一件你想做的小事。"
            action-text="创建一个轻计划"
            @action="navigate('/pages/plans/create')" /></view
      ></view>

      <view
        ><view class="section-heading"
          ><text>最近的生活片段</text
          ><button class="subtle-link" @click="navigate('/pages/records/index')">
            记录档案 <AppIcon name="arrow-right" :size="13" /></button></view
        ><view class="recent-card card"
          ><RecordCard
            v-for="record in recentRecords"
            :key="record.id"
            :record="record"
            @click="recordDetail" /><StateView
            v-if="!recentRecords.length"
            title="值得记录的事，正在发生"
            description="睡眠、一顿饭、一段散步，都可以成为生活的线索。"
            action-text="写下第一条"
            @action="showQuickRecord = true" /></view
      ></view>

      <view class="home-note"
        ><AppIcon name="leaf" :size="15" color="#91A38B" /><text
          >生活不必满分，每一小步都值得被看见。</text
        ></view
      >
    </template>

    <BottomSheet
      v-model="showQuickRecord"
      title="想记下哪一刻？"
      subtitle="记录实际发生的生活，听见自己的节奏。"
      ><view class="sheet-choices"
        ><button
          v-for="item in recordTypes"
          :key="item.type"
          class="sheet-choice"
          @click="openRecord(item.type)"
        >
          <view class="quick-symbol" :class="item.tone"
            ><AppIcon :name="item.icon" :size="23" /></view
          ><view
            ><text class="label">{{ item.title }}</text
            ><text class="muted">{{ item.note }}</text></view
          ><AppIcon name="chevron-right" :size="18" /></button></view
    ></BottomSheet>
  </view>
</template>

<style scoped lang="scss">
.home-view {
  padding-top: 9px;
  gap: 24px;
}
.home-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0 1px;
  gap: 12px;
}
.brand-word {
  letter-spacing: 2.3px;
  font-size: 8px;
  color: #7b9484;
  margin-bottom: 9px;
}
.greeting {
  display: block;
  font-size: 21px;
  font-weight: 650;
  color: var(--ink);
  letter-spacing: -0.8px;
}
.greeting-sun {
  font-size: 24px;
  color: #c0a15b;
  margin-left: 2px;
  font-weight: 400;
}
.home-date {
  font-size: 11px;
  color: var(--muted);
  display: block;
  margin-top: 6px;
}
.home-avatar {
  position: relative;
  width: 45px;
  height: 45px;
  flex-shrink: 0;
  margin: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4b7657;
  background: #e1ebd8;
  font-size: 16px;
  font-weight: 550;
  overflow: visible;
}
.avatar-dot {
  width: 8px;
  height: 8px;
  border: 2px solid var(--bg);
  border-radius: 50%;
  background: #709e74;
  position: absolute;
  bottom: 1px;
  right: 2px;
}
.companion-card {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  min-height: 237px;
  border-radius: 18px;
  background: #244e3e;
  padding: 22px 23px;
  color: #f4f7e9;
  box-shadow: 0 10px 24px rgba(35, 77, 50, 0.1);
}
.hero-grid {
  position: absolute;
  inset: 0;
  z-index: -1;
  background-image:
    linear-gradient(rgba(214, 235, 202, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(214, 235, 202, 0.04) 1px, transparent 1px);
  background-size: 30px 30px;
  mask-image: linear-gradient(90deg, transparent, #000);
}
.hero-orbit {
  position: absolute;
  z-index: -1;
  border: 1px solid rgba(220, 235, 185, 0.17);
  border-radius: 50%;
  transform: rotate(-24deg);
}
.orbit-outer {
  width: 190px;
  height: 260px;
  right: -47px;
  top: -15px;
}
.orbit-inner {
  width: 132px;
  height: 183px;
  right: -9px;
  top: 24px;
}
.hero-flower {
  position: absolute;
  right: 29px;
  top: 72px;
  width: 67px;
  height: 68px;
  opacity: 0.75;
  transform: rotate(-15deg);
}
.hero-flower view {
  position: absolute;
  width: 35px;
  height: 35px;
  background: linear-gradient(135deg, #c1dba3, #8fb388);
  border-radius: 100% 100% 18% 100%;
}
.hero-flower view:nth-child(2) {
  right: 0;
  transform: rotate(90deg);
}
.hero-flower view:nth-child(3) {
  bottom: 0;
  right: 0;
  transform: rotate(180deg);
}
.hero-flower view:nth-child(4) {
  bottom: 0;
  transform: rotate(270deg);
}
.hero-label {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 10px;
  color: #c7ddbd;
  letter-spacing: 0.5px;
}
.hero-headline {
  display: block;
  font-size: 28px;
  line-height: 1.45;
  font-weight: 560;
  letter-spacing: 1px;
  margin: 14px 0 8px;
}
.hero-headline text {
  display: block;
}
.hero-description {
  display: block;
  font-size: 11px;
  color: #bbd1bf;
  letter-spacing: 0.2px;
}
.hero-button {
  display: inline-flex;
  align-items: center;
  gap: 19px;
  border-radius: 10px;
  background: #e9eed5;
  color: #244c3b;
  padding: 10px 14px;
  margin: 18px 0 0;
  line-height: 1.5;
  font-weight: 600;
  font-size: 11px;
}
.hero-memo {
  position: absolute;
  right: 18px;
  bottom: 22px;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 8px;
  color: #c0d3b2;
  writing-mode: horizontal-tb;
}
.memo-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #adc78d;
}
.day-progress {
  display: flex;
  gap: 13px;
  align-items: center;
  padding: 16px;
  margin-top: -4px;
}
.progress-ring {
  flex-shrink: 0;
  width: 51px;
  height: 51px;
  border-radius: 50%;
  padding: 4px;
  transform: rotate(-45deg);
}
.ring-center {
  width: 100%;
  height: 100%;
  background: var(--surface);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: rotate(45deg);
  color: var(--ink);
  font-size: 17px;
  font-weight: 600;
}
.ring-denominator {
  font-size: 10px;
  font-weight: 400;
  color: var(--muted);
  margin-left: 2px;
}
.progress-copy {
  flex: 1;
  min-width: 0;
}
.progress-title {
  display: block;
  font-size: 13px;
  font-weight: 550;
  line-height: 1.7;
}
.progress-copy .muted {
  font-size: 10px;
}
.next-card {
  padding: 18px;
  background: var(--surface);
}
.next-card .eyebrow {
  font-size: 8px;
  letter-spacing: 1.6px;
  color: #8f9b86;
}
.time-pill {
  color: #7c8c7c;
  font-size: 10px;
}
.next-body {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 18px 0 19px;
}
.next-icon {
  width: 48px;
  height: 48px;
  border-radius: 15px;
  background: #e9efdf;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.next-title {
  display: block;
  font-size: 17px;
  color: var(--ink);
  font-weight: 600;
}
.next-note {
  display: block;
  font-size: 10px;
  color: var(--muted);
  margin-top: 5px;
}
.next-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.section-small {
  font-size: 10px;
  font-weight: 400;
  color: var(--muted);
}
.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.quick-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface);
  border-radius: 14px;
  padding: 16px 12px;
  margin: 0;
  text-align: left;
  line-height: 1.5;
  border: 1px solid rgba(150, 175, 155, 0.06);
}
.quick-symbol {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 36px;
  height: 40px;
  border-radius: 11px;
  flex-shrink: 0;
}
.lavender {
  background: #efebf6;
}
.sage {
  background: #e8f0e4;
}
.peach {
  background: #f8eee3;
}
.sand {
  background: #f5efd7;
}
.quick-title {
  display: block;
  color: var(--ink);
  font-size: 12px;
  font-weight: 550;
}
.quick-note {
  display: block;
  margin-top: 5px;
  color: var(--muted);
  font-size: 9px;
}
.quick-plus {
  position: absolute;
  top: 9px;
  right: 8px;
  opacity: 0.6;
}
.task-preview,
.recent-card {
  padding: 0 17px;
}
.task-preview :deep(.task-card + .task-card),
.recent-card :deep(.record-card + .record-card) {
  border-top: 1px solid var(--border);
}
.home-note {
  display: flex;
  gap: 7px;
  align-items: center;
  justify-content: center;
  color: #90a08c;
  font-size: 10px;
  padding: 0 0 8px;
}
.sheet-choices {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet-choice {
  display: flex;
  align-items: center;
  gap: 14px;
  text-align: left;
  padding: 15px;
  border-radius: 14px;
  background: var(--soft);
  margin: 0;
}
.sheet-choice > view:nth-child(2) {
  flex: 1;
}
.sheet-choice .muted {
  font-size: 11px;
}
@media (max-width: 360px) {
  .hero-memo {
    display: none;
  }
  .greeting {
    font-size: 19px;
  }
  .quick-item {
    padding: 14px 9px;
    gap: 7px;
  }
  .quick-note {
    font-size: 8px;
  }
}
</style>
