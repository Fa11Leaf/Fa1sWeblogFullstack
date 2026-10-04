package com.fa11leaf.blog.publish;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;
import com.fa11leaf.blog.config.GitHubProperties;
import com.fa11leaf.blog.post.MediaAsset;
import com.fa11leaf.blog.post.MediaAssetRepository;
import com.fa11leaf.blog.post.Post;
import com.fa11leaf.blog.post.PostRepository;
import com.fa11leaf.blog.post.PostService;

/**
 * 把一篇文章发布到公开站点的仓库。
 *
 * <p>一次发布做的事：把数据库里的元数据重新拼成 Front Matter，读出正文，
 * 再找出正文里引用到的所有图片，一起作为<b>一次提交</b>推上去。
 *
 * <p><b>刻意不在事务里调 HTTP。</b>本方法没有 @Transactional：
 * 一次发布要连 GitHub 五六次，慢的时候好几秒，把数据库连接握在手里干等网络，
 * 是这个规模的应用最不该犯的错误之一。因此流程是"读一次 → 联网 → 写回一次"，
 * 每一步各自是一个短事务。代价是中途失败会留下一条 FAILED 的任务记录 ——
 * 那正是这张表存在的意义。
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    /**
     * 匹配正文里的图片引用，例如 {@code ![](/images/a.png)}。
     * 只认 /images/ 开头的路径 —— 外链图片不归我们管，也不该被提交进仓库。
     */
    private static final Pattern IMAGE_REF = Pattern.compile("/images/([A-Za-z0-9._-]+)");

    private final PostRepository posts;
    private final MediaAssetRepository media;
    private final PublishJobRepository jobs;
    private final GitHubClient github;
    private final GitHubProperties githubProperties;

    public PublishService(PostRepository posts, MediaAssetRepository media,
                          PublishJobRepository jobs, GitHubClient github,
                          GitHubProperties githubProperties) {
        this.posts = posts;
        this.media = media;
        this.jobs = jobs;
        this.github = github;
        this.githubProperties = githubProperties;
    }

    /** 发布结果。 */
    public record PublishResult(Long jobId, String status, String commitSha, String commitUrl,
                               List<String> files, String message) {
    }

    /** 发布目标的状态，供前端在按钮旁边显示"有没有配好"。 */
    public record TargetStatus(boolean configured, String owner, String repo, String branch,
                               String postsPath, String imagesPath, String siteUrl) {
    }

    public TargetStatus targetStatus(String siteUrl) {
        return new TargetStatus(githubProperties.configured(),
                githubProperties.owner(), githubProperties.repo(), githubProperties.branch(),
                githubProperties.postsPath(), githubProperties.imagesPath(), siteUrl);
    }

    public PublishResult publish(Long postId) {
        // 没配令牌就别去连 GitHub 了，直接把原因说清楚
        if (!githubProperties.configured()) {
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "没有配置 GITHUB_TOKEN。到 https://github.com/settings/tokens 建一个 "
                            + "classic token，勾选 repo 权限，写进项目根目录的 .env，然后重启后端。");
        }

        Post post = posts.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "文章不存在：id=" + postId));

        PublishJob job = jobs.save(new PublishJob(postId));
        List<String> touched = new ArrayList<>();

        try {
            List<GitHubClient.RepoFile> files = new ArrayList<>();

            String markdown = renderMarkdown(post);
            String mdPath = githubProperties.postFilePath(post.getSlug());
            files.add(new GitHubClient.RepoFile(mdPath, markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8), false));
            touched.add(mdPath);

            for (MediaAsset asset : collectImages(post)) {
                byte[] bytes = readFile(asset);
                String path = githubProperties.imageFilePath(asset.getFilename());
                files.add(new GitHubClient.RepoFile(path, bytes, true));
                touched.add(path);
            }

            GitHubClient.CommitResult result = github.commitFiles(files, commitMessage(post));

            // 只有提交成功才把文章标成已发布
            post.markPublished(result.sha(), result.htmlUrl());
            posts.save(post);

            job.succeed(result.sha(), result.htmlUrl(),
                    "已提交 " + files.size() + " 个文件");
            jobs.save(job);

            log.info("文章 {} 发布成功，commit={}，文件 {} 个", post.getSlug(), result.sha(), files.size());
            return new PublishResult(job.getId(), job.getStatus(), result.sha(), result.htmlUrl(),
                    touched, job.getMessage());

        } catch (RuntimeException ex) {
            // 失败也要留痕，但不要把异常吞掉——控制器要把原因返回给浏览器
            job.fail(ex.getMessage());
            jobs.save(job);
            log.error("文章 {} 发布失败", post.getSlug(), ex);
            throw ex;
        }
    }

    public List<PublishJob> history(Long postId) {
        return jobs.findByPostIdOrderByIdDesc(postId);
    }

    // ────────────────────────────────────────────────────────────────

    /**
     * 用数据库里的元数据重新拼出带 Front Matter 的 Markdown。
     *
     * <p>为什么不直接把数据库里存的原文原样提交：因为标题、日期、标签是可编辑的字段，
     * 如果只在数据库里改、不重新拼，线上就会停留在旧标题上。
     * 每次发布都重拼一遍，就能保证"库里的元数据 = 线上的 Front Matter"。
     */
    String renderMarkdown(Post post) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("---\n");
        sb.append("title: ").append(yamlQuote(post.getTitle())).append('\n');

        if (post.getDescription() != null && !post.getDescription().isBlank()) {
            sb.append("description: ").append(yamlQuote(post.getDescription())).append('\n');
        }

        // 日期必须加引号：不加的话 YAML 会把它解析成日期对象，
        // 而前台的内容集合 schema 声明的是字符串，会直接校验失败。
        sb.append("date: '").append(post.getPublishDate()).append("'\n");

        List<String> tags = post.tagList();
        if (!tags.isEmpty()) {
            sb.append("tags:\n");
            for (String tag : tags) {
                sb.append("  - ").append(yamlQuote(tag)).append('\n');
            }
        }

        sb.append("---\n\n");
        String body = post.getMarkdown() == null ? "" : post.getMarkdown().trim();
        sb.append(body).append('\n');
        return sb.toString();
    }

    /**
     * YAML 双引号字符串。
     *
     * <p>标题里出现冒号、井号、引号都很常见（"CSS 布局入门：盒模型"），
     * 不处理的话 Front Matter 会解析失败，前台构建直接报错。
     * 双引号形式只需要转义反斜杠和双引号本身。
     */
    static String yamlQuote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 找出正文与封面里引用到的图片，同一张只提交一次。 */
    private List<MediaAsset> collectImages(Post post) {
        Set<String> filenames = new LinkedHashSet<>();

        Matcher m = IMAGE_REF.matcher(post.getMarkdown() == null ? "" : post.getMarkdown());
        while (m.find()) {
            filenames.add(m.group(1));
        }

        if (post.getCoverImage() != null) {
            Matcher cover = IMAGE_REF.matcher(post.getCoverImage());
            if (cover.find()) {
                filenames.add(cover.group(1));
            }
        }

        List<MediaAsset> found = new ArrayList<>();
        for (String name : filenames) {
            MediaAsset asset = media.findByFilename(name).orElseThrow(() ->
                    new BusinessException(ErrorCode.MEDIA_NOT_FOUND,
                            "正文引用了 " + name + "，但媒体库里没有这张图。"
                                    + "它可能是手工写进去的路径，先重新上传一次再发布。"));
            found.add(asset);
        }
        return found;
    }

    private byte[] readFile(MediaAsset asset) {
        Path path = Paths.get(asset.getLocalPath());
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND,
                    "图片文件读不到了：" + path + "（记录还在，文件可能被手工删过）", ex);
        }
    }

    private static String commitMessage(Post post) {
        return "发布文章：" + post.getTitle() + "\n\n"
                + "slug: " + post.getSlug() + "\n"
                + "由写作后台提交于 " + LocalDateTime.now() + "\n";
    }
}
