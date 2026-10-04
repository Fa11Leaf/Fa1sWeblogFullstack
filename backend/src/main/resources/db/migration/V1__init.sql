-- V1：P0 阶段的迁移脚本。
--
-- 本阶段只建 users 一张表，目的不是"把数据模型做完"，而是用一个最小可验证的目标
-- 证明整条链路是通的：Flyway 能启动 → 能连上 MySQL → 能建表 → 中文能正确落库。
-- 因此这里刻意在 COMMENT 里写中文，让字符集问题在第一次迁移就暴露出来，
-- 而不是等到几个月后写文章时才发现全是问号。
--
-- posts / media_assets / publish_jobs 三张表在 P2 随对应实体一起加迁移脚本。
--
-- 约定：表名小写下划线复数，字符集一律 utf8mb4。

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(50)  NOT NULL                COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL                COMMENT 'BCrypt 摘要，绝不存明文',
    display_name  VARCHAR(50)  NOT NULL                COMMENT '显示名',
    role          VARCHAR(20)  NOT NULL DEFAULT 'AUTHOR' COMMENT 'ADMIN 或 AUTHOR',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '后台用户；个人博客通常只有一行';
