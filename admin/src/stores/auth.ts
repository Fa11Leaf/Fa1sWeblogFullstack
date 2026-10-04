import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '../api/auth'
import { clearToken, getToken, setToken } from '../api/client'
import type { UserView } from '../api/types'

/**
 * 登录态。
 *
 * 令牌存在 localStorage，用户信息存在内存里 —— 刷新页面后要重新问一次后端。
 * 之所以不把用户信息也缓存进 localStorage：那样页面会先显示一个"看起来已登录"
 * 的界面，等接口返回 401 才跳登录，闪一下很难看。宁可多等一次 /auth/me。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserView | null>(null)
  /** 是否已经问过后端"我是谁"。路由守卫据此决定要不要等。 */
  const ready = ref(false)

  const isLoggedIn = computed(() => user.value !== null)

  /** 页面加载时恢复登录态。 */
  async function restore(): Promise<void> {
    if (!getToken()) {
      ready.value = true
      return
    }
    try {
      user.value = await authApi.fetchMe()
    } catch {
      // 网络不通或后端没起来：当作未登录处理，而不是把错误抛到界面上。
      // 用户此时真正需要的是一条"无法连接后端"的提示，而不是一个红色报错。
      user.value = null
    } finally {
      ready.value = true
    }
  }

  async function signIn(username: string, password: string): Promise<void> {
    const result = await authApi.login(username, password)
    setToken(result.token)
    user.value = result.user
    ready.value = true
  }

  async function signOut(): Promise<void> {
    try {
      // 后端会把这条令牌从数据库里删掉 —— 这正是用服务端令牌而不是 JWT 的好处
      await authApi.logout()
    } finally {
      forget()
    }
  }

  /** 只清本地状态。令牌已失效（401）时用这个，不必再调一次必然失败的登出接口。 */
  function forget(): void {
    clearToken()
    user.value = null
  }

  return { user, ready, isLoggedIn, restore, signIn, signOut, forget }
})
