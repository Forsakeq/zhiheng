<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { watch } from 'vue'
import { session } from '@/stores/session'
import { ui } from '@/stores/ui'

onLaunch(() => {
  uni.getNetworkType({
    success: (result) => {
      ui.online = result.networkType !== 'none'
    },
  })
  uni.onNetworkStatusChange((result) => {
    ui.online = result.isConnected
  })
})

watch(
  () => session.me?.preferences,
  (preferences) => {
    if (preferences) {
      ui.theme = preferences.theme
      ui.reduceMotion = preferences.reduceMotion
    }
  },
)
</script>

<style lang="scss">
@import './styles/app.scss';
</style>
