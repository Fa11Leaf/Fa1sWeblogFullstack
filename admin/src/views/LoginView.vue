<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '../api/client'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('admin')
const password = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function submit(): Promise<void> {
  error.value = null
  busy.value = true
  try {
    await auth.signIn(username.value.trim(), password.value)

    // 登录前想去哪就送回哪；没有就直接进文章列表
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/posts'
    await router.replace(target)
  } catch (e) {
    // 口令错与后端没起来是两回事，提示要分开，否则会让人一直重试口令
    if (e instanceof ApiError && e.code === 40101) {
      error.value = '用户名或口令不正确'
    } else if (e instanceof ApiError && e.code === -1) {
      error.value = `${e.message}（后端是不是没启动？）`
    } else {
      error.value = e instanceof Error ? e.message : String(e)
    }
    password.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <form class="panel" @submit.prevent="submit">
      <h1>写作后台</h1>
      <p class="muted lede">登录后可以写文章、上传配图，并一键发布到线上站点。</p>

      <p v-if="error" class="alert">{{ error }}</p>

      <div class="field">
        <label class="label" for="username">用户名</label>
        <input id="username" v-model="username" class="input" autocomplete="username" />
      </div>

      <div class="field">
        <label class="label" for="password">口令</label>
        <input
          id="password"
          v-model="password"
          class="input"
          type="password"
          autocomplete="current-password"
        />
      </div>

      <button class="btn btn-primary submit" type="submit" :disabled="busy || !password">
        {{ busy ? '登录中…' : '登录' }}
      </button>

      <p class="hint muted">
        第一次启动时，如果 .env 里没写 ADMIN_PASSWORD，后端会在启动日志里
        打印一个一次性口令，用它登录即可。
      </p>
    </form>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
}

.panel {
  width: 100%;
  max-width: 380px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg-subtle);
  padding: 28px 26px;
}

h1 {
  margin: 0 0 6px;
  font-size: 20px;
}

.lede {
  margin: 0 0 20px;
  font-size: 13.5px;
}

.submit {
  width: 100%;
  justify-content: center;
  padding: 9px 0;
  font-size: 14px;
}

.hint {
  margin: 16px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
