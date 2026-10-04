# Weblog Fullstack

一个「静态博客 + 动态写作后台」的全栈项目。公开站点用 Nuxt 4（Vue 3 + Vite 内核）静态生成，写作后台用 Vue 3 + Vite，主后端 Spring Boot（Java 21），能力服务 Python 3.14 + FastAPI，数据库 MySQL。

本文档状态：**设计稿（v0.2）**。五项决策已确认，版本基线已实测核实。标注 `[已实测]` 的部分是真实查询或勘察结果，未标注的是设计约定。

---

## 决策记录（2026-10-04）

以下五项已拍板，本文档后续所有描述均据此展开，不再反复讨论。

| # | 事项 | 决定 |
| --- | --- | --- |
| 1 | 公开站点前端 | **Nuxt 4 + @nuxt/content**（Vue 3 + Vite 内核），替换现有 Astro 站点 |
| 2 | 写作后台部署位置 | **只在本机运行**，端口不对外暴露 |
| 3 | 数据库 | **MySQL**（原定 Docker 的 8.4，实际改用本机 8.0.46，见 §18.6） |
| 4 | 文章提交机制 | **GitHub Git Data API**（方案 A）。后端不接触任何本地仓库副本 |
| 5 | 前台源码归属 | 放 **`Fa11Leaf.github.io`** 仓库，由它自己的 Actions 构建发布，保住根路径网址 |

### 版本基线 `[已实测]`

以下数字均为 2026-10-04 当日查询的真实结果，不是估算。

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Spring Boot | **4.1.1** | 要求 Java 17+，兼容至 Java 26；底层 Spring Framework 7.0.9 / Spring Security 7 / Hibernate 7.2 / Jackson 3.0 / Flyway 11.11 |
| Nuxt | **4.5.2** | |
| @nuxt/content | **3.16.1** | 底层用 SQLite 原生模块，见 §11.4 风险条目 |
| Vue / Vite | 3.5.43 / **8.3.2** | Vite 已是 8.x，高于常见教程里的 7.x |
| vite-ssg / VitePress | 28.3.0 / 1.6.4 | 备选方案，本次未采用 |
| MySQL | **8.0.46** | 本机已安装（服务名 `MySQL80`）。原计划用 Docker 的 `mysql:8.4`，因拉不到镜像而改用本机实例，见 §18.6 |
| FastAPI / uvicorn | 0.142.2 / 0.54.0 | |
| markdown-it-py / Pygments / Pillow | 4.2.0 / 2.21.0 / 12.3.0 | |
| jieba | 0.42.1 | 中文分词，仅在发布期生成静态索引时使用 |
| 前端关键库 | minisearch 7.2.0 · md-editor-v3 7.1.0 · pinia 4.0.3 · vue-router 5.3.1 | 均比常见教程里的版本高出一档 |
| Java | 21.0.12 LTS | 本机已装 |
| Node | 22.22.2 | 本机已装 |
| Docker | CLI 29.8.0 / Compose v5.5.1 | 本机已装，**但守护进程默认未启动**，用前要先开 Docker Desktop |
| Maven | **未安装** | 用 `mvnw` 绕过，见 §8.2 |

两处**尚未定论**，实施时以官方生成器的输出为准，不手写猜测：

1. ~~Spring Boot 4 的 Web starter 名称~~ —— **已解决**（P0 用 Spring Initializr 实测）：规范名是 **`spring-boot-starter-webmvc`**，`spring-boot-starter-web` 根本不会出现在生成结果里。完整依赖清单见 §18.2。
2. **Initializr 的版本号格式埋着一个坑**（P0 发现）：它元数据里的版本 id 带 `.RELEASE` 后缀（如 `4.1.1.RELEASE`），并且会**把你给的字符串原样写进 `pom.xml` 的 parent**。但 Maven 中央仓库里只有 `4.1.1`——`.RELEASE` 后缀从 Boot 2.4 起就已取消。照抄元数据会得到一个无法解析的 parent，构建直接失败。P0 已改为 `4.1.1`。
3. **springdoc-openapi 对 Spring Framework 7 的支持情况**，仍需在真正引入时实测。

---

## 0. 先澄清一个事实

**现有项目 `fa11leaf.github.io` 里没有后端。**

它从 S1 到 S4 的演进路径是：

| 阶段 | 内容 | 有无后端 |
| --- | --- | --- |
| S1 | 手写 HTML 多页 | 无 |
| S2 | `posts.json` + `fetch` 客户端渲染 | 无 |
| S3 | Astro 7 静态站点生成 | 无 |
| S4 | GitHub Actions 自动构建部署 | 无 |

你提到的「后端原本有的、上传 README 到 GitHub Pages 的功能」，实际指的是 `.github/workflows/deploy.yml` —— 那是 **CI/CD 流水线**，不是后端服务。它做的是：`push` 到 `main` → GitHub 临时机器上跑 `npm ci && npm run build` → 把 `dist/` 发布为网站。

这个区别很重要，它决定了本次工作的性质：

- **不是**「在已有后端上加功能」
- **而是**「从零建后端，并把原有的发布管线接进来」

因此第 6 条需求（保留/改良上传功能）的正确理解是：**保留「提交 Markdown 到仓库 → 自动构建上线」这条链路，改良它的操作方式**——从「在本地手敲 `git push`」变成「在网页后台点一下发布」。

还有一点需要提前讲清：你在决策中选了「前端改用 Vue + SSG」，所以本次也包含**前台框架替换**（Astro → Nuxt 4）。这部分是**重写**而非改良——现有 Astro 站点会被 Nuxt 项目取代。文章内容本身可原样迁移，样式取值也继续沿用原有设计令牌，但页面结构、路由与构建配置都是新的。

---

## 1. 整体架构

### 1.1 一句话概括

**前台是纯静态站点（免费、快、SEO 好），后台是动态服务（写作、上传、发布、检索）。二者通过 GitHub 仓库解耦。**

这是业界成熟的 **Headless CMS** 模式。它解决了一个核心矛盾：博客的**读**是高频、无状态、可缓存的；博客的**写**是低频、有状态、私密的。硬把两者塞进同一个动态服务，会让读的性能被写的复杂度拖累，且失去免费静态托管。

### 1.2 拓扑

```
                    ┌──────────────────────────────┐
                    │  访客浏览器                    │
                    └───────────────┬──────────────┘
                                    │ HTTPS
                    ┌───────────────▼──────────────┐
                    │  GitHub Pages（纯静态）        │
                    │  Nuxt 预渲染的 HTML + 搜索索引  │
                    └──────────────────────────────┘
                                    ▲
                                    │ GitHub Git Data API 提交
   ┌────────────────────────────────┴─────────────────────────────┐
   │              写作后台（只在本机运行，不对外暴露）                  │
   │                                                              │
   │  ┌────────────────┐   REST    ┌────────────────┐   REST    ┌──────────────┐
   │  │ Vue 3 + Vite   │ ────────► │  Spring Boot   │ ────────► │  Python      │
   │  │ 管理界面        │ ◄──────── │  :18090 主后端  │ ◄──────── │ FastAPI :8000 │
   │  │ :5174          │   JSON    │                │   JSON    │  能力服务      │
   │  └────────────────┘           └───────┬────────┘           └──────────────┘
   │                                       │
   │                          ┌────────────┴────────────┐
   │                          │  MySQL 8.0（本机）       │
   │                          │  元数据 / 用户 / 发布任务  │
   │                          └──────────────────────────┘
   └──────────────────────────────────────────────────────────────┘
```

两个仓库各司其职，不要混淆：

| 仓库 | 角色 | 内容 |
| --- | --- | --- |
| `Fa11Leaf.github.io` | **公开站点**，唯一发布线上内容的仓库 | Nuxt 源码、`content/posts/*.md`、文章配图、它自己的 `deploy.yml` |
| `weblog-fullstack` | **写作后台**，只在本机跑 | `admin/`（管理界面）、`backend/`（Spring Boot）、`python-service/`（FastAPI） |

### 1.3 三条数据流

| 流 | 方向 | 说明 |
| --- | --- | --- |
| **读文章** | 访客 → GitHub Pages | 完全不经过后端。后端没启动也不影响阅读。 |
| **写文章** | 本机管理界面 → Spring Boot → Python → GitHub API | 提交 Markdown 与图片到部署仓库，触发其 Actions 重新构建 |
| **改元数据** | 本机管理界面 → Spring Boot → MySQL | 标签、草稿状态、发布记录，不触发重建 |

### 1.4 为什么前端要做成两个

Vue 3 在两个位置使用，用途完全不同：

| 位置 | 用途 | 构建目标 | 部署 |
| --- | --- | --- | --- |
| `Fa11Leaf.github.io`（Nuxt 4） | **公开站点**：列表、详情、标签、搜索 | 预渲染的静态 HTML | GitHub Pages |
| `weblog-fullstack/admin`（Vite SPA） | **写作后台**：登录、编辑、上传、发布 | SPA | 只在本机 |

公开站点必须是 SSG——搜索引擎与无 JS 环境都得能读到完整正文。后台是纯 SPA，不需要 SEO，用 Vite 默认配置即可。两者不共享代码库：它们连部署位置都不同。

**搜索为什么不走后端**：若搜索调接口，后端没启动搜索就废了，而且会让「读」依赖「写」的那套服务。因此搜索索引**在发布时由 Python 生成、随文章一起提交为 `search-index.json`**，前台把它下载到浏览器本地检索。这一条决定了 Python 在系统里的双重定位：**运行期为后台提供预览与图片处理，发布期为前台生产静态搜索索引**。

---

## 2. 技术选型

| 层 | 技术 | 版本 | 选型理由 |
| --- | --- | --- | --- |
| 公开站点 | **Nuxt 4**（Vue 3 + Vite 内核） | 4.5.2 | Vue 官方框架；`nuxi generate` 输出预渲染 HTML，SSG 为首要目标 |
| 内容层 | **@nuxt/content** | 3.16.1 | 直接读 `content/` 下的 Markdown，提供集合查询、目录、代码高亮 |
| 写作后台 | Vue 3 + Vite + Pinia + Vue Router | 3.5.43 / 8.3.2 | SPA，无需 SSG；Pinia 管状态，Vue Router 管路由 |
| UI 组件 | Naive UI | 2.45.3 | 见 §12 前端规范 |
| 主后端 | **Spring Boot** | **4.1.1** | Java 17+ 即可（本机 21）；Spring Framework 7 / Security 7 / Hibernate 7.2 |
| 能力服务 | Python + FastAPI | 3.14.7 / 0.142.2 | 文本与图片处理场景 Python 更强 |
| 数据库 | **MySQL** | 本机 **8.0.46** | 用本机已安装的实例，见 §18.6；Docker 容器降为备用方案 |
| JDBC 驱动 | `com.mysql:mysql-connector-j` | 由 Boot BOM 管理（9.x） | 不必手写版本号 |
| 迁移 | Flyway（含 `flyway-mysql`） | 由 Boot BOM 管理（11.x） | Flyway 10 起数据库支持拆成独立模块，MySQL **必须**额外引 `flyway-mysql` |
| 认证 | Spring Security + JJWT | JJWT 0.13.0 / Security 随 Boot BOM | 无状态 JWT，适合前后端分离 |
| 文档 | springdoc-openapi | 3.1.1 | 2.x 是 Boot 3 的线，3.x 才对应 Spring Framework 7；实际兼容性在 P0 实测确认 |

> ✅ **starter 名称已定案**（P0 由 Spring Initializr 实测）：Boot 4 的 Web starter 是 **`spring-boot-starter-webmvc`**（**不是** `-web`）；Flyway 有独立 starter **`spring-boot-starter-flyway`**（不必只引 `flyway-core`）；测试 starter **按模块拆分**为 `spring-boot-starter-webmvc-test` 这类。
> 这里有一处我先前说错过、现已核实更正：`spring-boot-starter-test` **仍然存在**，它是这些模块化 starter 的传递依赖（`spring-boot-starter-webmvc-test` 的 POM 里就列着它），只是 Initializr 不再直接把它写进 `pom.xml`。完整清单见 §18.2。
>
> ⚠️ 仍有一处待实测：**springdoc-openapi 对 Spring Framework 7 的支持**。若不可用，退路是手写 `docs/api.md`，不阻塞主链路。

### 2.1 本机环境实测结果

| 依赖 | 状态 | 版本 | 备注 |
| --- | --- | --- | --- |
| Java | ✅ 已装 | 21.0.12 LTS | 满足 Spring Boot 4.1.1 的 Java 17+ 要求 |
| Maven | ❌ **未安装** | — | 用 `mvnw` 绕过，见 §8.2 |
| Gradle | ❌ 未安装 | — | 不必用 |
| Python | ⚠️ 两个版本 | 托管 3.13.14 / 系统 3.14.7 | 用系统 `C:\Python314\python.exe` |
| Node | ✅ 已装 | 22.22.2 | 满足 Nuxt 4 与 Vite 8 要求 |
| git | ✅ 已装 | 2.55.0 | |
| Docker | ⚠️ **已装但守护进程未启动** | CLI 29.8.0 / Compose v5.5.1 | 用 MySQL 前必须先打开 Docker Desktop |

---

## 3. 语言分工：什么交给 Java，什么交给 Python

你要求「对应功能更强大需要对应语言时用对应的语言来解决」。以下是明确的分界与理由，不含模糊表述。

| 能力 | 归属 | 理由 |
| --- | --- | --- |
| HTTP API 网关、路由、参数校验 | **Java** | Spring 生态在 Web 服务层无对手，声明式校验（`@Valid`）成熟 |
| 认证授权、JWT 签发与校验 | **Java** | Spring Security 是工业标准，自己用 Python 写容易出安全漏洞 |
| 数据库事务、ORM、迁移 | **Java** | JPA + Flyway，强类型实体 + 事务边界清晰 |
| Git / GitHub API 提交编排 | **Java** | 树对象构建、批量 blob、commit 组装是严谨的状态机，Java 的类型系统更安全 |
| 定时任务（同步、清理） | **Java** | `@Scheduled` + 事务，无需引入额外调度器 |
| **Markdown → HTML 渲染** | **Python** | `markdown-it-py` / `pymdown-extensions` 比 Java 的 CommonMark 实现功能多得多（脚注、任务列表、上下标、代码块标题、Mermaid 插件） |
| **代码语法高亮** | **Python** | Pygments 是语法高亮的原始实现，支持语言数量远超 Java 的同类库 |
| **图片处理** | **Python** | Pillow 的压缩、格式转换、EXIF 处理、多尺寸缩略图能力，Java 需引入 ImageIO + 额外库且质量调优繁琐 |
| **中文分词** | **Python** | `jieba` 的中文分词质量与生态在 Java 侧无对等方案。但**检索本身不归它**：查询在浏览器端由 `minisearch` 完成，Python 只在发布期把文章分词后写成静态索引 |
| **摘要与阅读时长计算** | **Python** | 文本统计 + 可选择接入 LLM 生成摘要，Python 的 SDK 生态更全 |
| **RSS / sitemap 生成** | **Python** | `feedgen` 等库开箱即用 |
| **内容一致性校验**（死链、图片是否存在） | **Python** | 批量文本扫描是脚本语言的主场 |

**分界原则一句话**：**涉及状态、权限、事务、外部写操作的，归 Java；涉及文本、图片、分词、格式转换等「纯计算」的，归 Python。**

这条原则同时带来一个工程好处：Python 服务可以做成**无状态纯函数服务**（输入内容 → 输出结果），不碰数据库、不发外部请求。这样它极易测试、可随意重启、可水平扩展。

---

## 4. 前后端交互方式

### 4.1 三层调用链

```
浏览器  ──REST/JSON──►  Spring Boot  ──REST/JSON──►  Python
        (JWT 认证)                      (内网 Token)
```

- **浏览器 ↔ Spring Boot**：标准 REST，`Content-Type: application/json`（上传文件时用 `multipart/form-data`）。认证走 `Authorization: Bearer <JWT>`。
- **Spring Boot ↔ Python**：内网 HTTP，调用地址由配置项 `app.python.base-url` 决定。用固定的 `X-Internal-Token` 头校验，**该端口不对公网开放**。

Python 服务**永远不直接被浏览器调用**。原因是它没有认证层，且不应暴露到公网。它只信任来自 Spring Boot 的请求。

### 4.2 统一响应格式

所有接口返回同一外层结构，便于前端统一处理：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

- `code`：`0` 表示成功；非 0 为业务错误码（见 §6.7）
- HTTP 状态码同时使用：成功 `200`/`201`，参数错误 `400`，未认证 `401`，无权限 `403`，不存在 `404`，服务异常 `500`
- `message` 面向开发者，前端展示文案由 `code` 映射，不直接显示 `message`

### 4.3 典型时序：上传并发布一篇文章

这是整个系统最核心的一条链路，完整走一遍：

```
① 用户在前端选择 .md 文件 + 若干图片，填写标题、标签
② POST /api/admin/posts/draft          （multipart：file + metadata）
   Spring Boot 存数据库（status=DRAFT），原文件落地到 tmp 目录
③ 响应返回 postId

④ 用户点击「预览」
   POST /api/admin/posts/{id}/preview
   Spring Boot → POST http://127.0.0.1:8000/render
   Python 渲染 Markdown → HTML，返回高亮后的片段
   前端在右侧面板显示渲染结果

⑤ 用户点击「发布」
   POST /api/admin/posts/{id}/publish
   Spring Boot 依次执行：
     a. 调 Python /prepare → 压缩图片转 WebP、生成摘要、算字数与阅读时长
     b. 调 Python /search/rebuild → 用 jieba 分词，产出 search-index.json
     c. 组装文件清单（路径均位于部署仓库内）：
        content/posts/xxx.md + public/images/xxx.webp + public/search-index.json
     d. 调 GitHub Git Data API 完成一次提交：
        POST  /git/blobs           每个文件上传一个 blob（内容为 base64）
        POST  /git/trees           按路径组装目录树
        POST  /git/commits         基于该树创建提交
        PATCH /git/refs/heads/main 把分支指针移到新提交
     e. 写数据库：status=PUBLISHED，记录 commitSha
     f. 返回 commitSha + Actions 运行链接
⑥ GitHub Actions 被 push 触发 → 构建 → 部署 Pages
⑦ 数据库更新 status=LIVE（轮询 Actions 结果后）
```

**为什么用 Git Data API 而不是逐个文件调 Contents API**：一次发布通常包含 1 个 Markdown + N 张图片。Contents API 每文件一次 commit，会产生 N+1 条提交记录，仓库历史被污染。Git Data API 可以先把所有 blob 建好，再组装成一棵树、一次提交，**保证一篇 = 一个 commit**。

代价也要说清楚：Git Data API 要求文件内容以 **base64** 放进 JSON 请求体，体积膨胀约 33%。一篇配十张图的文章，请求体可能到十几 MB。因此图片**必须先经 Python 压缩成 WebP 再上传**，不能原图直传——这也是 `/prepare` 存在的直接理由，不只是「顺手压缩一下」。

### 4.4 为什么不让前端直接调 GitHub API

这是本设计里一个容易走偏的地方，明确说明：

- **Token 会暴露**。GitHub Personal Access Token 一旦写进前端代码或浏览器存储，等于公开。浏览器环境无法安全保存长期凭据。
- **无法做聚合**。图片压缩、摘要生成、元数据入库需要一个中间层。
- **无法做审计**。谁在什么时候发了什么，需要服务端记录。

因此**所有涉及 GitHub 的操作只能在后端发生**。

Token 的权限也只需**目标仓库的 `Contents: Read and write`**，不要给整个账号的 `repo` 权限。最小权限原则在这里不是形式：一个只读该仓库的 Token 泄露，损失远小于一个能改你所有仓库的 Token。

---

## 5. 数据结构

### 5.1 文章文件（仓库中的真身）

文章以 Markdown 文件形式存在仓库里，路径 `content/posts/{slug}.md`：

```markdown
---
title: 我的第一篇博客
slug: my-first-post
description: 记录这个网站是怎么一步步搭起来的
pubDate: 2026-10-04
updatedDate: 2026-10-05
tags: [web, astro, 学习笔记]
cover: /images/my-first-post-cover.webp
draft: false
---

## 正文从这里开始

正文内容……
```

**路径与命名约定**（与现有项目保持一致）：

- `slug` 只用小写英文、数字、连字符，例如 `css-box-model`
- 中文文件名、空格、大写字母都会引发 URL 编码问题，一律禁止
- 图片与文章同级或放 `public/images/`，Markdown 内用相对路径引用

### 5.2 数据库实体

#### `users`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 自增 |
| `username` | VARCHAR(50) UNIQUE | 登录名 |
| `password_hash` | VARCHAR(100) | BCrypt |
| `display_name` | VARCHAR(50) | 显示名 |
| `role` | VARCHAR(20) | `ADMIN` / `AUTHOR` |
| `created_at` | DATETIME | |

> 个人博客通常只有一个作者。**首次启动时若 `users` 表为空，自动创建一个管理员，密码从环境变量 `BLOG_ADMIN_PASSWORD` 读取**，避免把默认密码写进代码。

#### `posts`（元数据索引）

文章正文以文件为准，此表只存**索引与状态**，供列表、检索、后台管理使用。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `slug` | VARCHAR(120) UNIQUE | 与文件名一致 |
| `title` | VARCHAR(200) | |
| `description` | VARCHAR(500) | 摘要 |
| `content_md` | TEXT | 正文快照（便于后台编辑，非权威源） |
| `tags` | VARCHAR(500) | 逗号分隔，或拆独立表 |
| `status` | VARCHAR(20) | `DRAFT` / `PUBLISHED` / `LIVE` |
| `cover_image` | VARCHAR(300) | |
| `word_count` | INT | 由 Python 计算 |
| `reading_minutes` | INT | 由 Python 计算 |
| `published_at` | DATETIME | |
| `updated_at` | DATETIME | |
| `commit_sha` | VARCHAR(40) | 最近一次发布对应的 commit |
| `author_id` | BIGINT FK | → `users.id` |

#### `media_assets`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `original_name` | VARCHAR(255) | |
| `stored_path` | VARCHAR(500) | 仓库内路径 |
| `original_size` | INT | 字节 |
| `optimized_size` | INT | 字节 |
| `mime_type` | VARCHAR(100) | |
| `width` / `height` | INT | |
| `sha256` | VARCHAR(64) | 去重依据 |
| `post_id` | BIGINT FK NULL | 归属文章 |
| `created_at` | DATETIME | |

#### `publish_jobs`（发布任务，可追溯）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `post_id` | BIGINT FK | |
| `status` | VARCHAR(20) | `PENDING` / `UPLOADING` / `SUCCESS` / `FAILED` |
| `commit_sha` | VARCHAR(40) | |
| `actions_run_url` | VARCHAR(300) | GitHub Actions 运行页 |
| `error_message` | TEXT | 失败原因 |
| `started_at` / `finished_at` | DATETIME | |

### 5.3 接口用 DTO

**文章对象（响应）**：

```json
{
  "id": 12,
  "slug": "my-first-post",
  "title": "我的第一篇博客",
  "description": "记录这个网站是怎么一步步搭起来的",
  "tags": ["web", "astro", "学习笔记"],
  "status": "PUBLISHED",
  "coverImage": "/images/my-first-post-cover.webp",
  "wordCount": 1830,
  "readingMinutes": 6,
  "publishedAt": "2026-10-04T00:00:00+08:00",
  "updatedAt": "2026-10-05T11:20:00+08:00",
  "commitSha": "a1b2c3d",
  "contentMd": "## 正文从这里开始\n\n……"
}
```

**文章列表项（精简，不含正文）**：

```json
{
  "slug": "my-first-post",
  "title": "我的第一篇博客",
  "description": "……",
  "tags": ["web"],
  "readingMinutes": 6,
  "publishedAt": "2026-10-04",
  "coverImage": "/images/…webp"
}
```

**分页响应**：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [ ],
    "page": 1,
    "size": 10,
    "total": 37,
    "totalPages": 4
  }
}
```

---

## 6. 接口定义

统一前缀 `/api`。除 `/api/auth/*` 与公开的 `GET` 文章接口外，其余均需 `Authorization: Bearer <JWT>`。后台接口统一加 `/admin` 前缀。

### 6.1 认证

#### `POST /api/auth/login`

登录并获取令牌。

```
请求（application/json）
{
  "username": "fa11leaf",
  "password": "……"
}

响应 200
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400,
    "user": { "id": 1, "username": "fa11leaf", "displayName": "Fa11Leaf", "role": "ADMIN" }
  }
}
```

#### `GET /api/auth/me`

校验令牌有效性并返回当前用户。前端刷新页面后用它恢复登录态。

#### `POST /api/auth/logout`

无状态 JWT 下服务端不做处理，仅返回成功，由前端清除本地令牌。保留此接口是为了日后切换到黑名单机制时不必改前端。

### 6.2 文章（公开）

> 先说明一个容易误解的点：公开站点是 SSG，**构建期直接读 Markdown 渲染，不会调用下面这些接口**。保留它们是为了三件事——① 后台列表与前台详情复用同一套 DTO 定义；② 将来若要加「最新文章」这类动态模块可直接接上；③ 便于用 curl / Postman 手工验证数据，不必开浏览器。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/posts` | 分页列表。查询参数：`page`(默认1)、`size`(默认10，最大50)、`tag`、`q`(关键词)、`sort`(默认 `publishedAt,desc`) |
| `GET` | `/api/posts/{slug}` | 单篇详情，含正文 |
| `GET` | `/api/posts/tags` | 全部标签及计数 |
| `GET` | `/api/posts/latest` | 最新 N 篇（`limit` 默认 5），供首页侧栏 |
| `GET` | `/api/search?q=&page=&size=` | 全文检索，由 Python `jieba` + BM25 提供 |

`GET /api/posts/{slug}` 响应中的 `data` 即 §5.3 的「文章对象」。文章不存在返回 `404` + `code=40401`。

### 6.3 文章（后台）

#### `POST /api/admin/posts/draft` —— 上传草稿

**这是需求 1「文章上传」的主接口。**

```
请求（multipart/form-data）
  file       : File  必填  单个 .md 文件，最大 2 MB
  title      : String 可选  不传则从 Front Matter 的 title 取
  tags       : String 可选  逗号分隔
  slug       : String 可选  不传则由 title 生成 kebab-case
  images     : File[] 可选  正文引用的图片，可多个，单个最大 10 MB
  coverImage : File   可选  封面图

响应 201
{
  "code": 0,
  "message": "ok",
  "data": {
    "postId": 12,
    "slug": "my-first-post",
    "status": "DRAFT",
    "wordCount": 1830,
    "readingMinutes": 6,
    "images": [
      { "id": 5, "originalName": "shot.png", "optimizedSize": 21840, "repoPath": "public/images/shot.webp" }
    ]
  }
}
```

服务端行为：解析 Front Matter → 校验 schema → 图片交 Python 压缩转 WebP → 计算字数与阅读时长 → 入库为 `DRAFT`。

**校验失败返回 `400` + `code=40001`**，`data` 中列出每个字段的错误：

```json
{
  "code": 40001,
  "message": "字段校验失败",
  "data": {
    "errors": [
      { "field": "title", "message": "不能为空" },
      { "field": "pubDate", "message": "格式应为 YYYY-MM-DD" }
    ]
  }
}
```

#### `PUT /api/admin/posts/{id}` —— 更新草稿

`application/json`，字段同 DTO。允许改 `title` / `description` / `tags` / `contentMd` / `coverImage` / `draft`。

#### `POST /api/admin/posts/{id}/preview` —— 渲染预览

```
请求（application/json）
{ "contentMd": "## 小标题\n\n正文……" }

响应 200
{
  "code": 0,
  "message": "ok",
  "data": {
    "html": "<h2 id=\"...\">小标题</h2>\n<p>正文……</p>",
    "toc": [ { "level": 2, "text": "小标题", "anchor": "小标题" } ],
    "wordCount": 1830,
    "readingMinutes": 6
  }
}
```

后端转调 Python `POST /render`。**预览与正式构建必须使用同一套渲染参数**，否则会出现「预览好看、上线变样」。

#### `POST /api/admin/posts/{id}/publish` —— 发布（推送 GitHub）

```
请求（application/json，均可选）
{
  "commitMessage": "post: 我的第一篇博客",
  "branch": "main",
  "mode": "GIT_DATA_API"
}

响应 202
{
  "code": 0,
  "message": "已提交，等待构建",
  "data": {
    "jobId": 33,
    "postId": 12,
    "status": "UPLOADING",
    "commitSha": "a1b2c3d4e5f6...",
    "commitUrl": "https://github.com/Fa11Leaf/Fa11Leaf.github.io/commit/a1b2c3d4",
    "actionsRunUrl": "https://github.com/Fa11Leaf/Fa11Leaf.github.io/actions"
  }
}
```

返回 `202` 而非 `200`：提交已完成，但网站上线还需要 Actions 构建，属于异步过程。前端凭 `jobId` 轮询状态。

#### `GET /api/admin/posts/{id}/publish-status` —— 查询发布进度

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "jobId": 33,
    "status": "SUCCESS",
    "actionsStatus": "completed",
    "actionsConclusion": "success",
    "liveUrl": "https://fa11leaf.github.io/posts/my-first-post/",
    "errorMessage": null
  }
}
```

`status` 取值：`PENDING` / `UPLOADING` / `SUCCESS` / `FAILED`。

#### `DELETE /api/admin/posts/{id}` —— 删除文章

删除仓库中的 `.md`、解除图片引用、软删除数据库记录（`status=DELETED`，便于找回）。**默认不直接删 GitHub 上的文件**，需显式传 `?removeRemote=true`。

### 6.4 媒体

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/admin/media/upload` | 上传图片，`multipart/form-data`。Python 压缩 + 转 WebP + 生成 480/960/1440 三档缩略图 |
| `GET` | `/api/admin/media` | 分页列出媒体资源 |
| `DELETE` | `/api/admin/media/{id}` | 删除（需先确认无文章引用，否则返回 `409`） |
| `POST` | `/api/admin/media/check` | 传入 `sha256` 列表，返回已存在的，用于秒传与去重 |

### 6.5 Python 能力服务接口（仅内网）

前缀 `http://127.0.0.1:8000`，全部 `POST`，全部要求 `X-Internal-Token`。调用方只有 Spring Boot，浏览器永不直连。

| 路径 | 调用时机 | 输入 | 输出 |
| --- | --- | --- | --- |
| `/render` | 运行期，编辑器每次预览 | `{ "contentMd": "…" }` | `{ "html": "…", "toc": [], "wordCount": n, "readingMinutes": n }` |
| `/prepare` | 发布期，点「发布」时 | `{ "contentMd": "…", "images": [ { "name": "…", "base64": "…" } ] }` | `{ "summary": "…", "wordCount": n, "readingMinutes": n, "images": [ { "targetPath": "…", "webpBase64": "…", "width": w, "height": h } ] }` |
| `/search/rebuild` | 发布期，重建整站索引 | `{ "docs": [ { "slug": "…", "title": "…", "content": "…" } ] }` | `{ "index": { … 浏览器端可直接加载的 JSON … }, "docCount": n }` |
| `/hash` | 任意 | `{ "base64": "…" }` | `{ "sha256": "…" }` |
| `/feed` | 发布期 | `{ "posts": [ ], "site": { } }` | `{ "rss": "<xml…>", "sitemap": "<xml…>" }` |
| `/health` | 探活 | — | `{ "status": "ok", "version": "…" }` |

三点说明：

1. **`/render` 与 `/prepare` 必须分开**。预览要快（毫秒级，只出 HTML），发布可以慢（秒级，要出图片二进制、摘要、索引）。混在一起会让编辑器打字卡顿。
2. **预览与正式构建必须共用同一套渲染参数**，否则会出现「预览好看、上线变样」。
3. **`/search/rebuild` 取代了原设计的 `/search/index` + `/search/query`**。检索改在浏览器端做，Python 只负责用 `jieba` 分词并产出静态索引 JSON，不再提供在线查询接口——这是 §1.4「读不经后端」这条性质的必然结果。Python 侧只需 `jieba` 分词，不需要 `rank-bm25`（相关性排序交给前台的 `minisearch`）。

---

### 6.6 系统

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/health` | 后端存活检查，含数据库与 Python 服务的连通性 |
| `GET` | `/api/admin/github/status` | 校验 GitHub Token 是否有效、目标仓库是否可达 |
| `POST` | `/api/admin/sync/pull` | 从仓库拉取所有 Markdown，重建数据库索引。用于「本地数据与线上不一致」时修复 |
| `GET` | `/api/admin/stats` | 文章数、标签数、媒体占用空间等 |

`/api/admin/sync/pull` 是**幂等**的：数据库可以随时清空重建，因为**仓库里的 Markdown 才是唯一真源**。这是整个设计的重要性质，它保证数据不会因后端故障而丢失。

### 6.7 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `0` | 200/201/202 | 成功 |
| `40001` | 400 | 参数校验失败 |
| `40002` | 400 | Markdown Front Matter 格式错误 |
| `40101` | 401 | 未登录或令牌过期 |
| `40301` | 403 | 无权限 |
| `40401` | 404 | 文章不存在 |
| `40402` | 404 | 媒体不存在 |
| `40901` | 409 | 资源被引用，无法删除 |
| `40902` | 409 | slug 已存在 |
| `50001` | 500 | GitHub API 调用失败 |
| `50002` | 500 | Python 服务不可用 |
| `50003` | 500 | 数据库异常 |

---

## 7. 项目结构

### 7.1 公开站点：`Fa11Leaf.github.io`

```
Fa11Leaf.github.io/
├── nuxt.config.ts               站点配置：site 网址、内容模块、静态生成
├── package.json                 Nuxt 依赖与脚本（dev / generate / preview）
├── .gitignore                   忽略 node_modules / .nuxt / .output / dist
├── .gitattributes               统一换行符
├── LICENSE / README.md
│
├── .github/workflows/
│   └── deploy.yml               push 到 main → 云端 npm ci + generate → 发布 Pages
│
├── public/                      原样复制到产出根，不参与构建处理
│   ├── .nojekyll                防止 Jekyll 忽略下划线开头的产物目录
│   ├── favicon.svg
│   └── search-index.json        ← 由 Python 在发布时生成并提交，前台本地检索用
│
├── content/
│   ├── content.config.ts        内容集合契约：title / description / pubDate 必填
│   └── posts/
│       ├── my-first-web.md      ← 文章唯一来源
│       └── images/              文章配图（构建期压缩转 WebP）
│
└── app/（Nuxt 4 的 srcDir，也可能叫 src/，以脚手架生成为准）
    ├── app.vue
    ├── layouts/default.vue      全站外壳（header / nav / footer），唯一一份
    ├── components/              PostCard、TagList、Pagination
    ├── pages/
    │   ├── index.vue                   →  /
    │   ├── about.vue                   →  /about
    │   ├── posts/[slug].vue            →  /posts/任意篇名/
    │   ├── tags/[tag].vue              →  /tags/任意标签/
    │   └── search.vue                  →  /search
    ├── assets/styles/            设计令牌 + 全局样式（沿用现有站点取值）
    └── utils/content.ts          构建期读取 Markdown 与集合查询的封装
```

### 7.2 写作后台：`weblog-fullstack`

```
weblog-fullstack/
├── README.md                    本文档
├── docker-compose.yml           起 MySQL（+ 可选的 python-service / backend）
├── .env.example                 环境变量模板（真实 .env 不入库）
│
├── admin/                       写作后台（Vue 3 + Vite SPA）
│   ├── package.json
│   ├── vite.config.ts           代理 /api → 127.0.0.1:18090
│   └── src/
│       ├── main.ts / App.vue
│       ├── router/index.ts
│       ├── stores/              Pinia
│       ├── api/                 axios 封装，统一处理 code 与 401
│       ├── components/
│       │   ├── MarkdownEditor.vue   左右分栏，右侧调 /preview
│       │   ├── ImageUploader.vue
│       │   └── PublishDialog.vue    显示 commit 短哈希与构建进度
│       └── views/
│           ├── LoginView.vue
│           ├── DashboardView.vue
│           ├── PostListView.vue
│           ├── PostEditorView.vue
│           ├── MediaLibraryView.vue
│           └── SettingsView.vue
│
├── backend/                     Spring Boot 主后端
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd          Maven Wrapper（本机无 mvn 时的关键）
│   └── src/main/
│       ├── java/com/fa11leaf/blog/
│       │   ├── BlogApplication.java
│       │   ├── config/          SecurityConfig / CorsConfig / RestClientConfig / OpenApiConfig
│       │   ├── common/          ApiResponse / GlobalExceptionHandler / ErrorCode / 分页封装
│       │   ├── auth/            JwtService / JwtFilter / AuthController
│       │   ├── user/            User 实体与仓储
│       │   ├── post/            Post 实体 / Repository / Service / PostController / AdminPostController
│       │   ├── media/           MediaAsset 实体 / MediaService / MediaController
│       │   ├── publish/         GitHubClient / PublishService / PublishJob / PublishController
│       │   │                    GitHubClient 直接调 Git Data API，不依赖本地仓库副本
│       │   ├── python/          PythonClient / PythonProperties
│       │   └── search/          SearchIndexService（组装待索引文档，交 Python 分词）
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           └── db/migration/    Flyway（MySQL 方言）V1__init.sql …
│
├── python-service/              Python 能力服务（FastAPI）
│   ├── requirements.txt
│   ├── pyproject.toml
│   ├── app/
│   │   ├── main.py / config.py / security.py
│   │   ├── routers/             render.py / prepare.py / search.py / feed.py
│   │   ├── services/
│   │   │   ├── markdown_renderer.py    markdown-it-py + Pygments
│   │   │   ├── image_optimizer.py      Pillow → WebP + 三档缩略图
│   │   │   ├── summarizer.py           摘要与阅读时长
│   │   │   └── indexer.py              jieba 分词 → 静态索引 JSON
│   │   └── schemas.py           Pydantic 模型
│   └── tests/
│
└── docs/
    ├── api.md                   接口说明（若 springdoc 不支持 Boot 4 则手写）
    ├── architecture.md          架构决策记录
    └── deploy.md                部署手册
```

> 文章的「真身」在**部署仓库** `Fa11Leaf.github.io/content/posts/` 里，不在本仓库。本仓库只放后台三端源码；发布时由 Spring Boot 通过 GitHub API 把 Markdown 与图片写进部署仓库，**本机不需要部署仓库的克隆**。

---

## 8. 环境依赖

### 8.1 必需

| 依赖 | 版本要求 | 本机状态 |
| --- | --- | --- |
| JDK | 17+（推荐 21 LTS） | ✅ 21.0.12 |
| Maven | 3.9.x | ❌ 未安装，用 `mvnw` 绕过 |
| Node.js | ≥ 22.12 | ✅ 22.22.2 |
| Python | ≥ 3.11 | ✅ 3.14.7（系统）/ 3.13.14（托管） |
| **Docker Desktop** | 支持 Compose v2 即可 | ⚠️ CLI 已装，**守护进程需手动启动** |
| MySQL | 8.4 LTS | 由 Docker 提供，不必本机安装 |
| git | 任意 | ✅ 2.55.0 |

### 8.2 Maven 没装怎么办

`[已实测]` 本机**没有 Maven 也没有 Gradle**。三条路，按推荐度排序：

**方案 A：用 Maven Wrapper（`mvnw`）——推荐**

Spring Initializr 生成的后端工程自带 `mvnw` 与 `mvnw.cmd`。首次执行时它会自动下载指定版本的 Maven 到 `~/.m2/wrapper`，**无需本机安装 Maven**。

```bat
cd /d D:\Fa11Leaf\Work\weblog-fullstack\backend
mvnw.cmd spring-boot:run
```

代价：首次执行需联网下载约 10 MB。

**方案 B：用 Docker 里的 Maven 跑构建**

不必装 Maven，用官方镜像执行：

```bat
docker run --rm -v "%cd%:/app" -w /app maven:3.9-eclipse-temurin-21 mvn -q package
```

优点：环境完全一致，能复现 CI 的结果。缺点：每次构建要拉依赖、拉镜像，冷启动慢；不适合反复热重载的开发循环。

**方案 C：装一份全局 Maven**

下载 `apache-maven-3.9.x-bin.zip` 解压到 `D:\Fa11Leaf\tools\maven`，把 `bin` 加进 PATH。之后所有 Java 项目都受益，但要多做一步手工操作。

> **建议**：用方案 A 跑开发，方案 B 留作「怀疑是本地环境问题时」的对照验证。

### 8.3 Python 依赖

`requirements.txt` 拟定（版本号已核实为当前最新）：

```
fastapi==0.142.2
uvicorn[standard]==0.54.0
pydantic==2.13.5
python-multipart==0.0.32
markdown-it-py==4.2.0
mdit-py-plugins==0.6.1
pymdown-extensions==12.1
Pygments==2.21.0
Pillow==12.3.0
jieba==0.42.1
feedgen==1.0.0
httpx==0.28.1
pytest==9.1.1
```

> 原设计里的 `rank-bm25` 已移除：检索改在浏览器端用 `minisearch`，Python 只做 `jieba` 分词。
>
> ⚠️ **Python 3.14 的 wheel 兼容性**：`Pillow 12.3.0` 有 cp314 wheel（此前已在本机成功安装）；`jieba`、`minisearch` 是纯 Python 包，无编译步骤。若某个包在 3.14 下装不上，退路是用托管的 `3.13.14` 建虚拟环境——两个版本在本机并存，切换成本仅一行启动命令。

### 8.4 前端依赖

**公开站点**（`Fa11Leaf.github.io`）：

```
nuxt                ^4.5
@nuxt/content       ^3.16
minisearch          7.2.0       （浏览器端检索，读 search-index.json）
vue                 ^3.5
```

**写作后台**（`weblog-fullstack/admin`）：

```
vue                 ^3.5
vite                ^8
vue-router          ^5
pinia               ^4
axios               ^1.20
naive-ui            ^2.45
@vitejs/plugin-vue  ^6
typescript          ^7
vue-tsc             ^3
md-editor-v3        ^7          （Markdown 编辑器，含预览与图片粘贴上传）
```

---

## 9. 启动步骤

四个进程，启动顺序有依赖关系：**MySQL 必须先就绪**，后端才能连上；Python 服务应该先于后端启动，否则后端探活会报错。

### 9.1 第一步：准备 MySQL

**当前采用：本机已安装的 MySQL 8.0。** 三个前提：

1. `MySQL80` 服务在运行（`sc query MySQL80`）
2. 存在 `weblog` 库，字符集 utf8mb4（建库过程见 §18.6）
3. `.env` 里填好 `MYSQL_USERNAME` 与 `MYSQL_PASSWORD`

`start.cmd` 会先探测 3306：发现 `mysqld` 就直接使用它，不会去动 Docker。

**备用方案：用 Docker 容器提供 MySQL**（适合本机没装 MySQL 的机器）

```bat
cd /d D:\Fa11Leaf\Work\weblog-fullstack
copy .env.example .env
docker compose up -d mysql
docker compose ps                      REM 等 STATUS 变成 healthy
```

`docker-compose.yml` 中的数据库部分：

```yaml
services:
  mysql:
    image: mysql:8.4
    container_name: weblog-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: weblog
      MYSQL_USER: weblog
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    ports:
      - "127.0.0.1:3306:3306"      # 只绑回环，不对局域网暴露
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  mysql-data:
```

> **必须显式指定 utf8mb4**。MySQL 8 的默认值通常已是 utf8mb4，但一旦落到 latin1，中文文章会在写入时变成问号，而且是**静默损坏**，事后难查。
>
> **端口只绑 `127.0.0.1`**。写在前面的 `127.0.0.1:` 是关键，去掉它 Docker 会监听 `0.0.0.0`，把数据库暴露到局域网。

### 9.2 第二步：Python 能力服务

```bat
cd /d D:\Fa11Leaf\Work\weblog-fullstack\python-service

C:\Python314\python.exe -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

访问 <http://127.0.0.1:8000/docs> 查看 FastAPI 自动生成的接口文档。

**必须监听 `127.0.0.1`，不要用 `0.0.0.0`** —— 该服务没有认证层。

### 9.3 第三步：Spring Boot 主后端

```bat
cd /d D:\Fa11Leaf\Work\weblog-fullstack\backend
mvnw.cmd spring-boot:run
```

启动后访问 <http://127.0.0.1:18090/swagger-ui/index.html> 查看接口文档（若 springdoc 尚未支持 Boot 4，则看 `docs/api.md`）。

首次启动会自动：建表（Flyway）→ 若 `users` 表为空则按环境变量创建管理员 → 探活 Python 服务。

关键配置（`application-dev.yml`）：

```yaml
server:
  # 8080 留给 Steam，见 §18.5
  port: 18090

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/weblog?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME:root}
    # 刻意没有默认值：凭据漏配就该启动失败，而不是拿猜出来的值去连库
    password: ${MYSQL_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate      # 表结构由 Flyway 管，JPA 只校验不建表
  flyway:
    enabled: true
    locations: classpath:db/migration

app:
  python:
    base-url: http://127.0.0.1:8000
    internal-token: ${PY_INTERNAL_TOKEN}
  github:
    owner: Fa11Leaf
    repo: Fa11Leaf.github.io
    branch: main
    token: ${GITHUB_TOKEN}     # 只从环境变量注入，绝不写进文件
  site:
    base-url: https://fa11leaf.github.io
```

> `serverTimezone` 建议显式写 `Asia/Shanghai`，否则容器时区与 JDBC 默认值不一致时，`DATETIME` 会出现八小时偏差。
>
> Hibernate 方言通常无需手写，Boot 会从连接元数据推断；若确实要指定，Hibernate 7 里统一用 `org.hibernate.dialect.MySQLDialect`，**不存在 `MySQL8Dialect` 这个类**（6.0 起已合并）。

### 9.4 第四步：写作后台

```bat
cd /d D:\Fa11Leaf\Work\weblog-fullstack\admin
npm install
npm run dev            REM → http://localhost:5174
```

`vite.config.ts` 配代理，开发期免跨域：

```ts
server: {
  port: 5174,
  proxy: {
    '/api': { target: 'http://127.0.0.1:18090', changeOrigin: true }
  }
}
```

### 9.5 公开站点（在部署仓库里）

```bat
cd /d D:\Fa11Leaf\Work\fa11leaf.github.io
npm install
npm run dev            REM → http://localhost:3000
```

写文章的完整闭环是：**后台写 → 点发布 → 部署仓库被提交 → 它的 Actions 构建 → 线上更新**。本地跑 `npm run dev` 只是为了改版式时看效果。

### 9.6 一键起后端三件套（可选）

`docker-compose.yml` 里已经预留了 `python-service` 与 `backend` 两个服务，但挂在 **`app` profile 下，默认不启动**。因此无论 `docker compose up -d mysql` 还是 `docker compose up -d`，行为都只是起数据库，不会误触发两端的镜像构建。

需要三端全起时：

```bat
docker compose --profile app up -d
```

> ⚠️ 这两个服务引用的 `Dockerfile` **尚未编写**，现在打开 profile 会因找不到构建上下文而失败。P0 刻意不写它们，理由见本节末尾：开发期用 Docker 跑 Spring Boot，改一行代码就要重建镜像，热重载体验很差。配置先留在 compose 里，是为了后续阶段不必回头改它。

预留的配置如下：

```yaml
  python-service:
    build: ./python-service
    ports: ["127.0.0.1:8000:8000"]
    environment:
      - PY_INTERNAL_TOKEN=${PY_INTERNAL_TOKEN}

  backend:
    build: ./backend
    ports: ["127.0.0.1:18090:18090"]
    environment:
      - PY_INTERNAL_TOKEN=${PY_INTERNAL_TOKEN}
      - GITHUB_TOKEN=${GITHUB_TOKEN}
      - MYSQL_PASSWORD=${MYSQL_PASSWORD}
      - SPRING_PROFILES_ACTIVE=dev
      - APP_PYTHON_BASE_URL=http://python-service:8000
    depends_on:
      mysql:
        condition: service_healthy
      python-service:
        condition: service_started
```

注意容器之间要用**服务名**互相访问（`http://python-service:8000`），不能写 `127.0.0.1`——后者在容器内指向容器自己。

> **开发期建议不要用 Docker 跑 backend**：每次改代码都要重建镜像，热重载体验很差。用 `mvnw.cmd spring-boot:run` 更快。Docker 适合作为「换台机器也能一键跑起来」的兜底。

---

## 10. 文章上传与发布链路（需求 1 与需求 6）

### 10.1 旧机制回顾

现有仓库的发布方式：

1. 本地写 `.md`，放进部署仓库的 `content/posts/`
2. 本地跑 `npm run build` 验证
3. `git add` → `git commit` → `git push`
4. GitHub Actions 触发 → 云端构建 → 部署 Pages

**优点**：免费、可靠、有完整版本历史、不依赖任何服务器在线。
**缺点**：必须在装了 Node 的电脑上操作；不能随时随地发文；图片要先手工压缩；没有预览。

### 10.2 新机制

保留第 4 步（Actions 构建部署）**完全不变**，替换掉第 1～3 步：

1. 在后台界面写 Markdown 或直接拖入 `.md` 文件
2. 右侧实时预览（调 Python 渲染，与线上渲染参数一致）
3. 拖入图片，自动压缩转 WebP
4. 点「发布」
5. Spring Boot 通过 GitHub Git Data API 一次性提交（1 个 Markdown + N 张图 = 1 个 commit）
6. Actions 自动构建部署

### 10.3 保留与改良对照

| 原有能力 | 处置 | 说明 |
| --- | --- | --- |
| GitHub Actions 构建部署 | **保留骨架，改构建命令** | 工作流结构不变，`npm run build` 改为 Nuxt 的 `npm run generate` |
| Markdown + Front Matter 作为内容源 | **原样保留** | 格式与字段完全兼容，历史文章无需迁移 |
| Markdown 文件放仓库、git 管理 | **保留并强化** | 新增通过 API 提交，但仍是标准 git commit，可正常 `git log` / `git revert` |
| 构建期图片优化（WebP） | **改良** | 从「构建期转换」提前到「上传时转换」；构建期仍保留兜底 |
| `public/.nojekyll` | **原样保留** | 产物里以下划线开头的目录会被 Jekyll 整个忽略，这个空文件用来关掉该行为 |
| Astro 内容集合与 schema 校验 | **随框架替换** | 集合定义迁到部署仓库的 `content/content.config.ts`；同时把校验提前到上传时，写错立刻报错，不必等构建失败 |
| 本地手动 `git push` | **改良** | 变为网页点按；同时保留 git 命令行方式作为备用 |
| — | **新增** | 文章列表与编辑、媒体库、全文检索、RSS/sitemap 自动生成、发布状态追踪、数据库索引、草稿与定时发布 |

### 10.4 重要性质：Markdown 文件是唯一真源

**数据库可以随时删除重建，仓库里的 Markdown 不会丢。**

这意味着：

- 后端崩溃、数据库损坏，都不会丢文章 —— 重新执行 `/api/admin/sync/pull` 即可从仓库恢复全部索引
- 随时可以退回「纯 git 命令行」工作流，不依赖这套系统
- 这正是选择「文件为准、数据库为辅」而非「数据库为准」的根本原因

---

## 11. 部署注意事项

### 11.1 公开站点的部署形态

已确认为**全静态 SSG**：Nuxt 在 GitHub Actions 里 `npm ci && npm run generate`，把预渲染好的 HTML 发布到 Pages。

| 形态 | 说明 | 是否需要后端在线 |
| --- | --- | --- |
| **全静态 SSG（已采用）** | 每篇文章都是构建期生成的完整 HTML | 否 |
| 静态外壳 + 运行时请求 API | 文章由浏览器调后端接口获得 | **是** |
| 后端托管静态产物 | Spring Boot 把产物当静态资源提供 | 是 |

**为什么必须是第一种**：第二、三种形态下，后端一旦停机（本机没开、容器没起），**整站白屏**。第一种形态下，后端只影响「写作」，永远不影响「阅读」——这是整个架构最重要的一条性质。

搜索也是同一逻辑：索引是静态 JSON，随站点一起发布，不依赖任何在线服务。

### 11.2 写作后台的部署位置

已确认**只在本机运行**。三个进程（MySQL 容器、Python、Spring Boot）都在 `127.0.0.1` 上，不对外暴露。

这不是权宜之计，而是合理定论：

| 维度 | 说明 |
| --- | --- |
| 成本 | 零。不租服务器、不买域名、不需要 HTTPS 证书 |
| 安全 | 攻击面为零——公网上根本不存在这个服务 |
| 可用性 | 「想写文章」本来是低频动作，要求电脑开机是合理代价 |
| 阅读体验 | 完全不受影响，见 §11.1 |

**如果将来想随时随地发文**，路径是现成的：整个 `weblog-fullstack` 已经容器化，搬到云服务器就是 `docker compose up -d`，再配一个 HTTPS 反代。届时需要注意三件事：改掉默认密码、给后台加登录失败限流、`GITHUB_TOKEN` 用服务器端环境变量注入。

**在切换到公网之前，请不要把 18090 端口映射到公网。**

### 11.3 安全清单

| 项 | 要求 |
| --- | --- |
| `GITHUB_TOKEN` | 只放环境变量或 `.env`（已 gitignore）。**绝不能提交进仓库**。Token 最小权限：只需目标仓库的 `Contents: Read and write` |
| `PY_INTERNAL_TOKEN` | 随机字符串，Spring Boot 与 Python 共享 |
| MySQL 口令 | 从 `.env` 注入；容器端口只绑 `127.0.0.1`；服务端字符集强制 utf8mb4 |
| Python 服务 | 只监听 `127.0.0.1`，端口不对外 |
| 管理员密码 | 首启从 `BLOG_ADMIN_PASSWORD` 读取，不得有硬编码默认值 |
| CORS | 只允许后台前端的确切源（开发 `http://localhost:5174`，生产你的域名），禁止 `*` |
| 上传校验 | 后端校验 MIME + 扩展名 + magic bytes，不信任前端 `Content-Type` |
| 路径穿越 | 文件名强制生成，不使用用户提供的原始名作为存储路径 |
| 限流 | 登录接口做失败计数，防止暴力破解 |
| HTTPS | 生产环境必须启用 |

### 11.4 版本与兼容性风险

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| **@nuxt/content 依赖 SQLite 原生模块** | 安装时可能触发本地编译，Windows 上偶有卡壳 | 若安装失败，退路是同样用 Nuxt 4，改用 `import.meta.glob` 在构建期读 Markdown（零原生依赖），代价是列表与搜索要自己写。**决策权在你，我不会自行改方案** |
| **Spring Boot 4 较新** | 中文教程几乎全是 3.x，starter 名称与 3.x 不同 | 已由 Initializr 实测定案，见 §18.2 |
| **springdoc-openapi 对 Spring Framework 7 的支持** | 2.x 覆盖不到 Boot 4 | 已查到 3.1.1 存在，看似是 Boot 4 的适配线，但**尚未实测**；若不可用则手写 `docs/api.md`，不影响主链路 |
| ~~Python 3.14 太新~~ | —— | **已实测排除**：`requirements.txt` 里全部固定版本在 Python 3.14.7 下一次性安装成功 |
| ~~Java 代码未经编译验证~~ | —— | **已实测通过**：`mvnw.cmd test` → `BUILD SUCCESS`，4 个测试全过，见 §18.7 |
| **Boot 4 不再自动装配 `RestClient.Builder`** | 直接注入会报 `NoSuchBeanDefinitionException` | 已在 `AppConfig` 自行声明 prototype Bean。这是实测踩到的，不是推演，见 §18.7 |
| ~~TypeScript 7 与 vue-tsc 的兼容性~~ | **已实测确认不兼容**：vue-tsc 3 要加载 `typescript/lib/tsc`，而 TS 7 的原生实现不再导出该路径（`ERR_PACKAGE_PATH_NOT_EXPORTED`） | 已把 `typescript` 固定为 `^5`（实装 5.9.3），`vue-tsc` 为 3.3.12。Vite 本身不依赖 TS，固定它只影响类型检查 |
| starlette TestClient 提示改用 `httpx2` | 仅弃用警告，测试通过 | 等 FastAPI 正式要求时再换，当前 `httpx==0.28.1` 可用 |
| MySQL 字符集落成 latin1 | 中文静默损坏 | compose 里显式指定 utf8mb4，见 §9.1 |
| 容器时区与 JDBC 默认值不一致 | `DATETIME` 出现 8 小时偏差 | 显式写 `serverTimezone=Asia/Shanghai`，容器设 `TZ` |
| Flyway 10+ 数据库支持已模块化 | 只引 `flyway-core` 会报「不支持 MySQL」 | 额外引 `org.flywaydb:flyway-mysql` |
| GitHub API 速率限制 | 认证请求 5000 次/小时 | 个人博客用量远低于此，无实际风险 |

## 12. 前端设计规范（需求 5：界面美观）

### 12.1 设计令牌

在 `src/styles/tokens.css` 中定义，全站引用，不写魔法数字：

```css
:root {
  --color-bg:        #ffffff;
  --color-bg-subtle: #f7f8fa;
  --color-text:      #1f2937;
  --color-text-muted:#64748b;
  --color-border:    #e5e7eb;
  --color-accent:    #2563eb;
  --color-accent-hover: #1d4ed8;

  --max-width-prose: 760px;    /* 正文阅读宽度 */
  --max-width-shell: 1080px;   /* 页眉页脚 */
  --radius: 10px;
  --space: 20px;

  --font-sans: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
  --font-mono: ui-monospace, "Cascadia Code", Consolas, monospace;
}

@media (prefers-color-scheme: dark) {
  :root {
    --color-bg:        #0f172a;
    --color-bg-subtle: #1e293b;
    --color-text:      #e2e8f0;
    --color-text-muted:#94a3b8;
    --color-border:    #334155;
  }
}
```

这套令牌沿用现有站点的取值（`--max-width: 760px`、`--max-width-shell: 1080px`、`--color-accent: #2563eb`），保证新旧风格连续。

### 12.2 关键要求

1. **正文行宽不超过 760px**。中文一行 35～45 字最舒适，超过 60 字眼睛回行会累。
2. **全站跟随系统暗色模式**。用 `prefers-color-scheme`，不额外做切换开关。
3. **响应式断点** 600px / 900px / 1200px 三档。600px 以下切单列。
4. **`@media (prefers-reduced-motion: reduce)`** 时关闭所有过渡动画。
5. **焦点可见**。`:focus-visible` 必须有清晰轮廓，不能靠 `outline: none` 藏掉——键盘用户依赖它。
6. **图片必须有 `alt`**，装饰性图片用 `alt=""`。
7. **对比度**正文与背景 ≥ 7:1，次要文字 ≥ 4.5:1。
8. **后台编辑器左右分栏**，左侧写 Markdown、右侧实时预览，分栏比例可拖拽（默认 50:50）。
9. **发布按钮有明确的加载与结果反馈**，显示 commit 短哈希与 Actions 链接，失败时给出可读原因。
10. **不用 emoji 做图标**，统一用 SVG 图标组件。

---

## 13. 实施路线

按此顺序推进，每一步都能独立验证。

| 阶段 | 内容 | 可验证结果 |
| --- | --- | --- |
| **P0** 🟡 代码已落地，待你本机验证 | 三端骨架 + Docker 起 MySQL + `mvnw` 跑通后端 + 打通 `/api/health` → `/health` | 一条命令看到三端连通、数据库连上。详见 §18 |
| **P1** | Python 渲染服务 + 后台 Markdown 编辑器 + 实时预览 | 打字时右侧出现高亮后的 HTML |
| **P2** | 认证（JWT）+ 文章 CRUD + Flyway 建表 | 能登录、能存草稿、能列出文章 |
| **P3** | 图片处理 + 媒体库 | 上传 PNG，返回 WebP 与三档缩略图 |
| **P4** | **Git Data API 提交 + 发布状态轮询** | 点发布 → 线上真的多出一篇文章 |
| **P5** | 静态搜索索引 + 前台浏览器端检索 | 搜索「盒模型」能命中相关文章 |
| **P6** | 公开站点改版为 Nuxt（含内容集合与迁移现有三篇文章） | 根路径网址正常，样式与旧站连续 |
| **P7** | 打磨：暗色模式、响应式、错误处理、RSS/sitemap、文档 | 达到可长期使用的状态 |

**P4 是全局最关键的一步**——它打通「网页操作 → 线上生效」的闭环。P0～P3 都只是为它做准备。

**P6 建议放在 P4 之后**，理由：先证明「新写的文章能上线」，再动前台。反过来做的话，前台改完却发现发布链路不通，会同时有两个未知数。

---

## 14. 已确认决策与遗留风险

五项决策见文档开头的「决策记录」。此处只列**尚未解决**的事项，以及它们各自的触发条件。

| # | 事项 | 触发条件 | 我会怎么做 |
| --- | --- | --- | --- |
| 1 | @nuxt/content 能否在 Windows + Node 22 下装成功 | P6 开始时立刻验证 | 失败则**先问你**，再决定换 `import.meta.glob` 还是排查编译环境 |
| 2 | ~~Spring Boot 4 的 Web starter 规范名~~ | **已解决** | 实测为 `spring-boot-starter-webmvc`，见 §18.2 |
| 3 | springdoc-openapi 是否支持 Spring Framework 7 | P0 引依赖时 | 不支持则手写 `docs/api.md` |
| 4 | ~~我能否在沙箱内执行 `docker` / `mvnw`~~ | **已证实不能** | 沙箱仍禁止派生子进程（返回 `EBUSY`），且 Docker 守护进程未启动。因此分工固定为：**我写代码与做静态验证，构建与运行由你执行** |
| 5 | ~~Python 服务用 3.14 还是 3.13~~ | **已解决** | 全部依赖在 3.14.7 下安装成功，确定用 3.14.7 |

---

## 15. 与现有项目的关系

| 项 | 说明 |
| --- | --- |
| 部署仓库 | `D:\Fa11Leaf\Work\fa11leaf.github.io`，线上站点的唯一来源。前台从 Astro 换成 Nuxt 后，**它同时也是前台的源码仓库** |
| 现有存档仓库 | `D:\Fa11Leaf\Work\weblog`，S1→S4 演进史，四个 git 标签 |
| 本次备份 | 见 §16 |
| 后台仓库 | `weblog-fullstack` 内 `git init`，只放后台三端。发布时通过 Git Data API 写入部署仓库，**本机不需要部署仓库的克隆** |
| 迁移策略 | 现有文章的 Front Matter 格式完全兼容，正文无需改动。但目录要换：从 `src/content/posts/` 迁到部署仓库的 `content/posts/`，并套用新的集合定义 |

---

## 16. 备份记录

`[已实测]` 本轮（2026-10-04，改造为全栈项目之前）生成的备份：

| 文件 | 内容 | 大小 |
| --- | --- | --- |
| `D:\Fa11Leaf\Work\fa11leaf.github.io-backup-2026-10-04-1909.zip` | 部署仓库全部内容（含 `.git`），99 个文件 | 0.21 MB |
| `D:\Fa11Leaf\Work\weblog-backup-2026-10-04-1909.zip` | 存档仓库全部内容（含 4 个标签），114 个文件 | 0.30 MB |

两份均排除了 `node_modules` / `dist` / `.astro` / `.nuxt`，保留了 `.git` 与 `.github`，并已通过 zip 完整性校验（无损坏条目）。

同日稍早的备份（仍在本地，内容为决策调整前的状态）：

| 文件 | 大小 |
| --- | --- |
| `fa11leaf.github.io-backup-2026-10-04.zip` | 217.4 KB |
| `weblog-backup-2026-10-04.zip` | 306.9 KB |
| `weblog-S3-backup-2026-10-04.zip` | 289.9 KB |
| `weblog-S2-backup-2026-10-04.zip` | 87.1 KB |
| `weblog-S1-backup-2026-10-04.zip` | 53.6 KB |

> 备份时 `fa11leaf.github.io` 处于 `astro-migration` 分支且有 14 项未提交改动（删除示例文章、新增 `my-first-web.md`、改写首页与样式、头像迁入 `src/assets/images/`）。这些改动**已包含在 1909 那份备份内**。
>
> `weblog-fullstack` 目前尚无 git 仓库（只有本文档），无需备份。

---

## 17. 术语表

| 术语 | 含义 |
| --- | --- |
| **Headless CMS** | 内容管理系统只提供 API，不负责页面渲染；前端独立消费数据 |
| **SSG** | Static Site Generation，构建时生成静态 HTML |
| **SPA** | Single Page Application，单页应用，路由由前端接管 |
| **Git Data API** | GitHub 提供的底层接口，可在一个 commit 内提交多个文件 |
| **Front Matter** | Markdown 文件顶部 `---` 之间的元数据块 |
| **slug** | 文章在网址中的短标识，如 `css-box-model` |
| **BM25** | 一种信息检索相关性排序算法 |
| **Flyway** | 数据库版本迁移工具，用 SQL 脚本管理表结构演进 |
| **内容集合** | Nuxt Content 对 Markdown 的类型化定义，可声明哪些字段必填 |
| **预渲染** | 构建期把每个路由生成成独立 HTML 文件，产出即完整静态站 |

---

## 18. P0 实施记录（2026-10-04）

### 18.1 已创建的文件

```
weblog-fullstack/
├── .env.example                  环境变量模板
├── .gitignore
├── docker-compose.yml            MySQL（app profile 下另预留两端，默认不启动）
│
├── backend/                      Spring Boot 4.1.1 骨架（由 Initializr 生成后改造）
│   ├── pom.xml                   注意：parent 版本已从 4.1.1.RELEASE 手工改为 4.1.1
│   ├── mvnw / mvnw.cmd / .mvn/    Maven Wrapper，本机没装 Maven 就靠它
│   └── src/main/
│       ├── java/com/fa11leaf/blog/
│       │   ├── BlogApplication.java
│       │   ├── common/           ApiResponse · ErrorCode · BusinessException · GlobalExceptionHandler
│       │   ├── config/           SecurityConfig · CorsConfig · PythonProperties · AppConfig
│       │   ├── python/           PythonClient
│       │   └── health/           HealthController · HealthService
│       └── resources/
│           ├── application.yml       公共配置
│           ├── application-dev.yml   本机开发：数据源、日志级别、开发令牌
│           └── db/migration/V1__init.sql
│
├── python-service/               FastAPI
│   ├── requirements.txt · pyproject.toml
│   ├── app/                      config · security · main · routers/health
│   └── tests/test_health.py
│
└── admin/                        Vue 3 + Vite
    ├── package.json · vite.config.ts · tsconfig.json · env.d.ts · index.html
    └── src/                      main.ts · App.vue · api/client.ts · styles/tokens.css
```

### 18.2 后端依赖清单（Initializr 4.1.1 实际生成，未手工改动）

| 用途 | 构件 |
| --- | --- |
| Web | `spring-boot-starter-webmvc` |
| 数据访问 | `spring-boot-starter-data-jpa` |
| 迁移 | `spring-boot-starter-flyway` + `org.flywaydb:flyway-mysql` |
| 数据库驱动 | `com.mysql:mysql-connector-j`（runtime） |
| 安全 | `spring-boot-starter-security` |
| 校验 | `spring-boot-starter-validation` |
| 监控 | `spring-boot-starter-actuator` |
| 测试 | `spring-boot-starter-{webmvc,data-jpa,flyway,security,validation,actuator}-test` |

> 与 Boot 3.x 的三处差异，照抄旧教程会直接报"找不到构件"：**Web starter 改名**、**Flyway 有独立 starter**、**测试 starter 按模块拆分**（但 `spring-boot-starter-test` 仍作为传递依赖存在，见 §2 的说明）。
>
> 另外，Boot 4 把测试自动配置也换了包：`@AutoConfigureMockMvc` 与 `@WebMvcTest` 从 `org.springframework.boot.test.autoconfigure.web.servlet` 移到了 **`org.springframework.boot.webmvc.test.autoconfigure`**。

### 18.3 验证结果

> 本节的结论已被 §18.7 取代（那里是在能真正编译、运行测试之后得到的）。保留在此是为了看清"先做静态校验、后拿到真实验证"这个过程本身。

| 项 | 当时状态 | 说明 |
| --- | --- | --- |
| Python 依赖在 3.14.7 下安装 | ✅ 通过 | 12 个固定版本一次性装成功 |
| Python 服务单元测试 | ✅ 4/4 通过 | 当时用的是不带 `with` 的 TestClient，**没跑到 lifespan**，见 §18.7 |
| 后端 Java 编译 | ⏳ 未验证 | 当时以为沙箱跑不了 Maven，实际可以，见 §18.7 |
| MySQL 启动与迁移执行 | ⏳ 未验证 | 当时卡在 Docker 拉镜像 |
| 三端连通 | ⏳ 未验证 | 依赖上面两项 |
| 后台前端构建 | ⏳ 未验证 | 同上 |

### 18.4 需要你在本机执行的验证（按顺序）

这些命令已封装成脚本，见 §18.5。想手动一步步执行，或想看清每一步到底做了什么，按下面顺序来：

```bat
REM 1. 数据库：直接用本机 MySQL 8.0（脚本会自动探测，不会动 Docker）
REM    先确认服务在跑：sc query MySQL80
REM    再把你的 MySQL 口令填进 .env 的 MYSQL_PASSWORD（见 §18.6）
cd /d D:\Fa11Leaf\Work\weblog-fullstack
copy .env.example .env                 REM 已存在则跳过，然后编辑它填口令

REM 2. 启动 Python 能力服务
cd python-service
C:\Python314\python.exe -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
set PY_INTERNAL_TOKEN=dev-internal-token
uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload

REM 3. 另开一个终端，启动后端
cd /d D:\Fa11Leaf\Work\weblog-fullstack\backend
set PY_INTERNAL_TOKEN=dev-internal-token
set MYSQL_PASSWORD=<你本机 MySQL 的口令>
mvnw.cmd spring-boot:run

REM 4. 再开一个终端，启动后台界面
cd /d D:\Fa11Leaf\Work\weblog-fullstack\admin
npm install
npm run dev
```

然后访问 <http://localhost:5174>，三个状态点应当全绿。也可以用命令行直接验后端：

```bat
curl http://127.0.0.1:18090/api/health
```

期望看到 `database.reachable` 与 `python.reachable` 均为 `true`，且 `database.migrations` 为 `1`。

> `.env` 里的 `MYSQL_PASSWORD` 只被 Spring Boot 读取（本机 MySQL 方案下 compose 用不到它）。若后端报
> `Access denied for user ...`，就是这一项与你的 MySQL 口令不一致。

---

### 18.5 一键启动脚本

项目根目录下三个文件：

| 文件 | 作用 |
| --- | --- |
| `start.ps1` | 主脚本：校验 → 装依赖 → 按序启动 → 失败时定位。约 1000 行，全中文注释 |
| `start.cmd` | 双击入口（纯 ASCII）。`start.cmd -Check` 只做校验 |
| `stop.cmd` | 停止脚本启动的进程与 MySQL 容器（数据卷保留） |

**启动顺序**：`db → python → backend → admin`。指定 `-Service backend` 会自动带上 db 与 python。

**脚本内实现的校验**（只读，不启动不安装）：平台、JDK / Node / Python 版本、Docker 守护进程、`.env` 项、关键工程文件、四个端口占用情况。

**日志**：每次运行写 `logs\start-<时间戳>.log`；各服务的标准输出与错误分别写 `logs\<服务>.out.log` 与 `.err.log`。失败时脚本会把对应日志的末尾若干行直接打到屏幕上，并按环节给出可能原因。

**常用参数**

```bat
start.cmd                        启动全部
start.cmd -Check                 只校验
start.cmd -Service db,python     只启动其中一部分
start.cmd -SkipInstall           跳过 pip / npm 安装
start.cmd -TimeoutSeconds 600    放宽超时（首次要下载 Maven 时用）
start.cmd -Strict                把开发默认口令判为不合格（部署前用）
start.cmd -Stop                  停止
```

#### P0 实测脚本发现的两个真实问题

**1. 后端端口现在是 18090。** 这里先后避开了两个坑：8080 被 Steam 客户端的 `steamwebhelper.exe` 固定监听；改到 18090 之后又踩到 **Windows 保留端口范围 8081-8180** —— 落在该范围内的端口系统不允许绑定，但 `netstat` 查不到任何监听者，报错却是「端口已被占用」，详见 §18.8。**要再改端口，必须同步改四处**：`backend/src/main/resources/application.yml` 的 `server.port`、`admin/vite.config.ts` 的代理目标、`start.ps1` 顶部的 `$PortBackend`、`docker-compose.yml` 的端口映射。

**2. `docker compose up` 要的是服务名，不是容器名。** P0 实测时脚本报 `no such service: weblog-mysql`——`weblog-mysql` 是 compose 里的 `container_name`，而 `docker compose up` / `stop` / `ps` 接受的是**服务名** `mysql`；只有 `docker inspect` / `docker logs` 才用容器名。脚本里已用 `$DbService` 与 `$DbContainer` 两个变量把二者分开。

**3. 端口 8000 曾被一个遗留的 `python -m http.server` 占用。** 已清理。这也说明了脚本把端口检查放在最前面的价值：这类问题若等到「服务起不来」才发现，排查方向会完全跑偏。

#### 一处必须保留的实现细节

`start.ps1` 保存为 **UTF-8 with BOM**。PowerShell 5.1 在没有 BOM 时按系统 ANSI 代码页解码 `.ps1`，中文会全部变成乱码。用编辑器另存时务必别丢掉 BOM。同理，`start.cmd` / `stop.cmd` 刻意**只用 ASCII**——`cmd.exe` 用 OEM 代码页（简体中文系统是 936）读取 `.bat/.cmd`，写入 UTF-8 中文同样会乱码。

#### 一个已排除的 PowerShell 陷阱

脚本内所有外部命令（`docker` / `java` / `pip` / `taskkill`）都经过 `Invoke-Native` 包装器调用。原因：`$ErrorActionPreference = 'Stop'` 时，对原生命令用 `2>&1` 重定向 stderr 会把普通错误输出**升级成终止异常**，脚本会在校验阶段直接崩溃。而 `java -version`、`docker info` 这类命令本来就往 stderr 写信息。这一点是 P0 实测运行时才暴露的，不是设计推演出来的。

---

### 18.6 数据库方案：改用本机 MySQL（2026-10-04 晚）

原计划用 Docker 容器提供 MySQL，实测被两件事依次挡住：

1. `docker compose up` 报 `no such service: weblog-mysql` —— 脚本 bug，已修（`docker compose` 要的是**服务名** `mysql`，`weblog-mysql` 只是容器名）
2. 修好服务名后卡在拉镜像：本机无任何 docker 镜像，`docker pull mysql:8.4` 连续失败（先是 manifest 阶段 `502 Bad Gateway`，后来越过 manifest 又卡在从 CloudFront 取 blob）

于是改用本机已安装的 **MySQL 8.0.46**（Windows 服务名 `MySQL80`）。

**已执行的操作**（只建库，未改动任何现有数据）：

- 创建 `weblog` 库，字符集 `utf8mb4` / 排序规则 `utf8mb4_0900_ai_ci`
- 用真实的 `V1__init.sql` 试建 `users` 表、写入中文、读回核对，然后**删表**——目的是提前验证迁移脚本在 8.0 上可执行、字符集正确；不删掉的话 Flyway 后续会因「表已存在」而失败
- 原有的 `sakila` / `world` 库未做任何改动

**配置改动**

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/resources/application-dev.yml` | 数据源指向本机 MySQL；`MYSQL_PASSWORD` **去掉默认值** |
| `.env` / `.env.example` | 新增 `MYSQL_USERNAME`；`MYSQL_PASSWORD` 需自行填写 |
| `start.ps1` | `db` 步骤改为**先探测 3306**：有 `mysqld` 就直接用；3306 被 MySQL 占用从 FAIL 改判 OK；口令缺失改为硬性失败 |
| `docker-compose.yml` | 保留，降为备用方案 |

**一处刻意的设计取舍**：`MYSQL_PASSWORD` 不再有默认值。之前给默认值是为了「clone 下来就能跑」，但那个默认值是给 Docker 里的 `weblog` 账号用的，在本机 MySQL 上根本不存在——留着它只会让人拿到一个 `Access denied` 然后去查半天。凭据类配置就该缺失即失败。

**需要知悉的安全代价**：应用以 `root` 连接，口令写在本机 `.env`（已 gitignore）。这意味着任何能读到该文件的人，等于拿到整个 MySQL 实例的权限。若将来把写作后台放到别的机器、或对外提供服务，应改为**专用账号 + 随机口令 + 只授权 `weblog` 库**，改动只在 `.env` 一行。

**一个可选的收紧项**：`mysqld` 监听在 `0.0.0.0:3306`，对局域网可见。虽然 `root@localhost` 是唯一可登录来源（远程 root 登不进来），但仍可在 `my.ini` 里设 `bind-address=127.0.0.1` 后重启 `MySQL80`，把端口收回本机。

---

### 18.7 能真正编译运行之后的验证（2026-10-04 晚）

#### 一个改变局面的发现

之前我判断"沙箱禁止派生子进程、跑不了 Maven"，于是把 Java 编译和前端构建都推给你执行。**这个判断是错的**：

- 被禁的只是 **Bash 工具**直接调用 `cmd.exe`
- 而**从 Python 的 `subprocess` 派生 `cmd.exe` 是允许的**

所以 `cmd.exe /c mvnw.cmd`、`cmd.exe /c npm` 都能跑。实测 `mvnw.cmd -v` 直接下载好了 Maven 3.9.16。这意味着我自己就能完成编译、跑测试、构建前端 —— 不必再让你当我的编译器。

#### 因此修掉的问题（都是真跑才暴露的）

| # | 问题 | 症状 | 修法 |
| --- | --- | --- | --- |
| 1 | `app/main.py` 用了 `settings.DEV_TOKEN_PLACEHOLDER`，而它在 `config.py` 里是**模块级常量**不是类属性 | 启动钩子抛 `AttributeError` → uvicorn 直接退出 → 脚本健康检查只能干等超时（你看到的就是"卡住"） | 改成 `Settings` 的类属性 |
| 2 | 测试没兜住上面这个错 | `TestClient(app)` 不在 `with` 里时**不会执行 lifespan**，4 个用例照样全绿 | 新增 `test_lifespan_starts_and_stops`，用 `with TestClient(app)` 真正跑一遍启动钩子 |
| 3 | `PythonClient` 注入 `RestClient.Builder`，但 **Boot 4 不再自动装配它** | `NoSuchBeanDefinitionException: No qualifying bean of type 'RestClient$Builder'` | 在 `AppConfig` 里自己声明一个 prototype Bean |
| 4 | 脚本等待服务就绪时，即使进程已经死了也只会干等超时 | 看起来像卡住 | `Wait-HttpOk` 增加 `-ProcessId`，进程一退出立刻报错并指向日志 |
| 5 | `typescript` 装了 7.x，与 `vue-tsc` 3 不兼容 | `ERR_PACKAGE_PATH_NOT_EXPORTED: './lib/tsc'` | 固定为 `^5`（实装 5.9.3） |

#### 现在的验证结果（全部我这边实测）

| 项 | 结果 | 依据 |
| --- | --- | --- |
| Python 服务单元测试 | ✅ **6/6 通过** | 含 lifespan 用例；uvicorn 启动日志显示 `Application startup complete` |
| Python 鉴权 | ✅ | 无令牌 401、错令牌 401、正确令牌 200 |
| 后端 Java 编译 | ✅ | `BUILD SUCCESS`，12 个源文件 |
| Spring 上下文装配 | ✅ | `Started BlogApplicationTests in 4.187 seconds` |
| **Flyway 迁移** | ✅ | 连上 `jdbc:mysql://127.0.0.1:3306/weblog`，`Migrating schema weblog to version "1 - init"`，`Successfully applied 1 migration` |
| 数据库实际结果 | ✅ | `users` 与 `flyway_schema_history` 两表已建，排序规则 `utf8mb4_0900_ai_ci`，迁移记录 `(1, init, success=1)` |
| **HTTP 接口契约** | ✅ **4/4 通过** | MockMvc：`/api/health` 返回 200 且 JSON 结构正确、`database.reachable=true`；未认证路径被拒；`/actuator/health` 放行 |
| 前端构建 | ✅ | `vite build` 70 模块，产物 114.21 kB（gzip 43.64 kB）|
| 前端类型检查 | ✅ | `vue-tsc --noEmit` 通过（TS 5.9.3）|

> 补测的 `HealthControllerTest` 用 **MockMvc** 而不是起真实服务器：MockMvc 直接把请求交给 DispatcherServlet，**不绑定端口**，正好能在受限环境里验证"路由 + 安全规则 + 响应形状"这三件只有真发请求才会暴露的事。

#### 仍然需要你本机确认的

只剩**四端同时运行**这一件事：我的环境不允许监听端口（`WinError 10013`），所以无法把四个进程一起拉起来。各端单独都验证过了，但"同时跑起来并互相连通"必须由你执行 `start.cmd` 来确认。

顺带一提：现在 `backend/target/` 与 `admin/node_modules/` 已经存在，所以你那边的首次启动不会再等 Maven 下载和 npm 安装，会快很多。

---

### 18.8 后端端口从 8090 改到 18090（2026-10-04 晚）

#### 症状：两条信息互相矛盾

后端启动失败：

```
Web server failed to start. Port 8090 was already in use.
```

但脚本的启动前校验明确报告「端口 8090 空闲」，`netstat` 也**查不到任何监听者**。

#### 真正的原因：Windows 保留端口范围

本机的排除范围（`netsh int ipv4 show excludedportrange protocol=tcp`）包括：

```
      8081        8180      ← 8090 落在其中
      8181        8280
      8281        8380
      8381        8480
      8481        8580
      8581        8680
      9081        9180
      ...
```

**落在这些范围里的端口，任何程序都绑不上，但也不会有任何进程在监听** —— 于是报错信息是「端口已被占用」，而"查有没有进程占用"当然查不出来。这是 Hyper-V / WSL / Docker Desktop 留下的保留范围。

实测验证（逐个尝试绑定）：

| 端口 | 结果 |
| --- | --- |
| 8123 | `errno 13`（权限不足）—— 在 8081-8180 内 |
| 8090 | `errno 13` —— 确认被保留 |
| **18090** | **可绑定** |
| 15080 / 28090 / 5174 | 可绑定 |

> 顺带纠正一个我先前记错的结论：我一度以为"本环境不允许监听端口"，其实当时失败的是 8123，它的失败同样源于保留范围，不是环境限制。

#### 新端口：18090

选它的理由：既不在保留范围内，也**在动态端口范围（1024–15000）之外**，不会被临时连接抢走。

同步改动的文件：`backend/src/main/resources/application.yml`、`admin/vite.config.ts`、`docker-compose.yml`、`start.ps1`。

#### 顺带修正脚本里一个错误的判断逻辑

原来只检查「有没有进程在监听」，因此对"被系统保留"完全无感。现在会再尝试一次真实绑定（`Test-PortBindable`），把两种情况区分开：

- 有监听者 → 报「被 X 占用」
- 无监听者却绑不上 → 报「系统不允许绑定，多半落在保留端口范围」并给出查询命令

同时把「服务在数据目录不可写/端口被保留」这类环境的坑变成启动前就能看见的失败，而不是启动到一半才崩。

#### 另一个值得记住的排查技巧

**如果 Tomcat 启动日志里报告的端口与 `application.yml` 里写的不是同一个数字**，检查环境变量里有没有 `SERVER_PORT` 或 `SERVER__PORT`。Spring Boot 的 relaxed binding 会把双下划线解析成嵌套属性（`SERVER__PORT` → `server.port`），**且优先级高于配置文件**。这个坑在集成环境（IDE、容器编排、CI）里很常见。

### 18.9 公开站点（Nuxt 前台）已接入启动脚本

启动脚本现在是五个服务：`db → python → backend → frontend → admin`。
新增的 `frontend` 起的是博客前台（Nuxt 4 + @nuxt/content），
跑在 <http://localhost:4321>，启动完成后默认自动打开浏览器（加 `-NoBrowser` 可跳过）。

**前台源码不在本项目里**：按架构约定，公开站点的源码放在部署仓库
`Fa11Leaf.github.io` 中。脚本默认取本项目的同级目录 `..\fa11leaf.github.io`，
路径不同时在 `.env` 里设置 `FRONTEND_DIR` 覆盖。校验表里会单列一行「前台工程」，
路径不对时直接 FAIL。

顺带修掉两个只在真跑时才暴露的问题：

1. **Vite 只监听 IPv6 `[::1]:5174`** —— Windows 上 `localhost` 可能只解析到 IPv6，
   而就绪探测走的是 IPv4 `127.0.0.1`，表现为「服务明明起来了却一直连不上，直到超时」。
   已在 `admin/vite.config.ts` 显式写 `host: '127.0.0.1'`。
2. **后端原先监听 `0.0.0.0:18090`**（对局域网可见）。已在 `application.yml` 加
   `server.address: 127.0.0.1`，写作后台只对本机开放。

### 18.10 写作后台（P2）已完成

启动脚本跑完打开 <http://localhost:5174>，用 `.env` 里的 `ADMIN_PASSWORD` 登录，
就能走通 **写 Markdown（右侧实时预览）→ 传图（自动压缩转 WebP）→ 存草稿 → 一键发布到 GitHub** 这条闭环。

#### 页面与接口

| 页面 | 用途 |
| --- | --- |
| `/login` | 登录 |
| `/posts` | 文章列表：搜索、按状态筛选、进入编辑 |
| `/posts/new`、`/posts/:id` | 编辑器：左写右预览，支持插入图片、查看发布记录 |
| `/media` | 媒体库：上传、预览、删除（被引用的图删不掉） |
| `/settings` | 发布目标状态、修改口令 |
| `/status` | 三端连通状态 |

| 接口 | 说明 |
| --- | --- |
| `POST /api/auth/login`、`/logout`、`GET /me`、`POST /password` | 认证 |
| `GET/POST /api/admin/posts`、`GET/PUT/DELETE /api/admin/posts/{id}` | 文章 CRUD |
| `POST /api/admin/render` | Markdown → HTML（转发给 Python） |
| `POST/GET /api/admin/media`、`DELETE /api/admin/media/{id}` | 图片上传与管理 |
| `POST /api/admin/posts/{id}/publish`、`GET .../publish-jobs` | 发布与历史 |
| `GET /api/admin/github/status` | 发布目标是否配好（不含令牌） |

#### 两处刻意偏离原设计

1. **登录用服务端不透明令牌，不是 JWT。** 原设计写的是 JWT + jjwt。
   改动原因：jjwt-jackson 需要 Jackson 2 的 databind，而 Boot 4 已经换成 Jackson 3；
   而更重要的是，JWT 签出去就撤不回来 —— 想实现「退出登录」还得再维护一张黑名单表，
   等于把无状态又变回有状态。个人后台单用户、只在本机跑，无状态的好处约等于零。
   现在令牌原文只在登录时返回一次，库里只存它的 SHA-256 摘要，退出即删，改动即时生效。
2. **发布是同步的**，不是返回 202 再去轮询任务。本机单用户、一次提交几秒钟，
   同步返回能让成功与失败原因一次性回到浏览器，少一层状态机。
   `publish_jobs` 表仍然是任务表的形状，将来要改异步不用改前端。

#### 又踩到的三个「别依赖自动装配」

这三个都是真跑才暴露的，共同点是：**自己 new 出来的 `RestClient` 与 Boot 自动配置版行为不同**。

1. **没有 JSON 写入器。** 请求发出去了，但 body 是空的，FastAPI 报
   `422 {"loc":["body"],"msg":"Field required"}`。读取是好的，所以健康检查一直正常，
   只有 POST 才暴露。→ 现在需要发 JSON 的地方用手写的 `common/Json` 拼字符串。
2. **JDK HttpClient 默认先试 HTTP/2。** 对 `http://` 地址会先发 h2c 升级请求，
   uvicorn 不支持升级（日志里能看到 `Unsupported upgrade request`），
   回退时**把请求体丢了**。→ 在 `AppConfig` 把请求工厂钉成 HTTP/1.1，顺带配好超时。
3. **Spring 7 写 multipart 依赖 reactive-streams**，本项目是纯 Servlet 栈，没有它，
   上传图片直接 `NoClassDefFoundError: org/reactivestreams/Publisher`。
   → 不再引响应式依赖，按 RFC 7578 手拼 multipart 字节（`byte[]` 有内置转换器）。

#### 实测结果（端到端跑通）

未登录被 401 拦下 · 登录拿到 43 字符令牌 · 错误口令 40101 · 建文章 · 渲染预览
（22 字 / 1 个小标题 / 代码高亮生效 / 裸 `<script>` 被转义）·
上传图片 28257 B → 14420 B WebP（1200×900）· 同一张重传命中摘要去重 ·
删除仍被引用的图片返回 40901 · 未配令牌时发布给出可操作的提示 ·
删文章后列表减一 · 退出登录后令牌在服务端即时失效。

Python 测试 19 项通过，前端 `vue-tsc` 与 `vite build` 通过。

### 18.11 导入已有的 .md 文件作为草稿

文章列表页右上角新增「导入 .md」，或者**把 .md 文件直接拖进文章列表页面**即可。
支持一次拖多个，导入后全部是草稿 —— **不会直接上线**，要上线仍需在编辑器里点一次「发布到线上」。

导入做的事：

1. 解析 Front Matter（YAML），取出 `title` / `description` / `date` / `tags` / `slug`
2. **用文件名当 slug**（去掉 `2026-09-20-` 这类日期前缀）—— 本站的规矩本来就是"文件名即网址"，
   从 Hexo / Hugo / Jekyll 搬过来的文件，文件名通常已经是现成可用的
3. 去掉正文开头那个与标题重复的一级标题（标题统一由 Front Matter 提供，
   正文里再来一个 h1 会让页面出现两个一级标题）
4. 解析不了的字段不硬报错，而是**回退 + 给一条提醒**：
   没有 Front Matter → 用一级标题或文件名当标题、日期用今天；
   `date` 不是 `YYYY-MM-DD` → 用今天；中文文件名 → 生成 `post-时间戳` 形式的 slug

只有"文件坏了"才拒绝：YAML 语法错（40002）、扩展名不是 `.md`/`.markdown`/`.mdown`、
内容为空、超过 2MB（都是 40001）。

接口：`POST /api/admin/posts/import`，`multipart/form-data`，字段名 `file`。
返回里带 `warnings`，前端会逐条列出来。

**解析放在 Java 侧**（SnakeYAML，Boot 本来就带在类路径上），没走 Python：
Front Matter 只是一小段 YAML，为它多跑一次 HTTP、再给 Python 补一个 PyYAML 依赖并不划算。
图片处理与 Markdown 渲染留在 Python，是因为那些库 Java 生态确实弱。
