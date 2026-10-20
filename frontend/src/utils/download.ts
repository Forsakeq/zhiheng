import type { DownloadView } from '@/api/types'

export async function downloadPrivateFile(file: DownloadView): Promise<void> {
  if (!/^https?:\/\//.test(file.url)) throw new Error('文件地址不完整，请重新获取下载链接')
  // #ifdef H5
  const response = await fetch(file.url, { credentials: 'omit', cache: 'no-store' })
  if (!response.ok) throw new Error('下载链接已失效，请重新认证后下载')
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = file.fileName || '知衡数据导出'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
  return
  // #endif
  // #ifndef H5
  await new Promise<void>((resolve, reject) => {
    uni.downloadFile({
      url: file.url,
      success(result) {
        if (result.statusCode !== 200) {
          reject(new Error('文件暂时无法下载，请重新获取链接'))
          return
        }
        uni.saveFile({
          tempFilePath: result.tempFilePath,
          success() {
            uni.showToast({ title: '导出文件已保存到应用文件区', icon: 'none' })
            resolve()
          },
          fail() {
            reject(new Error('下载成功，但保存未完成，请重试'))
          },
        })
      },
      fail() {
        reject(new Error('网络异常，文件下载未完成'))
      },
    })
  })
  // #endif
}
