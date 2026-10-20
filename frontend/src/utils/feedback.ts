import { ApiError } from '@/api/client'

export function toast(message: string) {
  uni.showToast({ title: message, icon: 'none', duration: 2300 })
}

export function showError(error: unknown) {
  if (error instanceof ApiError || error instanceof Error) toast(error.message)
  else toast('操作暂未完成，请稍后重试')
}

export function confirm(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText: '确认继续',
      cancelText: '再想想',
      confirmColor: '#1b6554',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}
