<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { deleteMedia, listMedia, uploadMedia } from '../api/media'
import type { MediaView } from '../api/types'

/**
 * 媒体库面板。
 *
 * 编辑器与媒体库页面共用同一个组件：区别只有"能不能删"。
 * 抽出来的价值在于上传后的插入逻辑只写一次。
 */
const props = withDefaults(
  defineProps<{
    /** 是否显示删除按钮。编辑器里不显示：正写着文章时误删一张图很难受。 */
    allowDelete?: boolean
  }>(),
  { allowDelete: false }
)

const emit = defineEmits<{ (e: 'pick', url: string, alt: string): void }>()

const items = ref<MediaView[]>([])
const loading = ref(false)
const uploading = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    items.value = await listMedia()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }

  uploading.value = true
  error.value = null
  notice.value = null
  try {
    const result = await uploadMedia(file)
    const saved = Math.round((1 - result.storedBytes / Math.max(1, result.originalBytes)) * 100)

    notice.value = result.deduplicated
      ? '这张图和媒体库里已有的内容相同，直接复用了，没有重复占用空间。'
      : `已优化：${fmtBytes(result.originalBytes)} → ${fmtBytes(result.storedBytes)}（省 ${saved}%）`

    // 上传完立刻插入，省掉"再点一下缩略图"这一步
    emit('pick', result.asset.url, result.asset.originalName ?? result.asset.filename)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    uploading.value = false
    // 清空 input，否则选同一个文件不会再触发 change
    input.value = ''
  }
}

async function remove(item: MediaView): Promise<void> {
  if (!window.confirm(`确定删除 ${item.filename}？`)) {
    return
  }
  error.value = null
  try {
    await deleteMedia(item.id)
    await load()
  } catch (e) {
    // 最常见的失败是"还有文章在引用它"，后端会给出明确原因，原样展示即可
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function fmtBytes(bytes: number | null): string {
  if (!bytes) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

onMounted(load)
</script>

<template>
  <div class="picker">
    <div class="bar">
      <button class="btn btn-primary" :disabled="uploading" @click="fileInput?.click()">
        {{ uploading ? '上传中…' : '上传图片' }}
      </button>
      <button class="btn" :disabled="loading" @click="load">刷新</button>
      <span class="muted count">{{ items.length }} 张</span>
      <input
        ref="fileInput"
        class="hidden-input"
        type="file"
        accept="image/*"
        @change="onFileChange"
      />
    </div>

    <p v-if="error" class="alert">{{ error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <div v-if="items.length" class="grid">
      <figure v-for="item in items" :key="item.id" class="thumb">
        <button
          class="thumb-btn"
          type="button"
          :title="`插入 ${item.url}`"
          @click="emit('pick', item.url, item.originalName ?? item.filename)"
        >
          <img :src="item.url" :alt="item.filename" loading="lazy" />
        </button>
        <figcaption class="meta">
          <span class="mono name" :title="item.filename">{{ item.filename }}</span>
          <span class="muted dims">
            {{ item.width }}×{{ item.height }} · {{ fmtBytes(item.sizeBytes) }}
          </span>
        </figcaption>
        <button
          v-if="props.allowDelete"
          class="btn btn-danger del"
          type="button"
          @click="remove(item)"
        >
          删除
        </button>
      </figure>
    </div>

    <p v-else-if="!loading" class="muted empty">
      还没有图片。上传一张试试 —— 它会被压到最多 1600px 宽并转成 WebP，
      再随文章一起提交到仓库。
    </p>
  </div>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.count {
  margin-left: auto;
  font-size: 12.5px;
}

.hidden-input {
  display: none;
}

.notice {
  margin: 0 0 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-success);
  border-radius: var(--radius-sm);
  color: var(--color-success);
  font-size: 13px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
  margin: 0;
  overflow-y: auto;
}

.thumb {
  margin: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-bg-subtle);
}

.thumb-btn {
  display: block;
  width: 100%;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
}

.thumb-btn img {
  display: block;
  width: 100%;
  height: 96px;
  object-fit: cover;
}

.thumb-btn:hover img {
  opacity: 0.85;
}

.meta {
  display: block;
  padding: 5px 7px;
  font-size: 11.5px;
  line-height: 1.45;
}

.name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dims {
  display: block;
}

.del {
  width: 100%;
  justify-content: center;
  border: none;
  border-top: 1px solid var(--color-border);
  border-radius: 0;
  font-size: 12px;
  padding: 5px 0;
}

.empty {
  font-size: 13.5px;
  line-height: 1.7;
}
</style>
