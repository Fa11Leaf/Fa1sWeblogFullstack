import { http, unwrap } from './client'
import type {
  ImportResult,
  PostDetail,
  PostInput,
  PostSummary,
  PublishJobView,
  PublishResult,
  RenderResult,
  TargetStatus
} from './types'

export function listPosts(status?: string, keyword?: string): Promise<PostSummary[]> {
  return unwrap<PostSummary[]>(http.get('/admin/posts', { params: { status, keyword } }))
}

export function getPost(id: number): Promise<PostDetail> {
  return unwrap<PostDetail>(http.get(`/admin/posts/${id}`))
}

export function createPost(input: PostInput): Promise<PostDetail> {
  return unwrap<PostDetail>(http.post('/admin/posts', input))
}

export function updatePost(id: number, input: PostInput): Promise<PostDetail> {
  return unwrap<PostDetail>(http.put(`/admin/posts/${id}`, input))
}

export function deletePost(id: number): Promise<void> {
  return unwrap<void>(http.delete(`/admin/posts/${id}`))
}

/**
 * 渲染预览。
 *
 * <p>超时单独放宽到 20 秒：默认的 15 秒对纯 CPU 的渲染是绰绰有余的，
 * 但编辑器在输入过程中会连续调用，慢的那次不该被误判成"服务挂了"。
 */
export function renderMarkdown(markdown: string): Promise<RenderResult> {
  return unwrap<RenderResult>(http.post('/admin/render', { markdown }, { timeout: 20000 }))
}

/**
 * 发布。
 *
 * <p>超时给到 90 秒。发布是同步的：一次要连 GitHub 五六次（读 ref、读 commit、
 * 逐个上传 blob、建 tree、建 commit、移动分支指针），图片多的时候可能十几秒。
 * 用默认的 15 秒会在成功前一刻超时，而那时提交其实已经完成了 —— 最难排查的一种"失败"。
 */
export function publishPost(id: number): Promise<PublishResult> {
  return unwrap<PublishResult>(http.post(`/admin/posts/${id}/publish`, null, { timeout: 90000 }))
}

export function fetchPublishHistory(id: number): Promise<PublishJobView[]> {
  return unwrap<PublishJobView[]>(http.get(`/admin/posts/${id}/publish-jobs`))
}

export function fetchTargetStatus(): Promise<TargetStatus> {
  return unwrap<TargetStatus>(http.get('/admin/github/status'))
}

/**
 * 导入一篇已有的 Markdown 作为草稿。
 *
 * <p>后端会解析文件里的 Front Matter（标题、摘要、日期、标签），
 * 并用文件名当 slug —— 因为本站的规矩本来就是"文件名即网址"。
 *
 * <p>只产生草稿，不会直接上线；返回的 warnings 里是"日期用了今天""正文里的一级标题被去掉"
 * 这类需要作者知晓的提醒。
 */
export function importMarkdown(file: File): Promise<ImportResult> {
  const form = new FormData()
  form.append('file', file)
  return unwrap<ImportResult>(http.post('/admin/posts/import', form, { timeout: 30000 }))
}
