<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchHealth } from '../api/system'
import type { HealthPayload } from '../api/types'

const health = ref<HealthPayload | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    health.value = await fetchHealth()
  } catch (e) {
    // 后端不可达时清空旧数据，避免显示上一次的成功结果造成误判
    health.value = null
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="head">
      <h1>连通状态</h1>
      <p class="muted lede">
        后端收到请求后会依次探测 MySQL 与 Python 能力服务。写作、预览、上传图片、
        发布这四件事分别依赖它们。
      </p>
    </header>

    <div class="card">
      <div class="card-head">
        <h2>服务</h2>
        <button class="btn" :disabled="loading" @click="load">
          {{ loading ? '检查中…' : '重新检查' }}
        </button>
      </div>

      <p v-if="error" class="alert"><strong>后端不可达。</strong>{{ error }}</p>

      <ul v-else-if="health" class="rows">
        <li class="row">
          <span class="dot ok" aria-hidden="true" />
          <span class="name">Spring Boot</span>
          <span class="detail">{{ health.service }} · v{{ health.version }}</span>
        </li>

        <li class="row">
          <span class="dot" :class="health.database.reachable ? 'ok' : 'bad'" aria-hidden="true" />
          <span class="name">MySQL</span>
          <span class="detail">
            <template v-if="health.database.reachable">
              已连接，已执行 {{ health.database.migrations }} 个迁移
            </template>
            <template v-else>{{ health.database.error }}</template>
          </span>
        </li>

        <li class="row">
          <span class="dot" :class="health.python.reachable ? 'ok' : 'bad'" aria-hidden="true" />
          <span class="name">Python 能力服务</span>
          <span class="detail">
            <template v-if="health.python.reachable">已连接 · v{{ health.python.version }}</template>
            <template v-else>{{ health.python.error }}</template>
          </span>
        </li>
      </ul>

      <p v-else class="muted">尚未检查。</p>
    </div>
  </section>
</template>

<style scoped>
.head h1 {
  margin: 0 0 6px;
  font-size: 20px;
}

.lede {
  margin: 0 0 20px;
  font-size: 13.5px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.card-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
}

.rows {
  list-style: none;
  margin: 0;
  padding: 0;
}

.row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid var(--color-border);
  font-size: 14px;
}

.row:first-child {
  border-top: none;
}

.dot {
  align-self: center;
  flex: 0 0 8px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-muted);
}

.dot.ok {
  background: var(--color-success);
}

.dot.bad {
  background: var(--color-danger);
}

.name {
  flex: 0 0 auto;
  font-weight: 500;
}

.detail {
  flex: 1 1 auto;
  color: var(--color-text-muted);
  font-family: var(--font-mono);
  font-size: 13px;
  word-break: break-all;
}
</style>
