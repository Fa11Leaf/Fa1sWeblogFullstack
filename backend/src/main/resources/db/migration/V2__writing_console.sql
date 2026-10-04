-- V2：写作后台的数据模型。
--
-- 四张表，对应四件不同性质的事：
--   posts          文章本身。markdown 原文存这里，它是唯一真源；
--                  发布到 GitHub 就是把这份原文写进 content/posts/{slug}.md。
--   user_tokens    登录令牌。存的是 SHA-256 摘要而不是令牌本身 ——
--                  数据库泄露也不能拿去冒充登录，和 users 存 BCrypt 同理。
--   media_assets   上传过的图片。这里只记元数据，文件本身落在本机磁盘。
--   publish_jobs   发布记录。发布是异步的（要调 GitHub 好几个接口），
--                  先把任务落库，前端才有东西可轮询。
--
-- 关于标签：这里用逗号分隔的字符串而不是 tags 关联表。个人博客的标签总量在个位数，
-- 建一张关联表换来的是三次 JOIN 和一堆中间代码，不值。真到需要按标签聚合时再拆。

CREATE TABLE posts
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    slug            VARCHAR(120) NOT NULL COMMENT '网址片段，也是 content/posts/{slug}.md 的文件名',
    title           VARCHAR(200) NOT NULL COMMENT '标题',
    description     VARCHAR(500) NULL COMMENT '摘要，显示在列表卡片上',
    tags            VARCHAR(300) NULL COMMENT '标签，英文逗号分隔',
    publish_date    DATE         NOT NULL COMMENT '文章日期，写入 Front Matter 的 date',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    markdown        MEDIUMTEXT   NOT NULL COMMENT 'Markdown 原文，唯一真源',
    cover_image     VARCHAR(300) NULL COMMENT '封面图 URL',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at    DATETIME     NULL COMMENT '最近一次发布成功的时间',
    last_commit_sha CHAR(40)     NULL COMMENT '最近一次发布的提交哈希',
    last_commit_url VARCHAR(400) NULL COMMENT '最近一次发布的提交网页地址',
    PRIMARY KEY (id),
    UNIQUE KEY uk_posts_slug (slug),
    KEY idx_posts_status_updated (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文章草稿与已发布内容';

CREATE TABLE user_tokens
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL COMMENT '所属用户',
    token_hash CHAR(64)    NOT NULL COMMENT '令牌的 SHA-256 十六进制摘要',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME    NOT NULL COMMENT '过期时间，过期即视为未登录',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tokens_hash (token_hash),
    KEY idx_tokens_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '登录令牌摘要，可随时删除以实现「退出登录」';

CREATE TABLE media_assets
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    filename      VARCHAR(200) NOT NULL COMMENT '最终文件名，也是前台 /images/ 下的文件名',
    original_name VARCHAR(200) NULL COMMENT '上传时的原始文件名，仅用于展示',
    local_path    VARCHAR(400) NOT NULL COMMENT '本机磁盘上的绝对路径，发布时从这里读文件',
    url           VARCHAR(400) NOT NULL COMMENT '前台可访问路径，如 /images/xxx.webp',
    content_type  VARCHAR(80)  NULL,
    size_bytes    INT          NULL COMMENT '优化后的字节数',
    width         INT          NULL,
    height        INT          NULL,
    sha256        CHAR(64)     NULL COMMENT '内容摘要，用于识别重复上传',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_filename (filename),
    KEY idx_media_sha (sha256)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '图片元数据；文件本身存在本机磁盘';

CREATE TABLE publish_jobs
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    post_id     BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED',
    commit_sha  CHAR(40)     NULL,
    commit_url  VARCHAR(400) NULL,
    message     VARCHAR(600) NULL COMMENT '失败原因或成功说明',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_jobs_post (post_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '发布任务；一次发布 = 一个 commit，成功与否都留痕';
