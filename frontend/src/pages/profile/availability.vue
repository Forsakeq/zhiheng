<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="为自己留出时间" subtitle="计划应当适应你的生活" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后管理可用时间"
        action-text="连接账号"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else
        ><view class="card form"
          ><text class="section-heading">每周重复的时间</text
          ><text class="muted"
            >忙碌、可用和保留休息时段会参与计划校验。跨午夜需明确选择次日结束。</text
          ><view v-for="item in rules" :key="item.id" class="time-row"
            ><view
              ><text class="label"
                >周{{ weekdays[item.weekday - 1] }} · {{ kinds[item.kind] }}
                {{ item.label || '' }}</text
              ><text class="muted"
                >{{ item.startTime.slice(0, 5) }}—{{ item.endTime.slice(0, 5)
                }}{{ item.endDayOffset ? '（次日）' : '' }} · {{ item.timeZone }}</text
              ></view
            ><AppButton size="small" variant="ghost" @click="removeRule(item)"
              >移除</AppButton
            ></view
          ><AppButton block variant="secondary" icon="plus" @click="sheet = true"
            >添加重复时段</AppButton
          ></view
        >
        <view class="card form"
          ><view class="row between"
            ><text class="section-heading">这一天，另作安排</text
            ><picker mode="date" :value="exceptionDate" @change="changeDate($event.detail.value)"
              ><text class="subtle-link">{{ exceptionDate }} ▾</text></picker
            ></view
          ><text class="muted"
            >日期例外可以替换当天重复安排，或在原安排上追加。空白替换表示当天不继承重复约束。</text
          ><picker
            :range="['替换当天安排', '追加到当天安排']"
            :value="exceptionMode === 'REPLACE_DAY' ? 0 : 1"
            @change="exceptionMode = Number($event.detail.value) === 0 ? 'REPLACE_DAY' : 'APPEND'"
            ><view class="input">{{
              exceptionMode === 'REPLACE_DAY' ? '替换当天安排' : '追加到当天安排'
            }}</view></picker
          ><view v-for="(slot, index) in slots" :key="index" class="slot-editor"
            ><view class="row between"
              ><picker
                :range="kindOptions"
                range-key="label"
                @change="slot.kind = kindOptions[Number($event.detail.value)].value"
                ><text class="label">{{ kinds[slot.kind] }} ▾</text></picker
              ><text class="subtle-link" @click="slots.splice(index, 1)">移除此段</text></view
            ><view class="two-col"
              ><picker
                mode="time"
                :value="slot.startTime"
                @change="slot.startTime = $event.detail.value"
                ><view class="input">{{ slot.startTime }}</view></picker
              ><picker
                mode="time"
                :value="slot.endTime"
                @change="slot.endTime = $event.detail.value"
                ><view class="input">{{ slot.endTime }}</view></picker
              ></view
            ><view class="row between"
              ><text class="muted">次日结束</text
              ><switch
                :checked="!!slot.endDayOffset"
                @change="slot.endDayOffset = switchValue($event) ? 1 : 0"
                color="#1B6554" /></view></view
          ><AppButton
            v-if="slots.length < 24"
            block
            variant="ghost"
            icon="plus"
            @click="
              slots.push({ kind: 'BUSY', startTime: '09:00', endTime: '10:00', endDayOffset: 0 })
            "
            >添加例外时段</AppButton
          ><AppButton block :loading="busy" @click="saveException">保存这一天的安排</AppButton
          ><AppButton v-if="currentException" block variant="ghost" @click="removeException"
            >恢复当天重复安排</AppButton
          ></view
        >
      </template>
    </view>
    <BottomSheet v-model="sheet" title="添加每周时段"
      ><view class="form"
        ><view class="two-col"
          ><picker
            :range="weekdays.map((day) => `周${day}`)"
            :value="weekday - 1"
            @change="weekday = Number($event.detail.value) + 1"
            ><view class="input">周{{ weekdays[weekday - 1] }}</view></picker
          ><picker
            :range="kindOptions"
            range-key="label"
            @change="kind = kindOptions[Number($event.detail.value)].value"
            ><view class="input">{{ kinds[kind] }}</view></picker
          ></view
        ><view class="two-col"
          ><picker mode="time" :value="start" @change="start = $event.detail.value"
            ><view class="input">{{ start }}</view></picker
          ><picker mode="time" :value="end" @change="end = $event.detail.value"
            ><view class="input">{{ end }}</view></picker
          ></view
        ><view class="row between"
          ><text>次日结束</text
          ><switch
            :checked="nextDay"
            color="#1B6554"
            @change="nextDay = switchValue($event)" /></view
        ><input
          v-model="label"
          class="input"
          placeholder="例如：上课、学习或留给自己的时间"
          maxlength="80"
        /><AppButton block :loading="busy" @click="saveRule">保存重复时段</AppButton></view
      ></BottomSheet
    >
  </view>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import {
  getAvailability,
  createAvailability,
  deleteAvailability,
  getExceptions,
  putException,
  deleteException,
} from '@/api/profile'
import type { Availability, DayException, ExceptionSlot } from '@/api/profile'
import { today, deviceTimeZone } from '@/utils/time'
import { navigate, refreshApp } from '@/stores/ui'
import { toast, showError, confirm } from '@/utils/feedback'
const rules = ref<Availability[]>([])
const currentException = ref<DayException | null>(null)
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const kinds: Record<string, string> = { BUSY: '忙碌', AVAILABLE: '可用', REST: '保留休息' }
const kindOptions = [
  { value: 'BUSY', label: '忙碌' },
  { value: 'AVAILABLE', label: '可用' },
  { value: 'REST', label: '保留休息' },
]
const sheet = ref(false)
const busy = ref(false)
const weekday = ref(1)
const kind = ref('BUSY')
const start = ref('09:00')
const end = ref('10:00')
const nextDay = ref(false)
const label = ref('')
const exceptionDate = ref(today())
const exceptionMode = ref('REPLACE_DAY')
const slots = ref<ExceptionSlot[]>([])
onShow(load)
async function load() {
  if (!session.tokens) return
  try {
    rules.value = (await getAvailability()).items
    await loadException()
  } catch (error) {
    showError(error)
  }
}
async function loadException() {
  currentException.value =
    (await getExceptions(exceptionDate.value, exceptionDate.value)).items[0] || null
  exceptionMode.value = currentException.value?.mode || 'REPLACE_DAY'
  slots.value = currentException.value?.slots.map((slot) => ({ ...slot })) || []
}
async function changeDate(date: string) {
  exceptionDate.value = date
  try {
    await loadException()
  } catch (error) {
    showError(error)
  }
}
function zone() {
  return session.me?.profile.timeZone || deviceTimeZone()
}
async function saveRule() {
  if (!nextDay.value && start.value >= end.value)
    return toast('结束时间应晚于开始，跨午夜请选次日结束')
  busy.value = true
  try {
    await createAvailability({
      weekday: weekday.value,
      kind: kind.value,
      startTime: start.value,
      endTime: end.value,
      endDayOffset: nextDay.value ? 1 : 0,
      label: label.value.trim() || undefined,
      timeZone: zone(),
      effectiveFrom: today(),
    })
    sheet.value = false
    await load()
    refreshApp()
    toast('重复时段已保存')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
async function removeRule(item: Availability) {
  if (!(await confirm('移除这个时段', '后续计划将不再使用此约束，现有计划不会自动改写。'))) return
  try {
    await deleteAvailability(item.id, item.rowVersion)
    await load()
    refreshApp()
  } catch (error) {
    showError(error)
  }
}
async function saveException() {
  if (slots.value.some((slot) => !slot.endDayOffset && slot.startTime >= slot.endTime))
    return toast('请检查每段时间，跨午夜需选次日结束')
  busy.value = true
  try {
    await putException(exceptionDate.value, {
      expectedRowVersion: currentException.value?.rowVersion || '0',
      timeZone: zone(),
      mode: exceptionMode.value,
      slots: slots.value,
    })
    await loadException()
    refreshApp()
    toast('日期安排已保存')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
async function removeException() {
  if (
    !currentException.value ||
    !(await confirm('恢复重复安排', '将删除当前日期例外，重新使用每周设置。'))
  )
    return
  try {
    await deleteException(exceptionDate.value, currentException.value.rowVersion)
    await loadException()
    refreshApp()
  } catch (error) {
    showError(error)
  }
}

function switchValue(event: unknown): boolean {
  return Boolean((event as { detail?: { value?: boolean } }).detail?.value)
}
</script>
<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.form .muted {
  font-size: 12px;
  line-height: 1.9;
}
.time-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid #edf1ec;
}
.time-row text {
  display: block;
  margin-bottom: 7px;
}
.slot-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  background: #f5f7f3;
  border-radius: 12px;
}
</style>
