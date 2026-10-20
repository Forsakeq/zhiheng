<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader
      :title="editingId ? '编辑健康记录' : '记录一下此刻'"
      subtitle="真实的感受，比完美的数字更重要"
      back
    />
    <view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后查看你的健康日常"
        description="登录账号后，即可保存和管理自己的计划与记录。"
        action-text="登录 / 注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在读取记录" />
      <StateView
        v-else-if="loadError"
        kind="error"
        title="无法编辑这条记录"
        :description="loadError"
        action-text="重新加载"
        @action="loadExisting"
      />
      <template v-else>
        <view class="type-grid"
          ><button
            v-for="(meta, type) in recordTypeMeta"
            :key="type"
            class="type-tile"
            :class="{ chosen: form.type === type }"
            :disabled="!!editingId && form.type !== type"
            @click="switchType(type)"
          >
            <AppIcon
              :name="meta.icon"
              :size="23"
              :color="form.type === type ? '#1B6554' : '#9AAA9F'"
            /><text>{{ meta.label }}</text>
          </button></view
        >
        <view class="card form-card">
          <text class="form-section">{{
            form.type === 'SLEEP' ? '睡眠区间' : form.type === 'ACTIVITY' ? '活动区间' : '发生时间'
          }}</text>
          <view class="field"
            ><text class="label">{{ intervalType ? '开始日期与时间' : '日期与时间' }}</text
            ><view class="two-col"
              ><picker
                mode="date"
                :value="form.startDate"
                @change="form.startDate = $event.detail.value"
                ><view class="picker-field"
                  >{{ form.startDate }}<AppIcon name="calendar" :size="15" /></view></picker
              ><picker
                mode="time"
                :value="form.startTime"
                @change="form.startTime = $event.detail.value"
                ><view class="picker-field"
                  >{{ form.startTime }}<AppIcon name="clock" :size="15" /></view></picker></view
          ></view>
          <view v-if="intervalType" class="field"
            ><text class="label">结束日期与时间</text
            ><view class="two-col"
              ><picker
                mode="date"
                :value="form.endDate"
                @change="form.endDate = $event.detail.value"
                ><view class="picker-field"
                  >{{ form.endDate }}<AppIcon name="calendar" :size="15" /></view></picker
              ><picker
                mode="time"
                :value="form.endTime"
                @change="form.endTime = $event.detail.value"
                ><view class="picker-field"
                  >{{ form.endTime }}<AppIcon name="clock" :size="15" /></view></picker></view
            ><text class="field-hint">{{
              form.type === 'SLEEP'
                ? '睡眠归入醒来的日期；可记录跨夜睡眠。'
                : '实际活动时长不得超过这个发生区间。'
            }}</text></view
          >
          <text class="timezone-note"
            >记录时区：{{ form.timeZone }}{{ editingId ? '（保留原记录时区）' : '' }}</text
          >
        </view>

        <view class="card form-card">
          <text class="form-section">{{ recordTypeMeta[form.type].label }}详情</text>
          <template v-if="form.type === 'SLEEP'">
            <view class="field"
              ><text class="label">睡眠类型</text
              ><view class="choice-row"
                ><button
                  class="choice"
                  :class="{ selected: form.sleepKind === 'MAIN' }"
                  @click="form.sleepKind = 'MAIN'"
                >
                  夜间主睡眠</button
                ><button
                  class="choice"
                  :class="{ selected: form.sleepKind === 'NAP' }"
                  @click="form.sleepKind = 'NAP'"
                >
                  午休 / 小睡
                </button></view
              ></view
            >
            <view class="field"
              ><text class="label">醒来后休息得怎么样？</text
              ><view class="scale-row"
                ><button
                  v-for="level in 5"
                  :key="level"
                  class="scale"
                  :class="{ selected: form.restedLevel === level }"
                  @click="form.restedLevel = level"
                >
                  {{ level }}
                </button></view
              ><view class="scale-labels"><text>没休息好</text><text>恢复充分</text></view></view
            >
            <view class="field"
              ><text class="label">中途醒来或其他感受 <text class="optional">选填</text></text
              ><textarea
                v-model="form.interruptionNote"
                class="text-area"
                placeholder="例如：夜里醒来一次，很快又睡着了"
                :maxlength="1000"
              />
            </view>
          </template>
          <template v-if="form.type === 'ACTIVITY'">
            <view class="field"
              ><text class="label">活动类型</text
              ><picker
                :range="activities"
                range-key="label"
                :value="activityIndex"
                @change="form.activityType = activities[Number($event.detail.value)].value"
                ><view class="picker-field"
                  >{{ activities[activityIndex].label
                  }}<AppIcon name="chevron-down" :size="16" /></view></picker
            ></view>
            <view class="two-col"
              ><view class="field"
                ><text class="label">实际时长（分钟）</text
                ><input
                  v-model="form.duration"
                  class="input"
                  type="number"
                  placeholder="30" /></view
              ><view class="field"
                ><text class="label">距离（米，选填）</text
                ><input
                  v-model="form.distance"
                  class="input"
                  type="digit"
                  placeholder="例如 2000" /></view
            ></view>
            <view class="field"
              ><text class="label">活动强度</text
              ><view class="choice-row"
                ><button
                  v-for="item in intensities"
                  :key="item.value"
                  class="choice"
                  :class="{ selected: form.intensity === item.value }"
                  @click="form.intensity = item.value"
                >
                  {{ item.label }}
                </button></view
              ></view
            >
            <view class="field"
              ><text class="label">活动名称 <text class="optional">选填</text></text
              ><input
                v-model="form.activityName"
                class="input"
                placeholder="例如：晚饭后的校园散步"
                :maxlength="80"
            /></view>
            <view class="field"
              ><text class="label">地点 <text class="optional">选填</text></text
              ><input
                v-model="form.location"
                class="input"
                placeholder="例如：操场"
                :maxlength="200"
            /></view>
          </template>
          <template v-if="form.type === 'MEAL'">
            <view class="field"
              ><text class="label">哪一餐</text
              ><view class="choice-row wrap"
                ><button
                  v-for="item in meals"
                  :key="item.value"
                  class="choice"
                  :class="{ selected: form.mealType === item.value }"
                  @click="form.mealType = item.value"
                >
                  {{ item.label }}
                </button></view
              ></view
            >
            <view class="field"
              ><text class="label">这一餐吃了什么</text
              ><textarea
                v-model="form.mealDescription"
                class="text-area"
                placeholder="例如：米饭、番茄炒蛋、青菜和一碗汤"
                :maxlength="2000"
              />
            </view>
            <view class="field"
              ><text class="label"
                >食物搭配 <text class="optional">选填，未选择表示未记录</text></text
              ><view class="food-grid"
                ><view v-for="food in foods" :key="food.key" class="food-row"
                  ><text>{{ food.label }}</text
                  ><view class="tri-controls"
                    ><button
                      v-for="option in foodOptions"
                      :key="option.label"
                      :class="{ selected: form[food.key] === option.value }"
                      @click="setFood(food.key, option.value)"
                    >
                      {{ option.label }}
                    </button></view
                  ></view
                ></view
              ></view
            >
            <view class="field"
              ><text class="label">饱腹感</text
              ><view class="scale-row"
                ><button
                  v-for="level in 5"
                  :key="level"
                  class="scale"
                  :class="{ selected: form.satiety === level }"
                  @click="form.satiety = level"
                >
                  {{ level }}
                </button></view
              ><view class="scale-labels"><text>仍然饿</text><text>很饱</text></view></view
            >
            <view class="field"
              ><text class="label">用餐场景</text
              ><picker
                :range="mealContexts"
                range-key="label"
                :value="mealContextIndex"
                @change="form.mealContext = mealContexts[Number($event.detail.value)].value"
                ><view class="picker-field"
                  >{{ mealContexts[mealContextIndex].label
                  }}<AppIcon name="chevron-down" :size="16" /></view></picker
            ></view>
          </template>
          <template v-if="form.type === 'ENERGY'">
            <view class="energy-illustration"
              ><AppIcon name="sun" :size="51" color="#BB9261" /><text>{{
                energyLabels[form.energyLevel - 1]
              }}</text></view
            >
            <view class="field"
              ><text class="label">此刻精力值</text
              ><view class="scale-row"
                ><button
                  v-for="level in 5"
                  :key="level"
                  class="scale energy-scale"
                  :class="{ selected: form.energyLevel === level }"
                  @click="form.energyLevel = level"
                >
                  {{ level }}
                </button></view
              ><view class="scale-labels"
                ><text>疲惫，需要休息</text><text>充沛，状态很好</text></view
              ></view
            >
            <view class="field"
              ><text class="label">你当时在做什么</text
              ><picker
                :range="energyContexts"
                range-key="label"
                :value="energyContextIndex"
                @change="form.energyContext = energyContexts[Number($event.detail.value)].value"
                ><view class="picker-field"
                  >{{ energyContexts[energyContextIndex].label
                  }}<AppIcon name="chevron-down" :size="16" /></view></picker
            ></view>
            <view class="field"
              ><text class="label">感受描述 <text class="optional">选填</text></text
              ><textarea
                v-model="form.energyDescription"
                class="text-area"
                placeholder="留意此刻的身体与情绪"
                :maxlength="1000"
              />
            </view>
          </template>
        </view>

        <view class="card form-card"
          ><text class="form-section">补充信息</text
          ><view class="field"
            ><text class="label">备注 <text class="optional">选填</text></text
            ><textarea
              v-model="form.notes"
              class="text-area"
              placeholder="还有什么想记住的？"
              :maxlength="2000"
            /></view
          ><view v-if="tags.length" class="field"
            ><text class="label">标签</text
            ><view class="tag-line"
              ><button
                v-for="tag in tags"
                :key="tag.id"
                class="tag-button"
                :class="{ chosen: selectedTags.includes(tag.id) }"
                @click="toggleTag(tag.id)"
              >
                {{ tag.name }}
              </button></view
            ></view
          ><text v-if="tagError" class="field-hint">标签加载失败，已有标签会原样保留。</text></view
        >
        <text v-if="validationError" class="error-text">{{ validationError }}</text>
        <view class="save-note"
          ><AppIcon name="shield" :size="15" color="#7A9688" /><text
            >这是你手动填写的记录，不会被当作设备测量数据。</text
          ></view
        >
        <AppButton :loading="saving" block @click="save">{{
          editingId ? '保存修改' : '保存这条记录'
        }}</AppButton>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  createRecord,
  getRecord,
  listRecordTags,
  recordTypeMeta,
  replaceRecord,
  type ExtendedRecordView,
  type RecordDetail,
  type RecordInput,
  type RecordTag,
  type RecordType,
} from '@/api/records'
import { session } from '@/stores/session'
import { back, navigate, refreshApp } from '@/stores/ui'
import { dayOffset, deviceTimeZone, localTimeToIso, today, uuid } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const editingId = ref('')
const draftClientRecordId = uuid()
let pendingFingerprint = ''
let pendingOperationId = ''
const existing = ref<ExtendedRecordView | null>(null)
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const validationError = ref('')
const tagError = ref(false)
const tags = ref<RecordTag[]>([])
const selectedTags = ref<string[]>([])
const form = reactive({
  type: 'ACTIVITY' as RecordType,
  startDate: today(),
  startTime: '18:00',
  endDate: today(),
  endTime: '18:30',
  timeZone: deviceTimeZone(),
  notes: '',
  restedLevel: 3,
  sleepKind: 'MAIN' as 'MAIN' | 'NAP',
  interruptionNote: '',
  activityType: 'WALK' as NonNullable<RecordDetail['activityType']>,
  duration: '30',
  distance: '',
  intensity: 'EASY' as NonNullable<RecordDetail['intensity']>,
  activityName: '',
  location: '',
  mealType: 'DINNER' as NonNullable<RecordDetail['mealType']>,
  mealDescription: '',
  vegetable: null as boolean | null,
  fruit: null as boolean | null,
  protein: null as boolean | null,
  wholeGrain: null as boolean | null,
  satiety: 3,
  mealContext: 'CANTEEN' as NonNullable<RecordDetail['mealContext']>,
  energyLevel: 3,
  energyContext: 'STUDY' as NonNullable<RecordDetail['contextCode']>,
  energyDescription: '',
})
const activities = [
  { label: '散步', value: 'WALK' },
  { label: '跑步', value: 'RUN' },
  { label: '骑行', value: 'CYCLING' },
  { label: '拉伸', value: 'STRETCH' },
  { label: '运动', value: 'SPORT' },
  { label: '其他', value: 'OTHER' },
] as const
const intensities = [
  { label: '轻松', value: 'EASY' },
  { label: '适中', value: 'MODERATE' },
  { label: '较高', value: 'HARD' },
] as const
const meals = [
  { label: '早餐', value: 'BREAKFAST' },
  { label: '午餐', value: 'LUNCH' },
  { label: '晚餐', value: 'DINNER' },
  { label: '加餐', value: 'SNACK' },
  { label: '其他', value: 'OTHER' },
] as const
const mealContexts = [
  { label: '食堂', value: 'CANTEEN' },
  { label: '家中', value: 'HOME' },
  { label: '外卖', value: 'TAKEAWAY' },
  { label: '餐厅', value: 'RESTAURANT' },
  { label: '其他', value: 'OTHER' },
] as const
const energyContexts = [
  { label: '刚刚起床', value: 'WAKE' },
  { label: '学习 / 工作', value: 'STUDY' },
  { label: '活动时', value: 'ACTIVITY' },
  { label: '晚间放松', value: 'EVENING' },
  { label: '其他', value: 'OTHER' },
] as const
const foods = [
  { key: 'vegetable', label: '蔬菜' },
  { key: 'fruit', label: '水果' },
  { key: 'protein', label: '蛋白质食物' },
  { key: 'wholeGrain', label: '全谷物' },
] as const
const foodOptions = [
  { label: '未记', value: null },
  { label: '有', value: true },
  { label: '无', value: false },
]
const energyLabels = ['需要休息', '有些疲惫', '平稳如常', '状态不错', '活力充沛']
const intervalType = computed(() => ['SLEEP', 'ACTIVITY'].includes(form.type))
const activityIndex = computed(() =>
  Math.max(
    0,
    activities.findIndex((item) => item.value === form.activityType),
  ),
)
const mealContextIndex = computed(() =>
  Math.max(
    0,
    mealContexts.findIndex((item) => item.value === form.mealContext),
  ),
)
const energyContextIndex = computed(() =>
  Math.max(
    0,
    energyContexts.findIndex((item) => item.value === form.energyContext),
  ),
)

function switchType(type: RecordType) {
  if (editingId.value) return
  form.type = type
  if (type === 'SLEEP') {
    form.startDate = dayOffset(-1)
    form.startTime = '23:00'
    form.endDate = today()
    form.endTime = '07:00'
  } else {
    form.startDate = today()
    form.endDate = today()
    form.startTime = '18:00'
    form.endTime = '18:30'
  }
}

function parts(iso: string, zone: string) {
  const formatted = new Intl.DateTimeFormat('sv-SE', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(iso))
  return { date: formatted.slice(0, 10), time: formatted.slice(11, 16) }
}

async function loadExisting() {
  loading.value = true
  loadError.value = ''
  try {
    const row = await getRecord(editingId.value)
    if (!row || row.deletedAt) throw new Error('这条记录不存在或已删除，请先恢复记录。')
    existing.value = row
    form.type = row.recordType
    form.timeZone = row.recordTimeZone || deviceTimeZone()
    const start = parts(row.startedAt, form.timeZone)
    const end = parts(row.endedAt || row.startedAt, form.timeZone)
    Object.assign(form, {
      startDate: start.date,
      startTime: start.time,
      endDate: end.date,
      endTime: end.time,
      notes: row.notes || '',
    })
    const detail = row.detail || { detailType: row.recordType }
    Object.assign(form, {
      restedLevel: detail.restedLevel || 3,
      sleepKind: detail.sleepKind || 'MAIN',
      interruptionNote: detail.interruptionNote || '',
      activityType: detail.activityType || 'WALK',
      duration: String(detail.actualDurationMinutes || 30),
      distance: detail.distanceMeters == null ? '' : String(detail.distanceMeters),
      intensity: detail.intensity || 'EASY',
      activityName: detail.activityName || '',
      location: detail.locationLabel || '',
      mealType: detail.mealType || 'DINNER',
      mealDescription: detail.description || '',
      vegetable: detail.vegetablePresent ?? null,
      fruit: detail.fruitPresent ?? null,
      protein: detail.proteinFoodPresent ?? null,
      wholeGrain: detail.wholeGrainPresent ?? null,
      satiety: detail.satietyLevel || 3,
      mealContext: detail.mealContext || 'CANTEEN',
      energyLevel: detail.energyLevel || 3,
      energyContext: detail.contextCode || 'STUDY',
      energyDescription: detail.description || '',
    })
    selectedTags.value = [...(row.tagIds || [])]
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '记录读取失败'
  } finally {
    loading.value = false
  }
}

function setFood(key: 'vegetable' | 'fruit' | 'protein' | 'wholeGrain', value: boolean | null) {
  form[key] = value
}
function toggleTag(id: string) {
  if (selectedTags.value.includes(id))
    selectedTags.value = selectedTags.value.filter((value) => value !== id)
  else if (selectedTags.value.length < 10) selectedTags.value.push(id)
  else toast('最多选择 10 个标签')
}

// Convert wall time in the record's own IANA zone, including when editing after travelling.
function zonedIso(date: string, time: string) {
  if (form.timeZone === deviceTimeZone()) return localTimeToIso(date, time)
  const wall = Date.parse(`${date}T${time}:00Z`)
  let instant = wall
  for (let index = 0; index < 3; index += 1) {
    const local = parts(new Date(instant).toISOString(), form.timeZone)
    const observed = Date.parse(`${local.date}T${local.time}:00Z`)
    instant += wall - observed
  }
  const resolved = parts(new Date(instant).toISOString(), form.timeZone)
  if (resolved.date !== date || resolved.time !== time)
    throw new Error('这个时间在所选时区不存在，请换一个时间。')
  return new Date(instant).toISOString()
}

function buildInput(): RecordInput {
  const startedAt = zonedIso(form.startDate, form.startTime)
  const endedAt = intervalType.value ? zonedIso(form.endDate, form.endTime) : null
  const minutes = endedAt ? (Date.parse(endedAt) - Date.parse(startedAt)) / 60000 : 0
  if (intervalType.value && (minutes < 1 || minutes > (form.type === 'SLEEP' ? 2160 : 1440)))
    throw new Error('请填写合理的开始与结束时间，结束时间须晚于开始时间。')
  let detail: RecordDetail
  if (form.type === 'SLEEP')
    detail = {
      detailType: 'SLEEP',
      restedLevel: form.restedLevel,
      sleepKind: form.sleepKind,
      interruptionNote: form.interruptionNote || undefined,
    }
  else if (form.type === 'ACTIVITY') {
    const duration = Number(form.duration)
    if (!Number.isInteger(duration) || duration < 1 || duration > minutes)
      throw new Error('实际活动时长须为正整数，且不能超过活动区间。')
    if (form.activityType === 'OTHER' && !form.activityName.trim())
      throw new Error('选择其他活动时，请填写具体的活动名称。')
    if (
      form.distance &&
      (!Number.isFinite(Number(form.distance)) ||
        Number(form.distance) < 0 ||
        Number(form.distance) > 1000000 ||
        !/^\d+(\.\d{1,2})?$/.test(form.distance))
    )
      throw new Error('距离应为 0 至 1000000 米之间的数字，最多两位小数。')
    detail = {
      detailType: 'ACTIVITY',
      activityType: form.activityType,
      actualDurationMinutes: duration,
      intensity: form.intensity,
      activityName: form.activityName || undefined,
      distanceMeters: form.distance ? Number(form.distance) : undefined,
      locationLabel: form.location || undefined,
    }
  } else if (form.type === 'MEAL') {
    if (!form.mealDescription.trim()) throw new Error('写下这一餐吃了什么，再保存吧。')
    detail = {
      detailType: 'MEAL',
      mealType: form.mealType,
      description: form.mealDescription.trim(),
      vegetablePresent: form.vegetable ?? undefined,
      fruitPresent: form.fruit ?? undefined,
      proteinFoodPresent: form.protein ?? undefined,
      wholeGrainPresent: form.wholeGrain ?? undefined,
      satietyLevel: form.satiety,
      mealContext: form.mealContext,
    }
  } else
    detail = {
      detailType: 'ENERGY',
      energyLevel: form.energyLevel,
      contextCode: form.energyContext,
      description: form.energyDescription || undefined,
    }
  const reference = form.type === 'SLEEP' && endedAt ? endedAt : startedAt
  const referenceParts = parts(reference, form.timeZone)
  const utcOffsetMinutes = Math.round(
    (Date.parse(`${referenceParts.date}T${referenceParts.time}:00Z`) - Date.parse(reference)) /
      60000,
  )
  return {
    clientRecordId: existing.value?.clientRecordId || draftClientRecordId,
    recordType: form.type,
    expectedRowVersion: existing.value?.rowVersion,
    startedAt,
    endedAt,
    recordTimeZone: form.timeZone,
    recordLocalDate: referenceParts.date,
    utcOffsetMinutes,
    notes: form.notes || undefined,
    tagIds: selectedTags.value,
    detail,
  }
}

async function save() {
  if (!session.tokens) {
    navigate('/pages/auth/index')
    return
  }
  if (saving.value) return
  validationError.value = ''
  let input: RecordInput
  try {
    input = buildInput()
  } catch (cause) {
    validationError.value = cause instanceof Error ? cause.message : '请检查填写内容'
    return
  }
  const fingerprint = JSON.stringify(input)
  if (fingerprint !== pendingFingerprint) {
    pendingFingerprint = fingerprint
    pendingOperationId = uuid()
  }
  saving.value = true
  try {
    if (editingId.value) await replaceRecord(editingId.value, input, pendingOperationId)
    else await createRecord(input, pendingOperationId)
    refreshApp()
    toast(editingId.value ? '修改已保存' : '已记下这一个日常片刻')
    back()
  } catch (cause) {
    showError(cause)
  } finally {
    saving.value = false
  }
}

onLoad((options) => {
  editingId.value = options?.id || ''
  if (options?.type && options.type in recordTypeMeta) switchType(options.type as RecordType)
})

onShow(async () => {
  if (!session.tokens) return
  if (editingId.value && !existing.value) await loadExisting()
  try {
    tags.value = (await listRecordTags()).items
    tagError.value = false
  } catch {
    tagError.value = true
  }
})
</script>

<style scoped>
.type-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.type-tile {
  background: #fff;
  border: 1px solid #e6ece7;
  border-radius: 14px;
  padding: 17px 0 14px;
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 9px;
  font-size: 12px;
  line-height: 1.4;
  color: #8b9b91;
}
.type-tile.chosen {
  background: #e8f1e7;
  border-color: #a1c3ac;
  color: #1b6554;
  font-weight: 600;
}
.type-tile[disabled] {
  opacity: 0.45;
}
.form-card {
  padding: 19px 17px;
}
.form-section {
  display: block;
  font-size: 15px;
  font-weight: 650;
  padding-bottom: 2px;
}
.field {
  margin-top: 20px;
}
.label {
  display: block;
  font-size: 12px;
  color: #647b6d;
  margin-bottom: 10px;
}
.optional {
  font-size: 10px;
  color: #a0afa5;
  margin-left: 5px;
}
.picker-field {
  border-radius: 10px;
  background: #f3f6f2;
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 43px;
  padding: 0 12px;
  font-size: 13px;
  color: #3f584a;
}
.input {
  background: #f3f6f2;
  font-size: 13px;
  border-radius: 10px;
  padding: 0 12px;
  height: 43px;
}
.text-area {
  width: 100%;
  min-height: 86px;
  height: 86px;
  padding: 12px;
  box-sizing: border-box;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  background: #f3f6f2;
}
.choice-row {
  display: flex;
  gap: 8px;
}
.wrap {
  flex-wrap: wrap;
}
.choice {
  margin: 0;
  background: #f1f5f0;
  color: #8a9b8d;
  border-radius: 9px;
  padding: 10px 14px;
  font-size: 12px;
  line-height: 1.4;
}
.selected {
  background: #1b6554 !important;
  color: #fff !important;
}
.scale-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}
.scale {
  background: #f0f4ef;
  border-radius: 10px;
  line-height: 1;
  padding: 15px 0;
  color: #8ea092;
  font-size: 18px;
  width: 100%;
}
.scale-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 9px;
  font-size: 10px;
  color: #9daaa1;
}
.field-hint,
.timezone-note {
  display: block;
  font-size: 10px;
  color: #96a69a;
  line-height: 1.7;
  margin-top: 9px;
}
.timezone-note {
  padding-top: 5px;
}
.energy-illustration {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 10px;
  padding: 25px 0 9px;
  font-size: 14px;
  color: #a17c4e;
}
.food-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.food-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #6d8475;
}
.tri-controls {
  display: flex;
  gap: 4px;
}
.tri-controls button {
  background: #f0f4ef;
  border-radius: 7px;
  color: #90a194;
  padding: 7px 9px;
  font-size: 10px;
  margin: 0;
  line-height: 1.4;
}
.tag-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag-button {
  padding: 7px 12px;
  background: #f0f4ef;
  color: #819786;
  border-radius: 25px;
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
}
.tag-button.chosen {
  background: #dcecdd;
  color: #1b6554;
}
.save-note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: #8c9e91;
  font-size: 10px;
  line-height: 1.7;
  padding: 0 3px;
}
</style>
