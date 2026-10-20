import { createSSRApp } from 'vue'
import App from './App.vue'
import { ui } from './stores/ui'

export function createApp() {
  const app = createSSRApp(App)
  Object.defineProperty(app.config.globalProperties, '$appTheme', { get: () => ui.theme })
  Object.defineProperty(app.config.globalProperties, '$reduceMotion', {
    get: () => ui.reduceMotion,
  })
  return { app }
}
