<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="知衡知识库" subtitle="有来源，有适用范围，也有边界" back /><view
      class="page-stack"
    >
      <view class="library-hero"
        ><view
          ><text class="eyebrow">A LITTLE UNDERSTANDING</text
          ><text class="library-title">了解一点， 把日常过得明白一点。</text></view
        ><view class="library-art"
          ><view class="book-spine" /><view class="book-face"
            ><AppIcon name="leaf" :size="38" /></view></view
      ></view>
      <view class="search-field"
        ><AppIcon name="search" :size="18" /><input
          v-model="query"
          placeholder="搜索作息、活动、记录方法…"
          confirm-type="search"
          :maxlength="100"
          @confirm="load()"
        /><text class="search-action" @tap="load()">搜索</text></view
      >
      <scroll-view scroll-x class="category-scroll"
        ><view class="category-tabs"
          ><view
            v-for="option in categories"
            :key="option.code"
            :class="{ selected: category === option.code }"
            @tap="selectCategory(option.code)"
            >{{ option.label }}</view
          ></view
        ></scroll-view
      >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后阅读已审核发布的知识与来源。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView
        v-else-if="loading && !items.length"
        kind="loading"
        title="正在寻找内容"
      /><StateView
        v-else-if="error"
        kind="error"
        title="知识库暂时无法读取"
        :description="error"
        action-text="重试"
        @action="load()"
      /><StateView
        v-else-if="!items.length"
        kind="empty"
        title="暂时没有匹配条目"
        description="可以换一个关键词，或切换分类再看看。真实知识库仅展示已审核发布的内容。"
      />
      <view
        v-for="(article, index) in authenticated ? items : []"
        :key="article.id"
        class="article-card"
        @tap="open(article)"
        ><view class="article-art" :class="artClass(article.category)"
          ><AppIcon :name="categoryIcon(article.category)" :size="30" /><text class="article-no">{{
            String(index + 1).padStart(2, '0')
          }}</text></view
        ><view class="article-content"
          ><view class="row between"
            ><text class="article-category">{{ categoryLabel(article.category) }}</text
            ><text class="verified-dot">已审核发布</text></view
          ><text class="article-title">{{ article.title }}</text
          ><text class="article-summary">{{ article.summary }}</text
          ><view class="article-bottom"
            ><text>{{ article.tags?.slice(0, 2).join(' / ') || '日常知识' }}</text
            ><AppIcon name="arrow-right" :size="16" /></view></view
      ></view>
      <AppButton
        v-if="authenticated && cursor"
        block
        variant="secondary"
        :loading="loading"
        @click="load(true)"
        >加载更多条目</AppButton
      >
      <view class="knowledge-boundary"
        ><AppIcon name="shield" :size="18" /><text
          >条目会标明适用人群、内容日期与来源。这里的内容用于日常知识学习，不能替代专业诊疗。</text
        ></view
      >
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getArticles, type KnowledgeArticle, type KnowledgeCategory } from '@/api/knowledge'
import { session } from '@/stores/session'
import { navigate } from '@/stores/ui'
const authenticated = computed(() => !!session.tokens)
const query = ref(''),
  category = ref(''),
  cursor = ref<string | null>(null),
  items = ref<KnowledgeArticle[]>([]),
  loading = ref(false),
  error = ref('')
const categories = [
  { code: '', label: '全部' },
  { code: 'ROUTINE', label: '作息' },
  { code: 'ACTIVITY', label: '活动' },
  { code: 'MEAL_HABIT', label: '饮食习惯' },
  { code: 'RECORDING', label: '记录方法' },
  { code: 'APP_HELP', label: '使用知衡' },
]
function categoryLabel(code: string) {
  return categories.find((item) => item.code === code)?.label || '日常知识'
}
function categoryIcon(code: string) {
  return (
    {
      ROUTINE: 'moon',
      ACTIVITY: 'activity',
      MEAL_HABIT: 'utensils',
      RECORDING: 'edit',
      APP_HELP: 'book',
    }[code] || 'leaf'
  )
}
function artClass(code: string) {
  return (
    {
      ROUTINE: 'lilac',
      ACTIVITY: 'mint',
      MEAL_HABIT: 'sand',
      RECORDING: 'blue',
      APP_HELP: 'cream',
    }[code] || 'mint'
  )
}
function selectCategory(code: string) {
  category.value = code
  void load()
}
function open(article: KnowledgeArticle) {
  navigate(`/pages/knowledge/detail?id=${article.id}`)
}
async function load(more = false) {
  if (!authenticated.value) {
    items.value = []
    cursor.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    const page = await getArticles({
      q: query.value.trim() || undefined,
      category: category.value ? (category.value as KnowledgeCategory) : undefined,
      cursor: more ? cursor.value || undefined : undefined,
    })
    items.value = more ? [...items.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch (err) {
    error.value = err instanceof Error ? err.message : '无法读取知识条目'
  } finally {
    loading.value = false
  }
}
onShow(() => load())
</script>
<style scoped>
.library-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px;
  background: #eaf0e3;
  border: 1px solid #e0e7d4;
  border-radius: 14px;
  overflow: hidden;
}
.library-title {
  font-size: 22px;
  white-space: pre-line;
  line-height: 1.65;
  font-weight: 600;
  color: var(--ink);
  display: block;
  margin-top: 16px;
}
.library-art {
  position: relative;
  width: 63px;
  height: 90px;
  flex-shrink: 0;
  margin-left: 8px;
  transform: rotate(9deg);
  margin-top: 22px;
}
.book-spine {
  position: absolute;
  width: 8px;
  height: 88px;
  top: 3px;
  left: -6px;
  background: #a4b78b;
  border-radius: 3px 0 0 3px;
}
.book-face {
  position: relative;
  height: 90px;
  width: 63px;
  border: 1px solid #b9c8a4;
  background: #d3dfc1;
  border-radius: 3px 10px 10px 3px;
  box-shadow: 4px 6px 0 #c8d3b566;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #93a977;
}
.search-field {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface);
  border: 1px solid #e2e8da;
  border-radius: 12px;
  padding: 13px;
  color: var(--muted);
}
.search-field input {
  flex: 1;
  font-size: 12px;
  min-width: 0;
}
.search-action {
  color: #6c8558;
  font-size: 11px;
  border-left: 1px solid #e6eadf;
  padding-left: 11px;
}
.category-scroll {
  width: 100%;
  white-space: nowrap;
}
.category-tabs {
  display: flex;
  gap: 8px;
}
.category-tabs > view {
  font-size: 11px;
  flex-shrink: 0;
  padding: 9px 13px;
  border: 1px solid #e1e7d9;
  border-radius: 10px;
  color: #9aa78b;
  background: var(--surface);
}
.category-tabs > view.selected {
  background: #1b6554;
  color: #fff;
  border-color: #1b6554;
}
.article-card {
  display: flex;
  gap: 16px;
  padding: 17px;
  background: var(--surface);
  border: 1px solid #e4eadb;
  border-radius: 14px;
}
.article-art {
  width: 69px;
  min-height: 110px;
  flex-shrink: 0;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  background: #e8f0df;
  color: #94ad75;
}
.article-art.lilac {
  background: #eeeaf3;
  color: #aca0bf;
}
.article-art.sand {
  background: #f3ebd9;
  color: #c2aa6f;
}
.article-art.blue {
  background: #e8eef0;
  color: #94adb4;
}
.article-art.cream {
  background: #f2eee4;
  color: #bdac84;
}
.article-no {
  position: absolute;
  left: 10px;
  bottom: 9px;
  font-size: 9px;
  opacity: 0.7;
}
.article-content {
  flex: 1;
  min-width: 0;
}
.article-category {
  font-size: 9px;
  color: #a2b092;
}
.verified-dot {
  font-size: 8px;
  color: #a5b397;
}
.article-title {
  display: block;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.65;
  color: var(--ink);
  margin: 9px 0;
}
.article-summary {
  display: block;
  font-size: 10px;
  line-height: 1.8;
  color: var(--muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.article-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 8px;
  color: #a7b599;
  margin-top: 13px;
}
.knowledge-boundary {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: #a5af96;
  font-size: 10px;
  line-height: 1.9;
  padding: 5px 2px;
}
</style>
