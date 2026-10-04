import { http, unwrap } from './client'
import type { LoginResponse, UserView } from './types'

export function login(username: string, password: string): Promise<LoginResponse> {
  return unwrap<LoginResponse>(http.post('/auth/login', { username, password }))
}

/**
 * 用当前令牌换回用户信息。
 *
 * <p>后端在未登录时返回 data: null 而不是 401，所以这里要允许 null ——
 * 刷新页面后"还没登录"是完全正常的状态，不该被当成错误处理。
 */
export function fetchMe(): Promise<UserView | null> {
  return unwrap<UserView | null>(http.get('/auth/me'))
}

export function logout(): Promise<void> {
  return unwrap<void>(http.post('/auth/logout'))
}

export function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  return unwrap<void>(http.post('/auth/password', { currentPassword, newPassword }))
}
