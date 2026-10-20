<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="这一周的回顾" subtitle="从真实记录出发，保留每个结论的依据" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后查看你的回顾与记录依据。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading && !review" kind="loading" title="正在整理回顾" /><StateView
        v-else-if="error"
        kind="error"
        title="暂时无法读取"
        :description="error"
        action-text="重试"
        @action="load"
      />
      <template v-if="authenticated && review"
        ><view class="detail-cover"
          ><text class="eyebrow">MY WEEKLY REVIEW</text><text class="cover-title">回望这一周</text
          ><text class="cover-date"
            >{{ formatDay(review.weekStart) }} — {{ formatDay(review.weekEnd) }}</text
          ><view class="cover-rule" /><text class="cover-caption">一点一滴，都算数。</text
          ><AppIcon class="cover-icon" name="leaf" :size="90"
        /></view>
        <view v-if="review.status === 'GENERATING'" class="card generating"
          ><AppIcon name="sparkles" :size="24" /><text>正在生成，结果会自动更新</text
          ><text class="muted">关闭页面不会重复提交请求。</text
          ><AppButton
            v-if="review.aiRunId"
            variant="secondary"
            size="small"
            @click="navigate(`/pages/ai/run?id=${review.aiRunId}`)"
            >查看生成状态</AppButton
          ></view
        >
        <view v-if="review.stale" class="stale-note"
          ><AppIcon name="info" :size="18" /><text
            >生成后，原始数据已发生变化。本回顾保留生成时的快照，请重新生成以查看最新结果。</text
          ></view
        >
        <view v-if="review.status === 'FAILED' || review.status === 'CANCELLED'" class="card"
          ><text class="label">{{
            review.status === 'FAILED' ? '本次回顾未能完成' : '本次回顾已取消'
          }}</text
          ><text class="muted block"
            >未生成可用的周回顾内容。可以检查服务配置、授权和数据后重新尝试。</text
          ></view
        >
        <template v-if="review.summary"
          ><view class="section-heading"
            ><text>这一周的发现</text
            ><text class="eyebrow">{{ review.metricDefinitionVersion }}</text></view
          ><view class="card summary-card"
            ><text>{{ review.summary }}</text></view
          ><view v-if="review.highlights?.length" class="highlight-list"
            ><view v-for="(item, index) in review.highlights" :key="index" class="highlight"
              ><text class="highlight-no">{{ String(index + 1).padStart(2, '0') }}</text
              ><text>{{ textValue(item) }}</text></view
            ></view
          >
          <view v-if="review.adjustmentSuggestions?.length" class="suggestion-card"
            ><view class="row"
              ><AppIcon name="leaf" :size="21" /><text class="suggestion-title"
                >下周，可以试试</text
              ></view
            ><text
              v-for="(item, index) in review.adjustmentSuggestions"
              :key="index"
              class="suggestion"
              >{{ textValue(item) }}</text
            ><text class="suggestion-footnote">建议不会自动创建目标或修改计划。</text></view
          >
        </template>
        <view class="section-heading"
          ><text>结论来自哪里</text
          ><text class="evidence-count">{{ evidence.length }} 条已加载依据</text></view
        >
        <StateView
          v-if="evidenceError"
          kind="error"
          title="依据暂时无法读取"
          :description="evidenceError"
          action-text="重试读取依据"
          @action="loadEvidence()"
        /><view v-else-if="!evidence.length && review.status !== 'GENERATING'" class="card"
          ><text class="muted"
            >本回顾没有可展示的逐条依据。记录不足时，不会推断未发生的行为。</text
          ></view
        >
        <view
          v-for="item in evidence"
          :key="item.id"
          class="card evidence-card"
          @tap="expanded = expanded === item.id ? '' : item.id"
          ><view class="row between"
            ><view
              ><text class="evidence-date">{{ formatDay(item.sourceDate) }}</text
              ><text class="evidence-title">{{ item.claimText || item.claimKey }}</text></view
            ><AppIcon name="chevron-down" :size="17" /></view
          ><view v-if="expanded === item.id" class="evidence-expanded"
            ><view class="row between"
              ><text>快照值</text><text>{{ evidenceValue(item) }}</text></view
            ><view class="row between"
              ><text>依据类型</text><text>{{ item.evidenceType }}</text></view
            ><view class="row between"
              ><text>原数据版本</text><text>{{ item.sourceRowVersion }}</text></view
            ><text class="evidence-hint"
              >依据为生成时的统计快照，原始数据变化后，本回顾会标注过期。</text
            ></view
          ></view
        >
        <AppButton v-if="evidenceCursor" block variant="secondary" @click="loadEvidence(true)"
          >加载更多依据</AppButton
        >
        <text class="bottom-note"
          >缺失不等于零。周回顾用于理解日常行为与计划执行，不用于医学诊断。</text
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import {
  getReviewEvidence,
  getWeeklyReview,
  type ReviewEvidence,
  type WeeklyReview,
} from '@/api/statistics'
import { session } from '@/stores/session'
import { navigate } from '@/stores/ui'
import { formatDay } from '@/utils/time'
const id = ref(''),
  review = ref<WeeklyReview | null>(null),
  evidence = ref<ReviewEvidence[]>([]),
  evidenceCursor = ref<string | null>(null),
  expanded = ref(''),
  loading = ref(false),
  error = ref(''),
  evidenceError = ref('')
const authenticated = computed(() => !!session.tokens)
let timer: ReturnType<typeof setTimeout> | undefined
let visible = true
function textValue(value: unknown) {
  if (typeof value === 'string') return value
  if (value && typeof value === 'object')
    return String(
      (value as Record<string, unknown>).text ||
        (value as Record<string, unknown>).title ||
        (value as Record<string, unknown>).message ||
        '',
    )
  return String(value ?? '')
}
function evidenceValue(item: ReviewEvidence) {
  if (item.value === null) return '缺失'
  const value = Number(item.value)
  return item.unit === 'RATIO'
    ? Math.round(value * 100) + '%'
    : value + (item.unit === 'MINUTES' ? ' 分钟' : item.unit === 'LEVEL' ? ' / 5' : '')
}
async function loadEvidence(more = false) {
  if (!authenticated.value || !id.value) return
  evidenceError.value = ''
  try {
    const page = await getReviewEvidence(
      id.value,
      more ? evidenceCursor.value || undefined : undefined,
    )
    evidence.value = more ? [...evidence.value, ...page.items] : page.items
    evidenceCursor.value = page.nextCursor
  } catch (err) {
    evidenceError.value = err instanceof Error ? err.message : '无法读取依据'
  }
}
async function load() {
  if (!id.value || !authenticated.value || loading.value) return
  clearTimeout(timer)
  loading.value = true
  error.value = ''
  try {
    review.value = await getWeeklyReview(id.value)
    if (review.value.status === 'GENERATING' && visible) timer = setTimeout(load, 2000)
    else await loadEvidence()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '无法读取回顾'
  } finally {
    loading.value = false
  }
}
onLoad((query) => {
  id.value = query?.id || ''
  void load()
})
onShow(() => {
  visible = true
  if (id.value) void load()
})
onHide(() => {
  visible = false
  clearTimeout(timer)
})
onUnload(() => {
  visible = false
  clearTimeout(timer)
})
</script>
<style scoped>
.detail-cover {
  position: relative;
  background: #ece7d8;
  border: 1px solid #e3dac5;
  border-radius: 14px;
  overflow: hidden;
  padding: 28px 24px;
  color: #8e7e56;
}
.cover-title {
  display: block;
  font-size: 28px;
  font-weight: 600;
  margin: 24px 0 12px;
  color: #75633f;
  letter-spacing: 2px;
}
.cover-date {
  font-size: 12px;
  color: #a79670;
}
.cover-rule {
  height: 1px;
  width: 40px;
  background: #c8b991;
  margin: 26px 0 16px;
}
.cover-caption {
  font-size: 10px;
  color: #aa9973;
  letter-spacing: 2px;
}
.cover-icon {
  position: absolute;
  right: 10px;
  bottom: 18px;
  color: #b5b19a55;
  transform: rotate(-24deg);
}
.generating {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 15px;
  color: #8d9b7c;
  font-size: 14px;
}
.generating .muted {
  font-size: 11px;
}
.stale-note {
  display: flex;
  gap: 9px;
  color: #9e8959;
  background: #f3eddf;
  border-radius: 12px;
  padding: 15px;
  font-size: 11px;
  line-height: 1.9;
}
.summary-card {
  font-size: 14px;
  color: #718063;
  line-height: 2;
  white-space: pre-line;
}
.highlight-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.highlight {
  display: flex;
  gap: 14px;
  line-height: 1.9;
  font-size: 12px;
  color: #8b977d;
  padding: 4px;
}
.highlight-no {
  font-size: 13px;
  color: #b2bea3;
}
.suggestion-card {
  padding: 20px;
  background: #e9f0e2;
  border-radius: 14px;
  color: #7b9666;
}
.suggestion-card .row {
  gap: 9px;
}
.suggestion-title {
  font-size: 14px;
  color: #65854e;
  font-weight: 600;
}
.suggestion {
  display: block;
  font-size: 12px;
  line-height: 1.9;
  margin-top: 15px;
}
.suggestion-footnote {
  display: block;
  font-size: 9px;
  color: #9cac8b;
  margin-top: 14px;
}
.evidence-count {
  font-size: 10px;
  color: #a4ae97;
}
.evidence-date {
  display: block;
  font-size: 10px;
  color: #a1af92;
  margin-bottom: 8px;
}
.evidence-title {
  font-size: 13px;
  color: #7b8d69;
}
.evidence-expanded {
  border-top: 1px solid #ecf0e6;
  margin-top: 15px;
  padding-top: 15px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 11px;
  color: var(--muted);
}
.evidence-expanded .row > text:last-child {
  color: #778d65;
}
.evidence-hint {
  font-size: 9px;
  line-height: 1.9;
  color: #a9b29d;
}
.bottom-note {
  font-size: 10px;
  line-height: 1.9;
  color: #a1ab93;
  text-align: center;
}
.block {
  display: block;
  line-height: 1.9;
  font-size: 12px;
  margin-top: 12px;
}
</style>
