<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader :title="title" subtitle="先生成草稿，再由你核对决定" back /><view
      class="page-stack"
    >
      <view class="create-intro"
        ><view class="intro-icon"
          ><AppIcon :name="kind === 'RECORD_EXTRACT' ? 'edit' : 'sparkles'" :size="26" /></view
        ><text class="intro-title">{{
          kind === 'RECORD_EXTRACT'
            ? '自然地说，认真地记'
            : kind === 'PLAN_ADJUST'
              ? '让计划跟上你的变化'
              : '给目标一份可执行的安排'
        }}</text
        ><text class="muted">{{
          kind === 'RECORD_EXTRACT'
            ? '时间、活动、感受，都可以先用一句话表达。识别结果不会直接成为健康记录。'
            : '服务端会结合目标与当前数据生成建议。任务时间与内容，生效前都可以再次核对。'
        }}</text></view
      >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后，让知衡协助整理记录和计划。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <view v-if="authenticated && !available" class="card blocked-card"
        ><AppIcon name="shield" :size="22" /><text class="intro-title">{{
          authorized ? '这项 AI 能力尚未开启' : '需要先授权 AI 处理'
        }}</text
        ><text class="muted">{{
          authorized
            ? '请联系部署人员配置服务端功能开关和模型。你仍可使用手动记录、手动计划。'
            : '请到「我的 → 隐私与授权」查看处理范围并决定是否授权。'
        }}</text
        ><AppButton
          v-if="!authorized"
          variant="secondary"
          size="small"
          @click="navigate('/pages/profile/consents')"
          >查看 AI 处理授权</AppButton
        ></view
      >

      <view v-if="kind !== 'RECORD_EXTRACT'" class="card form-card">
        <template v-if="kind === 'PLAN_GENERATE'">
          <text class="label">围绕哪些目标安排？</text
          ><text class="field-hint">最多 5 个，只选择当前启用的目标</text>
          <StateView
            v-if="!goals.length"
            kind="empty"
            title="还没有可用目标"
            description="请先在计划中创建并启用一个目标。"
          />
          <view v-for="goal in goals" :key="goal.id" class="select-row" @tap="toggleGoal(goal.id)"
            ><view class="selection" :class="{ checked: selectedGoals.includes(goal.id) }"
              ><AppIcon
                v-if="selectedGoals.includes(goal.id)"
                name="check"
                color="#fff"
                :size="13" /></view
            ><view
              ><text class="goal-title">{{ goal.title }}</text
              ><text class="field-hint"
                >{{ goal.targetValue || '习惯' }} {{ goal.unit || '持续实践' }}</text
              ></view
            ></view
          >
          <view class="divider" /><view class="two-col"
            ><view
              ><text class="label">开始日期</text
              ><picker mode="date" :value="from" @change="from = inputValue($event)"
                ><view class="input">{{ from }}</view></picker
              ></view
            ><view
              ><text class="label">结束日期</text
              ><picker mode="date" :value="to" :start="from" @change="to = inputValue($event)"
                ><view class="input">{{ to }}</view></picker
              ></view
            ></view
          >
          <text class="label">每天最多 {{ dailyLimit }} 项任务</text
          ><slider
            :value="dailyLimit"
            :min="1"
            :max="12"
            :step="1"
            activeColor="#1b6554"
            block-color="#1b6554"
            :block-size="20"
            @change="dailyLimit = Number(inputValue($event))"
          />
        </template>
        <template v-else>
          <text class="label">选择一个已生效计划</text
          ><picker :range="plans" range-key="title" @change="choosePlan(Number(inputValue($event)))"
            ><view class="input between row"
              ><text>{{ selectedPlan?.title || '选择计划' }}</text
              ><AppIcon name="chevron-down" :size="16" /></view
          ></picker>
          <text class="label">从什么时候调整？</text
          ><view class="two-col"
            ><picker
              mode="date"
              :value="effectiveDate"
              :start="today()"
              @change="effectiveDate = inputValue($event)"
              ><view class="input">{{ effectiveDate }}</view></picker
            ><picker mode="time" :value="effectiveTime" @change="effectiveTime = inputValue($event)"
              ><view class="input">{{ effectiveTime }}</view></picker
            ></view
          >
          <text class="label">选择受影响的待办任务</text
          ><text class="field-hint"
            >已完成、已跳过与已取消任务不参与调整；固定任务暂不在此修改。</text
          >
          <view
            v-for="task in adjustableTasks"
            :key="task.id"
            class="select-row"
            @tap="toggleTask(task.id)"
            ><view class="selection" :class="{ checked: selectedTaskIds.includes(task.id) }"
              ><AppIcon
                v-if="selectedTaskIds.includes(task.id)"
                name="check"
                color="#fff"
                :size="13" /></view
            ><view
              ><text class="goal-title">{{ task.title }}</text
              ><text class="field-hint"
                >{{ task.taskLocalDate }} · {{ formatTime(task.scheduledStartAt) }}</text
              ></view
            ></view
          ><text v-if="selectedPlan && !adjustableTasks.length" class="field-hint"
            >没有可供调整的非固定待办任务。</text
          >
        </template>
      </view>

      <view class="card form-card"
        ><text class="label">{{
          kind === 'RECORD_EXTRACT' ? '你想记下什么？' : '告诉知衡你的想法'
        }}</text
        ><textarea
          v-model="text"
          class="request-text"
          :maxlength="kind === 'RECORD_EXTRACT' ? 4000 : 2000"
          :placeholder="
            kind === 'RECORD_EXTRACT'
              ? '例如：今天下午散步了 25 分钟，感觉轻松，走完精神不错。'
              : kind === 'PLAN_ADJUST'
                ? '例如：今天临时有事，把今晚的散步调整到明天午后。'
                : '例如：这一周课程比较多，希望先从短时散步和睡前放松开始。'
          "
        /><view class="between row"
          ><text class="field-hint">{{ deviceTimeZone() }} · 以你确认的时间为准</text
          ><text class="field-hint">{{ text.length }}</text></view
        ></view
      >
      <view class="process-line"
        ><view><text>01</text><text>生成草稿</text></view
        ><AppIcon name="arrow-right" :size="14" /><view><text>02</text><text>逐项核对</text></view
        ><AppIcon name="arrow-right" :size="14" /><view
          ><text>03</text><text>确认保存</text></view
        ></view
      >
      <AppButton
        block
        icon="sparkles"
        :loading="loading"
        :disabled="!text.trim() || !available"
        @click="submit"
        >生成待确认草稿</AppButton
      >
      <text class="footnote"
        >AI 草稿可能存在遗漏或理解偏差。未确定的字段会明确标记，不会默认为真实数据。</text
      >
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { createRun, type AiKind } from '@/api/ai'
import { request } from '@/api/client'
import type { PageResult, TaskView, UserGoalView } from '@/api/types'
import { session } from '@/stores/session'
import { featureEnabled, refreshSession } from '@/stores/app'
import { navigate } from '@/stores/ui'
import { dayOffset, deviceTimeZone, formatTime, localTimeToIso, today, uuid } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

interface PlanChoice {
  id: string
  title: string
  activeVersionId: string | null
}
const kind = ref<AiKind>('RECORD_EXTRACT'),
  text = ref(''),
  loading = ref(false)
const goals = ref<UserGoalView[]>([]),
  selectedGoals = ref<string[]>([])
const plans = ref<PlanChoice[]>([]),
  selectedPlan = ref<PlanChoice | null>(null),
  tasks = ref<TaskView[]>([]),
  selectedTaskIds = ref<string[]>([])
const from = ref(today()),
  to = ref(dayOffset(6)),
  dailyLimit = ref(3),
  effectiveDate = ref(today()),
  effectiveTime = ref('18:00')
const authenticated = computed(() => !!session.tokens)
const title = computed(() =>
  kind.value === 'RECORD_EXTRACT'
    ? '一句话记事'
    : kind.value === 'PLAN_ADJUST'
      ? '调整计划'
      : '生成一周计划',
)
const authorized = computed(
  () =>
    session.me?.consents.some(
      (item) => item.purpose === 'AI_PROCESSING' && item.status === 'GRANTED',
    ) ?? false,
)
const available = computed(
  () =>
    authenticated.value &&
    authorized.value &&
    featureEnabled(kind.value === 'RECORD_EXTRACT' ? 'AI_RECORD' : 'AI_PLAN'),
)
const adjustableTasks = computed(() =>
  tasks.value.filter(
    (item) => item.planId === selectedPlan.value?.id && item.status === 'PENDING' && !item.isFixed,
  ),
)
function toggleGoal(id: string) {
  selectedGoals.value = selectedGoals.value.includes(id)
    ? selectedGoals.value.filter((item) => item !== id)
    : selectedGoals.value.length < 5
      ? [...selectedGoals.value, id]
      : selectedGoals.value
}
function toggleTask(id: string) {
  selectedTaskIds.value = selectedTaskIds.value.includes(id)
    ? selectedTaskIds.value.filter((item) => item !== id)
    : [...selectedTaskIds.value, id]
}
async function choosePlan(index: number) {
  selectedPlan.value = plans.value[index] || null
  selectedTaskIds.value = []
  if (authenticated.value && selectedPlan.value) {
    try {
      const page = await request<PageResult<TaskView>>('/api/v1/tasks', {
        query: {
          from: today(),
          to: dayOffset(30),
          planId: selectedPlan.value.id,
          status: 'PENDING',
          limit: 100,
        },
      })
      tasks.value = page.items
    } catch (error) {
      showError(error)
    }
  }
}
async function loadContext() {
  if (!authenticated.value) return
  try {
    await refreshSession()
    if (kind.value === 'PLAN_GENERATE') {
      const result = await request<PageResult<UserGoalView>>('/api/v1/me/goals', {
        query: { status: 'ACTIVE', limit: 100 },
      })
      goals.value = result.items
    } else if (kind.value === 'PLAN_ADJUST') {
      const result = await request<PageResult<PlanChoice>>('/api/v1/plans', {
        query: { status: 'ACTIVE', limit: 100 },
      })
      plans.value = result.items
    }
  } catch (error) {
    showError(error)
  }
}
async function submit() {
  if (!text.value.trim() || loading.value || !available.value) return
  if (kind.value === 'PLAN_GENERATE' && !selectedGoals.value.length) {
    toast('请至少选择一个目标')
    return
  }
  if (
    to.value < from.value ||
    new Date(to.value).getTime() - new Date(from.value).getTime() > 13 * 86400000
  ) {
    toast('请设置 1～14 天的计划范围')
    return
  }
  if (
    kind.value === 'PLAN_ADJUST' &&
    (!selectedPlan.value?.activeVersionId || !selectedTaskIds.value.length)
  ) {
    toast('请选择计划与受影响的任务')
    return
  }
  loading.value = true
  try {
    await refreshSession()
    const seq = session.me?.committedSeq || '0'
    const input =
      kind.value === 'RECORD_EXTRACT'
        ? {
            text: text.value.trim(),
            referenceInstant: new Date().toISOString(),
            timeZone: deviceTimeZone(),
            clientRecordId: uuid(),
          }
        : kind.value === 'PLAN_GENERATE'
          ? {
              goalIds: selectedGoals.value,
              from: from.value,
              to: to.value,
              timeZone: deviceTimeZone(),
              dailyTaskLimit: dailyLimit.value,
              requestText: text.value.trim(),
              expectedDataSeq: seq,
            }
          : {
              planId: selectedPlan.value!.id,
              expectedActiveVersionId: selectedPlan.value!.activeVersionId!,
              expectedDataSeq: seq,
              effectiveFrom: localTimeToIso(effectiveDate.value, effectiveTime.value),
              affectedTasks: adjustableTasks.value
                .filter((item) => selectedTaskIds.value.includes(item.id))
                .map((item) => ({ taskId: item.id, expectedRowVersion: item.rowVersion })),
              requestText: text.value.trim(),
              allowFixedTaskChanges: false,
            }
    const result = await createRun({ kind: kind.value, input, clientRequestId: uuid() })
    navigate(`/pages/ai/run?id=${result.runId}`)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}
onLoad((query) => {
  const incoming = query?.kind
  if (incoming === 'PLAN_GENERATE' || incoming === 'PLAN_ADJUST') kind.value = incoming
  void loadContext()
})

function inputValue(event: unknown): string {
  return String((event as { detail?: { value?: unknown } }).detail?.value ?? '')
}
</script>
<style scoped>
.create-intro {
  padding: 7px 0 5px;
}
.intro-icon {
  width: 52px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: #e7eee2;
  color: #1b6554;
  margin-bottom: 18px;
}
.intro-title {
  display: block;
  font-size: 23px;
  font-weight: 600;
  line-height: 1.5;
  margin-bottom: 10px;
  letter-spacing: 0.4px;
}
.create-intro .muted,
.blocked-card .muted {
  display: block;
  line-height: 1.9;
  font-size: 13px;
}
.form-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.field-hint {
  font-size: 10px;
  color: var(--muted);
  display: block;
  line-height: 1.7;
}
.select-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 0;
}
.selection {
  width: 20px;
  height: 20px;
  border: 1px solid #cbd8ca;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.selection.checked {
  background: #1b6554;
  border-color: #1b6554;
}
.goal-title {
  font-size: 14px;
  color: var(--ink);
}
.request-text {
  height: 155px;
  width: 100%;
  font-size: 14px;
  line-height: 1.9;
  color: var(--ink);
  padding: 8px 0;
}
.process-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  color: #9ea89a;
}
.process-line > view {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}
.process-line > view > text:first-child {
  font-size: 10px;
  color: #1b6554;
  border: 1px solid #d6e0d1;
  border-radius: 50%;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.footnote {
  font-size: 10px;
  color: var(--muted);
  line-height: 1.8;
  text-align: center;
}
.blocked-card {
  background: #f2eee2;
  color: #9d824c;
}
</style>
