package com.fa11leaf.blog.post;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一篇文章。
 *
 * <p><b>设计要点：{@code markdown} 是唯一真源。</b>标题、日期、标签这些元数据虽然解析得出来，
 * 但仍然单独存一份，原因有两个：一是列表页不必为了显示摘要去解析每篇正文，
 * 二是——更重要——发布时会用数据库里的元数据重新拼一遍 Front Matter，
 * 这样"数据库里看到的就是将要发布的内容"，不会因为文件被手工改过而对不上。
 *
 * <p>标签用逗号分隔的字符串存。个人博客标签总量在个位数，为它建关联表换来的是三次 JOIN
 * 和一堆中间代码，不值。真需要按标签聚合时再拆不迟。
 */
@Entity
@Table(name = "posts")
public class Post {

    /** 草稿：只在本机，线上没有。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已发布：至少成功提交过一次。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private static final String TAG_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120, unique = true)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(length = 300)
    private String tags;

    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;

    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    /**
     * Markdown 原文。
     *
     * <p>必须显式声明成 LONGVARCHAR，否则 Hibernate 默认按 VARCHAR(255) 处理，
     * 首次写入长文就会被<b>静默</b>截断。用 LONGVARCHAR 而不是 @Lob：
     * @Lob 在 MySQL 上会被映射成 LONGTEXT，而迁移脚本里建的是 MEDIUMTEXT，
     * 两者对不上，ddl-auto=validate 会在启动时直接报错。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String markdown;

    @Column(name = "cover_image", length = 300)
    private String coverImage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "last_commit_sha", length = 40)
    private String lastCommitSha;

    @Column(name = "last_commit_url", length = 400)
    private String lastCommitUrl;

    protected Post() {
        // JPA 需要
    }

    public Post(String slug, String title, String markdown) {
        this.slug = slug;
        this.title = title;
        this.markdown = markdown;
        this.publishDate = LocalDate.now();
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    /** 把逗号分隔的字符串拆成列表；空值返回空列表而不是 null，省掉调用方的一堆判空。 */
    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(TAG_SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 存标签。顺带去掉空项与重复项，避免出现 "a,,a" 这种脏数据。 */
    public void setTagList(List<String> values) {
        if (values == null || values.isEmpty()) {
            this.tags = null;
            return;
        }
        this.tags = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((a, b) -> a + TAG_SEPARATOR + b)
                .orElse(null);
    }

    public void markPublished(String commitSha, String commitUrl) {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastCommitSha = commitSha;
        this.lastCommitUrl = commitUrl;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getPublishDate() {
        return publishDate;
    }

    public void setPublishDate(LocalDate publishDate) {
        this.publishDate = publishDate;
    }

    public String getStatus() {
        return status;
    }

    public String getMarkdown() {
        return markdown;
    }

    public void setMarkdown(String markdown) {
        this.markdown = markdown;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public String getLastCommitSha() {
        return lastCommitSha;
    }

    public String getLastCommitUrl() {
        return lastCommitUrl;
    }
}
