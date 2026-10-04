<script setup lang="ts">
import { computed } from 'vue'

import MediaPicker from '../components/MediaPicker.vue'

/**
 * 媒体库页面。
 *
 * 内容和编辑器里的图片面板完全相同，只是多了删除能力。
 * 之所以允许在这里删，是为了让"清理上传错的图"有一个明确的地方 ——
 * 编辑器里正写着文章时误删一张正在引用的图，代价远大于收益。
 */
const tip = computed(
  () =>
    '删除前会检查有没有文章还在引用它；被引用的图片删不掉，会告诉你引用它的是几篇。'
)
</script>

<template>
  <section>
    <header class="head">
      <div>
        <h1>媒体库</h1>
        <p class="muted lede">
          上传的图片会被压缩到最多 1600px 宽并转成 WebP，存放在本机磁盘上，
          发布时随文章一起提交到仓库的 <code class="mono">public/images/</code>。
        </p>
        <p class="muted tip">{{ tip }}</p>
      </div>
    </header>

    <div class="card">
      <MediaPicker allow-delete />
    </div>
  </section>
</template>

<style scoped>
.head h1 {
  margin: 0 0 6px;
  font-size: 20px;
}

.lede {
  margin: 0 0 4px;
  font-size: 13.5px;
  max-width: 760px;
  line-height: 1.7;
}

.tip {
  margin: 0 0 18px;
  font-size: 12.5px;
}
</style>
