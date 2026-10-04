package com.fa11leaf.blog.publish;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次发布尝试的记录。
 *
 * <p>为什么失败也要落库：发布要连外部服务，失败是常态（令牌过期、网络抖动、仓库改名）。
 * 只有成功才留痕的话，出问题时你只会看到一句"发布失败"，
 * 然后不知道是第几次尝试、报的什么错。
 *
 * <p>目前发布是同步执行的，这张表主要起历史与审计作用；将来若改成队列异步执行，
 * 它会自然变成任务表，前端不用改。
 */
@Entity
@Table(name = "publish_jobs")
public class PublishJob {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "commit_url", length = 400)
    private String commitUrl;

    @Column(length = 600)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected PublishJob() {
    }

    public PublishJob(Long postId) {
        this.postId = postId;
        this.status = STATUS_RUNNING;
    }

    public void succeed(String commitSha, String commitUrl, String message) {
        this.status = STATUS_SUCCEEDED;
        this.commitSha = commitSha;
        this.commitUrl = commitUrl;
        this.message = truncate(message);
        this.finishedAt = LocalDateTime.now();
    }

    public void fail(String message) {
        this.status = STATUS_FAILED;
        this.message = truncate(message);
        this.finishedAt = LocalDateTime.now();
    }

    /** 数据库列是 VARCHAR(600)，超长会直接报错，所以在写入前截断。 */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 600 ? message.substring(0, 597) + "…" : message;
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public String getStatus() {
        return status;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getCommitUrl() {
        return commitUrl;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }
}
