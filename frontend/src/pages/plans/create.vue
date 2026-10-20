<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="设计你的健康日常" subtitle="一个适合自己的计划，比很多目标更重要" back />
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后创建自己的计划"
        description="计划和任务会保存到你的账号中。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else>
        <view class="create-intro"
          ><text class="eyebrow">A LITTLE INTENTION, EVERY DAY</text
          ><text class="intro-title">从一个小改变开始。</text
          ><text class="intro-sub">你来决定节奏，知衡陪你慢慢走。</text
          ><view class="intro-leaf"><AppIcon name="leaf" :size="48" color="#91B09A" /></view
        ></view>
        <view class="card"
          ><text class="section-title">先为计划取个名字</text
          ><input
            v-model="title"
            class="title-input"
            placeholder="例如：这一周，好好照顾自己"
            :maxlength="80"
          /><view class="two-col dates"
            ><view
              ><text class="label">开始日期</text
              ><picker mode="date" :value="startDate" @change="startDate = $event.detail.value"
                ><view class="picker-field"
                  >{{ startDate }}<AppIcon name="calendar" :size="14" /></view></picker></view
            ><view
              ><text class="label">结束日期</text
              ><picker
                mode="date"
                :value="endDate"
                :start="startDate"
                @change="endDate = $event.detail.value"
                ><view class="picker-field"
                  >{{ endDate }}<AppIcon name="calendar" :size="14" /></view></picker></view></view
          ><text class="field-hint">单个计划支持 1—31 天，先从一周开始会更轻松。</text></view
        >
        <view class="row between section-heading"
          ><text>想做的那些小事</text><text class="task-count">{{ tasks.length }} 项</text></view
        >
        <view v-if="!tasks.length" class="starter-card"
          ><view class="starter-icon"><AppIcon name="sparkles" :size="25" color="#699573" /></view
          ><text class="starter-title">一点灵感，送给今天的你</text
          ><text class="starter-sub">选择一个建议，再根据自己的生活修改。</text
          ><view class="starter-actions"
            ><button @click="addSuggestion('walk')">
              晚间散步 20 分钟 <AppIcon name="plus" :size="13" /></button
            ><button @click="addSuggestion('rest')">
              睡前放松 15 分钟 <AppIcon name="plus" :size="13" /></button></view
        ></view>
        <view v-for="(task, index) in tasks" :key="task.draftTaskKey" class="card draft-task"
          ><view class="task-order">{{ String(index + 1).padStart(2, '0') }}</view
          ><view class="task-copy" @click="editTask(index)"
            ><text class="task-name">{{ task.title }}</text
            ><text class="task-description"
              >{{ snapshotDate(task) }} · {{ formatTime(task.scheduledStartAt) }} ·
              {{ task.plannedDurationMinutes }} 分钟{{ task.isFixed ? ' · 固定安排' : '' }}</text
            ></view
          ><button class="icon-button" @click="removeTask(index)">
            <AppIcon name="close" :size="16" color="#A4B09F" /></button
        ></view>
        <button class="add-task" @click="openNewTask">
          <AppIcon name="plus" :size="19" color="#588065" /><text>添加一项任务</text>
        </button>
        <view class="flow-note"
          ><view class="flow-step"
            ><text>1</text><view><text>保存为草稿</text><text>先整理你的想法</text></view></view
          ><view class="flow-step"
            ><text>2</text><view><text>查看变更预览</text><text>检查时间与冲突</text></view></view
          ><view class="flow-step"
            ><text>3</text><view><text>确认后生效</text><text>由你做最终决定</text></view></view
          ></view
        >
        <text v-if="error" class="error-text">{{ error }}</text>
        <text v-if="savedPlanId" class="field-hint"
          >计划草稿已创建，任务保存未完成。重试会继续写入同一个计划。</text
        >
        <AppButton block :loading="saving" @click="save">{{
          savedPlanId ? '继续保存任务' : '保存草稿，查看计划'
        }}</AppButton>
        <text class="bottom-note">保存草稿不会自动加入你的执行日程</text>
      </template>
    </view>

    <BottomSheet
      v-model="taskSheet"
      :title="editingIndex >= 0 ? '调整这项小事' : '添加一项小事'"
      subtitle="具体一点、轻松一点，更容易开始。"
      ><view class="sheet-form"
        ><view
          ><text class="label">任务名称</text
          ><input
            v-model="taskForm.title"
            class="input"
            placeholder="例如：晚饭后去校园走走"
            :maxlength="100" /></view
        ><view
          ><text class="label">任务类型</text
          ><picker
            :range="taskTypes"
            range-key="label"
            :value="taskTypeIndex"
            @change="taskForm.type = taskTypes[Number($event.detail.value)].value"
            ><view class="picker-field"
              >{{ taskTypes[taskTypeIndex].label
              }}<AppIcon name="chevron-down" :size="15" /></view></picker></view
        ><view class="two-col"
          ><view
            ><text class="label">任务日期</text
            ><picker
              mode="date"
              :value="taskForm.date"
              :start="startDate"
              :end="endDate"
              @change="taskForm.date = $event.detail.value"
              ><view class="picker-field">{{ taskForm.date }}</view></picker
            ></view
          ><view
            ><text class="label">开始时间</text
            ><picker
              mode="time"
              :value="taskForm.time"
              @change="taskForm.time = $event.detail.value"
              ><view class="picker-field"
                >{{ taskForm.time }}<AppIcon name="clock" :size="14" /></view></picker></view></view
        ><view
          ><text class="label">预计时长（分钟）</text
          ><input v-model="taskForm.duration" class="input" type="number" placeholder="20" /></view
        ><view
          ><text class="label">给自己的提示 <text class="optional">选填</text></text
          ><textarea
            v-model="taskForm.instructions"
            class="instructions-input"
            placeholder="用舒服的速度开始，不必追求强度"
            :maxlength="1000"
          /></view
        ><view class="switch-line"
          ><view><text>固定安排</text><text>后续调整时，需要额外确认</text></view
          ><switch :checked="taskForm.isFixed" color="#1B6554" @change="updateFixed" /></view
        ><text v-if="taskError" class="error-text">{{ taskError }}</text
        ><AppButton block @click="saveTask">{{
          editingIndex >= 0 ? '保存调整' : '加入我的计划'
        }}</AppButton></view
      ></BottomSheet
    >
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { createPlan, getVersion, replaceVersion, type TaskSnapshot } from '@/api/plans'
import { session } from '@/stores/session'
import { navigate, refreshApp } from '@/stores/ui'
import { dayOffset, deviceTimeZone, formatTime, localTimeToIso, today, uuid } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const title = ref('')
const startDate = ref(today())
const endDate = ref(dayOffset(6))
const tasks = ref<TaskSnapshot[]>([])
const saving = ref(false)
const error = ref('')
const savedPlanId = ref('')
const savedVersionId = ref('')
let pendingPlanFingerprint = ''
let pendingPlanOperationId = ''
let pendingTaskFingerprint = ''
let pendingTaskOperationId = ''
const taskSheet = ref(false)
const taskError = ref('')
const editingIndex = ref(-1)
const timeZone = deviceTimeZone()
const taskForm = reactive({
  title: '',
  type: 'ACTIVITY' as TaskSnapshot['taskType'],
  date: today(),
  time: '18:30',
  duration: '20',
  instructions: '',
  isFixed: false,
})
const taskTypes = [
  { label: '日常习惯', value: 'ROUTINE' },
  { label: '身体活动', value: 'ACTIVITY' },
  { label: '饮食习惯', value: 'MEAL_HABIT' },
  { label: '健康记录', value: 'RECORDING' },
  { label: '自定义', value: 'CUSTOM' },
] as const
const taskTypeIndex = computed(() =>
  Math.max(
    0,
    taskTypes.findIndex((item) => item.value === taskForm.type),
  ),
)

function updateFixed(event: unknown) {
  taskForm.isFixed = (event as { detail?: { value?: unknown } }).detail?.value === true
}

function snapshotDate(task: TaskSnapshot) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: task.taskTimeZone }).format(
    new Date(task.scheduledStartAt),
  )
}
function openNewTask() {
  editingIndex.value = -1
  taskError.value = ''
  Object.assign(taskForm, {
    title: '',
    type: 'ACTIVITY',
    date: startDate.value,
    time: '18:30',
    duration: '20',
    instructions: '',
    isFixed: false,
  })
  taskSheet.value = true
}

function editTask(index: number) {
  const task = tasks.value[index]
  editingIndex.value = index
  taskError.value = ''
  Object.assign(taskForm, {
    title: task.title,
    type: task.taskType,
    date: snapshotDate(task),
    time: new Intl.DateTimeFormat('en-GB', {
      timeZone: task.taskTimeZone,
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(task.scheduledStartAt)),
    duration: String(task.plannedDurationMinutes),
    instructions: task.instructions || '',
    isFixed: task.isFixed,
  })
  taskSheet.value = true
}

function saveTask() {
  taskError.value = ''
  if (!taskForm.title.trim()) {
    taskError.value = '给这项任务取个清楚的名字。'
    return
  }
  const duration = Number(taskForm.duration)
  if (!Number.isInteger(duration) || duration < 1 || duration > 1440) {
    taskError.value = '预计时长应为 1—1440 分钟的整数。'
    return
  }
  if (taskForm.date < startDate.value || taskForm.date > endDate.value) {
    taskError.value = '任务日期需要在计划日期内。'
    return
  }
  const start = localTimeToIso(taskForm.date, taskForm.time)
  const snapshot: TaskSnapshot = {
    draftTaskKey: editingIndex.value >= 0 ? tasks.value[editingIndex.value].draftTaskKey : uuid(),
    title: taskForm.title.trim(),
    taskType: taskForm.type,
    scheduledStartAt: start,
    scheduledEndAt: new Date(Date.parse(start) + duration * 60000).toISOString(),
    plannedDurationMinutes: duration,
    taskTimeZone: timeZone,
    isFixed: taskForm.isFixed,
    priority: 3,
    sortOrder: editingIndex.value >= 0 ? editingIndex.value : tasks.value.length,
    instructions: taskForm.instructions || undefined,
  }
  if (editingIndex.value >= 0) tasks.value[editingIndex.value] = snapshot
  else tasks.value.push(snapshot)
  taskSheet.value = false
}

function addSuggestion(kind: 'walk' | 'rest') {
  openNewTask()
  Object.assign(
    taskForm,
    kind === 'walk'
      ? {
          title: '晚饭后轻松散步',
          type: 'ACTIVITY',
          time: '18:30',
          duration: '20',
          instructions: '选一段喜欢的路，感受一下今天的风。',
        }
      : {
          title: '睡前给自己一点放松时间',
          type: 'ROUTINE',
          time: '22:15',
          duration: '15',
          instructions: '放下手机，做几次舒缓的呼吸，慢慢结束今天。',
        },
  )
}

function removeTask(index: number) {
  tasks.value.splice(index, 1)
  tasks.value.forEach((task, order) => {
    task.sortOrder = order
  })
}

async function save() {
  if (!session.tokens) {
    navigate('/pages/auth/index')
    return
  }
  if (saving.value) return
  error.value = ''
  if (!title.value.trim()) {
    error.value = '先为这个计划取一个名字。'
    return
  }
  const rangeDays =
    (Date.parse(`${endDate.value}T00:00:00Z`) - Date.parse(`${startDate.value}T00:00:00Z`)) /
    86400000
  if (rangeDays < 0 || rangeDays > 30) {
    error.value = '计划日期范围应为 1—31 天。'
    return
  }
  if (!tasks.value.length) {
    error.value = '至少添加一项想做的小事。'
    return
  }
  if (
    tasks.value.some(
      (task) => snapshotDate(task) < startDate.value || snapshotDate(task) > endDate.value,
    )
  ) {
    error.value = '有任务不在当前计划日期内，请先调整任务日期。'
    return
  }
  saving.value = true
  try {
    if (!savedPlanId.value) {
      const input = {
        title: title.value.trim(),
        planKind: rangeDays === 0 ? 'DAY' : rangeDays === 6 ? 'WEEK' : 'CUSTOM',
        timeZone,
        startDate: startDate.value,
        endDate: endDate.value,
        goalIds: [],
      }
      const fingerprint = JSON.stringify(input)
      if (fingerprint !== pendingPlanFingerprint) {
        pendingPlanFingerprint = fingerprint
        pendingPlanOperationId = uuid()
      }
      const result = await createPlan(input, pendingPlanOperationId)
      savedPlanId.value = result.id
      savedVersionId.value = result.planVersionId
    }
    const version = await getVersion(savedPlanId.value, savedVersionId.value)
    const input = {
      expectedRowVersion: version.rowVersion,
      expectedActiveVersionId: null,
      changeReason: '手动编排日常计划',
      tasks: tasks.value,
    }
    const fingerprint = JSON.stringify(input)
    if (fingerprint !== pendingTaskFingerprint) {
      pendingTaskFingerprint = fingerprint
      pendingTaskOperationId = uuid()
    }
    await replaceVersion(savedPlanId.value, savedVersionId.value, input, pendingTaskOperationId)
    const id = savedPlanId.value
    refreshApp()
    toast('计划草稿已保存，确认预览后再生效')
    navigate(`/pages/plans/detail?id=${id}`)
  } catch (cause) {
    showError(cause)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.create-intro {
  position: relative;
  padding: 8px 0 16px;
}
.eyebrow {
  display: block;
  font-size: 9px;
  letter-spacing: 1.6px;
  color: #98a799;
}
.intro-title {
  display: block;
  margin-top: 14px;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.intro-sub {
  display: block;
  font-size: 12px;
  color: #95a28f;
  margin-top: 10px;
}
.intro-leaf {
  position: absolute;
  right: 2px;
  top: 18px;
  transform: rotate(-18deg);
  opacity: 0.5;
}
.section-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
}
.title-input {
  margin-top: 17px;
  height: 44px;
  font-size: 14px;
  color: #47634e;
  border-bottom: 1px solid #e9eee5;
}
.dates {
  margin-top: 23px;
}
.label {
  font-size: 11px;
  color: #869b87;
  margin-bottom: 9px;
  display: block;
}
.picker-field {
  background: #f2f6ee;
  padding: 13px 10px;
  border-radius: 10px;
  font-size: 12px;
  color: #647e65;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.field-hint {
  display: block;
  font-size: 10px;
  margin-top: 13px;
  line-height: 1.8;
  color: #9cac9a;
}
.task-count {
  font-size: 11px;
  color: #97a893;
}
.starter-card {
  border: 1px dashed #bccfba;
  border-radius: 14px;
  padding: 23px 18px 18px;
  text-align: center;
  background: #eff4e9;
}
.starter-icon {
  margin: 0 auto 12px;
  background: #dfead7;
  border-radius: 50%;
  width: 46px;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.starter-title {
  display: block;
  color: #597857;
  font-size: 14px;
  font-weight: 600;
}
.starter-sub {
  display: block;
  font-size: 11px;
  color: #94a28d;
  margin-top: 8px;
}
.starter-actions {
  margin-top: 19px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.starter-actions button {
  background: #fff;
  color: #71886a;
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  border-radius: 9px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
}
.draft-task {
  display: flex;
  gap: 13px;
  align-items: center;
  padding: 17px 14px;
}
.task-order {
  font-size: 13px;
  color: #a0b198;
  font-weight: 600;
}
.task-copy {
  flex: 1;
}
.task-name {
  display: block;
  font-size: 14px;
  color: #44613f;
}
.task-description {
  display: block;
  font-size: 10px;
  color: #97a58f;
  margin-top: 8px;
}
.icon-button {
  padding: 5px;
  margin: 0;
  background: transparent;
  line-height: 1;
}
.add-task {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  border: 1px dashed #c5d5be;
  background: transparent;
  border-radius: 14px;
  padding: 18px;
  font-size: 13px;
  color: #6d8864;
  line-height: 1.4;
}
.flow-note {
  display: flex;
  justify-content: space-between;
  margin: 8px 0;
  gap: 8px;
}
.flow-step {
  display: flex;
  gap: 7px;
  align-items: flex-start;
}
.flow-step > text {
  background: #e7eee1;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  text-align: center;
  line-height: 19px;
  font-size: 10px;
  color: #7b9470;
}
.flow-step view text {
  display: block;
  font-size: 10px;
  color: #7e9275;
  line-height: 1.9;
}
.flow-step view text + text {
  font-size: 8px;
  color: #a5b19d;
}
.bottom-note {
  font-size: 10px;
  color: #a1b097;
  text-align: center;
}
.sheet-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 7px 0;
}
.input {
  background: #f2f6ee;
  font-size: 13px;
  height: 43px;
  padding: 0 12px;
  border-radius: 10px;
}
.instructions-input {
  background: #f2f6ee;
  font-size: 12px;
  line-height: 1.8;
  height: 75px;
  box-sizing: border-box;
  padding: 12px;
  border-radius: 10px;
  width: 100%;
}
.switch-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.switch-line view text {
  display: block;
  color: #748b6b;
  font-size: 12px;
}
.switch-line view text + text {
  color: #a1af98;
  font-size: 10px;
  margin-top: 6px;
}
.optional {
  color: #b1bcab;
  font-size: 10px;
}
</style>
