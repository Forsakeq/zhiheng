<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="核对 AI 草稿" subtitle="决定权，一直在你手里" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后查看并确认你的 AI 草稿。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading && !draft" kind="loading" title="正在读取草稿" />
      <StateView
        v-else-if="error"
        kind="error"
        title="暂时无法读取草稿"
        :description="error"
        action-text="重试"
        @action="load"
      />
      <template v-if="authenticated && draft">
        <view class="draft-banner"
          ><view class="row between"
            ><text class="eyebrow">AI DRAFT · 待确认</text
            ><text class="draft-status">{{
              draft.status === 'APPLIED'
                ? '已应用'
                : draft.status === 'CANCELLED'
                  ? '已取消'
                  : dirty
                    ? '已修改，待保存'
                    : '第 ' + draft.revisionNo + ' 版'
            }}</text></view
          ><text class="draft-title">{{
            draft.kind === 'RECORD_EXTRACT'
              ? '把这次经历，记准确'
              : draft.kind === 'PLAN_ADJUST'
                ? '确认这次调整'
                : '为下一周留一点空间'
          }}</text
          ><text class="draft-description"
            >以下内容来自模型生成。请检查时间、数量和内容，未确认前不会成为真实记录或生效计划。</text
          ></view
        >
        <view v-if="draft.missingFields?.length || draft.questions?.length" class="missing-card"
          ><text class="label">还需要你补充</text
          ><text v-for="question in draft.questions" :key="question" class="question"
            >· {{ question }}</text
          ><text v-if="draft.missingFields?.length" class="field-hint"
            >待补字段：{{ draft.missingFields.join('、') }}</text
          ><text class="field-hint">补充后，仍需通过服务端校验。</text></view
        >

        <view v-if="record" class="card edit-card">
          <view class="between row"
            ><text class="label">{{ recordLabels[String(record.recordType)] || '生活' }}记录</text
            ><view class="small-icon"><AppIcon name="edit" :size="18" /></view
          ></view>
          <picker
            :range="recordTypeOptions"
            range-key="label"
            :disabled="!editable"
            @change="changeRecordType(Number(inputValue($event)))"
            ><view class="input row between"
              ><text>{{ recordLabels[String(record.recordType)] || '请选择记录类型' }}</text
              ><AppIcon name="chevron-down" :size="16" /></view
          ></picker>
          <text class="field-hint">开始时间</text
          ><view class="two-col"
            ><picker
              mode="date"
              :value="recordDate"
              :disabled="!editable"
              @change="setRecordDate(inputValue($event))"
              ><view class="input">{{ recordDate }}</view></picker
            ><picker
              mode="time"
              :value="recordTime"
              :disabled="!editable"
              @change="setRecordTime(inputValue($event))"
              ><view class="input">{{ recordTime }}</view></picker
            ></view
          >
          <template
            v-if="
              record.recordType === 'SLEEP' || record.recordType === 'ACTIVITY' || record.endedAt
            "
            ><text class="field-hint">结束时间</text
            ><view class="two-col"
              ><picker
                mode="date"
                :value="endDate"
                :disabled="!editable"
                @change="setEnd(inputValue($event), endTime)"
                ><view class="input">{{ endDate }}</view></picker
              ><picker
                mode="time"
                :value="endTime"
                :disabled="!editable"
                @change="setEnd(endDate, inputValue($event))"
                ><view class="input">{{ endTime }}</view></picker
              ></view
            ></template
          >
          <template v-if="record.recordType === 'ACTIVITY'"
            ><text class="field-hint">活动类型</text
            ><picker
              :range="activityOptions"
              range-key="label"
              :disabled="!editable"
              @change="setDetail('activityType', activityOptions[Number(inputValue($event))].code)"
              ><view class="input">{{
                activityOptions.find((item) => item.code === detail.activityType)?.label ||
                '请选择实际活动类型'
              }}</view></picker
            ><text class="field-hint">实际活动分钟数</text
            ><input
              class="input"
              type="number"
              :value="String(detail.actualDurationMinutes || '')"
              :disabled="!editable"
              @input="setDetail('actualDurationMinutes', Number(inputValue($event)))" /><text
              class="field-hint"
              >活动名称</text
            ><input
              class="input"
              :value="String(detail.activityName || '')"
              :disabled="!editable"
              @input="setDetail('activityName', inputValue($event))"
          /></template>
          <template v-if="record.recordType === 'ENERGY'"
            ><text class="field-hint">主观精力 · {{ detail.energyLevel || '未填写' }} / 5</text
            ><slider
              :value="Number(detail.energyLevel || 3)"
              :disabled="!editable"
              :min="1"
              :max="5"
              :step="1"
              activeColor="#1b6554"
              block-color="#1b6554"
              @change="setDetail('energyLevel', Number(inputValue($event)))"
          /></template>
          <template v-if="record.recordType === 'MEAL'"
            ><text class="field-hint">餐次</text
            ><picker
              :range="mealOptions"
              range-key="label"
              :disabled="!editable"
              @change="setDetail('mealType', mealOptions[Number(inputValue($event))].code)"
              ><view class="input">{{
                mealOptions.find((item) => item.code === detail.mealType)?.label || '请选择餐次'
              }}</view></picker
            ><text class="field-hint">用餐描述</text
            ><textarea
              class="note-input input"
              :value="String(detail.description || '')"
              :disabled="!editable"
              @input="setDetail('description', inputValue($event))"
            />
          </template>
          <text class="field-hint">补充备注</text
          ><textarea
            class="note-input input"
            :value="String(record.notes || '')"
            :disabled="!editable"
            placeholder="可以保留当时的感受"
            :maxlength="2000"
            @input="setRecord('notes', inputValue($event))"
          /><view class="read-field"
            ><text>记录时区</text><text>{{ record.recordTimeZone }}</text></view
          ><AppButton
            v-if="editable && draft.missingFields?.length"
            variant="secondary"
            size="small"
            @click="markResolved"
            >我已补齐以上待确认信息</AppButton
          >
        </view>

        <view v-if="draft.kind !== 'RECORD_EXTRACT'" class="card edit-card"
          ><text class="label">{{ draft.kind === 'PLAN_CREATE' ? '计划名称' : '调整内容' }}</text
          ><input
            v-if="draft.kind === 'PLAN_CREATE'"
            v-model="payload.planTitle"
            class="input"
            :disabled="!editable"
            :maxlength="80"
            @input="markDirty"
          /><text v-if="payload.explanation" class="plan-explanation">{{
            payload.explanation
          }}</text
          ><view
            v-for="(task, index) in planTasks"
            :key="String(task.draftTaskKey || index)"
            class="plan-task"
            ><view class="task-number">{{ String(index + 1).padStart(2, '0') }}</view
            ><view class="task-main"
              ><input
                class="task-title"
                :value="String(task.title || '')"
                :disabled="!editable"
                :maxlength="100"
                @input="setTaskTitle(task, $event)"
              /><text class="field-hint"
                >{{ formatDay(String(task.scheduledStartAt || '').slice(0, 10)) }} ·
                {{ formatTime(String(task.scheduledStartAt || '')) }}—{{
                  formatTime(String(task.scheduledEndAt || ''))
                }}</text
              ><text v-if="task.instructions" class="task-instructions">{{
                task.instructions
              }}</text
              ><view class="row task-tags"
                ><text>{{ task.plannedDurationMinutes }} 分钟</text
                ><text>{{ task.isFixed ? '固定任务' : '可调整' }}</text></view
              ></view
            ></view
          ><view v-for="(change, index) in removedChanges" :key="index" class="removed-task"
            ><AppIcon name="trash" :size="16" /><text>移除任务 #{{ change.taskId }}</text></view
          >
          <view v-if="draft.kind === 'PLAN_ADJUST'" class="read-field"
            ><text>影响起点</text
            ><text>{{ formatTime(String(payload.effectiveFrom || '')) }}</text></view
          >
        </view>

        <view class="card validation-card"
          ><view class="between row"
            ><text class="label">核对结果</text
            ><text class="validation-badge" :class="{ valid: report?.valid && !dirty }">{{
              dirty ? '修改后需重新校验' : report?.valid ? '当前修订校验通过' : '等待校验'
            }}</text></view
          ><text v-if="!report?.valid" class="field-hint"
            >校验会检查必填项、原数据版本、时间冲突与授权状态。</text
          ><view
            v-for="(issue, index) in report?.issues || []"
            :key="index"
            class="validation-issue"
            ><AppIcon name="info" :size="15" /><text>{{ issue.message || issue.code }}</text></view
          ><view v-if="report?.valid && !dirty" class="validation-success"
            ><AppIcon name="check-circle" :size="20" /><text
              >已核对当前数据。保存前仍需你明确确认。</text
            ></view
          ><text v-if="report?.checkedDataSeq" class="field-hint"
            >依据数据版本 {{ report.checkedDataSeq }} · 后端会在应用时再次检查</text
          ></view
        >
        <template v-if="editable"
          ><AppButton
            v-if="dirty"
            block
            variant="secondary"
            icon="edit"
            :loading="saving"
            @click="saveRevision"
            >保存本次修订</AppButton
          ><AppButton
            block
            variant="secondary"
            icon="shield"
            :loading="validating"
            @click="validate"
            >{{ report?.valid && !dirty ? '重新校验与预览' : '校验当前草稿' }}</AppButton
          ><view class="confirm-line" @tap="confirmed = !confirmed"
            ><view class="checkbox" :class="{ checked: confirmed }"
              ><AppIcon v-if="confirmed" name="check" color="#fff" :size="13" /></view
            ><text
              >我已核对内容、时间和影响范围，确认保存{{
                draft.kind === 'RECORD_EXTRACT' ? '这条记录' : '并使计划生效'
              }}。</text
            ></view
          ><AppButton
            block
            :loading="applying"
            :disabled="!confirmed || !report?.valid || dirty"
            icon="check"
            @click="apply"
            >{{ draft.kind === 'RECORD_EXTRACT' ? '确认保存记录' : '确认应用计划' }}</AppButton
          ><AppButton block variant="ghost" @click="discard">放弃这份草稿</AppButton></template
        >
        <text v-if="appliedMessage" class="success-note">{{ appliedMessage }}</text
        ><text class="footer-note"
          >只记录真实发生的行为。完成一项计划任务，不会自动推定为已发生的健康记录。</text
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  applyDraft,
  cancelDraft,
  getDraft,
  reviseDraft,
  validateDraft,
  type DraftApply,
  type DraftView,
  type ValidationReport,
} from '@/api/ai'
import { session } from '@/stores/session'
import { refreshSession } from '@/stores/app'
import { navigate, refreshApp } from '@/stores/ui'
import { deviceTimeZone, formatDay, formatTime, localTimeToIso, today } from '@/utils/time'
import { confirm, showError, toast } from '@/utils/feedback'
const id = ref(''),
  draft = ref<DraftView | null>(null),
  payload = ref<Record<string, unknown>>({}),
  report = ref<ValidationReport | null>(null)
const loading = ref(false),
  saving = ref(false),
  validating = ref(false),
  applying = ref(false),
  dirty = ref(false),
  confirmed = ref(false),
  error = ref(''),
  appliedMessage = ref('')
const authenticated = computed(() => !!session.tokens)
const recordLabels: Record<string, string> = {
  SLEEP: '睡眠',
  ACTIVITY: '活动',
  MEAL: '用餐',
  ENERGY: '精力',
}
const recordTypeOptions = [
  { code: 'SLEEP', label: '睡眠' },
  { code: 'ACTIVITY', label: '活动' },
  { code: 'MEAL', label: '用餐' },
  { code: 'ENERGY', label: '精力' },
]
const activityOptions = [
  { code: 'WALK', label: '散步' },
  { code: 'RUN', label: '跑步' },
  { code: 'CYCLING', label: '骑行' },
  { code: 'STRETCH', label: '伸展' },
  { code: 'SPORT', label: '运动' },
  { code: 'OTHER', label: '其他' },
]
const mealOptions = [
  { code: 'BREAKFAST', label: '早餐' },
  { code: 'LUNCH', label: '午餐' },
  { code: 'DINNER', label: '晚餐' },
  { code: 'SNACK', label: '加餐' },
  { code: 'OTHER', label: '其他' },
]
const record = computed(() =>
  payload.value.record && typeof payload.value.record === 'object'
    ? (payload.value.record as Record<string, unknown>)
    : null,
)
const detail = computed(() =>
  record.value?.detail && typeof record.value.detail === 'object'
    ? (record.value.detail as Record<string, unknown>)
    : {},
)
const recordDate = computed(() =>
  record.value?.startedAt
    ? localDatePart(String(record.value.startedAt))
    : String(record.value?.recordLocalDate || today()),
)
const recordTime = computed(() => {
  const value = record.value?.startedAt
  if (!value) return '12:00'
  const date = new Date(String(value))
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
})
const endDate = computed(() =>
  record.value?.endedAt ? localDatePart(String(record.value.endedAt)) : recordDate.value,
)
const endTime = computed(() =>
  record.value?.endedAt ? localTimePart(String(record.value.endedAt)) : '08:00',
)
const editable = computed(
  () => !!draft.value && ['READY', 'NEEDS_INPUT'].includes(draft.value.status),
)
const changes = computed(() =>
  Array.isArray(payload.value.changes) ? (payload.value.changes as Record<string, unknown>[]) : [],
)
const planTasks = computed(() =>
  Array.isArray(payload.value.tasks)
    ? (payload.value.tasks as Record<string, unknown>[])
    : changes.value
        .filter(
          (item) => item.action !== 'REMOVE' && item.snapshot && typeof item.snapshot === 'object',
        )
        .map((item) => item.snapshot as Record<string, unknown>),
)
const removedChanges = computed(() => changes.value.filter((item) => item.action === 'REMOVE'))
function markDirty() {
  dirty.value = true
  confirmed.value = false
  report.value = null
}
function setRecord(key: string, value: unknown) {
  if (record.value) {
    record.value[key] = value
    markDirty()
  }
}
function setDetail(key: string, value: unknown) {
  detail.value[key] = value
  markDirty()
}
function localDatePart(value: string) {
  const date = new Date(value)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function localTimePart(value: string) {
  const date = new Date(value)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
function updateZone() {
  if (!record.value) return
  record.value.recordTimeZone = deviceTimeZone()
  const reference =
    record.value.recordType === 'SLEEP' ? record.value.endedAt : record.value.startedAt
  if (reference) {
    record.value.recordLocalDate = localDatePart(String(reference))
    record.value.utcOffsetMinutes = -new Date(String(reference)).getTimezoneOffset()
  }
}
function setRecordDate(value: string) {
  if (!record.value) return
  record.value.startedAt = localTimeToIso(value, recordTime.value)
  record.value.recordLocalDate = value
  updateZone()
  markDirty()
}
function setRecordTime(value: string) {
  setRecord('startedAt', localTimeToIso(recordDate.value, value))
  updateZone()
}
function setEnd(date: string, time: string) {
  setRecord('endedAt', localTimeToIso(date, time))
  updateZone()
}
function changeRecordType(index: number) {
  if (!record.value) return
  const type = recordTypeOptions[index].code
  if (record.value.recordType === type) return
  record.value.recordType = type
  record.value.detail = { detailType: type }
  markDirty()
}
function markResolved() {
  payload.value.unresolvedFields = []
  payload.value.questions = []
  if (draft.value) {
    draft.value.missingFields = []
    draft.value.questions = []
  }
  markDirty()
  toast('待确认标记已移除，保存修订后需重新校验')
}
function hydrate(value: DraftView) {
  draft.value = value
  payload.value = JSON.parse(JSON.stringify(value.payload))
  report.value = value.validation?.valid === undefined ? null : value.validation
  dirty.value = false
  confirmed.value = false
}
async function load() {
  if (!id.value || !authenticated.value) return
  loading.value = true
  error.value = ''
  try {
    hydrate(await getDraft(id.value))
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取草稿失败'
  } finally {
    loading.value = false
  }
}
async function saveRevision() {
  if (!draft.value || !authenticated.value) return
  saving.value = true
  try {
    await reviseDraft(draft.value, payload.value)
    await load()
    toast('修订已保存')
  } catch (err) {
    showError(err)
  } finally {
    saving.value = false
  }
}
async function validate() {
  if (!draft.value || !authenticated.value) return
  validating.value = true
  try {
    if (dirty.value) {
      await saveRevision()
      if (dirty.value) return
    }
    await refreshSession()
    const result = await validateDraft(draft.value, session.me?.committedSeq || '0')
    const fresh = await getDraft(draft.value.id)
    hydrate(fresh)
    report.value = result
  } catch (err) {
    showError(err)
  } finally {
    validating.value = false
  }
}
async function apply() {
  if (
    !authenticated.value ||
    !draft.value ||
    !report.value?.valid ||
    dirty.value ||
    !confirmed.value
  )
    return
  applying.value = true
  try {
    const data: DraftApply = {
      expectedRowVersion: draft.value.rowVersion,
      expectedDraftRevisionNo: draft.value.revisionNo,
      expectedDataSeq: report.value.checkedDataSeq || session.me?.committedSeq || '0',
      userConfirmed: true,
    }
    if (draft.value.kind === 'RECORD_EXTRACT')
      data.clientRecordId = String(record.value?.clientRecordId || '')
    else {
      data.expectedActiveVersionId = draft.value.base.activeVersionId
        ? String(draft.value.base.activeVersionId)
        : undefined
      data.effectiveFrom =
        draft.value.kind === 'PLAN_ADJUST'
          ? String(payload.value.effectiveFrom)
          : planTasks.value.map((item) => String(item.scheduledStartAt)).sort()[0]
      data.affectedTasks =
        draft.value.kind === 'PLAN_ADJUST' && Array.isArray(draft.value.base.tasks)
          ? (draft.value.base.tasks as { taskId: string; expectedRowVersion: string }[])
          : []
      data.allowFixedTaskChanges = false
    }
    await applyDraft(draft.value.id, data)
    await load()
    await refreshSession()
    refreshApp()
    appliedMessage.value =
      draft.value.kind === 'RECORD_EXTRACT'
        ? '记录已保存。'
        : '计划已应用，请到计划页查看最新安排。'
    toast('已确认保存')
  } catch (err) {
    showError(err)
  } finally {
    applying.value = false
  }
}
async function discard() {
  if (
    !authenticated.value ||
    !draft.value ||
    !(await confirm('放弃这份草稿？', '这份草稿不会成为真实记录或生效计划。'))
  )
    return
  try {
    await cancelDraft(draft.value)
    draft.value.status = 'CANCELLED'
    toast('草稿已取消')
  } catch (err) {
    showError(err)
  }
}
onLoad((query) => {
  id.value = query?.id || ''
  void load()
})

function setTaskTitle(task: Record<string, unknown>, event: unknown) {
  task.title = inputValue(event)
  markDirty()
}

function inputValue(event: unknown): string {
  return String((event as { detail?: { value?: unknown } }).detail?.value ?? '')
}
</script>
<style scoped>
.draft-banner {
  padding: 22px;
  background: #eaf0e5;
  border-radius: 14px;
}
.draft-status {
  font-size: 10px;
  color: #65805a;
}
.draft-title {
  display: block;
  font-size: 24px;
  line-height: 1.5;
  font-weight: 600;
  margin: 19px 0 10px;
  color: var(--ink);
}
.draft-description {
  font-size: 12px;
  line-height: 1.9;
  color: var(--muted);
  display: block;
}
.edit-card {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.small-icon {
  color: #7d9974;
}
.field-hint {
  font-size: 11px;
  line-height: 1.8;
  color: var(--muted);
}
.note-input {
  height: 86px;
  width: 100%;
  line-height: 1.8;
  box-sizing: border-box;
}
.read-field {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--muted);
  font-size: 11px;
  padding: 9px 0;
  border-top: 1px solid #edf0e8;
}
.read-field > text:last-child {
  color: var(--muted);
}
.missing-card {
  background: #f5ecdc;
  padding: 18px;
  border-radius: 14px;
  color: #967943;
}
.question {
  display: block;
  font-size: 13px;
  line-height: 1.8;
  margin: 8px 0;
}
.plan-explanation {
  font-size: 12px;
  color: #89937c;
  line-height: 1.9;
}
.plan-task {
  display: flex;
  gap: 12px;
  padding: 15px 0;
  border-top: 1px solid #edf0e7;
}
.task-number {
  color: #9aab8d;
  font-size: 12px;
  line-height: 26px;
}
.task-main {
  flex: 1;
  min-width: 0;
}
.task-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 8px;
}
.task-instructions {
  display: block;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.8;
  margin: 5px 0;
}
.task-tags {
  gap: 8px;
  margin-top: 8px;
}
.task-tags > text {
  font-size: 9px;
  color: #69825b;
  background: #edf3e7;
  padding: 4px 7px;
  border-radius: 6px;
}
.removed-task {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #a67b68;
}
.validation-badge {
  font-size: 9px;
  padding: 5px 7px;
  background: #f1eee3;
  color: #a08b54;
  border-radius: 6px;
}
.validation-badge.valid {
  background: #e7f0e2;
  color: #5e8150;
}
.validation-card > .field-hint {
  display: block;
  margin-top: 12px;
}
.validation-issue {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #a77c5c;
  font-size: 12px;
  margin-top: 13px;
  line-height: 1.8;
}
.validation-success {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--ink);
  margin-top: 15px;
  line-height: 1.8;
}
.confirm-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 4px 0;
  font-size: 11px;
  line-height: 1.8;
  color: var(--muted);
}
.checkbox {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  border: 1px solid #bbcbb2;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.checkbox.checked {
  background: #1b6554;
  border-color: #1b6554;
}
.footer-note {
  font-size: 10px;
  line-height: 1.9;
  color: var(--muted);
  text-align: center;
}
.success-note {
  padding: 16px;
  background: #e8f0e2;
  color: #5d7c4e;
  font-size: 13px;
  border-radius: 12px;
}
</style>
