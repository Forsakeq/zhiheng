<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="对话记录" subtitle="随时接着上次的话题" back /><view class="page-stack">
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后查看和继续已保存的对话。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在读取对话" />
      <StateView
        v-else-if="error"
        kind="error"
        title="暂时无法读取"
        :description="error"
        action-text="重试"
        @action="load()"
      />
      <StateView
        v-else-if="!items.length"
        kind="empty"
        title="从一句话开始"
        description="你和知衡的对话会按时间保存在这里。"
      />
      <view v-for="item in authenticated ? items : []" :key="item.id" class="card history-card"
        ><view class="row" @tap="navigate(`/pages/ai/conversation?id=${item.id}`)"
          ><view class="history-icon"><AppIcon name="sparkles" :size="20" /></view
          ><view class="history-main"
            ><text class="history-title">{{ item.title || '新的对话' }}</text
            ><text class="muted"
              >{{ formatDay((item.lastMessageAt || item.createdAt).slice(0, 10)) }} ·
              {{ item.status === 'ARCHIVED' ? '已归档' : sceneLabel(item.scene) }}</text
            ></view
          ><AppIcon name="arrow-right" :size="18" /></view
        ><text v-if="item.status !== 'ARCHIVED'" class="archive-link" @tap="archive(item)"
          >归档对话</text
        ></view
      >
      <AppButton
        v-if="authenticated && nextCursor"
        variant="secondary"
        :loading="loadingMore"
        @click="load(true)"
        >加载更早的对话</AppButton
      >
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { archiveConversation, getConversations, type ConversationView } from '@/api/ai'
import { session } from '@/stores/session'
import { navigate } from '@/stores/ui'
import { formatDay } from '@/utils/time'
import { confirm, showError } from '@/utils/feedback'
const authenticated = computed(() => !!session.tokens)
const items = ref<ConversationView[]>([]),
  nextCursor = ref<string | null>(null)
const loading = ref(false),
  loadingMore = ref(false),
  error = ref('')
const sceneLabel = (scene: string) =>
  ({ GENERAL: '日常交流', PLAN: '计划讨论', RECORD: '记录整理', REVIEW: '每周回顾' })[scene] ||
  '对话'
async function load(more = false) {
  if (!authenticated.value) {
    items.value = []
    nextCursor.value = null
    return
  }
  if (more) loadingMore.value = true
  else loading.value = true
  error.value = ''
  try {
    const page = await getConversations(more ? nextCursor.value || undefined : undefined)
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor || null
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取失败'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}
async function archive(item: ConversationView) {
  if (!(await confirm('归档这段对话？', '归档后仍可查看内容，将无法继续发送消息。'))) return
  try {
    await archiveConversation(item.id, item.rowVersion)
    await load()
  } catch (err) {
    showError(err)
  }
}
onShow(() => load())
</script>
<style scoped>
.intro-title {
  display: block;
  font-size: 20px;
  font-weight: 600;
  margin: 12px 0;
}
.history-icon {
  background: #edf2e8;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  color: #1b6554;
  margin-right: 13px;
}
.history-main {
  flex: 1;
}
.history-title {
  font-size: 15px;
  display: block;
  margin-bottom: 8px;
}
.history-main .muted {
  font-size: 11px;
}
.archive-link {
  display: block;
  font-size: 11px;
  color: #929c91;
  margin-top: 15px;
  text-align: right;
}
</style>
