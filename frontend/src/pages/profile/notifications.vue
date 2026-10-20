<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="恰好的一声提醒" subtitle="不打扰，也不遗漏" back /><view class="page-stack">
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后管理提醒和消息"
        description="提醒意图、系统注册与实际送达是不同状态。"
        action-text="连接账号"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else
        ><view class="card form"
          ><view class="row between"
            ><text class="section-heading">我的提醒规则</text
            ><AppButton variant="ghost" size="small" icon="plus" @click="sheet = true"
              >添加</AppButton
            ></view
          ><text class="muted"
            >当前页面创建应用内提醒。手机系统通知的权限与平台能力尚需验证，不会显示为已经送达。</text
          ><view v-for="rule in rules" :key="rule.id" class="rule-row"
            ><view
              ><text class="label">{{
                rule.scope === 'DAILY_RECORD' ? '每日记录提醒' : '任务开始提醒'
              }}</text
              ><text class="muted"
                >{{ rule.localTime?.slice(0, 5) || '随任务时间' }} ·
                {{ rule.channel === 'IN_APP' ? '应用内' : '系统提醒意图' }}</text
              ><text class="subtle-link" @click="removeRule(rule)">删除规则</text></view
            ><switch :checked="rule.enabled" color="#1B6554" @change="toggle(rule)" /></view
          ><text v-if="!rules.length" class="muted"
            >还没有提醒规则，可先从每天的一声轻提醒开始。</text
          ></view
        >
        <view class="row between"
          ><text class="section-heading">消息通知</text
          ><text v-if="unread.length" class="subtle-link" @click="readAll">全部标为已读</text></view
        >
        <view
          v-for="item in messages"
          :key="item.id"
          :class="['card message', { unread: !item.readAt }]"
          @click="readOne(item)"
          ><view class="row between"
            ><view class="row"
              ><view v-if="!item.readAt" class="dot" /><text class="message-title">{{
                item.title
              }}</text></view
            ><AppIcon name="bell" :size="18" /></view
          ><text class="muted">{{ item.body }}</text
          ><view class="row between"
            ><text class="date">{{ item.createdAt.slice(0, 16).replace('T', ' ') }}</text
            ><text class="subtle-link" @click.stop="removeMessage(item)">移除</text></view
          ><text v-if="!item.readAt" class="read-hint">轻触标为已读</text></view
        >
        <StateView
          v-if="!messages.length"
          kind="empty"
          title="暂时没有新消息"
          description="安静的日常，也是一种很好的状态。"
        />
      </template> </view
    ><BottomSheet v-model="sheet" title="给自己一个温柔提醒" subtitle="应用内提醒，需要授权通知用途"
      ><view class="form"
        ><text class="label">每天提醒我记录生活的时间</text
        ><picker mode="time" :value="localTime" @change="localTime = $event.detail.value"
          ><view class="input">{{ localTime }}</view></picker
        ><text class="muted"
          >按当前档案时区计算，尊重安静时段。应用内消息需要后端运行提醒作业。</text
        ><AppButton block :loading="busy" @click="addRule">保存提醒</AppButton
        ><AppButton block variant="ghost" @click="navigate('/pages/profile/consents')"
          >查看通知用途授权</AppButton
        ></view
      ></BottomSheet
    ></view
  >
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import {
  getReminders,
  createReminder,
  toggleReminder,
  deleteReminder,
  getNotifications,
  readNotifications,
  deleteNotification,
} from '@/api/reminders'
import type { Reminder, Notification } from '@/api/reminders'
import { deviceTimeZone } from '@/utils/time'
import { navigate, refreshApp } from '@/stores/ui'
import { toast, showError, confirm } from '@/utils/feedback'
const rules = ref<Reminder[]>([])
const messages = ref<Notification[]>([])
const unread = computed(() => messages.value.filter((item) => !item.readAt))
const sheet = ref(false)
const localTime = ref('20:30')
const busy = ref(false)
onShow(load)
async function load() {
  if (!session.tokens) return
  try {
    const [a, b] = await Promise.all([getReminders(), getNotifications()])
    rules.value = a.items
    messages.value = b.items
  } catch (error) {
    showError(error)
  }
}
async function addRule() {
  busy.value = true
  try {
    await createReminder({
      scope: 'DAILY_RECORD',
      enabled: true,
      channel: 'IN_APP',
      localTime: localTime.value,
      timeZone: session.me?.profile.timeZone || deviceTimeZone(),
      quietPolicy: 'SUPPRESS',
      installationId: session.tokens!.installationId,
    })
    sheet.value = false
    await load()
    toast('应用内提醒规则已保存')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
async function toggle(item: Reminder) {
  try {
    await toggleReminder(item)
    await load()
    refreshApp()
  } catch (error) {
    showError(error)
    await load()
  }
}
async function removeRule(item: Reminder) {
  if (!(await confirm('删除提醒规则', '后续将不再使用此规则，已注册提醒会进入撤销流程。'))) return
  try {
    await deleteReminder(item)
    await load()
  } catch (error) {
    showError(error)
  }
}
async function readOne(item: Notification) {
  if (item.readAt) return
  try {
    await readNotifications([item])
    await load()
  } catch (error) {
    showError(error)
  }
}
async function readAll() {
  if (!unread.value.length) return
  try {
    await readNotifications(unread.value)
    await load()
    toast('当前消息已标为已读')
  } catch (error) {
    showError(error)
  }
}
async function removeMessage(item: Notification) {
  if (!(await confirm('移除这条消息', '只移除通知，不会改变对应任务或计划。'))) return
  try {
    await deleteNotification(item)
    await load()
  } catch (error) {
    showError(error)
  }
}
</script>
<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.form .muted {
  line-height: 1.9;
  font-size: 12px;
}
.rule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #edf1ea;
  padding: 13px 0;
}
.rule-row text {
  display: block;
  margin: 7px 0;
}
.rule-row .subtle-link {
  font-size: 11px;
}
.message {
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.message.unread {
  border-left: 3px solid #7aab87;
}
.message-title {
  font-size: 15px;
  font-weight: 600;
}
.message .muted {
  font-size: 13px;
  line-height: 1.9;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #1b6554;
}
.date {
  color: #98a18f;
  font-size: 10px;
}
.read-hint {
  font-size: 10px;
  color: #91a591;
}
</style>
