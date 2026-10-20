import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
    pool: 'threads',
    poolOptions: {
      threads: { singleThread: true },
    },
    env: {
      VITE_API_BASE_URL: '',
      VITE_PREVIEW_PLATFORM: 'ANDROID',
    },
  },
})
