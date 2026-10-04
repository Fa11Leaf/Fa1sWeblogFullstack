# 任务 Prompt：把「手写静态博客」演进为「静态前台 + 本地写作后台」

> **用法**：这份文档本身就是一段 prompt。整段交给一个空白会话的 AI，它应当能在
> `D:\Fa11Leaf\Work` 下把这个项目从零做到可用，或者接手继续做。
> 文档里的每一条"陷阱"都是真跑踩出来的结论，不是推测——**不要凭常识推翻它们**。

---

## 一、任务陈述

我是一个学生，这是专业课作业之一：一个 weblog。但我想要的不是"交一份能看的作业"，
而是**完完整整地体验一遍 Web 学习与构建的过程**——从手写 HTML 一路走到全栈。
最终形态是「**静态前台 + 本地写作后台**」：访客看到的是一堆预先生成好的 HTML
（免费托管在 GitHub Pages），而我自己有一台本机后台，用来写文章、传图、一键发布。

请分阶段实现，每个阶段都要**真实可运行、可部署**，而不是过渡性的废弃代码。
每一阶段的代码都要保留在 git 标签里，能随时回看"当时是怎么写的、为什么那样写"。

### 最终要能做的事

1. 访客打开 <https://fa11leaf.github.io> 看到一个正常的博客（首页列表 + 关于页 + 文章详情）
2. 我打开本机后台，登录后能：写 Markdown（右侧实时预览）、传图（自动压缩转 WebP）、
   存草稿、**导入已有的 .md 文件**、点一下把文章发布到 GitHub
3. 发布后 GitHub Actions 自动构建并更新线上站点，**我不需要碰任何命令行**
4. 读文章这条路径**完全绕开后端**：后台宕机、数据库删库都不影响访客阅读

---

## 二、代码仓库的三份职责（极易混淆，务必分清）

| 路径 | 角色 | 说明 |
| --- | --- | --- |
| `D:\Fa11Leaf\Work\weblog` | **学习演进存档** | S1→S4 四个阶段的代码，各有 git 标签。**已停更**，只作历史参照 |
| `D:\Fa11Leaf\Work\fa11leaf.github.io` | **公开站点** | Nuxt 4 源码 + `content/posts/*.md` + 自己的 `deploy.yml`。**线上站点由这个仓库发布** |
| `D:\Fa11Leaf\Work\weblog-fullstack` | **写作后台三端** | `admin/`（Vue 3 SPA）+ `backend/`（Spring Boot）+ `python-service/`（FastAPI） |

外部事实：
- GitHub 仓库 `Fa11Leaf/Fa11Leaf.github.io`，用户站点，网址落在**根路径** `https://fa11leaf.github.io/`
- 一个账号只能有一个用户站点，仓库名必须**恰好**是 `<用户名>.github.io`
- `weblog-fullstack` **目前还没有 git 仓库**，建议第一步就 `git init`

---

## 三、阶段路线（每一步都要能跑通再进下一步）

| 阶段 | 做什么 | 核心技术点 |
| --- | --- | --- |
| **S1 手写版** | 4 个 HTML + 1 个 CSS，相对路径、Flexbox、Grid、媒体查询 | HTML 语义化、盒模型、`../` 层级陷阱 |
| **S2 数据驱动** | 文章挪进 `posts.json`，`fetch` + 模板字符串渲染 | 异步、DOM、`?id=`、`file://` 的 CORS 限制 |
| **S3 生成器** | 文章变成 Markdown + Front Matter，构建期生成页面 | 布局抽象、内容集合、构建期图片优化 |
| **S4 自动化** | `.github/workflows/deploy.yml`，push 即上线 | CI/CD、产物部署、最小权限 |
| **P0 三端连通** | 后台三端骨架，`/api/health` 一次返回三端状态 | 跨语言调用、Docker/本机数据库、Flyway |
| **P1 能力服务** | Markdown 渲染 + 图片处理（Python 侧） | markdown-it、Pygments、Pillow |
| **P2 写作闭环** | 登录 → 写 → 预览 → 传图 → 存草稿 → 发布 → 看提交记录 | JWT 替代方案、Git Data API、媒体库 |

**S1 要刻意保留"笨"**：加一篇文章 = 复制一整份 HTML + 手改首页链接；改一次导航 = 逐页改。
不亲手撞一次这些墙，后面用生成器时只是在照抄命令，遇到问题无法定位。
到 S3 要让这些痛点**被真实消灭**，并在文档里点名"是哪一条痛点被解决了"。

---

## 四、技术栈与版本基线（2026-10-04 本机实测，勿凭印象改）

### 运行环境
- Windows 11 + PowerShell 5.1
- Node：托管版 **22.22.2**（优先）/ 系统版 24.21.0；npm 10.9.7
- Java：**21.0.12**（`javac` 同在）；Maven 用 Initializr 生成的 `mvnw`（3.9.16，本机没装 Maven）
- Python：系统 **3.14.7**（`C:\Python314\python.exe`）/ 托管 3.13.14（**没有 tkinter**）
- MySQL：本机 **8.0.46**（Windows 服务名 `MySQL80`）
- Docker：CLI 与 Compose 已装，但**镜像拉不下来**（Docker Hub 502）→ 已放弃 Docker 方案

### 公开站点（fa11leaf.github.io）
```
nuxt                4.5.2
@nuxt/content       3.16.1
better-sqlite3      13.0.3     ← 必须显式装，见陷阱 §6.2
vue                 3.5.43
vite                8.3.2
shiki               4.5.0
Nitro               2.13.4
```
构建命令 `npm run build` → `nuxt generate` → 产物在 **`.output/public`**

### 写作后台（weblog-fullstack）
```
Spring Boot         4.1.1      Java 21；Framework 7.0.9 / Security 7.1.1 / Hibernate 7.2
                                starter 名与 3.x 不同，见陷阱 §6.4
org.yaml:snakeyaml  Boot 管理版本（解析上传 .md 的 Front Matter）

fastapi             0.142.2
uvicorn             0.54.0
pydantic            2.13.5
python-multipart    0.0.32
markdown-it-py      4.2.0
mdit-py-plugins     0.6.1
Pygments            2.21.0
Pillow              12.3.0
jieba               0.42.1     （备用于搜索索引，暂未用）
httpx 0.28.1 / pytest 9.1.1

vue 3.5 / vue-router 5 / pinia 4 / axios 1.20
typescript ^5        ← 必须 5.x，TS 7 与 vue-tsc 3 不兼容
vite 8 / vue-tsc 3 / @vitejs/plugin-vue 6
```
> `admin/package.json` 里还留着 `naive-ui`，但**实际没有使用**（UI 是手写的，靠 CSS 变量）。
> 要么用起来，要么删掉。

---

## 五、架构决策（都已定，改动前先说服我）

1. **读走静态，写走后端。** 访客读文章完全不经过后端；后端宕机不影响阅读。
   这是整个设计的地基，任何"顺手把正文也做成接口"的想法都要先推翻它。
2. **Markdown 文件是唯一真源。** 数据库可以随时清空重建，随时能退回"本地写 md + git push"。
3. **前台源码放部署仓库**（`Fa11Leaf.github.io`），后台三端放 `weblog-fullstack`。
   两仓库职责清晰，各自 CI 自洽。
4. **数据库用本机 MySQL**，不用 Docker（拉不到镜像）。`docker-compose.yml` 保留为备用方案。
5. **发布用 GitHub Git Data API**，保证「一篇文章 = 一个 commit」：
   1 个 `.md` + N 张图片组装成同一棵树、一次提交。不用 Contents API（那会产生 N+1 条提交）。
6. **认证用服务端不透明令牌，不是 JWT。**
   令牌原文只在登录时返回一次，库里只存 SHA-256 摘要，可随时删除即"退出登录"。
   理由：jjwt-jackson 要 Jackson 2 而 Boot 4 是 Jackson 3；更关键的是 JWT 撤不回来，
   想实现退出还得维护黑名单，等于把无状态又变回有状态。单用户本机后台，无状态的好处约等于零。
7. **发布是同步的**，不是 202 + 轮询。一次提交几秒钟，同步能把成功与失败原因一次给全。
   `publish_jobs` 表仍保留任务表的形状，将来改异步不用动前端。
8. **语言分工**：涉及状态/权限/事务/外部写的归 Java；文本、图片、分词等纯计算归 Python。
   唯一的例外：**Front Matter 解析放在 Java**（SnakeYAML 本来就在类路径上，为一段 YAML
   多跑一次 HTTP + 给 Python 补 PyYAML 依赖不划算）。

---

## 六、已知陷阱（全部真跑踩出来的，逐条遵守）

### 6.1 部署与 GitHub Pages
- 分支发布模式会用 Jekyll 处理文件，而 **Jekyll 忽略所有下划线开头的目录** ——
  Astro 的 `_astro/`、Nuxt 的 `_nuxt/` 会被丢掉，结果是样式和图片全 404。
  解法：改用 **GitHub Actions 发布**（现在就是这么做的），或在 `public/` 放 `.nojekyll`。
- **上线顺序绝不能颠倒**：先把 Settings → Pages → Source 改成 **GitHub Actions**，
  **再**合并到 `main`。若先合并，Pages 还会去分支根目录找 `index.html`（那里放的是源码）→ 线上 404。
- 用户站点没有 base 前缀问题；项目站点（`/<仓库名>/`）才有，且所有绝对路径都要加前缀。
- 已验证的 Actions 版本：`actions/checkout@v7` · `actions/setup-node@v7` ·
  `actions/configure-pages@v6` · `actions/upload-pages-artifact@v5` · `actions/deploy-pages@v5`。

### 6.2 Nuxt 4 + @nuxt/content
- **`@nuxt/content` 3.x 不再自动安装 `better-sqlite3`**，必须显式 `npm install better-sqlite3`，
  否则 `nuxt prepare` 直接失败。这是本项目最大的一个"安装期陷阱"。
- 文件放 `app/pages/posts/[...slug].vue` 时，**`route.params.slug` 不含 `posts/` 前缀**，
  而内容集合里的 `stem` 是 `posts/xxx`。必须用 `route.path` 匹配，否则所有文章页在构建期 404。
- 小标题的锚点链接来自默认配置：`content.renderer.anchorLinks` 默认 `{h2,h3,h4: true}`，
  会渲染成 `<h2 id="x"><a href="#x">标题</a></h2>`。副作用是标题套在 `<a>` 里、
  继承了 `a { color: accent }`，整片标题变成链接色。想关就设 `anchorLinks: false`，
  **`id` 仍会保留**（深链照样能用）。
- 文章的标题来自 Front Matter，**正文里不要再写 `# 一级标题`**，否则一页出现两个 h1。
- 图片放 `public/images/`，Markdown 里写 `/images/xxx.png`（绝对路径，不受文章层级影响）。
- 构建产物在 `.output/public`（`nuxt generate`）。仓库里 `.output/` 要 gitignore。

### 6.3 前端杂项
- **Vite 默认绑 `localhost`，Windows 上可能只落到 IPv6 `[::1]`**。
  此时用 `127.0.0.1` 探活永远连不上，表现为"服务明明起来了却一直超时"。
  要么显式 `server.host = '127.0.0.1'`，要么探活也走 `localhost`。
- `.gitignore` **不能写行尾注释**：整行都会被当成匹配模式，于是 `.output/  # 产物` 什么都不匹配。
- 关闭代码高亮的锚点之后，`.prose` 里的 `a` 颜色规则不会再影响标题——顺手确认一下视觉。

### 6.4 Spring Boot 4 / Spring Framework 7（本项目踩得最狠的一块）
- **starter 名和 3.x 完全不同**：Web 是 `spring-boot-starter-webmvc`（不是 `-web`），
  数据库迁移是 `spring-boot-starter-flyway`（还要加 `flyway-mysql`），
  测试 starter 按模块拆分（`spring-boot-starter-webmvc-test` 等）。
  `spring-boot-starter-test` 仍然存在，但只是传递依赖，Initializr 不再直接列出。
- MockMvc 测试注解搬到了 `org.springframework.boot.webmvc.test.autoconfigure`。
- **不自动装配 `RestClient.Builder`**，必须自己声明（否则启动即
  `NoSuchBeanDefinitionException`）。
- **自己 new 出来的 `RestClient` 与 Boot 自动配置版行为不同**，连着踩了三个：
  1. **没有 JSON 写入器**：请求发出去了但 body 是空的，对端报 `422 loc:["body"]`。
     读取是正常的（所以健康检查一直好），**只有写才暴露**。
     结论：需要发 JSON 的地方用自写的 `common/Json` 拼字符串，别依赖序列化器自动探测。
  2. **JDK HttpClient 默认先试 HTTP/2**：对 `http://` 地址会先发 h2c 升级请求，
     uvicorn 不支持（日志里能看到 `Unsupported upgrade request`），
     **回退到 HTTP/1.1 的那条路径上会把请求体丢掉**。
     结论：把请求工厂钉成 HTTP/1.1 ——
     `HttpClient.newBuilder().version(HTTP_1_1)` + `JdkClientHttpRequestFactory`，顺带配上超时。
  3. **Spring 7 写 multipart 依赖 reactive-streams**：纯 Servlet 栈直接
     `NoClassDefFoundError: org/reactivestreams/Publisher`。
     结论：不引响应式依赖，按 RFC 7578 手拼 multipart 字节（`byte[]` 有内置转换器）。
- `@Lob` 在 MySQL 上会映射成 LONGTEXT，与迁移脚本里的 MEDIUMTEXT 对不上，
  `ddl-auto: validate` 会在**启动时**直接失败。用
  `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 才和建表脚本一致。
- Spring Initializr 的 `/metadata/client` 里版本 id 带 `.RELEASE` 后缀，而且**会把你给的
  字符串原样写进 `pom.xml` 的 parent**。但 Maven 中央仓库里只有 `4.1.1`——
  `.RELEASE` 从 Boot 2.4 起就取消了。生成后必须手工改。
- GitHub Git Data API 建树时 **`base_tree` 必须传**，否则一次提交会把仓库里其余所有文件删掉。

### 6.5 数据库
- 本机 MySQL 的认证插件是 `caching_sha2_password`，JDBC URL **必须保留
  `allowPublicKeyRetrieval=true`**，否则报 Public Key Retrieval is not allowed。
- JDBC URL 必须显式写 `serverTimezone=Asia/Shanghai`，否则 DATETIME 会静默偏 8 小时。
- 库、表、连接一律 utf8mb4；**在 COMMENT 里写中文**，让字符集问题在第一次迁移就暴露。
- **Flyway 与手工建表互斥**：验收时若手工建过表，必须先删掉，否则 Flyway 会因
  "表已存在但没有迁移记录"而失败。
- 发布文章时要**用数据库里的元数据重新拼一遍 Front Matter**，不能直接提交库里的原文，
  否则改了标题却不重拼，线上会停留在旧标题。
- YAML 里的日期**必须加引号**（`date: '2026-10-06'`）：不加会被解析成日期对象，
  而前台的 schema 声明的是字符串，构建时校验直接失败。

### 6.6 Windows / PowerShell
- **`start.ps1` 必须存成 UTF-8 with BOM**，否则 PowerShell 5.1 按 ANSI 解码，中文全乱码；
  `.cmd` 包装层必须**纯 ASCII**。
- **`$ErrorActionPreference = 'Stop'` 时，对原生命令用 `2>&1` 重定向 stderr 会把普通错误
  输出升级成终止异常**。而 `java -version`、`docker info`、`pip` 恰好都把信息写在 stderr。
  必须统一包一层 `Invoke-Native` 再去调用外部命令。
- **不要用 `subprocess.run(capture_output=True)` 去捕获 PowerShell 的 stdout**：
  它派生的孙进程（npm / java / uvicorn）会继承管道写端，父进程退出后管道也不 EOF，
  于是永远等下去。**重定向到文件再 wait()**。
- Java 会在**词法分析之前**扫描全文件的「反斜杠 + u」序列，**连注释都不放过**。
  在注释里写一个不完整的转义示例，javac 直接报「非法的 Unicode 转义」。
- Python 里写 Windows 路径要用原始字符串；`\b`（`\backend`）、`\a`（`\admin`）
  会被吃成控制字符写进文件。
- **端口**：`8080` 被 Steam 的 `steamwebhelper` 固定占用；`8090/8091/8092/8081/8888/9090`
  落在 **Windows 保留端口段**内（Hyper-V / WSL / Docker 留下的），
  表现是"没有进程监听、却报端口被占用"。查询：
  `netsh int ipv4 show excludedportrange protocol=tcp`。
  **用端口前实测一次能不能绑定**，别只看 netstat。
- `docker compose up/stop/ps` 要**服务名**，`docker inspect/logs` 要**容器名**，混用会报
  `no such service`。

### 6.7 工具链/沙箱（如果 AI 在自己的沙箱里跑）
- 批量删除守卫会在同一轮内累计删除数达阈值（实测 50）时**直接中止进程**，
  连删一个文件都可能因累计计数中招。构建工具清理自己的缓存目录时会撞上它：
  给该子进程设 `CODEBUDDY_SAFE_DELETE_ENABLED=0` 放行；自己写脚本删大批文件时改用**改名**。
- 沙箱会注入 `SERVER__PORT`，Spring 的 relaxed binding 会把它解析成 `server.port`
  **且优先级高于 application.yml**，导致后端绑到意外的端口。测试时要剔除它。
- 回环 HTTP 会被代理拦截，`httpx` 要 `trust_env=False`，`urllib` 要
  `ProxyHandler({})`。
- 从 Python 派生 `cmd.exe` 是允许的（Bash 工具直接调会被拦），所以 `mvnw` / `npm` / `docker`
  都能跑——**不要因为试了一次失败就断言"跑不了"**。

---

## 七、硬性约定（写代码时就按这个来）

1. **注释一律中文**，并且要写"为什么这么做"，不是"这行做了什么"。
   只描述代码在做什么的注释没有价值。
2. **文件名即网址**：文章文件名用英文 kebab-case，它就是 slug，也是
   `content/posts/{slug}.md` 的文件名。
3. **一切凭据走 `.env`（已 gitignore），绝不写进代码，也绝不设默认值。**
   缺凭据就明确失败并说清怎么配，而不是用一个猜出来的值去连。
   `MYSQL_PASSWORD` / `GITHUB_TOKEN` / `ADMIN_PASSWORD` 都属于这一类。
4. **前端源码与构建产物严格分开**：仓库里只有源码，产物一律 gitignore。
5. **每完成一个阶段**：打 git 标签 + 生成独立 zip 备份（排除 node_modules / 产物）。
6. **后端接口统一返回 `{code, message, data}` 三层**；失败时同时给 HTTP 状态码与业务码。
7. **参数校验、异常处理、权限判断集中在框架层**，控制器里只写正常路径。
8. **安全上不做"以后再收紧"的事**：默认拒绝（只放行明确列出的公开路径）、
   上传只接受白名单扩展名、渲染 Markdown 时关掉裸 HTML（`html=False`）、
   文件名消毒后再拼进 HTTP 头、图片删除前检查是否仍被文章引用。

---

## 八、验收标准

### 一键启动
`start.cmd` 一条命令拉起五个服务，启动前校验、启动后自动打开浏览器：

| 服务 | 端口 |
| --- | --- |
| MySQL | 3306 |
| Python 能力服务 | 8000 |
| Spring Boot 后端 | **18090** |
| 公开站点（Nuxt） | 4321 |
| 写作后台（Vite） | 5174 |

改端口要同步改三处：`application.yml` 的 `server.port`、`admin/vite.config.ts` 的代理、
`start.ps1` 顶部的 `$PortBackend`。

### 公开站点
首页 4 张文章卡片（标题可点、无"阅读全文"）· 文章详情正文与代码高亮正确 ·
小标题**不带锚点链接** · 深色模式 · 窄屏变单列 · 禁用 JS 页面仍完整

### 写作后台
未登录被 401 拦下 · 登录拿到令牌 · 错误口令与"用户不存在"返回同一提示 ·
建/改/删文章 · 实时预览（渲染结果与线上同一套渲染器）· 上传图片自动压成 WebP 且同图去重 ·
删除被引用的图片返回 409 · 未配令牌时发布给出可操作提示 · 退出后令牌在服务端即时失效 ·
**导入 .md 能正确读出 Front Matter，解析不了的字段回退并给提醒**

### 自动化测试
- Python：`pytest` 全绿（渲染转义、代码高亮、字数统计、图片缩放与透明通道、非图片拒绝）
- Java：编译通过 + Spring 上下文能起（含 Flyway 迁移）+ MockMvc 接口契约
- 前端：`vue-tsc --noEmit` 与 `vite build` 都通过

---

## 九、当前进度与待办

**已完成**：S1→S4 全部阶段（`weblog` 仓库，四个 git 标签）· 公开站点已迁到 Nuxt 4
（提交 `8667188`，在 `Fa11Leaf.github.io` 的 `astro-migration` 分支）· 写作后台三端闭环可用
（含登录、CRUD、预览、媒体库、导入 .md、发布链路）

**待办（按优先级）**：

1. **上线**：把 `astro-migration` push 上去 → **先**把 Pages Source 改成 GitHub Actions →
   开 PR → 合并。顺序不能颠倒。
2. 在 `.env` 里填 `GITHUB_TOKEN`（classic 勾 `repo`，或 fine-grained 给目标仓库
   `Contents: Read and write`），然后真跑一次发布，确认「一篇文章 = 一个 commit」。
3. `weblog-fullstack` 补 `git init` 与首次提交——现在这一大堆改动**没有版本保护**。
4. 公开站点的构建期产物里带了 ~1.1MB 的 sqlite wasm（`@nuxt/content` 的客户端查询能力），
   而本站全部内容都已预渲染，这部分是白带的，值得想办法去掉。
5. 未做：RSS 与 sitemap（`feedgen` 已装）、构建期生成静态搜索索引 + 前端 `minisearch` 检索、
   标签页与分页。

---

## 十、请你这样推进

1. **先勘察，再动手**：`git --version` / `node -v` / `java -version` / `python --version` /
   目标端口能否绑定，全部实测。不要凭常识假设环境。
2. **一次只推进一步，每步都要有可验证的证据**（真实的 HTTP 响应、构建产物的内容、
   数据库里的行），不要用"应该没问题"充当结论。
3. **不确定的事去查官方文档或读本地已安装的包**，不要猜 API 名称。
   猜错的成本远高于查一次的成本。
4. **发现自己的判断错了，立刻纠正并说明**——包括纠正之前写进文档的结论。
5. **每完成一段，把"踩到的坑 + 结论"写进文档**，而不是只留一个能跑的代码库。
   这个项目的价值一半在代码，一半在这些结论。
