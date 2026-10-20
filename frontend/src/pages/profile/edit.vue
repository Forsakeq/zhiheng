<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="个人档案与偏好" subtitle="按你的节奏，慢慢来" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后管理个人档案"
        action-text="去登录"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else>
        <view class="card form"
          ><text class="section-heading">认识你</text
          ><view class="field"
            ><text class="label">昵称</text
            ><input
              v-model="nickname"
              class="input"
              maxlength="40"
              placeholder="希望知衡怎么称呼你" /></view
          ><view class="field"
            ><text class="label">个人简介</text
            ><textarea
              v-model="bio"
              class="input"
              maxlength="200"
              placeholder="给自己留一句温柔的话"
            /></view
          ><view class="field"
            ><text class="label">时区</text
            ><input v-model="timeZone" class="input" placeholder="Asia/Shanghai" /><text
              class="muted"
              >修改只影响后续安排，历史记录保留原业务日期。</text
            ></view
          ><AppButton :loading="busy" block @click="saveProfile">保存档案</AppButton></view
        >
        <view v-if="session.me" class="card form"
          ><text class="section-heading">适合你的安排</text
          ><view class="two-col"
            ><view class="field"
              ><text class="label">每日任务上限</text
              ><input v-model="dailyLimit" type="number" class="input" /></view
            ><view class="field"
              ><text class="label">默认时长 / 分钟</text
              ><input v-model="defaultMinutes" type="number" class="input" /></view></view
          ><view class="two-col"
            ><view class="field"
              ><text class="label">期望起床时间</text
              ><picker mode="time" :value="wake" @change="wake = $event.detail.value"
                ><view class="input">{{ wake || '未设置' }}</view></picker
              ></view
            ><view class="field"
              ><text class="label">期望入睡时间</text
              ><picker mode="time" :value="bed" @change="bed = $event.detail.value"
                ><view class="input">{{ bed || '未设置' }}</view></picker
              ></view
            ></view
          ><view class="row between"
            ><text>保留安静时段</text
            ><switch :checked="quiet" color="#1B6554" @change="quiet = switchValue($event)" /></view
          ><view v-if="quiet" class="two-col"
            ><picker mode="time" :value="quietStart" @change="quietStart = $event.detail.value"
              ><view class="input">{{ quietStart }}</view></picker
            ><picker mode="time" :value="quietEnd" @change="quietEnd = $event.detail.value"
              ><view class="input">{{ quietEnd }} 结束</view></picker
            ></view
          ><view class="row between"
            ><text>减少动态效果</text
            ><switch
              :checked="reduceMotion"
              color="#1B6554"
              @change="reduceMotion = switchValue($event)" /></view
          ><AppButton block variant="secondary" :loading="busy" @click="savePreferences"
            >保存偏好</AppButton
          ></view
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import { refreshSession } from '@/stores/app'
import { patchProfile, patchPreferences } from '@/api/profile'
import { navigate, refreshApp } from '@/stores/ui'
import { showError, toast } from '@/utils/feedback'
const nickname = ref('')
const bio = ref('')
const timeZone = ref('Asia/Shanghai')
const dailyLimit = ref('4')
const defaultMinutes = ref('20')
const wake = ref('07:00')
const bed = ref('23:00')
const quiet = ref(true)
const quietStart = ref('22:00')
const quietEnd = ref('07:00')
const reduceMotion = ref(false)
const busy = ref(false)
onShow(async () => {
  if (!session.tokens) return
  try {
    await refreshSession()
    const me = session.me
    if (!me) return
    nickname.value = me.profile.nickname || ''
    bio.value = me.profile.bio || ''
    timeZone.value = me.profile.timeZone
    dailyLimit.value = String(me.preferences.dailyTaskLimit)
    defaultMinutes.value = String(me.preferences.defaultTaskMinutes)
    wake.value = me.preferences.preferredWakeTime || '07:00'
    bed.value = me.preferences.preferredBedTime || '23:00'
    quiet.value = me.preferences.quietHoursEnabled
    quietStart.value = me.preferences.quietStart || '22:00'
    quietEnd.value = me.preferences.quietEnd || '07:00'
    reduceMotion.value = me.preferences.reduceMotion
  } catch (error) {
    showError(error)
  }
})
async function saveProfile() {
  if (!nickname.value.trim()) return toast('请填写昵称')
  busy.value = true
  try {
    if (!session.me) return toast('档案尚未读取成功，请返回后重试')
    await patchProfile({
      expectedRowVersion: session.me.profile.rowVersion,
      nickname: nickname.value.trim(),
      bio: bio.value.trim() || null,
      timeZone: timeZone.value,
    })
    await refreshSession()
    refreshApp()
    toast('档案已保存')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
async function savePreferences() {
  if (!session.me) return toast('偏好尚未读取成功，请返回后重试')
  const limit = Number(dailyLimit.value)
  const minutes = Number(defaultMinutes.value)
  if (
    !Number.isInteger(limit) ||
    limit < 1 ||
    limit > 12 ||
    !Number.isInteger(minutes) ||
    minutes < 1 ||
    minutes > 240
  )
    return toast('任务数为 1～12，时长为 1～240 分钟')
  busy.value = true
  try {
    await patchPreferences({
      expectedRowVersion: session.me!.preferences.rowVersion,
      dailyTaskLimit: limit,
      defaultTaskMinutes: minutes,
      preferredWakeTime: wake.value,
      preferredBedTime: bed.value,
      quietHoursEnabled: quiet.value,
      quietStart: quietStart.value,
      quietEnd: quietEnd.value,
      reduceMotion: reduceMotion.value,
    })
    await refreshSession()
    refreshApp()
    toast('偏好已保存')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
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
  gap: 20px;
}
.form textarea {
  width: auto;
  min-height: 68px;
}
.form .muted {
  display: block;
  margin-top: 9px;
  font-size: 11px;
  line-height: 1.7;
}
</style>
