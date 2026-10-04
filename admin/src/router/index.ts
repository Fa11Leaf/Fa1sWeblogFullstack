import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '../stores/auth'

/**
 * 路由表。
 *
 * 组件用动态 import（懒加载）：后台有编辑器、媒体库、设置几个互不相干的页面，
 * 一次性全打进首屏包没有意义。Vite 会按 import 自动切分 chunk。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, bare: true, title: '登录' }
  },
  { path: '/', redirect: { name: 'posts' } },
  {
    path: '/posts',
    name: 'posts',
    component: () => import('../views/PostListView.vue'),
    meta: { title: '文章' }
  },
  {
    path: '/posts/new',
    name: 'post-new',
    component: () => import('../views/PostEditView.vue'),
    meta: { title: '新文章' }
  },
  {
    path: '/posts/:id(\\d+)',
    name: 'post-edit',
    component: () => import('../views/PostEditView.vue'),
    meta: { title: '编辑文章' }
  },
  {
    path: '/media',
    name: 'media',
    component: () => import('../views/MediaLibraryView.vue'),
    meta: { title: '媒体库' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/SettingsView.vue'),
    meta: { title: '设置' }
  },
  {
    path: '/status',
    name: 'status',
    component: () => import('../views/StatusView.vue'),
    meta: { title: '连通状态' }
  },
  // 兜底：访问不存在的路径时回文章列表，而不是留一个空白页
  { path: '/:pathMatch(.*)*', redirect: { name: 'posts' } }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 登录守卫。
 *
 * 放在这里而不是每个页面里各写一遍：这样"哪些页面需要登录"这件事只有路由表一处定义
 * （看 meta.public 就知道），加新页面时不会忘。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 首次导航时先问一次后端"我是谁"。只问一次，之后 ready 为 true 就直接判断。
  if (!auth.ready) {
    await auth.restore()
  }

  if (to.meta.public) {
    // 已登录还去登录页就直接送回去，避免出现"登录后再手点后退"的怪状态
    return auth.isLoggedIn ? { name: 'posts' } : true
  }

  if (!auth.isLoggedIn) {
    // 记住原本想去哪，登录后送回去
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · weblog 写作后台` : 'weblog 写作后台'
})
