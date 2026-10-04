import axios from 'axios'

/**
 * 与后端通信的唯一入口。
 *
 * 后端的响应统一是 { code, message, data } 三层。这里在拦截器里把它拆平：
 * 成功时直接把 data 交给业务代码，失败时统一抛 ApiError。
 * 好处是业务代码不需要每次都写 if (res.data.code !== 0)。
 */

const TOKEN_KEY = 'weblog.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 业务错误：同时带着业务码与 HTTP 状态，前端可以据此区分"该提示"还是"该跳登录"。 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: number,
    readonly status?: number
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 令牌失效时的回调。由 main.ts 注入，避免 api 层直接依赖 router。 */
let unauthorizedHandler: (() => void) | null = null

export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data

    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new ApiError(body.message ?? `业务错误 ${body.code}`, body.code))
    }

    return body?.data ?? body
  },
  (error) => {
    const status: number | undefined = error?.response?.status
    const body = error?.response?.data
    const code: number = typeof body?.code === 'number' ? body.code : -1
    const detail: string = body?.message ?? error?.message ?? '未知错误'

    // 令牌无效或过期：清掉本地令牌并让上层跳登录页。
    // 这里不直接改 window.location —— 由注入的回调决定怎么跳，便于测试。
    if (status === 401 || code === 40101) {
      clearToken()
      unauthorizedHandler?.()
      return Promise.reject(new ApiError('登录已过期，请重新登录', 40101, status))
    }

    // 分三档说清失败原因：后端返回了业务错误 / HTTP 层失败 / 根本没连上
    const prefix = status ? `HTTP ${status}` : '无法连接后端'
    return Promise.reject(new ApiError(`${prefix}：${detail}`, code, status))
  }
)

/**
 * 把响应"拆平"之后的返回类型。
 *
 * 拦截器已经返回了 data 本身，但 axios 的类型签名仍以为返回的是 AxiosResponse，
 * 只能在这里手工纠正一次。
 */
export function unwrap<T>(promise: Promise<unknown>): Promise<T> {
  return promise as unknown as Promise<T>
}
