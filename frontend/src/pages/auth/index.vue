<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }">
    <AppHeader title="让生活，回到自己的节奏" subtitle="知衡 · ACCOUNT" back />
    <view class="page-stack auth-page">
      <view class="brand-mark"><AppIcon name="leaf" :size="34" color="#1B6554" /></view>
      <text class="auth-title">从今天开始， 慢慢照顾自己。</text>
      <text class="muted">连接你的账号，让每一份记录和安排都有归处。</text>
      <view class="mode-tabs">
        <text
          v-for="item in modes"
          :key="item.key"
          :class="['mode-tab', { selected: mode === item.key }]"
          @click="changeMode(item.key)"
          >{{ item.label }}</text
        >
      </view>
      <view class="card form-card">
        <view class="field"
          ><text class="label">邮箱</text
          ><input
            v-model="account"
            class="input"
            type="text"
            placeholder="you@example.com"
            maxlength="254"
        /></view>
        <view class="field"
          ><text class="label">{{ mode === 'reset' ? '新密码' : '密码' }}</text
          ><input
            v-model="password"
            class="input"
            password
            placeholder="至少 12 位字符"
            maxlength="128"
        /></view>
        <view v-if="mode !== 'login'" class="field">
          <text class="label">邮箱验证码</text>
          <view class="row"
            ><input
              v-model="code"
              class="input code-input"
              type="number"
              maxlength="6"
              placeholder="6 位验证码"
            /><AppButton
              variant="secondary"
              size="small"
              :loading="sending"
              :disabled="cooldown > 0"
              @click="sendCode"
              >{{ cooldown ? `${cooldown}s 后重发` : '获取验证码' }}</AppButton
            ></view
          >
          <text v-if="verification" class="muted">{{
            consoleDelivery
              ? '验证码已输出到后端控制台，请复制后填写。'
              : `已发送至 ${verification.maskedDestination}，请在有效期内填写。`
          }}</text>
          <text v-else-if="consoleDelivery" class="muted"
            >点击获取验证码后，请在运行 Java 后端的控制台中查看。</text
          >
        </view>
        <view v-if="mode === 'register'" class="policy-box">
          <text class="label">服务说明</text>
          <view v-if="policy" class="row between"
            ><text class="muted">版本 {{ policy.version }}</text
            ><text class="subtle-link" @click="readPolicy">阅读完整说明 ↗</text></view
          >
          <template v-else
            ><text class="error-text">{{
              configError || '服务端尚未提供可阅读的服务政策。当前无法注册。'
            }}</text
            ><text class="subtle-link" @click="loadConfiguration">重新获取服务说明</text></template
          >
          <view class="agreement" @click="accepted = !accepted"
            ><view :class="['check-box', { checked: accepted }]"
              ><AppIcon v-if="accepted" name="check" :size="13" color="#fff" /></view
            ><text>我已阅读并同意当前版本服务说明</text></view
          >
          <view class="agreement" @click="adult = !adult"
            ><view :class="['check-box', { checked: adult }]"
              ><AppIcon v-if="adult" name="check" :size="13" color="#fff" /></view
            ><text>我确认已年满 18 周岁</text></view
          >
        </view>
        <text v-if="error" class="error-text">{{ error }}</text>
        <AppButton
          block
          :loading="busy"
          :disabled="mode === 'register' && (!policy || !accepted || !adult)"
          @click="submit"
          >{{
            mode === 'login' ? '登录知衡' : mode === 'register' ? '创建账号' : '重置密码'
          }}</AppButton
        >
      </view>
      <view class="card connection"
        ><AppIcon name="cloud" :size="20" /><view
          ><text class="label">连接真实服务</text
          ><text class="muted server">{{
            session.apiBaseUrl || '使用当前开发环境的默认连接'
          }}</text></view
        ><text class="subtle-link" @click="navigate('/pages/profile/settings')">更改</text></view
      >
      <text class="footnote"
        >账号令牌仅保存在本次会话内存中。验证码、密码和健康原文不会写入明文缓存。</text
      >
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, onUnmounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  getConfiguration,
  login,
  register,
  resetPassword,
  sendVerification,
  LOCAL_SERVICE_POLICY_PATH,
} from '@/api/account'
import type { Policy, Verification } from '@/api/account'
import { session, applyTokens, clearSession } from '@/stores/session'
import { refreshSession } from '@/stores/app'
import { navigate, goTab } from '@/stores/ui'
import { toast } from '@/utils/feedback'

type Mode = 'login' | 'register' | 'reset'
const modes: { key: Mode; label: string }[] = [
  { key: 'login', label: '登录' },
  { key: 'register', label: '注册' },
  { key: 'reset', label: '找回密码' },
]
const mode = ref<Mode>('login')
const account = ref('')
const password = ref('')
const code = ref('')
const accepted = ref(false)
const adult = ref(false)
const busy = ref(false)
const sending = ref(false)
const error = ref('')
const configError = ref('')
const policies = ref<Policy[]>([])
const verification = ref<Verification | null>(null)
const sentAccount = ref('')
const cooldown = ref(0)
const localDevelopment = ref(false)
const consoleDelivery = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
const policy = computed(() =>
  policies.value.find(
    (item) =>
      item.purpose === 'SERVICE' &&
      item.version &&
      (item.documentUrl?.startsWith('https://') ||
        (localDevelopment.value && item.documentUrl === LOCAL_SERVICE_POLICY_PATH)),
  ),
)

onShow(loadConfiguration)
async function loadConfiguration() {
  const oldVersion = policy.value?.version
  configError.value = ''
  try {
    const configuration = await getConfiguration()
    session.configuration = configuration
    policies.value = configuration.policyVersions as Policy[]
    localDevelopment.value = configuration.localDevelopment === true
    consoleDelivery.value =
      localDevelopment.value && configuration.authentication?.verificationDelivery === 'CONSOLE'
    if (oldVersion !== policy.value?.version) accepted.value = false
  } catch (cause) {
    configError.value = message(cause)
  }
}
onUnmounted(() => {
  if (timer) clearInterval(timer)
  password.value = ''
  code.value = ''
})
function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '连接未完成，请检查服务器地址后重试。'
}
function changeMode(next: Mode) {
  mode.value = next
  error.value = ''
  verification.value = null
  code.value = ''
  password.value = ''
}
async function sendCode() {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(account.value.trim())) {
    error.value = '请填写有效邮箱。'
    return
  }
  sending.value = true
  error.value = ''
  try {
    verification.value = await sendVerification(
      account.value,
      mode.value === 'reset' ? 'RESET_PASSWORD' : 'REGISTER',
    )
    sentAccount.value = account.value.trim()
    cooldown.value = verification.value.resendAfterSeconds
    if (timer) clearInterval(timer)
    timer = setInterval(() => {
      cooldown.value = Math.max(0, cooldown.value - 1)
      if (!cooldown.value && timer) clearInterval(timer)
    }, 1000)
  } catch (cause) {
    error.value = message(cause)
  } finally {
    sending.value = false
  }
}
function readPolicy() {
  if (policy.value?.documentUrl === LOCAL_SERVICE_POLICY_PATH && localDevelopment.value) {
    navigate(`/pages/auth/policy?local=1&version=${encodeURIComponent(policy.value.version)}`)
  } else if (policy.value?.documentUrl)
    navigate(
      `/pages/auth/policy?url=${encodeURIComponent(policy.value.documentUrl)}&version=${encodeURIComponent(policy.value.version)}`,
    )
}
async function submit() {
  if (!account.value.trim() || !password.value) {
    error.value = '请填写邮箱和密码。'
    return
  }
  if (
    mode.value !== 'login' &&
    (password.value.length < 12 ||
      !/^\d{6}$/.test(code.value) ||
      !verification.value ||
      sentAccount.value !== account.value.trim())
  ) {
    error.value = '请使用当前邮箱的验证码，密码至少 12 位。'
    return
  }
  if (mode.value === 'register' && (!policy.value || !accepted.value || !adult.value)) return
  busy.value = true
  error.value = ''
  try {
    clearSession()
    if (mode.value === 'reset') {
      await resetPassword(
        account.value.trim(),
        password.value,
        verification.value!.verificationId,
        code.value,
      )
      changeMode('login')
      toast('密码已重置，请重新登录')
      return
    }
    const tokens =
      mode.value === 'login'
        ? await login(account.value, password.value)
        : await register({
            account: account.value,
            password: password.value,
            verificationId: verification.value!.verificationId,
            verificationCode: code.value,
            policyVersion: policy.value!.version,
          })
    applyTokens(tokens)
    password.value = ''
    code.value = ''
    await refreshSession()
    goTab('today')
  } catch (cause) {
    error.value = message(cause)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.auth-page {
  padding-top: 12px;
  gap: 20px;
}
.brand-mark {
  width: 68px;
  height: 68px;
  border-radius: 22px;
  background: #e6efe9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.auth-title {
  font-size: 30px;
  line-height: 1.4;
  font-weight: 600;
  white-space: pre-line;
  color: #173f35;
  letter-spacing: -1px;
}
.mode-tabs {
  display: flex;
  padding: 5px;
  background: #e9eeea;
  border-radius: 14px;
}
.mode-tab {
  flex: 1;
  text-align: center;
  padding: 11px 0;
  color: #7b887e;
  font-size: 14px;
}
.mode-tab.selected {
  background: white;
  color: #1b6554;
  border-radius: 10px;
  box-shadow: 0 3px 10px #123f3510;
  font-weight: 600;
}
.form-card {
  display: flex;
  flex-direction: column;
  gap: 19px;
}
.code-input {
  flex: 1;
  min-width: 0;
}
.policy-box {
  display: flex;
  flex-direction: column;
  gap: 13px;
  padding: 14px;
  background: #f5f7f3;
  border-radius: 10px;
}
.agreement {
  display: flex;
  gap: 9px;
  font-size: 12px;
  line-height: 1.7;
  align-items: flex-start;
}
.check-box {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
  border: 1px solid #becbc2;
  border-radius: 5px;
  margin-top: 1px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.checked {
  background: #1b6554;
  border-color: #1b6554;
}
.connection {
  display: flex;
  gap: 13px;
  align-items: center;
}
.connection > view {
  flex: 1;
  min-width: 0;
}
.server {
  display: block;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 5px;
}
.footnote {
  text-align: center;
  font-size: 11px;
  color: #879289;
  line-height: 1.8;
  padding: 0 16px 24px;
}
</style>
