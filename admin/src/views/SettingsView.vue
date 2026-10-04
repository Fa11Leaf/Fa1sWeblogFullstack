<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { changePassword } from '../api/auth'
import { fetchTargetStatus } from '../api/posts'
import { ApiError } from '../api/client'
import type { TargetStatus } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()

const target = ref<TargetStatus | null>(null)

const currentPassword = ref('')
const newPassword = ref('')
const repeatPassword = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)

onMounted(async () => {
  try {
    target.value = await fetchTargetStatus()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})

async function submit(): Promise<void> {
  error.value = null
  notice.value = null

  if (newPassword.value !== repeatPassword.value) {
    error.value = '两次输入的新口令不一致'
    return
  }
  if (newPassword.value.length < 8) {
    error.value = '新口令至少 8 位'
    return
  }

  busy.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    // 后端改完口令会一次性作废所有令牌，所以这里必须重新登录 ——
    // 这不是"顺便",而是刻意的：让可能已泄漏的旧令牌立刻失效。
    auth.forget()
    await router.replace({ name: 'login' })
  } catch (e) {
    error.value =
      e instanceof ApiError && e.code === 40101
        ? '当前口令不正确'
        : e instanceof Error
          ? e.message
          : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <header class="head">
      <h1>设置</h1>
      <p class="muted lede">账号口令与发布目标。</p>
    </header>

    <p v-if="error" class="alert">{{ error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div class="grid">
      <div class="card">
        <h2>发布目标</h2>

        <template v-if="target">
          <dl class="kv">
            <dt>仓库</dt>
            <dd class="mono">{{ target.owner }}/{{ target.repo }}</dd>
            <dt>分支</dt>
            <dd class="mono">{{ target.branch }}</dd>
            <dt>文章目录</dt>
            <dd class="mono">{{ target.postsPath }}</dd>
            <dt>图片目录</dt>
            <dd class="mono">{{ target.imagesPath }}</dd>
            <dt>线上站点</dt>
            <dd class="mono">{{ target.siteUrl }}</dd>
            <dt>令牌</dt>
            <dd>
              <span class="pill" :class="target.configured ? 'pill-ok' : 'pill-warn'">
                {{ target.configured ? '已配置' : '未配置' }}
              </span>
            </dd>
          </dl>

          <p v-if="!target.configured" class="muted note">
            在项目根目录的 <code>.env</code> 里写一行
            <code>GITHUB_TOKEN=ghp_xxx</code>，然后重启后端。
            令牌需要 <code>repo</code>（classic）或 <code>Contents: Read and write</code>（fine-grained）权限。
          </p>
          <p v-else class="muted note">
            令牌只保存在后端的进程环境里，不会返回给浏览器，也不会写进任何接口响应。
          </p>
        </template>

        <p v-else class="muted">读取中…</p>
      </div>

      <div class="card">
        <h2>修改口令</h2>

        <form @submit.prevent="submit">
          <div class="field">
            <label class="label" for="cur">当前口令</label>
            <input
              id="cur"
              v-model="currentPassword"
              class="input"
              type="password"
              autocomplete="current-password"
            />
          </div>

          <div class="field">
            <label class="label" for="new">新口令（至少 8 位）</label>
            <input
              id="new"
              v-model="newPassword"
              class="input"
              type="password"
              autocomplete="new-password"
            />
          </div>

          <div class="field">
            <label class="label" for="rep">再输一次</label>
            <input
              id="rep"
              v-model="repeatPassword"
              class="input"
              type="password"
              autocomplete="new-password"
            />
          </div>

          <button
            class="btn btn-primary"
            type="submit"
            :disabled="busy || !currentPassword || !newPassword"
          >
            {{ busy ? '提交中…' : '修改口令' }}
          </button>

          <p class="muted note">
            改完会退出登录并需要重新登录一次 —— 这样所有已签发的令牌都会立刻失效。
          </p>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.head h1 {
  margin: 0 0 6px;
  font-size: 20px;
}

.lede {
  margin: 0 0 18px;
  font-size: 13.5px;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

h2 {
  margin: 0 0 14px;
  font-size: 14px;
  font-weight: 500;
}

.kv {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 7px 12px;
  margin: 0;
  font-size: 13.5px;
}

.kv dt {
  color: var(--color-text-muted);
}

.kv dd {
  margin: 0;
  word-break: break-all;
}

.note {
  margin: 14px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
}

.note code {
  font-family: var(--font-mono);
  font-size: 0.94em;
}

.notice {
  margin: 0 0 14px;
  padding: 9px 13px;
  border: 1px solid var(--color-success);
  border-radius: var(--radius-sm);
  color: var(--color-success);
  font-size: 13.5px;
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
