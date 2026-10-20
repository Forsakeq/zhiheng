const pad = (value: number) => String(value).padStart(2, '0')

export function today(): string {
  const date = new Date()
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function dayOffset(amount: number, from = today()): string {
  const date = new Date(`${from}T12:00:00`)
  date.setDate(date.getDate() + amount)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function localTimeToIso(date: string, time: string): string {
  const value = new Date(`${date}T${time.length === 5 ? `${time}:00` : time}`)
  if (!Number.isFinite(value.getTime())) throw new Error('请填写有效的日期和时间')
  // A nonexistent DST local time must not silently roll into another time.
  if (`${pad(value.getHours())}:${pad(value.getMinutes())}` !== time.slice(0, 5)) {
    throw new Error('该本地时间不存在，请重新选择')
  }
  return value.toISOString()
}

export function deviceTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'
  } catch {
    return 'Asia/Shanghai'
  }
}

export function formatTime(value?: string | null): string {
  if (!value) return '--:--'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '--:--'
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatDay(value: string): string {
  const date = new Date(`${value.slice(0, 10)}T12:00:00`)
  if (!Number.isFinite(date.getTime())) return value
  if (value.slice(0, 10) === today()) return '今天'
  if (value.slice(0, 10) === dayOffset(-1)) return '昨天'
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

export function formatDuration(value: number): string {
  const minutes = Math.max(0, Math.round(value || 0))
  if (minutes < 60) return `${minutes} 分钟`
  return `${Math.floor(minutes / 60)} 小时${minutes % 60 ? ` ${minutes % 60} 分钟` : ''}`
}

export function weekDays(anchor = today()) {
  const date = new Date(`${anchor}T12:00:00`)
  const weekday = date.getDay() || 7
  const first = dayOffset(1 - weekday, anchor)
  return ['一', '二', '三', '四', '五', '六', '日'].map((label, index) => {
    const day = dayOffset(index, first)
    return { date: day, day: day.slice(8), label, isToday: day === today() }
  })
}

export function uuid(): string {
  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.random() * 256
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
