/// <reference types="vite/client" />

// 让 TypeScript 认识 .vue 文件。没有这段声明，import App from './App.vue' 会报"找不到模块"。
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}
