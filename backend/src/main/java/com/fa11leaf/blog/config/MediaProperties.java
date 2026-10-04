package com.fa11leaf.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片在本机的存放配置。
 *
 * @param localDir 存放目录，相对项目根目录或绝对路径
 * @param maxWidth 超过该宽度会被等比缩小
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(String localDir, int maxWidth) {
}
