<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'

import MediaPicker from '../components/MediaPicker.vue'
import {
  createPost,
  deletePost,
  fetchPublishHistory,
  fetchTargetStatus,
  getPost,
  publishPost,
  renderMarkdown,
  updatePost
} from '../api/posts'
import type { PostInput, PublishJobView, RenderResult, TargetStatus } from '../api/types'

const route = useRoute()
const router = useRouter()

const postId = computed<number | null>(() => {
  const raw = route.params.id
  const value = Array.isArray(raw) ? raw[0] : raw
  return value ? Number(value) : null
})
const isNew = computed(() => postId.value === null)

function today(): string {
  const d = new Date()
  // 用本地时间拼而不是 toISOString()：后者是 UTC，
  // 北京时间 10 月 5 日 00:30 会得到 "2026-10-04"，日期会莫名其妙差一天。
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const form = reactive({
  title: '',
  slug: '',
  description: '',
  tags: '',
  publishDate: today(),
  markdown: ''
})

/** 已保存内容的快照，用来判断"有没有未保存的改动"。 */
const snapshot = ref('')
const dirty = computed(() => currentSnapshot() !== snapshot.value)

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)

const preview = ref<RenderResult | null>(null)
const previewError = ref<string | null>(null)
const previewStale = ref(false)

const history = ref<PublishJobView[]>([])
const target = ref<TargetStatus | null>(null)
const showPicker = ref(false)

const mdRef = ref<HTMLTextAreaElement | null>(null)

function currentSnapshot(): string {
  return JSON.stringify({ ...form })
}

function parseTags(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 从标题猜一个 slug。中文标题猜不出来，返回空串交给后端生成 post-时间戳。 */
function suggestSlug(title: string): string {
  return title
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 100)
}

// ── 预览：输入停下 450ms 再请求 ────────────────────────────────────────
// 每敲一个字符都发一次请求既浪费也会让顺序错乱（后发的先到就会看到旧内容）。
let previewTimer: number | undefined

async function refreshPreview(): Promise<void> {
  try {
    preview.value = await renderMarkdown(form.markdown)
    previewError.value = null
    previewStale.value = false
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
  }
}

watch(
  () => form.markdown,
  () => {
    previewStale.value = true
    window.clearTimeout(previewTimer)
    previewTimer = window.setTimeout(refreshPreview, 450)
  }
)

onBeforeUnmount(() => window.clearTimeout(previewTimer))

// 未保存就关标签页时，让浏览器拦一下
function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (dirty.value) {
    event.preventDefault()
  }
}
onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

// 站内跳转同理。返回 false 会取消导航。
onBeforeRouteLeave(() => {
  if (!dirty.value) {
    return true
  }
  return window.confirm('有未保存的改动，确定离开？')
})

// ── 加载 ──────────────────────────────────────────────────────────────
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    target.value = await fetchTargetStatus()

    if (postId.value !== null) {
      const post = await getPost(postId.value)
      form.title = post.title
      form.slug = post.slug
      form.description = post.description ?? ''
      form.tags = post.tags.join(', ')
      form.publishDate = post.publishDate
      form.markdown = post.markdown
      history.value = await fetchPublishHistory(post.id)
    }

    snapshot.value = currentSnapshot()
    await refreshPreview()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function buildPayload(): PostInput {
  return {
    title: form.title.trim(),
    slug: form.slug.trim() || undefined,
    description: form.description.trim() || null,
    tags: parseTags(form.tags),
    publishDate: form.publishDate,
    markdown: form.markdown
  }
}

/** 保存。返回是否成功，供 publish 判断要不要继续。 */
async function save(quiet = false): Promise<boolean> {
  if (!form.title.trim()) {
    error.value = '标题不能为空'
    return false
  }

  saving.value = true
  error.value = null
  if (!quiet) {
    notice.value = null
  }

  try {
    if (postId.value === null) {
      const created = await createPost(buildPayload())
      snapshot.value = currentSnapshot()
      // 用 replace：新建后浏览器后退不该回到"新建"状态
      await router.replace({ name: 'post-edit', params: { id: created.id } })
      if (!quiet) {
        notice.value = '已保存为草稿'
      }
    } else {
      await updatePost(postId.value, buildPayload())
      snapshot.value = currentSnapshot()
      if (!quiet) {
        notice.value = '已保存'
      }
    }
    return true
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    return false
  } finally {
    saving.value = false
  }
}

async function publish(): Promise<void> {
  publishing.value = true
  error.value = null
  notice.value = null
  try {
    // 先存再发：否则线上拿到的是上一次保存的内容，而界面上已经改过了
    if (!(await save(true))) {
      return
    }
    if (postId.value === null) {
      return
    }

    const result = await publishPost(postId.value)
    notice.value = `已发布，提交 ${result.commitSha?.slice(0, 7) ?? ''}（${result.files.length} 个文件）。GitHub Actions 约 1 分钟后更新线上。`
    history.value = await fetchPublishHistory(postId.value)
    if (target.value) {
      target.value = await fetchTargetStatus()
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    publishing.value = false
  }
}

async function remove(): Promise<void> {
  if (postId.value === null) {
    return
  }
  if (!window.confirm('确定删除这篇草稿？删除后无法恢复。')) {
    return
  }
  try {
    await deletePost(postId.value)
    // 先解除"未保存"标记，否则下面的路由守卫会弹确认框拦住这次跳转
    snapshot.value = currentSnapshot()
    await router.replace({ name: 'posts' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

/** 把图片语法插到光标处，而不是简单追加到末尾。 */
function insertImage(url: string, alt: string): void {
  const snippet = `![${alt || '图片'}](${url})`
  const el = mdRef.value
  if (!el) {
    form.markdown += `\n${snippet}\n`
    return
  }
  const start = el.selectionStart ?? form.markdown.length
  const end = el.selectionEnd ?? start
  form.markdown = form.markdown.slice(0, start) + snippet + form.markdown.slice(end)
  showPicker.value = false

  // 等 Vue 把新值同步到 DOM 之后再设置光标，否则会被覆盖回旧位置
  void Promise.resolve().then(() => {
    el.focus()
    el.setSelectionRange(start + snippet.length, start + snippet.length)
  })
}

// 新建文章时，标题变化顺带建议一个 slug；一旦手工改过就不再覆盖
watch(
  () => form.title,
  (title) => {
    if (isNew.value && !form.slug) {
      form.slug = suggestSlug(title)
    }
  }
)

onMounted(load)
</script>

<template>
  <section class="editor">
    <header class="head">
      <div class="head-left">
        <RouterLink :to="{ name: 'posts' }" class="back muted">← 文章列表</RouterLink>
        <h1>{{ isNew ? '写新文章' : form.title || '未命名' }}</h1>
      </div>

      <div class="actions">
        <span v-if="dirty" class="pill pill-warn">未保存</span>
        <button class="btn" :disabled="saving || publishing" @click="save(false)">
          {{ saving ? '保存中…' : '保存草稿' }}
        </button>
        <button
          class="btn btn-primary"
          :disabled="saving || publishing || !target?.configured"
          :title="target?.configured ? '' : '未配置 GITHUB_TOKEN，无法发布'"
          @click="publish"
        >
          {{ publishing ? '发布中…' : '发布到线上' }}
        </button>
        <button v-if="!isNew" class="btn btn-danger" :disabled="publishing" @click="remove">
          删除
        </button>
      </div>
    </header>

    <p v-if="error" class="alert">{{ error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <p v-if="target && !target.configured" class="warnbar">
      还没有配置 <code>GITHUB_TOKEN</code>，所以「发布」暂时不可用。写作与预览不受影响；
      配好令牌重启后端即可发布到 <code>{{ target.owner }}/{{ target.repo }}@{{ target.branch }}</code>。
    </p>

    <div class="panes">
      <!-- 左：元数据 + 正文 -->
      <div class="pane">
        <div class="field">
          <label class="label" for="title">标题</label>
          <input id="title" v-model="form.title" class="input" placeholder="文章标题" />
        </div>

        <div class="row">
          <div class="field grow">
            <label class="label" for="slug">文件名 slug</label>
            <input id="slug" v-model="form.slug" class="input mono" placeholder="留空则自动生成" />
            <p class="hint muted">将来线上就是 /posts/{{ form.slug || '…' }}，发布前定好。</p>
          </div>
          <div class="field date">
            <label class="label" for="date">日期</label>
            <input id="date" v-model="form.publishDate" class="input" type="date" />
          </div>
        </div>

        <div class="row">
          <div class="field grow">
            <label class="label" for="tags">标签</label>
            <input id="tags" v-model="form.tags" class="input" placeholder="用逗号分隔，例如：CSS, 布局" />
          </div>
        </div>

        <div class="field">
          <label class="label" for="desc">摘要</label>
          <input id="desc" v-model="form.description" class="input" placeholder="一句话说明这篇文章讲了什么" />
        </div>

        <div class="field">
          <div class="label-row">
            <label class="label" for="md">正文（Markdown）</label>
            <button class="btn" type="button" @click="showPicker = !showPicker">
              {{ showPicker ? '收起图片' : '插入图片' }}
            </button>
          </div>
          <textarea
            id="md"
            ref="mdRef"
            v-model="form.markdown"
            class="textarea md"
            placeholder="正文从这里开始。标题已经在上面填过了，正文不要再写 # 一级标题。"
          />
          <p class="hint muted">
            <template v-if="preview">
              {{ preview.words }} 字 · 约 {{ preview.readingMinutes }} 分钟 · {{ preview.headings }} 个小标题
            </template>
            图片路径形如 <code>/images/xxx.webp</code>，用上面的按钮插入最省事。
          </p>
        </div>

        <div v-if="showPicker" class="field picker-box">
          <MediaPicker @pick="insertImage" />
        </div>
      </div>

      <!-- 右：实时预览 -->
      <div class="pane">
        <div class="label-row">
          <span class="label">预览</span>
          <span v-if="previewStale" class="muted tiny">正在更新…</span>
        </div>

        <div class="preview">
          <p v-if="previewError" class="alert">{{ previewError }}</p>
          <h2 v-else-if="form.title" class="preview-title">{{ form.title }}</h2>
          <!--
            这里用 v-html 是安全的：后端渲染时把原文里的裸 HTML 转义掉了
            （markdown_renderer 里 html=False），插进来的只可能是正文标签。
          -->
          <div v-if="preview" class="prose" v-html="preview.html" />
          <p v-else class="muted">正文还是空的。</p>
        </div>

        <div v-if="history.length" class="history">
          <div class="label-row">
            <span class="label">发布记录</span>
          </div>
          <ul class="jobs">
            <li v-for="job in history" :key="job.id" class="job">
              <span
                class="pill"
                :class="job.status === 'SUCCEEDED' ? 'pill-ok' : job.status === 'FAILED' ? 'pill-warn' : ''"
              >
                {{ job.status }}
              </span>
              <span class="mono when">{{ job.createdAt }}</span>
              <a
                v-if="job.commitUrl"
                class="mono"
                :href="job.commitUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ job.commitSha?.slice(0, 7) }} ↗
              </a>
              <span v-if="job.message" class="muted msg" :title="job.message">{{ job.message }}</span>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.back {
  font-size: 12.5px;
  text-decoration: none;
}

.head h1 {
  margin: 2px 0 0;
  font-size: 20px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.notice {
  margin: 0 0 14px;
  padding: 9px 13px;
  border: 1px solid var(--color-success);
  border-radius: var(--radius-sm);
  color: var(--color-success);
  font-size: 13.5px;
}

.warnbar {
  margin: 0 0 14px;
  padding: 9px 13px;
  border: 1px solid var(--color-warn);
  border-radius: var(--radius-sm);
  color: var(--color-warn);
  font-size: 13px;
}

.warnbar code,
.hint code {
  font-family: var(--font-mono);
  font-size: 0.92em;
}

.panes {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}

.pane {
  min-width: 0;
}

.row {
  display: flex;
  gap: 12px;
}

.grow {
  flex: 1 1 auto;
}

.date {
  flex: 0 0 150px;
}

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 5px;
}

.label-row .label {
  margin-bottom: 0;
}

.hint {
  margin: 5px 0 0;
  font-size: 12.5px;
}

.md {
  min-height: 420px;
  font-family: var(--font-mono);
  font-size: 13.5px;
}

.picker-box {
  margin-top: 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 12px;
}

.preview {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 16px 18px;
  min-height: 420px;
  max-height: 720px;
  overflow-y: auto;
}

.preview-title {
  margin: 0 0 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--color-border);
  font-size: 22px;
}

.tiny {
  font-size: 12px;
}

.history {
  margin-top: 18px;
}

.jobs {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12.5px;
}

.job {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-top: 1px solid var(--color-border);
}

.job:first-child {
  border-top: none;
}

.when {
  color: var(--color-text-muted);
}

.msg {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .panes {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
