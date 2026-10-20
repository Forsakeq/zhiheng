<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="计划详情" subtitle="让每一个决定，都由你确认" back />
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后查看你的健康日常"
        description="登录账号后，即可保存和管理自己的计划与记录。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在打开你的计划" />
      <StateView
        v-else-if="error"
        kind="error"
        title="无法打开计划"
        :description="error"
        action-text="重新加载"
        @action="load"
      />
      <template v-else-if="plan">
        <view class="plan-heading"
          ><view class="row between"
            ><text class="eyebrow">YOUR PLAN, YOUR PACE</text
            ><text class="plan-state">{{ planStatuses[plan.status] }}</text></view
          ><text class="plan-title">{{ plan.title }}</text
          ><view class="plan-range"
            ><AppIcon name="calendar" :size="14" color="#869C82" /><text
              >{{ plan.startDate }} — {{ plan.endDate }}</text
            ></view
          ></view
        >
        <view class="card version-switch" @click="versionsSheet = true"
          ><view class="version-symbol"><AppIcon name="book" :size="21" color="#64825A" /></view
          ><view class="version-copy"
            ><text
              >{{ version ? `第 ${version.versionNo} 版` : '选择一个版本'
              }}<text v-if="version" class="version-status">{{
                version.status === 'DRAFT' ? '草稿' : version.isActive ? '正在生效' : '历史版本'
              }}</text></text
            ><text>{{ version?.changeReason || '每一次调整，都有记录' }}</text></view
          ><AppIcon name="chevron-down" :size="18" color="#91A18A"
        /></view>

        <template v-if="version">
          <view class="row between section-heading"
            ><text>计划中的小事</text
            ><button v-if="version.status === 'DRAFT'" class="text-action" @click="openEditor">
              编辑草稿</button
            ><text v-else class="history-label">已封存 · 只读</text></view
          >
          <view v-if="!version.tasks.length" class="card empty-draft"
            ><AppIcon name="leaf" :size="28" color="#A1B59A" /><text
              >草稿还是空的，添加第一项小事吧。</text
            ><AppButton variant="secondary" size="small" @click="openEditor"
              >编排任务</AppButton
            ></view
          >
          <view class="snapshot-list"
            ><view
              v-for="(task, index) in version.tasks"
              :key="task.draftTaskKey"
              class="snapshot-item"
              @click="openSnapshotTask(task)"
              ><view class="snapshot-time"
                ><text>{{ formatTime(task.scheduledStartAt) }}</text
                ><view class="timeline-dot" /><view
                  v-if="index < version.tasks.length - 1"
                  class="timeline-stem" /></view
              ><view class="card snapshot-card"
                ><view class="row between"
                  ><text class="snapshot-title">{{ task.title }}</text
                  ><AppIcon v-if="task.isFixed" name="lock" :size="12" color="#9AAF90" /></view
                ><text class="snapshot-meta"
                  >{{ taskDate(task) }} · {{ task.plannedDurationMinutes }} 分钟 ·
                  {{ taskTypeNames[task.taskType] }}</text
                ><text v-if="task.instructions" class="snapshot-instructions">{{
                  task.instructions
                }}</text></view
              ></view
            ></view
          >
          <template v-if="version.status === 'DRAFT'">
            <view class="review-card"
              ><AppIcon name="shield" :size="21" color="#7A986D" /><view
                ><text>先看预览，再确认生效</text
                ><text>系统会检查时间冲突、固定任务和历史记录。已完成的任务不会被改写。</text></view
              ></view
            >
            <AppButton block :loading="working" icon="eye" @click="preview"
              >检查并预览变更</AppButton
            >
            <AppButton variant="ghost" block :loading="working" @click="discard"
              >放弃这个草稿版本</AppButton
            >
          </template>
          <AppButton
            v-else-if="plan.status !== 'ARCHIVED'"
            variant="secondary"
            block
            icon="plus"
            :loading="working"
            @click="copyVersion"
            >复制为新草稿，开始调整</AppButton
          >
          <view v-if="plan.status === 'ARCHIVED'" class="review-card"
            ><AppIcon name="info" :size="19" /><text
              >这个计划已经归档，历史版本和执行记录仍可查看。</text
            ></view
          >
        </template>
      </template>
    </view>

    <BottomSheet
      v-model="versionsSheet"
      title="每一次调整，都有迹可循"
      subtitle="查看生效版本、草稿与完整历史。"
      ><view class="sheet-stack"
        ><view
          v-for="item in versions"
          :key="item.id"
          class="version-choice"
          :class="{ current: version?.id === item.id }"
          @click="selectVersion(item.id)"
          ><view class="version-number">{{ String(item.versionNo).padStart(2, '0') }}</view
          ><view
            ><text class="version-choice-title"
              >第 {{ item.versionNo }} 版 ·
              {{
                item.status === 'DRAFT'
                  ? '草稿'
                  : item.isActive
                    ? '当前生效'
                    : item.status === 'DISCARDED'
                      ? '已放弃'
                      : '已封存'
              }}</text
            ><text class="version-choice-note">{{ item.changeReason }}</text
            ><text class="version-choice-date"
              >{{ item.createdAt.slice(0, 10) }} · {{ item.taskCount }} 项任务</text
            ></view
          ><AppIcon v-if="version?.id === item.id" name="check" :size="17" color="#1B6554" /></view
        ><text v-if="!versions.length" class="muted">暂无可查看的版本。</text></view
      ></BottomSheet
    >

    <BottomSheet
      v-model="editorSheet"
      title="编排草稿任务"
      subtitle="此处仅修改草稿，保存后仍需预览和确认。"
      ><view class="sheet-stack"
        ><view v-for="(task, index) in editableTasks" :key="task.draftTaskKey" class="edit-task"
          ><view
            ><text>{{ task.title }}</text
            ><text
              >{{ taskDate(task) }} · {{ formatTime(task.scheduledStartAt) }} ·
              {{ task.plannedDurationMinutes }} 分钟</text
            ></view
          ><button class="delete-icon" @click="editableTasks.splice(index, 1)">
            <AppIcon name="close" :size="16" color="#A2AF9A" /></button></view
        ><view class="add-editor"
          ><text class="editor-label">添加新的活动</text
          ><input
            v-model="newTask.title"
            class="input"
            placeholder="例如：课后散步一会儿"
            :maxlength="100"
          /><view class="two-col"
            ><picker
              mode="date"
              :value="newTask.date"
              :start="plan?.startDate"
              :end="plan?.endDate"
              @change="newTask.date = $event.detail.value"
              ><view class="picker-field">{{ newTask.date }}</view></picker
            ><picker mode="time" :value="newTask.time" @change="newTask.time = $event.detail.value"
              ><view class="picker-field"
                >{{ newTask.time }}<AppIcon name="clock" :size="13" /></view></picker></view
          ><view class="duration-field"
            ><input
              v-model="newTask.duration"
              class="input"
              type="number"
              placeholder="分钟"
            /><text>分钟</text
            ><AppButton variant="secondary" size="small" @click="appendTask">加入</AppButton></view
          ></view
        ><view class="reason-field"
          ><text class="label">本次修改的原因</text
          ><input
            v-model="changeReason"
            class="input"
            placeholder="例如：根据这周课程调整日程"
            :maxlength="500" /></view
        ><text v-if="editorError" class="error-text">{{ editorError }}</text
        ><AppButton block :loading="working" @click="saveDraft">保存草稿任务</AppButton></view
      ></BottomSheet
    >

    <BottomSheet
      v-model="previewSheet"
      title="确认这次计划调整"
      subtitle="看清变化，再决定是否让它生效。"
      ><view v-if="previewData" class="sheet-stack"
        ><view class="preview-result" :class="{ blocked: !previewData.canApply }"
          ><AppIcon
            :name="previewData.canApply ? 'check-circle' : 'info'"
            :size="26"
            :color="previewData.canApply ? '#4D805B' : '#A78A4F'"
          /><view
            ><text>{{ previewData.canApply ? '检查通过，可以生效' : '请先调整这些问题' }}</text
            ><text
              >{{ previewData.changes.length }} 项变更 ·
              {{ previewData.unchangedHistoricalTaskIds.length }} 项历史保持原样</text
            ></view
          ></view
        ><view
          v-for="issue in previewData.validation.issues"
          :key="`${issue.code}-${issue.fieldPath}`"
          class="validation-issue"
          ><text>{{ issue.message }}</text
          ><text v-if="issue.suggestedAction">{{ issue.suggestedAction }}</text></view
        ><view v-for="(change, index) in previewData.changes" :key="index" class="change-row"
          ><view class="change-icon"
            ><AppIcon :name="change.action === 'REMOVE' ? 'close' : 'plus'" :size="15" /></view
          ><view
            ><text>{{ change.after?.title || change.before?.title || '日程调整' }}</text
            ><text
              >{{ changeLabels[change.action] || change.action
              }}{{ change.after ? ` · ${formatTime(change.after.scheduledStartAt)}` : '' }}</text
            ></view
          ></view
        ><view v-if="previewData.reminderChanges.length" class="reminder-note"
          >将同步计算
          {{ previewData.reminderChanges.length }}
          项提醒变更；提醒是否可用以应用能力配置为准。</view
        ><view class="confirmation-line" @click="userConfirmed = !userConfirmed"
          ><view class="confirm-box" :class="{ checked: userConfirmed }"
            ><AppIcon v-if="userConfirmed" name="check" :size="12" color="#FFF" /></view
          ><text>我已阅读以上调整，同意让这个版本生效。</text></view
        ><AppButton
          block
          :disabled="!previewData.canApply || !userConfirmed"
          :loading="working"
          @click="activate"
          >确认并生效</AppButton
        ><text class="preview-validity">如其他设备修改了数据，生效前需要重新预览。</text></view
      ></BottomSheet
    >
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  activateVersion,
  createVersion,
  discardVersion,
  getPlan,
  getVersion,
  listTasks,
  listVersions,
  previewVersion,
  replaceVersion,
  type PlanDetail,
  type PlanPreview,
  type PlanScope,
  type PlanVersion,
  type PlanVersionDetail,
  type TaskSnapshot,
} from '@/api/plans'
import type { TaskView } from '@/api/types'
import { session } from '@/stores/session'
import { navigate, refreshApp } from '@/stores/ui'
import { deviceTimeZone, formatTime, localTimeToIso, today, uuid } from '@/utils/time'
import { confirm, showError, toast } from '@/utils/feedback'

const id = ref('')
const plan = ref<PlanDetail | null>(null)
const version = ref<PlanVersionDetail | null>(null)
const versions = ref<PlanVersion[]>([])
const loading = ref(false)
const working = ref(false)
const error = ref('')
const versionsSheet = ref(false)
const editorSheet = ref(false)
const previewSheet = ref(false)
const editableTasks = ref<TaskSnapshot[]>([])
const changeReason = ref('根据实际生活节奏调整计划')
const editorError = ref('')
const previewData = ref<PlanPreview | null>(null)
const previewScope = ref<PlanScope | null>(null)
const userConfirmed = ref(false)
const newTask = reactive({ title: '', date: today(), time: '18:30', duration: '20' })
const planStatuses: Record<string, string> = {
  DRAFT: '尚未生效',
  ACTIVE: '正在进行',
  PAUSED: '已暂停',
  ARCHIVED: '已归档',
}
const taskTypeNames: Record<string, string> = {
  ROUTINE: '日常习惯',
  ACTIVITY: '身体活动',
  MEAL_HABIT: '饮食习惯',
  RECORDING: '健康记录',
  CUSTOM: '自定义',
}
const changeLabels: Record<string, string> = {
  ADD: '新增任务',
  CREATE: '新增任务',
  UPDATE: '调整任务',
  REPLACE: '调整任务',
  REMOVE: '移除任务',
  KEEP: '保持不变',
}

function taskDate(task: TaskSnapshot) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: task.taskTimeZone }).format(
    new Date(task.scheduledStartAt),
  )
}
function openSnapshotTask(task: TaskSnapshot) {
  if (task.taskId && version.value?.status === 'SEALED')
    navigate(`/pages/plans/task?id=${task.taskId}`)
}

async function load(preferredVersionId?: string) {
  if (!session.tokens || !id.value) {
    plan.value = null
    version.value = null
    versions.value = []
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [detail, history] = await Promise.all([getPlan(id.value), listVersions(id.value)])
    plan.value = detail
    versions.value = history.items
    const chosen =
      preferredVersionId ||
      plan.value.draftVersions[0]?.id ||
      plan.value.activeVersionId ||
      versions.value[0]?.id
    if (chosen) await selectVersion(chosen)
    else version.value = null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '计划加载失败'
  } finally {
    loading.value = false
  }
}

async function selectVersion(versionId: string) {
  try {
    version.value = await getVersion(id.value, versionId)
    versionsSheet.value = false
    previewData.value = null
  } catch (cause) {
    showError(cause)
  }
}

function openEditor() {
  if (!version.value || version.value.status !== 'DRAFT') return
  editableTasks.value = JSON.parse(JSON.stringify(version.value.tasks)) as TaskSnapshot[]
  changeReason.value = version.value.changeReason || '根据实际生活节奏调整计划'
  newTask.date =
    plan.value?.startDate && plan.value.startDate >= today() ? plan.value.startDate : today()
  editorError.value = ''
  editorSheet.value = true
}

function appendTask() {
  editorError.value = ''
  if (!newTask.title.trim()) {
    editorError.value = '请写下任务名称'
    return
  }
  const duration = Number(newTask.duration)
  if (!Number.isInteger(duration) || duration < 1 || duration > 1440) {
    editorError.value = '预计时长应为 1—1440 分钟'
    return
  }
  if (!plan.value || newTask.date < plan.value.startDate || newTask.date > plan.value.endDate) {
    editorError.value = '任务日期需要在计划日期内'
    return
  }
  if (plan.value.timeZone !== deviceTimeZone()) {
    editorError.value =
      '当前设备时区与计划不同，请在计划时区一致的设备上新增任务。已有任务可以保留。'
    return
  }
  const start = localTimeToIso(newTask.date, newTask.time)
  editableTasks.value.push({
    draftTaskKey: uuid(),
    title: newTask.title.trim(),
    taskType: 'ACTIVITY',
    scheduledStartAt: start,
    scheduledEndAt: new Date(Date.parse(start) + duration * 60000).toISOString(),
    plannedDurationMinutes: duration,
    taskTimeZone: plan.value.timeZone,
    isFixed: false,
    priority: 3,
    sortOrder: editableTasks.value.length,
  })
  newTask.title = ''
}

async function saveDraft() {
  if (!plan.value || !version.value || working.value) return
  if (!changeReason.value.trim()) {
    editorError.value = '请填写本次调整的原因'
    return
  }
  working.value = true
  try {
    const latestTasks = await allPlanTasks()
    const latestVersions = new Map(latestTasks.map((task) => [task.id, task.rowVersion]))
    const tasks = editableTasks.value.map((task, index) => ({
      ...task,
      sortOrder: index,
      expectedTaskRowVersion: task.taskId
        ? latestVersions.get(task.taskId) || task.expectedTaskRowVersion
        : undefined,
    }))
    await replaceVersion(id.value, version.value.id, {
      expectedRowVersion: version.value.rowVersion,
      expectedActiveVersionId: plan.value.activeVersionId || null,
      changeReason: changeReason.value.trim(),
      tasks,
    })
    editorSheet.value = false
    toast('草稿已保存')
    refreshApp()
    await load(version.value.id)
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

async function allPlanTasks() {
  if (!plan.value) return []
  const items: TaskView[] = []
  let cursor: string | undefined
  do {
    const page = await listTasks(plan.value.startDate, plan.value.endDate, {
      planId: id.value,
      cursor,
    })
    items.push(...page.items)
    cursor = page.hasMore ? page.nextCursor || undefined : undefined
  } while (cursor)
  return items
}

async function preview() {
  if (!plan.value || !version.value || working.value) return
  working.value = true
  try {
    const current = await allPlanTasks()
    const effectiveFrom = plan.value.activeVersionId
      ? new Date().toISOString()
      : version.value.effectiveFrom || localTimeToIso(plan.value.startDate, '00:00')
    const affected = current.filter(
      (task) =>
        task.scheduledStartAt >= effectiveFrom && ['PENDING', 'CANCELLED'].includes(task.status),
    )
    const scope: PlanScope = {
      expectedRowVersion: version.value.rowVersion,
      expectedActiveVersionId: plan.value.activeVersionId || null,
      effectiveFrom,
      affectedTaskIds: affected.map((task) => task.id),
      allowFixedTaskChanges: false,
      expectedTasks: affected.map((task) => ({
        taskId: task.id,
        expectedRowVersion: task.rowVersion,
      })),
    }
    previewScope.value = scope
    previewData.value = await previewVersion(id.value, version.value.id, scope)
    userConfirmed.value = false
    previewSheet.value = true
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

async function activate() {
  if (
    !version.value ||
    !previewScope.value ||
    !previewData.value?.canApply ||
    !userConfirmed.value ||
    working.value
  )
    return
  working.value = true
  try {
    const versionId = version.value.id
    await activateVersion(
      id.value,
      versionId,
      previewScope.value,
      previewData.value.validation.checkedDataSeq,
    )
    previewSheet.value = false
    refreshApp()
    toast('新计划已经生效，按自己的节奏开始吧')
    await load(versionId)
  } catch (cause) {
    showError(cause)
    previewData.value = null
    previewSheet.value = false
  } finally {
    working.value = false
  }
}

async function copyVersion() {
  if (!plan.value || !version.value || working.value) return
  working.value = true
  try {
    const result = await createVersion(id.value, {
      expectedPlanRowVersion: plan.value.rowVersion,
      expectedActiveVersionId: plan.value.activeVersionId || null,
      changeReason: '根据日常节奏创建新草稿',
      copyFromVersionId: version.value.id,
    })
    const versionId = result.id
    toast('已复制为草稿，可以开始调整')
    await load(versionId)
    refreshApp()
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

async function discard() {
  if (
    !version.value ||
    working.value ||
    !(await confirm('放弃这个草稿版本？', '草稿会标记为已放弃，当前生效的计划保持原样。'))
  )
    return
  working.value = true
  try {
    await discardVersion(id.value, version.value.id, version.value.rowVersion)
    refreshApp()
    toast('草稿已放弃')
    await load()
  } catch (cause) {
    showError(cause)
  } finally {
    working.value = false
  }
}

onLoad((options) => {
  id.value = options?.id || ''
})
onShow(() => {
  void load()
})
</script>

<style scoped>
.plan-heading {
  padding: 10px 0 8px;
}
.eyebrow {
  color: #9aa991;
  font-size: 9px;
  letter-spacing: 1.7px;
}
.plan-state {
  background: #e5eddf;
  color: #729064;
  font-size: 10px;
  padding: 5px 9px;
  border-radius: 20px;
}
.plan-title {
  display: block;
  color: #304f35;
  font-size: 27px;
  font-weight: 650;
  margin: 17px 0 13px;
  line-height: 1.5;
}
.plan-range {
  display: flex;
  gap: 7px;
  align-items: center;
  color: #91a187;
  font-size: 11px;
}
.version-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}
.version-symbol {
  background: #edf3e5;
  width: 41px;
  height: 43px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 11px;
}
.version-copy {
  flex: 1;
  min-width: 0;
}
.version-copy > text {
  display: block;
  font-size: 14px;
  color: #5a7550;
}
.version-copy > text + text {
  font-size: 10px;
  color: #a1ae96;
  margin-top: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
  overflow: hidden;
}
.version-status {
  font-size: 9px;
  color: #8ba07f;
  margin-left: 8px;
}
.text-action {
  background: transparent;
  margin: 0;
  padding: 0;
  line-height: 1.5;
  font-size: 11px;
  color: #6d8e5f;
}
.history-label {
  font-size: 10px;
  color: #a0af97;
}
.snapshot-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.snapshot-item {
  display: flex;
  gap: 13px;
}
.snapshot-time {
  width: 42px;
  flex-shrink: 0;
  text-align: center;
  position: relative;
  padding-top: 12px;
}
.snapshot-time > text {
  font-size: 10px;
  color: #8ba17f;
}
.timeline-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b5caa5;
  margin: 8px auto 0;
}
.timeline-stem {
  position: absolute;
  height: calc(100% - 25px);
  width: 1px;
  top: 44px;
  left: 50%;
  background: #dae4d1;
}
.snapshot-card {
  flex: 1;
  padding: 16px;
  min-width: 0;
}
.snapshot-title {
  font-size: 14px;
  color: #48663d;
  font-weight: 500;
}
.snapshot-meta {
  font-size: 10px;
  color: #9aa98e;
  margin-top: 9px;
  display: block;
}
.snapshot-instructions {
  font-size: 11px;
  color: #97a58d;
  display: block;
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px solid #edf2e7;
  line-height: 1.8;
}
.review-card {
  display: flex;
  gap: 11px;
  padding: 16px;
  background: #eaf1e1;
  border-radius: 14px;
  color: #88a078;
  font-size: 11px;
  line-height: 1.8;
}
.review-card view {
  flex: 1;
}
.review-card view text {
  display: block;
}
.review-card view text:first-child {
  color: #6b8a59;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 5px;
}
.empty-draft {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-direction: column;
  padding: 28px;
  color: #a1af97;
  font-size: 12px;
}
.sheet-stack {
  display: flex;
  flex-direction: column;
  gap: 15px;
  padding: 5px 0;
}
.version-choice {
  display: flex;
  gap: 13px;
  align-items: center;
  padding: 15px 10px;
  border-bottom: 1px solid #edf2e6;
  border-radius: 10px;
}
.version-choice.current {
  background: #eef5e5;
}
.version-choice > view:nth-child(2) {
  flex: 1;
}
.version-number {
  font-size: 23px;
  font-weight: 300;
  color: #aec29c;
}
.version-choice-title {
  font-size: 13px;
  color: #658051;
  display: block;
}
.version-choice-note {
  font-size: 11px;
  color: #97a787;
  display: block;
  line-height: 1.7;
  margin-top: 7px;
}
.version-choice-date {
  font-size: 9px;
  color: #b0bba4;
  display: block;
  margin-top: 6px;
}
.edit-task {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 11px 0;
  border-bottom: 1px solid #eef3e7;
}
.edit-task > view {
  flex: 1;
}
.edit-task text {
  color: #6a8656;
  font-size: 12px;
  display: block;
}
.edit-task text + text {
  color: #a2b090;
  font-size: 10px;
  margin-top: 6px;
}
.delete-icon {
  padding: 4px;
  background: transparent;
  margin: 0;
  line-height: 1;
}
.add-editor {
  padding: 15px;
  background: #f2f6ec;
  display: flex;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
}
.editor-label,
.label {
  font-size: 11px;
  color: #8da17c;
  display: block;
}
.input {
  height: 43px;
  box-sizing: border-box;
  padding: 0 12px;
  font-size: 12px;
  color: #688251;
  background: #fff;
  border-radius: 9px;
}
.picker-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-radius: 9px;
  padding: 13px 10px;
  font-size: 11px;
  color: #80976a;
}
.duration-field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: #9faf8e;
}
.duration-field .input {
  flex: 1;
  width: 70px;
}
.reason-field .input {
  background: #f3f6ef;
  margin-top: 10px;
}
.preview-result {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #e9f2de;
  border-radius: 12px;
  padding: 16px;
}
.preview-result.blocked {
  background: #f9f0db;
}
.preview-result text {
  display: block;
  font-size: 13px;
  color: #77955d;
}
.preview-result text + text {
  color: #a0b089;
  font-size: 10px;
  margin-top: 7px;
}
.validation-issue {
  padding: 14px;
  background: #fcf6e9;
  border-radius: 10px;
}
.validation-issue text {
  display: block;
  color: #aa945e;
  font-size: 12px;
  line-height: 1.8;
}
.validation-issue text + text {
  font-size: 10px;
  margin-top: 6px;
}
.change-row {
  display: flex;
  gap: 10px;
  padding: 11px 0;
  border-bottom: 1px solid #edf3e5;
}
.change-icon {
  border-radius: 50%;
  width: 28px;
  height: 28px;
  background: #edf4e4;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #8ca274;
}
.change-row text {
  display: block;
  font-size: 12px;
  color: #6e8859;
}
.change-row text + text {
  font-size: 10px;
  color: #a2b28d;
  margin-top: 6px;
}
.reminder-note {
  color: #a2b08f;
  font-size: 11px;
  line-height: 1.9;
}
.confirmation-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 11px;
  color: #889d72;
  line-height: 1.8;
}
.confirm-box {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  border-radius: 5px;
  border: 1px solid #c9d8bc;
  display: flex;
  justify-content: center;
  align-items: center;
}
.confirm-box.checked {
  background: #1b6554;
  border-color: #1b6554;
}
.preview-validity {
  text-align: center;
  color: #b0bca0;
  font-size: 9px;
  line-height: 1.8;
}
</style>
