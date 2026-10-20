<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="生成进度" subtitle="你可以离开此页，稍后从对话继续查看" back /><view
      class="page-stack"
    >
      <StateView
        v-if="!authenticated"
        kind="auth"
        title="登录后继续"
        description="登录后查看本次生成进度。"
        action-text="登录或注册"
        @action="navigate('/pages/auth/index')"
      />
      <StateView v-else-if="loading && !run" kind="loading" title="正在读取状态" />
      <StateView
        v-else-if="error"
        kind="error"
        title="状态读取中断"
        :description="error"
        action-text="重新读取"
        @action="load"
      />
      <template v-if="authenticated && run"
        ><view class="run-card"
          ><view class="run-symbol" :class="{ finished: run.status === 'SUCCEEDED' }"
            ><AppIcon
              :name="
                run.status === 'SUCCEEDED' ? 'check' : run.status === 'FAILED' ? 'info' : 'sparkles'
              "
              :size="35" /></view
          ><text class="eyebrow">{{ run.kind.replace(/_/g, ' ') }}</text
          ><text class="run-title">{{ statusTitle }}</text
          ><text class="muted">{{ statusDescription }}</text
          ><view class="progress-track"><view :style="{ width: progress + '%' }" /></view
          ><view class="progress-stages"
            ><text>已提交</text><text>生成中</text><text>待核对</text></view
          ></view
        >
        <view class="card run-facts"
          ><view class="between row"
            ><text>请求编号</text><text class="muted">{{ run.id }}</text></view
          ><view class="between row"
            ><text>提交时间</text><text class="muted">{{ formatTime(run.createdAt) }}</text></view
          ><view class="between row"
            ><text>持久状态</text><text class="status-chip">{{ run.status }}</text></view
          ></view
        >
        <AppButton
          v-if="run.draftId"
          block
          icon="edit"
          @click="navigate(`/pages/ai/draft?id=${run.draftId}`)"
          >核对生成的草稿</AppButton
        >
        <AppButton
          v-if="run.weeklyReviewId && run.status === 'SUCCEEDED'"
          block
          @click="navigate(`/pages/reviews/detail?id=${run.weeklyReviewId}`)"
          >查看本周回顾</AppButton
        >
        <AppButton
          v-if="run.conversationId && run.status === 'SUCCEEDED'"
          variant="secondary"
          block
          @click="navigate(`/pages/ai/conversation?id=${run.conversationId}`)"
          >回到对话</AppButton
        >
        <AppButton v-if="run.retryable" variant="secondary" block :loading="acting" @click="retry"
          >使用原输入重新尝试</AppButton
        >
        <AppButton
          v-if="!terminal"
          variant="ghost"
          block
          :loading="acting"
          :disabled="run.cancelRequested"
          @click="cancel"
          >{{ run.cancelRequested ? '正在等待取消确认' : '取消本次生成' }}</AppButton
        >
        <text class="footnote"
          >生成完成不代表已经保存或生效。记录和计划草稿仍需你核对、校验并确认。</text
        >
      </template>
    </view></view
  >
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { cancelRun, getRun, retryRun, type RunView } from '@/api/ai'
import { navigate } from '@/stores/ui'
import { session } from '@/stores/session'
import { formatTime } from '@/utils/time'
import { showError } from '@/utils/feedback'
const id = ref(''),
  run = ref<RunView | null>(null),
  loading = ref(false),
  acting = ref(false),
  error = ref('')
const authenticated = computed(() => !!session.tokens)
let timer: ReturnType<typeof setTimeout> | undefined
let visible = true
const terminal = computed(
  () => !!run.value && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.value.status),
)
const progress = computed(() => (terminal.value ? 100 : run.value?.status === 'QUEUED' ? 18 : 64))
const statusTitle = computed(
  () =>
    ({
      QUEUED: '想法已收到',
      RUNNING: '正在认真整理',
      CANCEL_REQUESTED: '正在取消',
      SUCCEEDED: '已经准备好了',
      FAILED: '这次未能生成',
      CANCELLED: '本次生成已取消',
    })[run.value?.status || 'QUEUED'],
)
const statusDescription = computed(
  () =>
    run.value?.error?.message ||
    (run.value?.status === 'SUCCEEDED'
      ? '接下来请逐项核对结果，确认适合自己。'
      : run.value?.status === 'FAILED'
        ? '未保存为真实记录或生效计划。你可以检查配置和输入后重试。'
        : run.value?.status === 'CANCELLED'
          ? '你仍可以手动记录或安排计划。'
          : '正在结合你提供的信息处理，不需要反复提交。'),
)
async function load() {
  if (!id.value || !authenticated.value || loading.value) return
  clearTimeout(timer)
  loading.value = true
  error.value = ''
  try {
    run.value = await getRun(id.value)
    if (!terminal.value && visible) timer = setTimeout(load, 1800)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '无法读取生成状态'
  } finally {
    loading.value = false
  }
}
async function cancel() {
  if (!run.value) return
  acting.value = true
  try {
    await cancelRun(run.value.id, run.value.rowVersion)
    await load()
  } catch (err) {
    showError(err)
  } finally {
    acting.value = false
  }
}
async function retry() {
  if (!run.value) return
  acting.value = true
  try {
    const accepted = await retryRun(run.value.id, run.value.rowVersion)
    id.value = accepted.runId
    await load()
  } catch (err) {
    showError(err)
  } finally {
    acting.value = false
  }
}
onLoad((query) => {
  id.value = query?.id || ''
  void load()
})
onShow(() => {
  visible = true
  if (id.value) void load()
})
onHide(() => {
  visible = false
  clearTimeout(timer)
})
onUnload(() => {
  visible = false
  clearTimeout(timer)
})
</script>
<style scoped>
.run-card {
  padding: 35px 24px;
  background: var(--surface);
  border: 1px solid #e4eae0;
  border-radius: 14px;
  text-align: center;
}
.run-symbol {
  height: 88px;
  width: 88px;
  border-radius: 28px;
  background: #ecf2e8;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3e7859;
  margin: 0 auto 25px;
}
.run-symbol.finished {
  background: #1b6554;
  color: #fff;
}
.run-title {
  display: block;
  font-size: 26px;
  font-weight: 600;
  margin: 14px 0;
}
.run-card .muted {
  font-size: 13px;
  line-height: 1.9;
  display: block;
}
.progress-track {
  height: 5px;
  background: #edf1e8;
  border-radius: 5px;
  overflow: hidden;
  margin: 30px 0 12px;
}
.progress-track > view {
  height: 100%;
  background: #759d69;
  border-radius: 5px;
  transition: width 0.6s;
}
.progress-stages {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--muted);
}
.run-facts {
  display: flex;
  flex-direction: column;
  gap: 17px;
  font-size: 12px;
  color: #6f7b6c;
}
.status-chip {
  font-size: 9px;
  background: #f0f3eb;
  padding: 4px 7px;
  border-radius: 6px;
  color: #6b855e;
}
.footnote {
  font-size: 11px;
  color: var(--muted);
  line-height: 1.9;
  text-align: center;
}
</style>
