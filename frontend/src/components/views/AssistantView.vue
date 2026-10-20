<template>
  <view class="page-stack assistant-view">
    <view class="between row">
      <view
        ><text class="eyebrow">YOUR DAILY COPILOT</text
        ><text class="page-title">把今天，聊明白</text></view
      >
      <view class="history-button" @tap="navigate('/pages/ai/history')"
        ><AppIcon name="clock" :size="21"
      /></view>
    </view>

    <view class="assistant-hero">
      <view class="hero-orbit orbit-one" /><view class="hero-orbit orbit-two" />
      <view class="hero-top"
        ><view class="assistant-mark"><AppIcon name="sparkles" color="#FFFFFF" :size="28" /></view
        ><text class="hero-badge">知衡 · 日常节奏助手</text></view
      >
      <text class="hero-title">一点点改变， 也值得认真对待。</text>
      <text class="hero-copy">帮你整理记录、拆解计划， 找到适合自己的生活节奏。</text>
      <view class="hero-bottom"
        ><view class="small-dot" /><text>你的确认，始终是最后一步</text></view
      >
    </view>

    <view class="scene-grid">
      <view
        v-for="scene in scenes"
        :key="scene.kind"
        class="scene-card"
        @tap="openScene(scene.kind)"
      >
        <view class="scene-icon" :class="scene.color"
          ><AppIcon :name="scene.icon" :size="21"
        /></view>
        <text class="scene-title">{{ scene.title }}</text
        ><text class="scene-copy">{{ scene.copy }}</text
        ><AppIcon class="scene-arrow" name="arrow-right" :size="16" />
      </view>
    </view>

    <StateView
      v-if="!authenticated"
      kind="auth"
      title="登录后继续"
      description="登录后，与知衡讨论你的记录和计划。"
      action-text="登录或注册"
      @action="navigate('/pages/auth/index')"
    />
    <view v-if="authenticated && !canChat" class="notice-card"
      ><AppIcon name="shield" :size="19" /><view
        ><text class="notice-title">{{
          consentGranted ? '智能对话尚未启用' : '先由你决定，是否使用 AI'
        }}</text
        ><text class="muted">{{
          consentGranted
            ? '服务端需要开启功能并配置模型。记录和计划仍可手动使用。'
            : 'AI 会处理你主动发送的内容。可在「我的 → 隐私与授权」中查看并管理。'
        }}</text
        ><text v-if="!consentGranted" class="subtle-link" @tap="navigate('/pages/profile/consents')"
          >查看 AI 处理授权 →</text
        ></view
      ></view
    >

    <view class="section-heading"
      ><text>和知衡聊聊</text><text class="subtle-link" @tap="newChat">新对话</text></view
    >
    <view class="chat-space">
      <view class="message-row bot"
        ><view class="avatar"><AppIcon name="sparkles" :size="17" /></view
        ><view class="bubble"
          ><text>今天有什么想调整的小事？ 你可以说「最近晚睡，帮我理一理晚上怎么安排」。</text
          ><text class="message-meta">知衡助手</text></view
        ></view
      >
      <view
        v-for="message in messages"
        :key="message.id"
        class="message-row"
        :class="message.role === 'USER' ? 'mine' : 'bot'"
      >
        <view v-if="message.role !== 'USER'" class="avatar"
          ><AppIcon name="sparkles" :size="17"
        /></view>
        <view class="bubble"
          ><text>{{ message.content || '正在准备回复…' }}</text>
          <view
            v-if="message.draftId"
            class="draft-entry"
            @tap="navigate(`/pages/ai/draft?id=${message.draftId}`)"
            ><AppIcon name="edit" :size="18" /><view
              ><text>有一份草稿等待核对</text
              ><text class="message-meta">查看字段与来源，再决定是否保存</text></view
            ><AppIcon name="arrow-right" :size="16"
          /></view>
          <view
            v-for="citation in message.citations"
            :key="citation.id"
            class="citation"
            @tap="
              navigate(
                `/pages/knowledge/detail?id=${citation.articleId}&versionId=${citation.articleVersionId}`,
              )
            "
            >{{ citation.label }} {{ citation.sourceTitle
            }}{{ citation.unavailable ? ' · 来源待核对' : '' }}</view
          >
          <text class="message-meta">{{ formatTime(message.createdAt) }}</text>
        </view>
      </view>
      <view v-if="run && !isTerminal(run.status)" class="run-inline"
        ><view class="pulse-dot" /><text>{{
          run.status === 'CANCEL_REQUESTED'
            ? '正在等待取消确认'
            : run.status === 'QUEUED'
              ? '请求已排队，正在等待处理'
              : '正在整理你的问题'
        }}</text
        ><text class="subtle-link" @tap="stopRun">取消</text></view
      >
      <view v-if="run?.status === 'FAILED'" class="inline-error"
        ><text>{{ run.error?.message || '生成失败，未保存为记录或计划。' }}</text
        ><text v-if="run.retryable" class="subtle-link" @tap="retry">重试</text></view
      >
      <view v-if="chatError" class="inline-error"
        ><text>{{ chatError }}</text
        ><text class="subtle-link" @tap="recoverChat">重新读取</text></view
      >
    </view>

    <view v-if="messages.length === 0" class="prompt-list"
      ><view v-for="prompt in prompts" :key="prompt" class="prompt-chip" @tap="messageText = prompt"
        >{{ prompt }}<AppIcon name="plus" :size="14" /></view
    ></view>
    <view class="composer">
      <textarea
        v-model="messageText"
        class="chat-input"
        placeholder="说说你的想法…"
        :maxlength="4000"
        auto-height
        :disabled="busy" /><button
        class="send-button"
        :disabled="busy || !messageText.trim() || !canChat"
        @tap="send"
      >
        <AppIcon name="send" color="#FFFFFF" :size="20" /></button
    ></view>
    <text class="assistant-footnote"
      >记录和计划建议都需要核对确认。知衡帮助管理日常生活，不提供诊断或治疗建议。</text
    >
    <view class="knowledge-link" @tap="navigate('/pages/knowledge/index')"
      ><view class="knowledge-icon"><AppIcon name="book" :size="22" /></view
      ><view
        ><text class="scene-title">有依据，才更安心</text
        ><text class="scene-copy">阅读带来源与适用范围的知识条目</text></view
      ><AppIcon name="arrow-right" :size="18"
    /></view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  cancelRun,
  createConversation,
  getConversation,
  getMessages,
  getRun,
  retryRun,
  sendMessage,
  type MessageView,
  type RunView,
} from '@/api/ai'
import { session } from '@/stores/session'
import { featureEnabled, refreshSession } from '@/stores/app'
import { navigate } from '@/stores/ui'
import { formatTime, uuid } from '@/utils/time'
import { showError, toast } from '@/utils/feedback'

const props = defineProps<{ conversationId?: string }>()
const authenticated = computed(() => !!session.tokens)
const consentGranted = computed(
  () =>
    session.me?.consents?.some(
      (item) => item.purpose === 'AI_PROCESSING' && item.status === 'GRANTED',
    ) ?? false,
)
const canChat = computed(
  () => authenticated.value && featureEnabled('AI_CHAT') && consentGranted.value,
)
const messages = ref<MessageView[]>([])
const messageText = ref('')
const currentConversation = ref(props.conversationId || '')
const run = ref<RunView | null>(null)
const busy = ref(false)
const chatError = ref('')
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const prompts = ['最近总是晚睡，如何开始调整？', '怎样让每天的计划更容易坚持？']
const scenes = [
  {
    kind: 'RECORD_EXTRACT',
    title: '说一句，记下来',
    copy: '从自然语言整理记录草稿',
    icon: 'edit',
    color: 'mint',
  },
  {
    kind: 'PLAN_GENERATE',
    title: '帮我安排一周',
    copy: '围绕目标生成可核对计划',
    icon: 'calendar',
    color: 'sand',
  },
  {
    kind: 'PLAN_ADJUST',
    title: '今天有点变化',
    copy: '选择任务，重新调整节奏',
    icon: 'refresh',
    color: 'blue',
  },
  {
    kind: 'WEEKLY_REVIEW',
    title: '一起回看这一周',
    copy: '从记录出发，发现小变化',
    icon: 'chart',
    color: 'lilac',
  },
]

function isTerminal(status: string) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)
}
function openScene(kind: string) {
  navigate(kind === 'WEEKLY_REVIEW' ? '/pages/reviews/index' : `/pages/ai/create?kind=${kind}`)
}
async function readMessages() {
  if (!currentConversation.value || !authenticated.value) return
  let cursor: string | undefined
  const all: MessageView[] = []
  for (let i = 0; i < 10; i++) {
    const page = await getMessages(currentConversation.value, cursor)
    all.push(...page.items)
    if (!page.hasMore || !page.nextCursor) break
    cursor = page.nextCursor
  }
  messages.value = all
}
async function recoverChat() {
  if (!authenticated.value) return
  chatError.value = ''
  try {
    await readMessages()
  } catch (error) {
    chatError.value = error instanceof Error ? error.message : '无法读取对话，请重试'
  }
}
async function poll() {
  if (!run.value || disposed || !authenticated.value) return
  try {
    run.value = await getRun(run.value.id)
    if (isTerminal(run.value.status)) {
      busy.value = false
      await readMessages()
      return
    }
    pollTimer = setTimeout(poll, 1800)
  } catch (error) {
    busy.value = false
    chatError.value = error instanceof Error ? error.message : '状态读取中断，请重新读取'
  }
}
async function send() {
  const text = messageText.value.trim()
  if (!text || busy.value || !canChat.value) return
  chatError.value = ''
  busy.value = true
  try {
    if (!currentConversation.value)
      currentConversation.value = (await createConversation(text.slice(0, 25))).id
    const accepted = await sendMessage(currentConversation.value, text, uuid())
    messageText.value = ''
    await readMessages()
    run.value = await getRun(accepted.runId)
    await poll()
  } catch (error) {
    busy.value = false
    showError(error)
  }
}
async function stopRun() {
  if (!run.value || !authenticated.value) return
  try {
    await cancelRun(run.value.id, run.value.rowVersion)
    await poll()
  } catch (error) {
    showError(error)
  }
}
async function retry() {
  if (!run.value || !authenticated.value) return
  try {
    const result = await retryRun(run.value.id, run.value.rowVersion)
    run.value = await getRun(result.runId)
    busy.value = true
    await poll()
  } catch (error) {
    showError(error)
  }
}
async function newChat() {
  if (busy.value) {
    toast('请先等待当前请求结束或取消')
    return
  }
  currentConversation.value = ''
  messages.value = []
  run.value = null
  chatError.value = ''
}
async function initialize() {
  disposed = false
  if (!authenticated.value) return
  try {
    await refreshSession()
    if (!props.conversationId) return
    await getConversation(props.conversationId)
    await readMessages()
    const pending = [...messages.value].reverse().find((item) => item.runId && !item.final)
    if (pending?.runId) {
      run.value = await getRun(pending.runId)
      busy.value = !isTerminal(run.value.status)
      if (busy.value) await poll()
    }
  } catch (error) {
    chatError.value = error instanceof Error ? error.message : '无法读取助手服务，请重试'
    showError(error)
  }
}
watch(
  () => [session.tokens?.userId, session.apiBaseUrl],
  () => {
    clearTimeout(pollTimer)
    currentConversation.value = props.conversationId || ''
    messages.value = []
    run.value = null
    busy.value = false
    void initialize()
  },
)
onMounted(initialize)
onUnmounted(() => {
  disposed = true
  clearTimeout(pollTimer)
})
</script>

<style scoped lang="scss">
.page-title {
  display: block;
  margin-top: 7px;
}
.history-button {
  width: 42px;
  height: 42px;
  border: 1px solid #e1e7e2;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface);
}
.assistant-hero {
  position: relative;
  overflow: hidden;
  border-radius: 14px;
  padding: 23px;
  background: #1c5c4e;
  color: #fff;
}
.hero-orbit {
  position: absolute;
  width: 180px;
  height: 180px;
  border: 1px solid #b3d8bc36;
  border-radius: 50%;
  right: -65px;
  top: 24px;
}
.orbit-two {
  width: 250px;
  height: 250px;
  right: -100px;
  top: -12px;
}
.hero-top,
.hero-bottom {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
}
.assistant-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  background: #ffffff19;
  border-radius: 14px;
}
.hero-badge {
  font-size: 12px;
  color: #d5e7d6;
  letter-spacing: 1px;
}
.hero-title {
  position: relative;
  display: block;
  white-space: pre-line;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.45;
  margin: 20px 0 10px;
  letter-spacing: 0.7px;
}
.hero-copy {
  display: block;
  color: #d5e3dc;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-line;
}
.hero-bottom {
  font-size: 10px;
  color: #c0dacb;
  margin-top: 22px;
}
.small-dot {
  width: 5px;
  height: 5px;
  background: #d5dc9b;
  border-radius: 50%;
}
.scene-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 11px;
}
.scene-card {
  padding: 16px;
  background: var(--surface);
  border: 1px solid #e6ebe5;
  border-radius: 14px;
  position: relative;
}
.scene-icon {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 13px;
  color: #316e5c;
}
.mint {
  background: #e7f0e6;
}
.sand {
  background: #f4eedf;
  color: #9b7b3b;
}
.blue {
  background: #e9f0f3;
  color: #52778a;
}
.lilac {
  background: #f0ebf3;
  color: #82708c;
}
.scene-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.scene-copy {
  display: block;
  font-size: 10px;
  color: var(--muted);
  margin-top: 6px;
  line-height: 1.6;
}
.scene-arrow {
  position: absolute;
  right: 15px;
  top: 23px;
  color: #a5b2a7;
}
.notice-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #f0efe4;
  color: #807344;
  border-radius: 14px;
  padding: 15px;
}
.notice-title {
  display: block;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 5px;
}
.notice-card .muted {
  font-size: 11px;
  line-height: 1.7;
}
.chat-space {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.message-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.mine {
  justify-content: flex-end;
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1b6554;
  background: #e6eee4;
  flex-shrink: 0;
}
.bubble {
  background: var(--surface);
  border: 1px solid #e5eae3;
  border-radius: 3px 14px 14px;
  padding: 14px;
  max-width: 82%;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink);
  white-space: pre-line;
}
.mine .bubble {
  background: #e5efe6;
  border: none;
  border-radius: 14px 3px 14px 14px;
}
.message-meta {
  display: block;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.5;
  margin-top: 7px;
}
.draft-entry {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 11px;
  background: #eef3eb;
  border-radius: 10px;
  font-size: 12px;
  margin-top: 10px;
  white-space: normal;
}
.draft-entry .message-meta {
  margin-top: 3px;
}
.citation {
  color: #397560;
  font-size: 11px;
  margin-top: 7px;
  text-decoration: underline;
}
.run-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  font-size: 11px;
  color: var(--muted);
}
.run-inline .subtle-link {
  margin-left: auto;
}
.pulse-dot {
  width: 6px;
  height: 6px;
  background: #55916a;
  border-radius: 50%;
}
.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8eee9;
  color: #a66f55;
  padding: 13px;
  border-radius: 12px;
  font-size: 12px;
}
.prompt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 36px;
}
.prompt-chip {
  padding: 10px 12px;
  border: 1px solid #e1e8dd;
  border-radius: 10px;
  font-size: 11px;
  color: var(--muted);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.composer {
  display: flex;
  align-items: flex-end;
  padding: 12px;
  border: 1px solid #dce6d9;
  background: var(--surface);
  border-radius: 14px;
  gap: 10px;
}
.chat-input {
  flex: 1;
  font-size: 13px;
  line-height: 24px;
  min-height: 32px;
  max-height: 140px;
  padding-top: 5px;
}
.send-button {
  margin: 0;
  padding: 0;
  height: 38px;
  width: 38px;
  border-radius: 12px;
  background: #1b6554;
  display: flex;
  align-items: center;
  justify-content: center;
}
.send-button[disabled] {
  background: #b9cbbf;
}
.assistant-footnote {
  font-size: 9px;
  text-align: center;
  color: var(--muted);
  line-height: 1.7;
  margin-top: -8px;
}
.knowledge-link {
  background: #eaf0e6;
  padding: 18px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.knowledge-icon {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #63815b;
}
.knowledge-link > :last-child {
  margin-left: auto;
}
</style>
