package com.fa11leaf.blog.publish;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fa11leaf.blog.common.ApiResponse;

/**
 * 发布接口。
 *
 * <p>发布是<b>同步</b>的：点下按钮后，请求会一直等到 GitHub 提交完成再返回。
 * 这么做是权衡后的选择 —— 本机单用户、一次提交几秒钟，
 * 同步返回能让"成功/失败"和原因一次性回到浏览器，比让前端轮询一个任务状态简单得多，
 * 也少一层状态机要维护。代价是前端必须把这次请求的超时放宽（前端设的是 90 秒）。
 *
 * <p>如果哪天要支持多篇批量发布，再改成异步入队：{@code publish_jobs} 表已经是任务表的形状，
 * 那时只需把返回值从"结果"换成"任务 id"。
 */
@RestController
@RequestMapping("/api/admin")
public class PublishController {

    private final PublishService publishService;
    private final String siteUrl;

    public PublishController(PublishService publishService,
                             @Value("${app.site.base-url}") String siteUrl) {
        this.publishService = publishService;
        this.siteUrl = siteUrl;
    }

    /** 发布历史的一行。 */
    public record JobView(Long id, String status, String commitSha, String commitUrl,
                          String message, LocalDateTime createdAt, LocalDateTime finishedAt) {

        static JobView of(PublishJob j) {
            return new JobView(j.getId(), j.getStatus(), j.getCommitSha(), j.getCommitUrl(),
                    j.getMessage(), j.getCreatedAt(), j.getFinishedAt());
        }
    }

    @PostMapping("/posts/{id}/publish")
    public ApiResponse<PublishService.PublishResult> publish(@PathVariable Long id) {
        return ApiResponse.ok(publishService.publish(id));
    }

    @GetMapping("/posts/{id}/publish-jobs")
    public ApiResponse<List<JobView>> history(@PathVariable Long id) {
        return ApiResponse.ok(publishService.history(id).stream().map(JobView::of).toList());
    }

    /**
     * 发布目标状态。
     *
     * <p>刻意只返回 owner/repo/branch 这类非敏感信息，绝不回显令牌。
     * 前端据此决定「发布」按钮能不能点。
     */
    @GetMapping("/github/status")
    public ApiResponse<PublishService.TargetStatus> githubStatus() {
        return ApiResponse.ok(publishService.targetStatus(siteUrl));
    }
}
