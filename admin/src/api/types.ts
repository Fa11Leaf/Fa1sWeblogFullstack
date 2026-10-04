/** 后端返回的各种数据结构。集中放一处，视图里只引用类型，不重复声明形状。 */

export interface DependencyStatus {
  reachable: boolean
  error: string | null
}

export interface DatabaseStatus extends DependencyStatus {
  migrations: number | null
}

export interface PythonStatus extends DependencyStatus {
  version: string | null
}

export interface HealthPayload {
  service: string
  version: string
  database: DatabaseStatus
  python: PythonStatus
}

export interface UserView {
  id: number
  username: string
  displayName: string
  role: string
}

export interface LoginResponse {
  token: string
  expiresAt: string
  user: UserView
}

/** 列表行：不含 markdown 原文。 */
export interface PostSummary {
  id: number
  slug: string
  title: string
  description: string | null
  tags: string[]
  publishDate: string
  status: 'DRAFT' | 'PUBLISHED'
  updatedAt: string
  publishedAt: string | null
  lastCommitUrl: string | null
}

/** 编辑器加载的完整内容。 */
export interface PostDetail extends Omit<PostSummary, 'tags'> {
  tags: string[]
  markdown: string
  coverImage: string | null
  createdAt: string
  lastCommitSha: string | null
}

/** 新建 / 更新时提交上去的内容。 */
export interface PostInput {
  title: string
  slug?: string
  description?: string | null
  tags: string[]
  publishDate: string
  markdown: string
  coverImage?: string | null
}

export interface RenderResult {
  html: string
  words: number
  readingMinutes: number
  headings: number
  hasCode: boolean
}

export interface MediaView {
  id: number
  filename: string
  url: string
  originalName: string | null
  contentType: string | null
  sizeBytes: number | null
  width: number | null
  height: number | null
  createdAt: string
}

export interface UploadResult {
  asset: MediaView
  originalBytes: number
  storedBytes: number
  deduplicated: boolean
}

export interface TargetStatus {
  configured: boolean
  owner: string
  repo: string
  branch: string
  postsPath: string
  imagesPath: string
  siteUrl: string
}

export interface PublishResult {
  jobId: number
  status: string
  commitSha: string | null
  commitUrl: string | null
  files: string[]
  message: string | null
}

export interface PublishJobView {
  id: number
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  commitSha: string | null
  commitUrl: string | null
  message: string | null
  createdAt: string
  finishedAt: string | null
}

/** 导入一篇 .md 作为草稿的结果。 */
export interface ImportResult {
  post: PostDetail
  sourceFilename: string
  bytes: number
  /** 需要作者知道、但不影响导入的提醒（例如"没有 Front Matter，日期用了今天"） */
  warnings: string[]
}
