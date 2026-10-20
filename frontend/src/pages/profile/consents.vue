<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="每份授权，由你决定" subtitle="清楚了解，再安心使用" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="请先登录"
        action-text="去登录"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else
        ><view class="consent-intro"
          ><AppIcon name="shield" :size="29" /><text
            >授权彼此独立，随时可以撤回。撤回会停止该用途的后续处理，清除已有数据请前往「隐私与数据」。</text
          ></view
        >
        <view v-for="purpose in purposes" :key="purpose.code" class="card consent-card"
          ><view class="row between"
            ><view class="row"
              ><view class="consent-icon"><AppIcon :name="purpose.icon" :size="23" /></view
              ><text class="section-heading">{{ purpose.title }}</text></view
            ><text class="pill">{{ granted(purpose.code) ? '已授权' : '未授权' }}</text></view
          ><text class="muted description">{{ purpose.description }}</text
          ><text class="version">说明版本：{{ policy(purpose.code)?.version || '尚未发布' }}</text
          ><text
            v-if="policy(purpose.code)?.documentUrl"
            class="subtle-link"
            @click="read(purpose.code)"
            >阅读完整用途说明 ↗</text
          ><text v-else class="muted">说明文档尚未发布，暂不能授予新授权。</text
          ><AppButton
            block
            :variant="granted(purpose.code) ? 'secondary' : 'primary'"
            :loading="busy === purpose.code"
            :disabled="
              !policy(purpose.code)?.version ||
              (!granted(purpose.code) && !policy(purpose.code)?.documentUrl)
            "
            @click="toggle(purpose.code)"
            >{{ granted(purpose.code) ? '撤回此用途授权' : '阅读并确认授权' }}</AppButton
          ></view
        >
        <AppButton variant="ghost" block @click="navigate('/pages/profile/privacy')"
          >管理已有数据 →</AppButton
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import { refreshSession } from '@/stores/app'
import { getConsents, changeConsent } from '@/api/profile'
import type { Consent } from '@/api/profile'
import type { Policy } from '@/api/account'
import { navigate, refreshApp } from '@/stores/ui'
import { showError, confirm, toast } from '@/utils/feedback'
const items = ref<Consent[]>([])
const policies = ref<Policy[]>([])
const busy = ref('')
const purposes = [
  {
    code: 'AI_PROCESSING',
    title: 'AI 智能辅助',
    icon: 'sparkles',
    description:
      '允许向已配置的模型服务发送本次功能必需的输入，用于计划草稿、记录提取与对话。生成结果需要你核对确认。',
  },
  {
    code: 'NOTIFICATION',
    title: '提醒通知',
    icon: 'bell',
    description:
      '用于按你的规则安排提醒与消息。系统通知还需要手机权限和实际能力验证，登记成功不代表已送达。',
  },
  {
    code: 'EXTERNAL_HEALTH',
    title: '外部数据连接',
    icon: 'cloud',
    description:
      '用于经明确授权连接的外部数据来源。当前设备适配未启用时，授权本身不会产生连接或导入数据。',
  },
]
onShow(load)
async function load() {
  if (!session.tokens) return
  try {
    const data = await getConsents()
    items.value = data.items
    policies.value = data.currentPolicyVersions
  } catch (error) {
    showError(error)
  }
}
function policy(code: string) {
  return policies.value.find((item) => item.purpose === code)
}
function granted(code: string) {
  return items.value.some((item) => item.purpose === code && item.status === 'GRANTED')
}
function read(code: string) {
  const item = policy(code)
  if (item?.documentUrl?.startsWith('https://'))
    navigate(
      `/pages/auth/policy?url=${encodeURIComponent(item.documentUrl)}&version=${encodeURIComponent(item.version)}`,
    )
}
async function toggle(code: string) {
  const current = items.value.find((item) => item.purpose === code)
  const doc = policy(code)
  if (!doc?.version) return
  const withdraw = granted(code)
  if (!withdraw && !doc.documentUrl?.startsWith('https://')) return
  if (
    !(await confirm(
      withdraw ? '撤回这项授权' : '确认用途授权',
      withdraw
        ? '后续相应用途处理将停止。已有数据可另行申请清除。'
        : `请确认已阅读版本 ${doc.version} 的完整说明，并同意用于上述用途。`,
    ))
  )
    return
  busy.value = code
  try {
    await changeConsent(
      code,
      current?.rowVersion || '0',
      withdraw ? 'WITHDRAW' : 'GRANT',
      doc.version,
    )
    await load()
    await refreshSession()
    refreshApp()
    toast(withdraw ? '授权已撤回' : '授权已更新')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = ''
  }
}
</script>
<style scoped>
.consent-intro {
  display: flex;
  gap: 13px;
  padding: 12px 4px;
  color: #66806c;
  font-size: 12px;
  line-height: 1.9;
}
.consent-card {
  display: flex;
  flex-direction: column;
  gap: 17px;
}
.consent-icon {
  width: 40px;
  height: 40px;
  background: #edf4ee;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.description {
  line-height: 1.9;
  font-size: 13px;
}
.version {
  color: #728573;
  font-size: 11px;
}
</style>
