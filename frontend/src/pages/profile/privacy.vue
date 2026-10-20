<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="隐私与数据" subtitle="数据属于你，决定权也属于你" back /><view
      class="page-stack"
    >
      <view class="privacy-hero"
        ><AppIcon name="shield" :size="30" color="#1B6554" /><text class="hero-title"
          >安心，是看得见的选择。</text
        ><text class="muted">先查看具体范围，再验证身份。操作结果以服务端处理进度为准。</text></view
      >
      <StateView
        v-if="!session.tokens && !receiptToken"
        kind="auth"
        title="登录后管理个人数据"
        action-text="去登录"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else>
        <view v-if="session.tokens" class="card form"
          ><text class="section-heading">你想管理哪些数据？</text
          ><view class="type-grid"
            ><view
              v-for="item in types"
              :key="item.code"
              :class="[
                'type-choice',
                { selected: type === item.code, danger: item.code === 'DELETE_ACCOUNT' },
              ]"
              @click="selectType(item.code)"
              ><AppIcon :name="item.icon" :size="21" /><text>{{ item.title }}</text></view
            ></view
          ><text class="muted">{{ description }}</text>
          <template v-if="type === 'EXPORT' || type === 'DELETE_RECORDS'"
            ><view class="row between"
              ><text>限定记录日期</text
              ><switch
                :checked="useDates"
                color="#1B6554"
                @change="useDates = switchValue($event)" /></view
            ><view v-if="useDates" class="two-col"
              ><picker mode="date" :value="from" @change="from = $event.detail.value"
                ><view class="input">{{ from }}</view></picker
              ><picker mode="date" :value="to" @change="to = $event.detail.value"
                ><view class="input">{{ to }}</view></picker
              ></view
            ><view v-if="type === 'EXPORT'" class="row between"
              ><text>同时包含计划</text
              ><switch
                :checked="includePlans"
                color="#1B6554"
                @change="includePlans = switchValue($event)" /></view
            ><view class="row between"
              ><text>包含 AI 相关内容</text
              ><switch
                :checked="includeAi"
                color="#1B6554"
                @change="includeAi = switchValue($event)" /></view
            ><text class="footnote"
              >附件内容不会自动加入本次申请。日期只筛选记录，启用的其他模块可能包含该模块全部数据。</text
            ></template
          >
          <AppButton block :loading="busy" variant="secondary" icon="eye" @click="makePreview"
            >查看本次范围</AppButton
          >
        </view>
        <view v-if="active" class="card form"
          ><view class="row between"
            ><text class="section-heading">{{ typeLabel(active.type) }}</text
            ><text class="pill">{{ statusLabel(active.status) }}</text></view
          ><text class="muted"
            >请求 #{{ active.requestId }} · 数据代际 {{ active.datasetEpoch || '—' }}</text
          ><view v-if="active.scopeSummary?.moduleCounts" class="count-list"
            ><view
              v-for="(count, table) in active.scopeSummary.moduleCounts"
              :key="table"
              class="row between"
              ><text>{{ moduleLabel(String(table)) }}</text
              ><text class="count">{{ count }} 条</text></view
            ></view
          ><text v-if="active.status === 'PREVIEW_READY'" class="footnote"
            >预览有效至
            {{ displayInstant(active.previewExpiresAt) }}。期间数据变化会要求重新预览。</text
          ><template v-if="active.status === 'PREVIEW_READY' && active.manifestDigest"
            ><view class="scope-confirm" @click="scopeConfirmed = !scopeConfirmed"
              ><view :class="['check-box', { checked: scopeConfirmed }]"
                ><AppIcon v-if="scopeConfirmed" name="check" :size="13" color="#fff" /></view
              ><text>我已核对上述具体模块与数量，确认这是本次希望处理的范围。</text></view
            ><AppButton
              block
              :variant="active.type === 'EXPORT' ? 'primary' : 'danger'"
              :disabled="!scopeConfirmed"
              @click="openReauth('confirm')"
              >{{
                active.type === 'EXPORT' ? '验证身份并开始导出' : '验证身份并确认处理'
              }}</AppButton
            ></template
          >
          <view v-if="active.status === 'PREVIEW_READY'" class="row"
            ><AppButton variant="ghost" :loading="busy" @click="cancelActive"
              >取消此申请</AppButton
            ></view
          >
          <text v-if="pending" class="muted"
            >后台正在处理，页面每 3 秒查询一次。网络中断不会被当作已完成。</text
          >
          <text v-if="active.errorCode" class="error-text"
            >处理提示：{{ active.errorCode }}。请确认结果后重试或联系维护人员。</text
          >
          <AppButton
            v-if="active.type === 'EXPORT' && active.status === 'COMPLETED' && session.tokens"
            block
            icon="download"
            :loading="busy"
            @click="openReauth('download')"
            >重新验证并下载导出文件</AppButton
          >
          <AppButton v-if="pending" block variant="secondary" @click="poll(true)"
            >立即刷新进度</AppButton
          >
          <text v-if="active.type === 'DELETE_ACCOUNT' && receiptToken" class="receipt-note"
            >注销确认后登录会话将被撤销。本页通过独立回执查询进度；回执仅保留在本次页面内存，离开或结束应用后无法从本地找回。</text
          >
          <text v-if="pollError" class="error-text">{{ pollError }}</text>
        </view>
        <view v-if="history.length && session.tokens" class="card form"
          ><text class="section-heading">最近的数据申请</text
          ><view
            v-for="item in history"
            :key="item.id"
            class="history-row"
            @click="viewHistory(item)"
            ><view
              ><text class="label">{{ typeLabel(item.type) }}</text
              ><text class="muted">#{{ item.id }}</text></view
            ><text class="pill">{{ statusLabel(item.status) }}</text
            ><AppIcon name="arrow-right" :size="16" /></view
        ></view>
      </template>
    </view>
    <BottomSheet
      v-model="reauthSheet"
      title="验证是你本人"
      subtitle="密码仅用于本次验证，不会保存在本地"
    >
      <view class="form"
        ><input
          v-model="password"
          class="input"
          password
          maxlength="128"
          placeholder="请输入当前登录密码"
        /><text class="muted">{{
          action === 'download'
            ? '下载地址有效期很短，请在验证后立即保存。'
            : '确认后将开始处理，已执行的删除无法恢复。'
        }}</text
        ><AppButton
          block
          :loading="busy"
          :disabled="!password"
          :variant="action === 'confirm' && active?.type !== 'EXPORT' ? 'danger' : 'primary'"
          @click="verifyAndRun"
          >验证并继续</AppButton
        ></view
      >
    </BottomSheet>
  </view>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { session, clearSession } from '@/stores/session'
import { refreshSession } from '@/stores/app'
import {
  previewPrivacy,
  confirmPrivacy,
  getPrivacyRequest,
  listPrivacyRequests,
  privacyReceipt,
  cancelPrivacy,
  getExportDownload,
} from '@/api/privacy'
import type { PrivacyRequest, PrivacyType } from '@/api/privacy'
import { downloadPrivateFile } from '@/utils/download'
import { dayOffset, today } from '@/utils/time'
import { navigate, refreshApp } from '@/stores/ui'
import { showError, toast, confirm } from '@/utils/feedback'
const type = ref<PrivacyType>('EXPORT')
const useDates = ref(false)
const from = ref(dayOffset(-30))
const to = ref(today())
const includeAi = ref(false)
const includePlans = ref(false)
const active = ref<PrivacyRequest | null>(null)
const history = ref<PrivacyRequest[]>([])
const busy = ref(false)
const scopeConfirmed = ref(false)
const reauthSheet = ref(false)
const password = ref('')
const action = ref<'confirm' | 'download'>('confirm')
const receiptToken = ref('')
const pollError = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let polling = false
const types: { code: PrivacyType; title: string; icon: string }[] = [
  { code: 'EXPORT', title: '导出数据', icon: 'download' },
  { code: 'DELETE_RECORDS', title: '范围删除', icon: 'filter' },
  { code: 'ERASE_HEALTH_DATA', title: '清除健康数据', icon: 'trash' },
  { code: 'DELETE_ACCOUNT', title: '注销账号', icon: 'lock' },
]
const pending = computed(
  () => !!active.value && ['QUEUED', 'PROCESSING', 'RETRY_WAIT'].includes(active.value.status),
)
const description = computed(
  () =>
    ({
      EXPORT: '导出你选择的模块，保留一份自己的记录。',
      DELETE_RECORDS: '删除所选记录和依赖内容。计划执行历史将保留，相关记录链接会清除。',
      ERASE_HEALTH_DATA: '清除健康记录、计划、AI 内容及相关数据，保留账号及基本偏好。',
      DELETE_ACCOUNT: '注销账号并清除账号相关数据，确认后全部登录会话立即撤销。',
    })[type.value],
)
watch(reauthSheet, (value) => {
  if (!value) password.value = ''
})
watch([type, useDates, from, to, includeAi, includePlans], () => {
  scopeConfirmed.value = false
  if (active.value?.status === 'PREVIEW_READY') {
    active.value = null
    receiptToken.value = ''
  }
})
onShow(async () => {
  if (session.tokens) {
    try {
      history.value = (await listPrivacyRequests()).items
    } catch (error) {
      showError(error)
    }
  }
  if (pending.value) startPolling()
})
onHide(() => {
  stopPolling()
  password.value = ''
  reauthSheet.value = false
})
onUnload(() => {
  stopPolling()
  password.value = ''
  receiptToken.value = ''
  active.value = null
})
function selectType(value: PrivacyType) {
  type.value = value
  scopeConfirmed.value = false
}
function typeLabel(value: string) {
  return types.find((item) => item.code === value)?.title || value
}
function statusLabel(value: string) {
  return (
    (
      {
        PREVIEW_READY: '等待确认',
        QUEUED: '已排队',
        PROCESSING: '处理中',
        COMPLETED: '处理完成',
        FAILED: '处理失败',
        CANCELLED: '已取消',
        EXPIRED: '已过期',
        RETRY_WAIT: '等待重试',
      } as Record<string, string>
    )[value] || value
  )
}
function displayInstant(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) + ' UTC' : '—'
}
function moduleLabel(value: string) {
  const labels: Record<string, string> = {
    health_record: '生活记录',
    sleep_record_detail: '睡眠记录详情',
    activity_record_detail: '活动记录详情',
    meal_record_detail: '饮食记录详情',
    energy_record_detail: '精力记录详情',
    record_tag: '记录标签',
    record_tag_relation: '记录分类关联',
    record_attachment: '记录附件',
    task: '计划任务',
    task_event: '任务执行历史',
    task_record_link: '任务记录关联',
    plan: '计划',
    plan_version: '计划版本',
    plan_version_task: '任务历史快照',
    plan_rule: '计划循环规则',
    ai_run: 'AI 处理任务',
    ai_message: '对话消息',
    ai_message_citation: '对话来源引用',
    ai_conversation: '对话会话',
    ai_draft: 'AI 草稿',
    ai_draft_revision: '草稿修订历史',
    weekly_review: '每周回顾',
    weekly_review_evidence: '周报数据依据',
    file_object: '文件',
    user_goal: '个人目标',
    daily_stat: '每日统计',
    reminder_rule: '提醒规则',
    reminder_delivery: '提醒处理状态',
    notification_inbox: '通知',
    user_profile: '个人档案',
    user_preference: '使用偏好',
    availability_rule: '每周时间安排',
    availability_exception: '日期例外安排',
    external_data_connection: '外部数据连接',
    external_import_batch: '外部导入批次',
  }
  return labels[value] || '其他关联内容'
}
async function makePreview() {
  if (useDates.value && from.value > to.value) return toast('结束日期不能早于开始日期')
  busy.value = true
  scopeConfirmed.value = false
  stopPolling()
  try {
    const scope: Record<string, unknown> = {}
    if (type.value === 'EXPORT' || type.value === 'DELETE_RECORDS') {
      if (useDates.value) {
        scope.from = from.value
        scope.to = to.value
      }
      scope.includeAiContent = includeAi.value
      scope.includeFiles = false
      if (type.value === 'EXPORT') scope.includePlans = includePlans.value
    }
    active.value = await previewPrivacy(type.value, scope)
    receiptToken.value = active.value.receiptToken || ''
    pollError.value = ''
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
function openReauth(next: 'confirm' | 'download') {
  action.value = next
  password.value = ''
  reauthSheet.value = true
}
async function verifyAndRun() {
  if (!active.value || !password.value || busy.value) return
  const reauth = { method: 'PASSWORD' as const, password: password.value }
  busy.value = true
  try {
    if (action.value === 'download') {
      const file = await getExportDownload(active.value.id, reauth)
      password.value = ''
      await downloadPrivateFile(file)
      toast('导出文件已保存')
    } else {
      const source = active.value
      if (!scopeConfirmed.value || !source.manifestDigest) return
      const result = await confirmPrivacy(source, reauth)
      active.value = { ...source, ...result }
      password.value = ''
      if (source.type === 'DELETE_ACCOUNT') clearSession()
      startPolling()
      toast('申请已确认，正在处理')
    }
    reauthSheet.value = false
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
    password.value = ''
  }
}
function startPolling() {
  stopPolling()
  timer = setInterval(() => {
    void poll(false)
  }, 3000)
  void poll(false)
}
function stopPolling() {
  if (timer) clearInterval(timer)
  timer = undefined
}
async function poll(explicit: boolean) {
  if (!active.value || polling) return
  polling = true
  const id = active.value.id
  try {
    const state =
      active.value.type === 'DELETE_ACCOUNT' && receiptToken.value
        ? await privacyReceipt(receiptToken.value)
        : await getPrivacyRequest(id)
    if (active.value?.id !== id) return
    active.value = { ...active.value, ...state }
    pollError.value = ''
    if (!pending.value) {
      stopPolling()
      if (session.tokens) {
        await refreshSession()
        refreshApp()
        history.value = (await listPrivacyRequests()).items
      }
    }
  } catch (error) {
    pollError.value = error instanceof Error ? error.message : '进度查询暂时失败，请重试'
    if (explicit) showError(error)
  } finally {
    polling = false
  }
}
async function cancelActive() {
  if (!active.value || !(await confirm('取消本次申请', '尚未开始的预览将取消，不会处理数据。')))
    return
  busy.value = true
  try {
    await cancelPrivacy(active.value)
    active.value = { ...active.value, status: 'CANCELLED' }
    scopeConfirmed.value = false
    toast('申请已取消')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
function viewHistory(item: PrivacyRequest) {
  stopPolling()
  active.value = item
  scopeConfirmed.value = false
  pollError.value = ''
  if (pending.value) startPolling()
}

function switchValue(event: unknown): boolean {
  return Boolean((event as { detail?: { value?: boolean } }).detail?.value)
}
</script>
<style scoped>
.privacy-hero {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 12px 5px 18px;
}
.hero-title {
  font-size: 25px;
  color: #284e3c;
  letter-spacing: -0.5px;
}
.privacy-hero .muted {
  line-height: 1.9;
  font-size: 13px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.form .muted {
  font-size: 12px;
  line-height: 1.9;
}
.type-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.type-choice {
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid #edf1ea;
  border-radius: 12px;
  padding: 17px 12px;
  font-size: 12px;
  color: #65796b;
}
.type-choice.selected {
  background: #edf5ee;
  border-color: #8fb4a0;
  color: #1b6554;
}
.type-choice.danger.selected {
  background: #fff2ed;
  border-color: #d7b1a1;
  color: #a35f48;
}
.count-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 12px;
  padding: 15px;
  background: #f5f7f3;
  border-radius: 12px;
}
.count {
  color: #1b6554;
  font-weight: 600;
}
.scope-confirm {
  display: flex;
  gap: 10px;
  font-size: 12px;
  line-height: 1.8;
}
.check-box {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border: 1px solid #adbcad;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.checked {
  background: #1b6554;
}
.footnote,
.receipt-note {
  font-size: 11px;
  color: #8a8f7c;
  line-height: 1.9;
}
.receipt-note {
  padding: 12px;
  background: #faf3e5;
  border-radius: 10px;
}
.history-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #eef1eb;
}
.history-row > view {
  flex: 1;
}
.history-row .muted {
  display: block;
  margin-top: 7px;
}
</style>
