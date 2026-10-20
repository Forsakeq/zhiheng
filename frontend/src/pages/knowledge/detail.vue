<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="知识条目" subtitle="阅读内容，也查看它的依据" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后查看知识条目及其来源。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading" kind="loading" title="正在读取条目" /><StateView
        v-else-if="error"
        kind="error"
        title="暂时无法读取"
        :description="error"
        action-text="重试"
        @action="load"
      />
      <template v-if="authenticated && article"
        ><view class="article-heading"
          ><text class="article-category">已审核发布 · 第 {{ article.versionNo }} 版</text
          ><text class="article-title">{{ article.title }}</text
          ><text class="article-summary">{{ article.summary }}</text
          ><view class="article-meta"
            ><text>{{ article.contentDate ? formatDay(article.contentDate) : '日期未标注' }}</text
            ><text>{{ article.tags?.join(' / ') }}</text></view
          ></view
        >
        <view v-if="article.retired" class="retired-note"
          ><AppIcon name="info" :size="18" /><text>{{
            article.retiredReason || '此版本已停止当前推荐，请核对最新资料。'
          }}</text></view
        >
        <view class="scope-card"
          ><view class="row"
            ><AppIcon name="user" :size="18" /><text class="scope-label">适用范围</text></view
          ><text>{{ article.applicablePopulation }}</text></view
        >
        <view class="article-body"
          ><view
            v-for="(block, index) in blocks"
            :key="index"
            :class="['body-block', 'block-' + block.type]"
            ><text v-if="block.type === 'list'" class="list-dot">•</text
            ><text selectable>{{ block.text }}</text></view
          ></view
        >
        <view class="limitations-card"
          ><view class="row"><AppIcon name="shield" :size="19" /><text>内容边界</text></view
          ><text class="limitations-copy">{{ article.limitations }}</text></view
        >
        <view class="section-heading"
          ><text>来源与核验</text
          ><text class="eyebrow">{{ article.sources.length }} 个来源</text></view
        >
        <view v-if="!article.sources.length" class="card"
          ><text class="empty-source">此版本暂无可读取的来源信息，请谨慎核对内容。</text></view
        >
        <view v-for="source in article.sources" :key="source.id" class="card source-card"
          ><view class="row between"
            ><text class="source-publisher">{{ source.organization }}</text
            ><text
              class="source-state"
              :class="{ unverified: source.verificationStatus !== 'VERIFIED' }"
              >{{ source.verificationStatus === 'VERIFIED' ? '已核验' : '当前不可确认' }}</text
            ></view
          ><text class="source-title">{{ source.title }}</text
          ><text class="source-url" selectable>{{ source.url }}</text
          ><view class="source-dates"
            ><text>发布 {{ source.publishedOn || '未标注' }}</text
            ><text>访问核验 {{ source.accessedOn || '未标注' }}</text></view
          ><view class="source-actions"
            ><text class="subtle-link" @tap="copyUrl(source.url)">复制来源链接</text
            ><text class="subtle-link" @tap="sourceDetails(source)">查看使用依据</text></view
          ><view v-if="expandedSource === source.id" class="source-rights"
            ><text>使用依据：{{ basisLabel(source.usageBasis) }}</text
            ><text>{{ source.rightsNote }}</text
            ><text v-if="source.verificationNote"
              >核验说明：{{ source.verificationNote }}</text
            ></view
          ></view
        >
        <view class="rights-note"
          ><text>{{ article.rightsNote }}</text
          ><text>条目内容用于日常知识学习；个人情况存在差异，请结合适用范围理解。</text></view
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getArticle, getSource, type KnowledgeDetail, type KnowledgeSource } from '@/api/knowledge'
import { session } from '@/stores/session'
import { formatDay } from '@/utils/time'
import { navigate } from '@/stores/ui'
import { showError, toast } from '@/utils/feedback'
const id = ref(''),
  versionId = ref<string | undefined>(),
  article = ref<KnowledgeDetail | null>(null),
  loading = ref(false),
  error = ref(''),
  expandedSource = ref('')
const authenticated = computed(() => !!session.tokens)
const blocks = computed(() =>
  (article.value?.bodyMarkdown || '')
    .split('\n')
    .filter((line) => line.trim())
    .map((line) => {
      const text = line.trim()
      if (/^#{1,6}\s/.test(text)) return { type: 'heading', text: text.replace(/^#{1,6}\s+/, '') }
      if (/^[-*]\s/.test(text)) return { type: 'list', text: text.replace(/^[-*]\s+/, '') }
      if (text.startsWith('> ')) return { type: 'quote', text: text.slice(2) }
      return { type: 'paragraph', text: text.replace(/\*\*([^*]+)\*\*/g, '$1') }
    }),
)
function basisLabel(value: string) {
  return (
    {
      LINK_ONLY: '仅链接引用',
      PERMITTED_EXCERPT: '获准摘录',
      LICENSED: '授权使用',
      PUBLIC_DOMAIN: '公有领域',
    }[value] || value
  )
}
function copyUrl(value: string) {
  if (!/^https:\/\//i.test(value)) {
    toast('来源链接不可用')
    return
  }
  uni.setClipboardData({ data: value, success: () => toast('来源链接已复制') })
}
async function sourceDetails(source: KnowledgeSource) {
  if (expandedSource.value === source.id) {
    expandedSource.value = ''
    return
  }
  try {
    const current = await getSource(source.id)
    if (article.value)
      article.value.sources = article.value.sources.map((item) =>
        item.id === source.id ? current : item,
      )
    expandedSource.value = source.id
  } catch (err) {
    showError(err)
  }
}
async function load() {
  if (!id.value || !authenticated.value) return
  loading.value = true
  error.value = ''
  try {
    article.value = await getArticle(id.value, versionId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '无法读取条目'
  } finally {
    loading.value = false
  }
}
onLoad((query) => {
  id.value = query?.id || ''
  versionId.value = query?.versionId
  void load()
})
</script>
<style scoped>
.article-heading {
  padding: 3px 0 14px;
}
.article-category {
  font-size: 10px;
  color: #91a27c;
  letter-spacing: 0.5px;
}
.article-title {
  font-size: 27px;
  color: var(--ink);
  font-weight: 600;
  line-height: 1.55;
  display: block;
  margin: 17px 0;
}
.article-summary {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.9;
  display: block;
}
.article-meta {
  display: flex;
  gap: 18px;
  font-size: 9px;
  color: #b0b89f;
  margin-top: 17px;
}
.scope-card {
  border: 1px solid #dfe8d5;
  background: #edf3e6;
  border-radius: 12px;
  padding: 17px;
  color: #8da07b;
}
.scope-card > .row {
  gap: 8px;
  margin-bottom: 9px;
}
.scope-label {
  font-size: 12px;
  font-weight: 600;
}
.scope-card > text {
  font-size: 11px;
  line-height: 1.8;
  display: block;
}
.article-body {
  padding: 0 3px;
}
.body-block {
  font-size: 14px;
  color: var(--muted);
  line-height: 2;
  margin: 14px 0;
  letter-spacing: 0.2px;
}
.block-heading {
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.7;
  margin-top: 29px;
  margin-bottom: 15px;
}
.block-heading:first-child {
  margin-top: 9px;
}
.block-list {
  display: flex;
  gap: 10px;
  font-size: 13px;
}
.list-dot {
  color: #9db385;
}
.block-quote {
  border-left: 3px solid #cbdcbb;
  background: #eff4e9;
  padding: 12px 15px;
  font-size: 13px;
}
.limitations-card {
  padding: 18px;
  background: #f4efe1;
  border: 1px solid #ebe1c9;
  border-radius: 14px;
  color: #a59162;
}
.limitations-card > .row {
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
}
.limitations-copy {
  display: block;
  margin-top: 11px;
  line-height: 1.9;
  font-size: 11px;
}
.empty-source {
  font-size: 12px;
  line-height: 1.9;
  color: var(--muted);
}
.source-publisher {
  font-size: 10px;
  color: #a4b391;
  max-width: 70%;
}
.source-state {
  background: #ecf4e5;
  font-size: 8px;
  color: #8aa475;
  padding: 4px 7px;
  border-radius: 5px;
}
.source-state.unverified {
  background: #f6ecd8;
  color: #b39a66;
}
.source-title {
  display: block;
  font-size: 13px;
  color: #6d8459;
  line-height: 1.8;
  font-weight: 600;
  margin: 13px 0 9px;
}
.source-url {
  display: block;
  font-size: 10px;
  line-height: 1.7;
  color: #98ac82;
  word-break: break-all;
}
.source-dates {
  display: flex;
  flex-wrap: wrap;
  gap: 13px;
  font-size: 8px;
  color: #acb69b;
  margin-top: 13px;
}
.source-actions {
  display: flex;
  gap: 20px;
  border-top: 1px solid #eef2e6;
  padding-top: 14px;
  margin-top: 16px;
  font-size: 10px;
}
.source-rights {
  background: #f7f9f2;
  padding: 13px;
  border-radius: 9px;
  margin-top: 14px;
}
.source-rights > text {
  display: block;
  font-size: 10px;
  line-height: 1.9;
  color: #96a681;
  margin-bottom: 5px;
}
.rights-note {
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 9px;
  color: #b0b99e;
  line-height: 1.8;
  padding: 0 4px;
}
.retired-note {
  display: flex;
  gap: 9px;
  padding: 15px;
  background: #f7eee0;
  color: #ac9465;
  border-radius: 12px;
  font-size: 11px;
  line-height: 1.9;
}
</style>
