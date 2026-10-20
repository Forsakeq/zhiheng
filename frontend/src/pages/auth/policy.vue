<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="服务说明" :subtitle="version ? `当前版本 ${version}` : undefined" back />
    <StateView v-if="loading" kind="loading" title="正在读取服务说明" />
    <view v-else-if="document" class="page-stack policy-content">
      <view class="card policy-document">
        <text class="document-title">{{ document.title }}</text>
        <view v-for="section in document.sections" :key="section.heading" class="document-section">
          <text class="section-heading">{{ section.heading }}</text>
          <text class="section-content">{{ section.content }}</text>
        </view>
      </view>
    </view>
    <web-view v-else-if="url" :src="url" />
    <StateView
      v-else
      kind="error"
      title="政策链接不可用"
      :description="error || '需要服务端提供可阅读的服务说明，才能确认当前版本。'"
    />
  </view>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getConfiguration, getLocalServicePolicy, LOCAL_SERVICE_POLICY_PATH } from '@/api/account'
import type { ServicePolicyDocument } from '@/api/account'
const url = ref('')
const version = ref('')
const document = ref<ServicePolicyDocument | null>(null)
const loading = ref(false)
const error = ref('')

onLoad(async (query) => {
  version.value = String(query?.version || '')
  if (query?.local === '1') {
    loading.value = true
    try {
      const configuration = await getConfiguration()
      const policy = configuration.policyVersions.find((item) => item.purpose === 'SERVICE')
      if (!configuration.localDevelopment || policy?.documentUrl !== LOCAL_SERVICE_POLICY_PATH) {
        throw new Error('当前服务未提供本地开发说明，请返回并重新获取服务配置。')
      }
      const content = await getLocalServicePolicy()
      if (content.version !== policy.version)
        throw new Error('服务说明版本已更新，请返回重新阅读。')
      document.value = content
      version.value = content.version
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '服务说明读取失败，请稍后重试。'
    } finally {
      loading.value = false
    }
    return
  }
  const candidate = String(query?.url || '')
  if (/^https:\/\/[^\s]+$/.test(candidate)) url.value = candidate
})
</script>

<style scoped>
.policy-content {
  padding-top: 8px;
}
.policy-document {
  display: flex;
  flex-direction: column;
  gap: 25px;
}
.document-title {
  font-size: 22px;
  font-weight: 600;
  color: var(--ink);
}
.document-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.section-content {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.9;
  white-space: pre-line;
}
</style>
