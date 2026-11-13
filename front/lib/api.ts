/**
 * 后端 API 根地址 —— 全站唯一读取入口。
 *
 * 取值优先级：
 *   1. NEXT_PUBLIC_API_BASE —— 临时覆盖用（NEXT_PUBLIC_ 前缀是 Next.js 注入浏览器端的要求）
 *   2. API_BASE_URL         —— next.config.ts 从 front/server.config.js 的 api.baseUrl 注入
 *   3. 开发环境访问当前主机 9529 端口；生产环境使用同源相对路径
 *
 * 云部署由 HTTPS 反向代理统一提供前端和 API，避免浏览器访问 localhost。
 */
export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE || process.env.API_BASE_URL ||
  (process.env.NODE_ENV === 'development' && typeof window !== 'undefined'
    ? `${window.location.protocol}//${window.location.hostname}:9529`
    : '')

/** 拼接后端接口地址，path 以 / 开头，例如 apiUrl('/api/boss/config') */
export function apiUrl(path: string): string {
  return `${API_BASE}${path}`
}

export async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(input instanceof Request ? input.headers : undefined)
  new Headers(init.headers).forEach((value, key) => headers.set(key, value))
  headers.set('X-Requested-With', 'GetJobs')
  const response = await fetch(input, { ...init, headers, credentials: 'include', cache: 'no-store' })
  if (response.status === 401 && typeof window !== 'undefined') {
    window.dispatchEvent(new Event('getjobs:unauthorized'))
  }
  return response
}
