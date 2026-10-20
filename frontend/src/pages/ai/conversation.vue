<template>
  <view class="sub-page" :data-theme="$appTheme" :class="{ 'reduce-motion': $reduceMotion }"
    ><AppHeader title="对话" back /><AssistantView :conversation-id="conversationId"
  /></view>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantView from '@/components/views/AssistantView.vue'
const conversationId = ref('')
onLoad((query) => {
  conversationId.value = query?.id || ''
})
</script>
