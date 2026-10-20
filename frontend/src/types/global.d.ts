import 'vue'

declare module 'vue' {
  interface ComponentCustomProperties {
    readonly $appTheme: 'LIGHT' | 'DARK' | 'SYSTEM'
    readonly $reduceMotion: boolean
  }
}
