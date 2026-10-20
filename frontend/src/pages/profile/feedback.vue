<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="让知衡更懂你" subtitle="每个认真表达，都值得被听见" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!session.tokens"
        kind="auth"
        title="登录后提交反馈"
        description="连接真实账号，让建议有真实的回复与处理进度。"
        action-text="连接账号"
        @action="navigate('/pages/auth/index')"
      />
      <template v-else
        ><view class="card form"
          ><text class="section-heading">想对知衡说什么？</text
          ><view class="categories"
            ><text
              v-for="item in categories"
              :key="item.code"
              :class="['chip', { selected: category === item.code }]"
              @click="category = item.code"
              >{{ item.title }}</text
            ></view
          ><input
            v-model="title"
            class="input"
            maxlength="100"
            placeholder="一句话概括问题或建议"
          /><textarea
            v-model="description"
            class="input description"
            maxlength="3000"
            placeholder="发生了什么？你期待怎样的体验？请勿填写密码、验证码或其他不必要的敏感内容。"
          /><input
            v-model="contact"
            class="input"
            maxlength="254"
            placeholder="联系方式（可选）"
          /><text class="muted">本次仅提交你填写的文字，不自动附加健康记录、设备诊断或图片。</text
          ><AppButton block :loading="busy" @click="send">提交反馈</AppButton></view
        >
        <view v-if="items.length" class="section-heading">我的反馈</view
        ><view v-for="item in items" :key="item.id" class="card ticket"
          ><view class="row between"
            ><text class="ticket-title">{{ item.title }}</text
            ><text class="pill">{{ statusLabel(item.status) }}</text></view
          ><text class="muted">{{ item.description }}</text
          ><view v-if="item.reply" class="reply"
            ><text class="label">知衡的回复</text><text>{{ item.reply }}</text></view
          ><text class="date">#{{ item.id }} · {{ item.createdAt.slice(0, 10) }}</text></view
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { session } from '@/stores/session'
import { createFeedback, listFeedback } from '@/api/feedback'
import type { Feedback } from '@/api/feedback'
import { navigate } from '@/stores/ui'
import { toast, showError } from '@/utils/feedback'
const categories = [
  { code: 'SUGGESTION', title: '体验建议' },
  { code: 'BUG', title: '遇到问题' },
  { code: 'AI_QUALITY', title: 'AI 内容' },
  { code: 'SYNC', title: '数据同步' },
  { code: 'PRIVACY', title: '隐私问题' },
]
const category = ref('SUGGESTION')
const title = ref('')
const description = ref('')
const contact = ref('')
const busy = ref(false)
const items = ref<Feedback[]>([])
onShow(load)
async function load() {
  if (!session.tokens) return
  try {
    items.value = (await listFeedback()).items
  } catch (error) {
    showError(error)
  }
}
function statusLabel(code: string) {
  return (
    (
      {
        OPEN: '待处理',
        IN_PROGRESS: '处理中',
        WAITING_USER: '待你补充',
        RESOLVED: '已解决',
        CLOSED: '已关闭',
      } as Record<string, string>
    )[code] || code
  )
}
async function send() {
  if (!title.value.trim() || !description.value.trim()) return toast('请填写标题和具体内容')
  busy.value = true
  try {
    await createFeedback({
      category: category.value,
      title: title.value.trim(),
      description: description.value.trim(),
      contact: contact.value.trim() || undefined,
    })
    title.value = ''
    description.value = ''
    contact.value = ''
    await load()
    toast('反馈已提交')
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
</script>
<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.categories {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}
.categories .chip {
  background: #f1f4ef;
  color: #80917d;
}
.categories .selected {
  color: #1b6554;
  background: #e7f0e7;
  border: 1px solid #a6bfaa;
}
.description {
  width: auto;
  min-height: 140px;
  line-height: 1.9;
}
.form .muted {
  font-size: 11px;
  line-height: 1.9;
}
.ticket {
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.ticket-title {
  font-size: 16px;
  font-weight: 600;
  flex: 1;
  padding-right: 12px;
}
.ticket > .muted {
  line-height: 1.8;
  white-space: pre-wrap;
}
.reply {
  padding: 14px;
  border-radius: 12px;
  background: #eef5ed;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 13px;
  line-height: 1.8;
}
.date {
  color: #9aa492;
  font-size: 10px;
}
</style>
