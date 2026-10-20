<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="设置" subtitle="选择你的使用方式" back /><view class="page-stack">
      <view class="card form"
        ><text class="section-heading">服务连接</text
        ><text class="muted"
          >浏览器开发预览可留空，使用开发代理；手机安装版请填写可访问的服务器根地址。</text
        ><view class="field"
          ><text class="label">服务器基础地址</text
          ><input v-model="baseUrl" class="input" placeholder="http://192.168.1.10:8080" /></view
        ><AppButton block variant="secondary" @click="saveServer">保存并重新连接</AppButton></view
      >
      <view class="card form"
        ><text class="section-heading">账号管理</text
        ><AppButton v-if="!session.tokens" block @click="navigate('/pages/auth/index')"
          >登录 / 注册</AppButton
        ><template v-if="session.tokens"
          ><AppButton block variant="ghost" :loading="busy" @click="signOut(false)"
            >退出当前设备</AppButton
          ><AppButton block variant="danger" :loading="busy" @click="signOut(true)"
            >退出所有设备</AppButton
          ></template
        ></view
      >
      <view class="card form"
        ><text class="section-heading">会话安全</text
        ><text class="muted">密码和登录令牌仅用于当前会话。结束进程后需要重新登录。</text
        ><AppButton v-if="session.tokens" block variant="secondary" @click="loadSessions"
          >查看登录会话</AppButton
        ><view v-for="item in sessions" :key="item.id" class="session-row"
          ><view
            ><text class="label">{{ item.clientName || '未命名设备' }}</text
            ><text class="muted"
              >{{ item.current ? '当前会话' : item.status }} · {{ item.clientAudience }}</text
            ></view
          ><AppButton
            v-if="!item.current && item.status === 'ACTIVE'"
            size="small"
            variant="ghost"
            @click="removeSession(item)"
            >撤销</AppButton
          ></view
        ></view
      >
      <text class="muted version">知衡 App 0.1.0 · 为你的日常留一点呼吸</text>
    </view></view
  >
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { session, setApiBaseUrl, clearSession } from '@/stores/session'
import { getSessions, revokeSession, logout } from '@/api/account'
import type { SessionView } from '@/api/account'
import { navigate } from '@/stores/ui'
import { toast, showError, confirm } from '@/utils/feedback'
const baseUrl = ref(session.apiBaseUrl)
const sessions = ref<SessionView[]>([])
const busy = ref(false)
async function saveServer() {
  const url = baseUrl.value.trim().replace(/\/+$/, '')
  if (url && (!/^https?:\/\/[^\s/?#]+(?::\d+)?(?:\/[^\s?#]*)?$/.test(url) || /@/.test(url)))
    return toast('请输入有效的 HTTP 或 HTTPS 地址，不要包含账号密码')
  if (!(await confirm('切换服务连接', '将清除当前登录会话并前往登录，新服务器的数据保持独立。')))
    return
  clearSession()
  setApiBaseUrl(url)
  navigate('/pages/auth/index')
}
async function signOut(all: boolean) {
  if (
    !(await confirm(
      all ? '退出所有设备' : '退出当前设备',
      all ? '服务端将撤销此账号的全部登录会话。' : '此设备将需要重新登录。',
    ))
  )
    return
  busy.value = true
  try {
    await logout(all ? 'ALL' : 'CURRENT')
    clearSession()
    navigate('/pages/auth/index')
  } catch (error) {
    showError(error)
    if (!all) {
      clearSession()
      toast('本地已退出，服务端撤销未完成')
    }
  } finally {
    busy.value = false
  }
}
async function loadSessions() {
  try {
    sessions.value = (await getSessions()).items
  } catch (error) {
    showError(error)
  }
}
async function removeSession(item: SessionView) {
  if (!(await confirm('撤销登录', '该会话将不能继续访问账号数据。'))) return
  try {
    await revokeSession(item.id)
    await loadSessions()
    toast('会话已撤销')
  } catch (error) {
    showError(error)
  }
}
</script>
<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 17px;
}
.form .muted {
  line-height: 1.8;
  font-size: 12px;
}
.session-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #eef1ed;
  padding-top: 15px;
}
.session-row text {
  display: block;
  margin-bottom: 6px;
}
.version {
  text-align: center;
  font-size: 10px;
  padding: 10px 0;
}
</style>
