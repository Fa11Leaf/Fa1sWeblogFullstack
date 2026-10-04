package com.fa11leaf.blog.post;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一张上传过的图片。
 *
 * <p>这里只存元数据，文件本身在本机磁盘上（{@code localPath}）。
 * 为什么不像文章那样直接存进数据库：图片动辄几百 KB，塞进 MEDIUMBLOB 会让
 * 每次备份、每次 mysqldump 都变慢，而且发布时还得从数据库读出来再 base64 一遍。
 * 存磁盘、库里只留路径，是这类场景的常规做法。
 */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, unique = true)
    private String filename;

    @Column(name = "original_name", length = 200)
    private String originalName;

    @Column(name = "local_path", nullable = false, length = 400)
    private String localPath;

    /** 前台可访问路径，形如 /images/xxx.webp。文章正文里引用的就是这个。 */
    @Column(nullable = false, length = 400)
    private String url;

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    private Integer width;

    private Integer height;

    /** 原始文件内容的 SHA-256，用来识别重复上传同一张图。 */
    @Column(length = 64)
    private String sha256;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(String filename, String originalName, String localPath, String url,
                      String contentType, Integer sizeBytes, Integer width, Integer height,
                      String sha256) {
        this.filename = filename;
        this.originalName = originalName;
        this.localPath = localPath;
        this.url = url;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
    }

    public Long getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getLocalPath() {
        return localPath;
    }

    public String getUrl() {
        return url;
    }

    public String getContentType() {
        return contentType;
    }

    public Integer getSizeBytes() {
        return sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getSha256() {
        return sha256;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
