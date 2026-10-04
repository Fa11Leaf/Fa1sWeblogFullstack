import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    // 必须显式指定 host。Vite 默认绑 localhost，在 Windows 上它可能只解析到
    // IPv6 的 ::1，于是只监听 [::1]:5174 —— 而启动脚本用 127.0.0.1 探活，
    // 结果就是「服务明明起来了却一直连不上」，白等到超时。
    host: '127.0.0.1',
    port: 5174,
    // 端口被占用时直接失败，而不是悄悄换到 5175 ——
    // 否则 CORS 白名单里的 5174 会对不上，排查起来很费时间。
    strictPort: true,

    proxy: {
      // 开发期把 /api 转给 Spring Boot：浏览器看到的始终是同源请求，
      // 于是开发期根本不会触发 CORS，后端的白名单只在别的场景下才需要生效。
      '/api': {
        target: 'http://127.0.0.1:18090',
        changeOrigin: true
      }
    }
  }
})
