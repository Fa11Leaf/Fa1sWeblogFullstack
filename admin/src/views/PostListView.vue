<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { importMarkdown, listPosts } from '../api/posts'
import type { ImportResult, PostSummary } from '../api/types'

const router = useRouter()

const posts = ref<PostSummary[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const keyword = ref('')
const status = ref<'' | 'DRAFT' | 'PUBLISHED'>('')

// ── 导入 .md ──────────────────────────────────────────────────────────
const importing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const imported = ref<ImportResult[]>([])
const importErrors = ref<string[]>([])

/**
 * 拖拽高亮用计数器而不是布尔值。
 *
 * 因为 dragenter / dragleave 会在子元素之间反复触发：鼠标从卡片移到空白处，
 * 会先进入 section 再离开卡片……用布尔值就会疯狂闪烁。
 * 计到 0 才算真的离开。
 */
let dragDepth = 0
const dragActive = ref(false)

const MD_PATTERN = /\.(md|markdown|mdown)$/i

const counts = computed(() => ({
  total: posts.value.length,
  draft: posts.value.filter((p) => p.status === 'DRAFT').length,
  published: posts.value.filter((p) => p.status === 'PUBLISHED').length
}))

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    posts.value = await listPosts(status.value || undefined, keyword.value || undefined)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

function onDragEnter(event: DragEvent): void {
  if (!hasFiles(event)) {
    return
  }
  dragDepth += 1
  dragActive.value = true
}

function onDragLeave(): void {
  dragDepth -= 1
  if (dragDepth <= 0) {
    dragDepth = 0
    dragActive.value = false
  }
}

function resetDrag(): void {
  dragDepth = 0
  dragActive.value = false
}

async function onDrop(event: DragEvent): Promise<void> {
  resetDrag()
  const files = Array.from(event.dataTransfer?.files ?? [])
  await handleFiles(files)
}

function onFilePicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  void handleFiles(files)
  // 清空，否则连着选同一个文件不会再触发 change
  input.value = ''
}

/**
 * 逐个导入。
 *
 * 串行而不是 Promise.all：一次拖十几篇时，串行能让后面失败的请求不受前面影响，
 * 而且后端是单用户的本机服务，并发也没有收益。
 */
async function handleFiles(files: File[]): Promise<void> {
  if (!files.length || importing.value) {
    return
  }

  imported.value = []
  importErrors.value = []
  error.value = null

  const markdown = files.filter((f) => MD_PATTERN.test(f.name))
  const rejected = files.filter((f) => !MD_PATTERN.test(f.name))
  for (const f of rejected) {
    importErrors.value.push(`${f.name}：只接受 .md / .markdown 文件`)
  }
  if (!markdown.length) {
    return
  }

  importing.value = true
  try {
    for (const file of markdown) {
      try {
        imported.value.push(await importMarkdown(file))
      } catch (e) {
        importErrors.value.push(`${file.name}：${e instanceof Error ? e.message : String(e)}`)
      }
    }

    await load()

    // 只导入了一篇就直接进编辑器 —— 导入完马上能改，这是最常见的使用方式。
    // 一次拖进来多篇时留在列表，让人先看清单与提醒。
    const first = imported.value[0]
    if (imported.value.length === 1 && !importErrors.value.length && first) {
      await router.push({ name: 'post-edit', params: { id: first.post.id } })
    }
  } finally {
    importing.value = false
  }
}

function dismissImport(): void {
  imported.value = []
  importErrors.value = []
}

onMounted(load)
</script>

<template>
  <section
    class="page"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <header class="head">
      <div>
        <h1>文章</h1>
        <p class="muted lede">
          共 {{ counts.total }} 篇 · 草稿 {{ counts.draft }} · 已发布 {{ counts.published }}
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" :disabled="importing" @click="fileInput?.click()">
          {{ importing ? '导入中…' : '导入 .md' }}
        </button>
        <RouterLink :to="{ name: 'post-new' }" class="btn btn-primary">写新文章</RouterLink>
        <input
          ref="fileInput"
          class="hidden-input"
          type="file"
          accept=".md,.markdown,.mdown,text/markdown"
          multiple
          @change="onFilePicked"
        />
      </div>
    </header>

    <p v-if="error" class="alert">{{ error }}</p>

    <div v-if="imported.length || importErrors.length" class="result card">
      <div class="result-head">
        <strong v-if="imported.length">已导入 {{ imported.length }} 篇草稿</strong>
        <span v-if="importErrors.length" class="bad"> · {{ importErrors.length }} 个文件未能导入</span>
        <button class="btn dismiss" @click="dismissImport">知道了</button>
      </div>

      <ul v-if="imported.length" class="result-list">
        <li v-for="item in imported" :key="item.post.id">
          <RouterLink :to="{ name: 'post-edit', params: { id: item.post.id } }">
            {{ item.post.title }}
          </RouterLink>
          <span class="muted mono">
            {{ item.sourceFilename }} → {{ item.post.slug }}.md（{{ item.bytes }} 字节）
          </span>
          <ul v-if="item.warnings.length" class="warns">
            <li v-for="(w, i) in item.warnings" :key="i" class="muted">{{ w }}</li>
          </ul>
        </li>
      </ul>

      <ul v-if="importErrors.length" class="result-list">
        <li v-for="(msg, i) in importErrors" :key="i" class="bad">{{ msg }}</li>
      </ul>
    </div>

    <div class="filters">
      <input
        v-model="keyword"
        class="input search"
        placeholder="按标题、slug 或标签搜索"
        @keyup.enter="load"
      />
      <select v-model="status" class="select status" @change="load">
        <option value="">全部状态</option>
        <option value="DRAFT">仅草稿</option>
        <option value="PUBLISHED">仅已发布</option>
      </select>
      <button class="btn" :disabled="loading" @click="load">
        {{ loading ? '加载中…' : '搜索' }}
      </button>
    </div>

    <table v-if="posts.length" class="table">
      <thead>
        <tr>
          <th class="col-title">标题</th>
          <th class="col-status">状态</th>
          <th class="col-date">日期</th>
          <th class="col-updated">最后修改</th>
          <th class="col-ops"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="post in posts" :key="post.id">
          <td>
            <RouterLink class="title" :to="{ name: 'post-edit', params: { id: post.id } }">
              {{ post.title }}
            </RouterLink>
            <div class="sub muted mono">{{ post.slug }}.md</div>
            <div v-if="post.tags.length" class="tags">
              <span v-for="tag in post.tags" :key="tag" class="pill">{{ tag }}</span>
            </div>
          </td>
          <td>
            <span class="pill" :class="post.status === 'PUBLISHED' ? 'pill-ok' : 'pill-warn'">
              {{ post.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </span>
          </td>
          <td class="mono">{{ post.publishDate }}</td>
          <td class="mono">{{ post.updatedAt }}</td>
          <td>
            <a
              v-if="post.lastCommitUrl"
              class="muted mono"
              :href="post.lastCommitUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              查看提交 ↗
            </a>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else-if="!loading" class="card empty">
      <p class="muted">
        还没有文章。点右上角「写新文章」开始，或者把你的 <code>.md</code> 文件
        <strong>拖到这个页面里</strong> —— 元数据会自动读出来填好。
      </p>
    </div>

    <!-- 拖拽遮罩：只在真的拖着文件时才出现 -->
    <div v-if="dragActive" class="drop-mask" aria-hidden="true">
      <div class="drop-box">
        <strong>松手即导入</strong>
        <span class="muted">支持一次拖多个 .md，导入后都是草稿，不会直接上线</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page {
  position: relative;
  min-height: 60vh;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.head h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.lede {
  margin: 0;
  font-size: 13.5px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hidden-input {
  display: none;
}

.result {
  margin-bottom: 16px;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
}

.dismiss {
  margin-left: auto;
  font-size: 12.5px;
  padding: 3px 10px;
}

.result-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  font-size: 13px;
}

.result-list > li {
  padding: 6px 0;
  border-top: 1px solid var(--color-border);
  line-height: 1.6;
}

.result-list > li:first-child {
  border-top: none;
}

.result-list span {
  margin-left: 8px;
  font-size: 12px;
}

.warns {
  list-style: disc;
  margin: 4px 0 0;
  padding-left: 1.4em;
  font-size: 12.5px;
}

.bad {
  color: var(--color-danger);
}

.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.search {
  flex: 1 1 auto;
  max-width: 420px;
}

.status {
  width: 130px;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 12.5px;
  font-weight: 500;
  text-align: left;
}

.table td {
  padding: 12px 10px;
  border-bottom: 1px solid var(--color-border);
  vertical-align: top;
}

.col-status,
.col-date,
.col-updated {
  width: 130px;
}

.col-ops {
  width: 110px;
}

.title {
  color: var(--color-text);
  font-weight: 500;
  text-decoration: none;
}

.title:hover {
  color: var(--color-accent);
}

.sub {
  margin-top: 2px;
  font-size: 12px;
}

.tags {
  margin-top: 5px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.empty {
  text-align: center;
}

.empty p {
  margin: 0;
  font-size: 13.5px;
}

.empty code {
  font-family: var(--font-mono);
}

/* pointer-events: none 很关键：遮罩要覆盖整页，但不能把 drop 事件吃掉，
   否则拖到遮罩上时浏览器认为落在它上面，我们的处理函数收不到。 */
.drop-mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--color-bg) 72%, transparent);
  pointer-events: none;
}

.drop-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  padding: 28px 40px;
  border: 2px dashed var(--color-accent);
  border-radius: var(--radius);
  background: var(--color-bg);
  color: var(--color-text);
  font-size: 15px;
}

.drop-box .muted {
  font-size: 12.5px;
}
</style>
