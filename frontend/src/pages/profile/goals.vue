<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="我的小目标" subtitle="比起完美，更在意持续" back
      ><template #right
        ><AppButton size="small" variant="ghost" icon="plus" @click="openCreate"
          >新目标</AppButton
        ></template
      ></AppHeader
    >
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后设置生活目标"
        action-text="去登录"
        @action="navigate('/pages/auth/index')"
      />
      <StateView
        v-else-if="error"
        kind="error"
        title="目标加载失败"
        :description="error"
        action-text="重试"
        @action="load"
      />
      <template v-else>
        <view class="goal-intro"
          ><text class="eyebrow">ONE SMALL STEP</text
          ><text class="intro-title">向喜欢的自己， 再靠近一点。</text
          ><text class="muted">目标是计划的方向，也可以随生活调整。</text></view
        >
        <view v-for="goal in goals" :key="goal.id" class="card goal-card">
          <view class="row between"
            ><view class="goal-icon"><AppIcon name="flag" :size="22" /></view
            ><text class="pill">{{ statusLabel(goal.status) }}</text></view
          >
          <text class="goal-title">{{ goal.title }}</text>
          <text class="muted"
            >{{
              goal.targetValue == null
                ? '用自己的节奏坚持'
                : `${goal.targetValue} ${unitLabel(goal.unit)}`
            }}
            · {{ goal.startDate }} 起</text
          >
          <view class="goal-actions"
            ><AppButton
              v-if="goal.status === 'ACTIVE' || goal.status === 'PAUSED'"
              variant="secondary"
              size="small"
              :loading="busy"
              @click="toggle(goal)"
              >{{ goal.status === 'ACTIVE' ? '暂停' : '继续' }}</AppButton
            ><AppButton
              v-if="goal.status === 'ACTIVE' || goal.status === 'PAUSED'"
              variant="ghost"
              size="small"
              :loading="busy"
              @click="finish(goal)"
              >结束目标</AppButton
            ></view
          >
        </view>
        <StateView
          v-if="!goals.length && !loading"
          kind="empty"
          title="从一个小目标开始"
          description="例如规律作息、每天活动，或认真记录生活。"
          action-text="创建目标"
          @action="openCreate"
        />
      </template>
    </view>
    <BottomSheet v-model="sheet" title="给生活一个小方向" subtitle="一次专注一件值得坚持的事">
      <view class="sheet-form"
        ><view class="field"
          ><text class="label">目标类型</text
          ><picker
            :range="definitions"
            range-key="title"
            :value="definitionIndex"
            @change="selectDefinition(Number($event.detail.value))"
            ><view class="input">{{ definition?.title || '选择目标类型' }}</view></picker
          ><text class="muted">{{ definition?.description }}</text></view
        ><view class="field"
          ><text class="label">目标名称</text
          ><input
            v-model="title"
            class="input"
            maxlength="80"
            placeholder="每天留 20 分钟给散步" /></view
        ><view class="two-col"
          ><view class="field"
            ><text class="label">目标值（可选）</text
            ><input v-model="target" class="input" type="digit" placeholder="20" /></view
          ><view class="field"
            ><text class="label">单位</text
            ><picker
              :range="units.map(unitLabel)"
              @change="unit = units[Number($event.detail.value)]"
              ><view class="input">{{ unitLabel(unit) }}</view></picker
            ></view
          ></view
        ><AppButton block :loading="busy" :disabled="!definition" @click="save"
          >创建这个目标</AppButton
        ></view
      >
    </BottomSheet>
  </view>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import type { UserGoalView } from '@/api/types'
import { getGoals, getGoalDefinitions, createGoal, patchGoal, closeGoal } from '@/api/profile'
import type { GoalDefinition } from '@/api/profile'
import { today, deviceTimeZone } from '@/utils/time'
import { navigate, refreshApp } from '@/stores/ui'
import { toast, showError, confirm } from '@/utils/feedback'
const goals = ref<UserGoalView[]>([])
const definitions = ref<GoalDefinition[]>([])
const definitionIndex = ref(0)
const definition = computed(() => definitions.value[definitionIndex.value])
const sheet = ref(false)
const title = ref('')
const target = ref('')
const unit = ref('MINUTES')
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const units = computed(() =>
  definition.value?.allowedUnits?.length
    ? definition.value.allowedUnits
    : ['MINUTES', 'DAYS_PER_WEEK', 'TIMES_PER_WEEK', 'CUSTOM'],
)
function unitLabel(value: string | null | undefined) {
  return (
    (
      {
        MINUTES: '分钟',
        DAYS_PER_WEEK: '天 / 周',
        TIMES_PER_WEEK: '次 / 周',
        CUSTOM: '自定义',
      } as Record<string, string>
    )[value || 'CUSTOM'] || value
  )
}
function statusLabel(value: string) {
  return (
    (
      { ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', ARCHIVED: '已结束' } as Record<
        string,
        string
      >
    )[value] || value
  )
}
onShow(load)
async function load() {
  if (!session.tokens) return
  loading.value = true
  error.value = ''
  try {
    const [list, defs] = await Promise.all([getGoals(), getGoalDefinitions()])
    goals.value = list.items
    definitions.value = defs.items.filter((item) => item.enabled)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '请稍后重试'
  } finally {
    loading.value = false
  }
}
function selectDefinition(index: number) {
  definitionIndex.value = index
  title.value = definition.value?.title || ''
  target.value = definition.value?.defaultValue == null ? '' : String(definition.value.defaultValue)
  unit.value = units.value[0] || 'CUSTOM'
}
function openCreate() {
  if (!session.tokens) return navigate('/pages/auth/index')
  if (definitions.value.length) selectDefinition(0)
  sheet.value = true
}
async function save() {
  if (!title.value.trim()) return toast('给目标起个名字吧')
  const amount = target.value === '' ? null : Number(target.value)
  if (amount != null && (!Number.isFinite(amount) || amount < 0)) return toast('请填写有效目标值')
  busy.value = true
  try {
    if (!definition.value) return toast('请先选择目标类型')
    await createGoal({
      definitionId: definition.value.id,
      title: title.value.trim(),
      targetValue: amount,
      unit: amount == null ? null : unit.value,
      startDate: today(),
      timeZone: deviceTimeZone(),
    })
    await load()
    toast('目标已创建')
    sheet.value = false
    refreshApp()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}
async function toggle(goal: UserGoalView) {
  busy.value = true
  const status = goal.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
  try {
    await patchGoal(goal.id, { expectedRowVersion: goal.rowVersion, status })
    await load()
    refreshApp()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}
async function finish(goal: UserGoalView) {
  if (!(await confirm('结束这个目标', '目标将保留在历史中，不再参与后续计划生成。'))) return
  busy.value = true
  try {
    await closeGoal(goal.id, goal.rowVersion, today())
    await load()
    refreshApp()
    toast('目标已结束')
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}
</script>
<style scoped>
.goal-intro {
  padding: 8px 4px 14px;
}
.intro-title {
  display: block;
  white-space: pre-line;
  font-size: 27px;
  line-height: 1.5;
  color: #234b3c;
  margin: 12px 0;
}
.goal-card {
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.goal-icon {
  width: 43px;
  height: 43px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #edf4ed;
}
.goal-title {
  font-size: 18px;
  font-weight: 600;
}
.goal-actions {
  display: flex;
  gap: 10px;
  padding-top: 4px;
}
.sheet-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.sheet-form .muted {
  display: block;
  margin-top: 9px;
  line-height: 1.7;
}
</style>
