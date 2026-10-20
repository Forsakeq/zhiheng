<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="每周回顾" subtitle="记录事实，也留意自己的感受" back /><view
      class="page-stack"
    >
      <view class="review-hero"
        ><view class="hero-book"><AppIcon name="book" :size="32" /></view
        ><text class="eyebrow">A MOMENT TO LOOK BACK</text
        ><text class="hero-title">这一周的你， 值得被好好看见。</text
        ><text class="hero-description"
          >从记录与任务中整理事实， 用可追溯的依据，回看自己的节奏。</text
        ></view
      >
      <view class="card generate-card"
        ><text class="label">选择要回顾的一周</text
        ><picker
          mode="date"
          :value="weekStart"
          :end="today()"
          @change="chooseWeek(inputValue($event))"
          ><view class="input row between"
            ><text>{{ formatDay(weekStart) }} — {{ formatDay(dayOffset(6, weekStart)) }}</text
            ><AppIcon name="calendar" :size="18" /></view></picker
        ><text class="field-hint">按周一开始计算；记录缺失会标注，不补造行为数据。</text
        ><AppButton
          block
          icon="sparkles"
          :loading="creating"
          :disabled="!canGenerate"
          @click="generate"
          >生成这一周的回顾</AppButton
        ><text v-if="authenticated && !canGenerate" class="disabled-note">{{
          authorized ? '每周回顾能力尚未由服务端启用。' : '需要先在隐私与授权中启用 AI 处理。'
        }}</text></view
      >
      <view class="section-heading"
        ><text>我的回顾</text><text class="eyebrow">{{ items.length }} 份已加载</text></view
      >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后生成并查看你的每周回顾。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在读取每周回顾" /><StateView
        v-else-if="error"
        kind="error"
        title="读取失败"
        :description="error"
        action-text="重试"
        @action="load()"
      /><StateView
        v-else-if="!items.length"
        kind="empty"
        title="留下一周的生活痕迹"
        description="记录一些真实发生的事，再生成你的第一份回顾。"
      />
      <view
        v-for="review in authenticated ? items : []"
        :key="review.id"
        class="card review-item"
        @tap="open(review)"
        ><view class="between row"
          ><text class="review-date"
            >{{ formatDay(review.weekStart) }} — {{ formatDay(review.weekEnd) }}</text
          ><text class="review-status" :class="{ stale: review.stale }">{{
            reviewStatus(review.status)
          }}</text></view
        ><text class="review-summary">{{ review.summary || '正在从你记录的事实整理回顾…' }}</text
        ><view class="row between review-bottom"
          ><text>有数据依据 · 保留缺失说明</text><AppIcon name="arrow-right" :size="17" /></view
      ></view>
      <AppButton v-if="authenticated && cursor" block variant="secondary" @click="load(true)"
        >加载更多回顾</AppButton
      >
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { createWeeklyReview, getWeeklyReviews, type WeeklyReview } from '@/api/statistics'
import { session } from '@/stores/session'
import { featureEnabled, refreshSession } from '@/stores/app'
import { navigate } from '@/stores/ui'
import { dayOffset, formatDay, today } from '@/utils/time'
import { showError } from '@/utils/feedback'
const authenticated = computed(() => !!session.tokens)
const authorized = computed(
  () =>
    session.me?.consents.some(
      (item) => item.purpose === 'AI_PROCESSING' && item.status === 'GRANTED',
    ) ?? false,
)
const canGenerate = computed(
  () => authenticated.value && authorized.value && featureEnabled('WEEKLY_REVIEW'),
)
const weekStart = ref(monday(today())),
  items = ref<WeeklyReview[]>([]),
  cursor = ref<string | null>(null),
  loading = ref(false),
  creating = ref(false),
  error = ref('')
function monday(date: string) {
  const parsed = new Date(date + 'T12:00:00')
  return dayOffset(-((parsed.getDay() + 6) % 7), date)
}
function chooseWeek(value: string) {
  weekStart.value = monday(value)
}
function reviewStatus(status: string) {
  return (
    {
      GENERATING: '生成中',
      READY: '已生成',
      STALE: '依据已变化',
      FAILED: '未生成',
      CANCELLED: '已取消',
    }[status] || status
  )
}
function open(review: WeeklyReview) {
  navigate(`/pages/reviews/detail?id=${review.id}`)
}
async function generate() {
  if (!canGenerate.value || creating.value) return
  creating.value = true
  try {
    await refreshSession()
    const result = await createWeeklyReview(weekStart.value, session.me?.committedSeq || '0')
    navigate(`/pages/reviews/detail?id=${result.jobId}`)
  } catch (err) {
    showError(err)
  } finally {
    creating.value = false
  }
}
async function load(more = false) {
  if (!authenticated.value) {
    items.value = []
    cursor.value = null
    return
  }
  error.value = ''
  loading.value = true
  try {
    if (!more) await refreshSession()
    const page = await getWeeklyReviews(more ? cursor.value || undefined : undefined)
    items.value = more ? [...items.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (err) {
    error.value = err instanceof Error ? err.message : '无法读取回顾'
  } finally {
    loading.value = false
  }
}
onShow(() => load())

function inputValue(event: unknown): string {
  return String((event as { detail?: { value?: unknown } }).detail?.value ?? '')
}
</script>
<style scoped>
.review-hero {
  padding: 26px;
  background: #eee9dc;
  border-radius: 14px;
  color: #7b6e50;
}
.hero-book {
  width: 54px;
  height: 59px;
  background: #faf6eb;
  border: 1px solid #daceb0;
  border-radius: 6px 14px 14px 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #b09a68;
  margin-bottom: 24px;
  box-shadow: -4px 0 #dfd4b9;
}
.hero-title {
  display: block;
  font-size: 25px;
  line-height: 1.55;
  white-space: pre-line;
  font-weight: 600;
  margin: 15px 0 12px;
}
.hero-description {
  display: block;
  font-size: 12px;
  line-height: 1.9;
  white-space: pre-line;
  color: #9f9172;
}
.generate-card {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.field-hint {
  font-size: 10px;
  color: var(--muted);
  line-height: 1.8;
}
.disabled-note {
  font-size: 11px;
  color: #a18b5e;
  line-height: 1.7;
}
.review-date {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.review-status {
  font-size: 9px;
  border-radius: 6px;
  background: #eaf2e3;
  color: #769266;
  padding: 4px 7px;
}
.review-status.stale {
  background: #f5ecdb;
  color: #a48b54;
}
.review-summary {
  display: block;
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
  margin: 16px 0;
  white-space: pre-line;
  max-height: 98px;
  overflow: hidden;
}
.review-bottom {
  font-size: 9px;
  color: var(--muted);
  padding-top: 12px;
  border-top: 1px solid #edf0e5;
}
</style>
