import { http, unwrap } from './client'
import type { MediaView, UploadResult } from './types'

export function listMedia(): Promise<MediaView[]> {
  return unwrap<MediaView[]>(http.get('/admin/media'))
}

/**
 * 上传图片。
 *
 * <p>用 FormData 直接塞 File 对象，让浏览器自己处理 multipart 边界 ——
 * 手工拼 multipart 是典型的"看起来能用、偶尔在某个字符上炸掉"的做法。
 * 超时放宽到 60 秒：图片要先经过 Python 压缩转 WebP，大图会慢。
 */
export function uploadMedia(file: File): Promise<UploadResult> {
  const form = new FormData()
  form.append('file', file)
  return unwrap<UploadResult>(
    http.post('/admin/media', form, { timeout: 60000 })
  )
}

export function deleteMedia(id: number): Promise<void> {
  return unwrap<void>(http.delete(`/admin/media/${id}`))
}
