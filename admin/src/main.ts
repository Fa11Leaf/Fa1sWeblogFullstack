import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import { onUnauthorized } from './api/client'
import { router } from './router'
import './styles/tokens.css'

const app = createApp(App)

// pinia 必须在 router 之前注册：路由守卫里会用 useAuthStore()，
// 而 store 只有在 pinia 已经是当前活跃实例时才能创建。
app.use(createPinia())
app.use(router)

// 令牌失效时统一跳到登录页。
// 这里用回调注入而不是让 api 层直接 import router：那会形成
// api → router → views → api 的循环依赖，Vite 打包时报错很难看懂。
onUnauthorized(() => {
  void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
})

app.mount('#app')
