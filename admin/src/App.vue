<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 登录页不需要外壳（它没有导航栏可点，也不需要显示用户名）。
 * 用路由 meta 而不是判断路径字符串：将来若再加单独的页面，加一个 meta 就行。
 */
const bare = computed(() => route.meta.bare === true)

const navItems = [
  { name: 'posts', label: '文章' },
  { name: 'media', label: '媒体库' },
  { name: 'settings', label: '设置' },
  { name: 'status', label: '状态' }
]

async function handleSignOut(): Promise<void> {
  await auth.signOut()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <RouterView v-if="bare" />

  <div v-else class="shell">
    <header class="topbar">
      <div class="container topbar-inner">
        <RouterLink :to="{ name: 'posts' }" class="brand">weblog 写作后台</RouterLink>

        <nav class="nav">
          <RouterLink
            v-for="item in navItems"
            :key="item.name"
            :to="{ name: item.name }"
            class="nav-link"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="account">
          <span class="who">{{ auth.user?.displayName ?? auth.user?.username }}</span>
          <button class="btn" type="button" @click="handleSignOut">退出</button>
        </div>
      </div>
    </header>

    <main class="body">
      <div class="container">
        <RouterView />
      </div>
    </main>

    <footer class="foot">
      <div class="container">
        <p class="muted">
          文章发布后会提交到 Fa11Leaf.github.io 仓库，由 GitHub Actions 自动构建上线。
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.container {
  width: 100%;
  max-width: var(--max-width-shell);
  margin: 0 auto;
  padding: 0 var(--space);
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg);
}

.topbar-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 56px;
}

.brand {
  font-weight: 600;
  color: var(--color-text);
  text-decoration: none;
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 2px;
  flex: 1 1 auto;
}

.nav-link {
  padding: 5px 12px;
  border-radius: 999px;
  color: var(--color-text-muted);
  font-size: 14px;
  text-decoration: none;
}

.nav-link:hover {
  background: var(--color-bg-subtle);
  color: var(--color-text);
}

/* Vue Router 自动给当前路由的链接加这两个类，不必手写判断 */
.nav-link.router-link-active {
  background: var(--color-accent-soft);
  color: var(--color-accent);
  font-weight: 500;
}

.account {
  display: flex;
  align-items: center;
  gap: 10px;
}

.who {
  color: var(--color-text-muted);
  font-size: 13px;
}

.body {
  flex: 1 0 auto;
  padding: 26px 0 40px;
}

.foot {
  border-top: 1px solid var(--color-border);
  padding: 14px 0 22px;
  font-size: 12.5px;
}

.foot p {
  margin: 0;
}
</style>
