package com.fa11leaf.blog.post;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;

/**
 * 文章的增删改查。
 *
 * <p>这一层负责三件控制器不该管的事：slug 的唯一性与生成规则、草稿与已发布状态的迁移、
 * 以及"元数据与 markdown 必须一起改"的原子性。
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private static final DateTimeFormatter SLUG_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PostRepository posts;

    public PostService(PostRepository posts) {
        this.posts = posts;
    }

    /** 新建或更新时提交上来的内容。 */
    public record PostInput(
            String title,
            String slug,
            String description,
            List<String> tags,
            LocalDate publishDate,
            String markdown,
            String coverImage) {
    }

    /** 列表行：不含 markdown，避免列表页拉走几百 KB 正文。 */
    public record PostSummary(
            Long id, String slug, String title, String description,
            List<String> tags, LocalDate publishDate, String status,
            LocalDateTime updatedAt, LocalDateTime publishedAt, String lastCommitUrl) {
    }

    /** 详情：编辑器加载时用，含 markdown 原文。 */
    public record PostDetail(
            Long id, String slug, String title, String description,
            List<String> tags, LocalDate publishDate, String status,
            String markdown, String coverImage,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime publishedAt,
            String lastCommitSha, String lastCommitUrl) {

        static PostDetail of(Post p) {
            return new PostDetail(p.getId(), p.getSlug(), p.getTitle(), p.getDescription(),
                    p.tagList(), p.getPublishDate(), p.getStatus(), p.getMarkdown(),
                    p.getCoverImage(), p.getCreatedAt(), p.getUpdatedAt(), p.getPublishedAt(),
                    p.getLastCommitSha(), p.getLastCommitUrl());
        }
    }

    @Transactional(readOnly = true)
    public List<PostSummary> list(String status, String keyword) {
        String s = (status == null || status.isBlank()) ? null : status.trim().toUpperCase(Locale.ROOT);
        // 空串在 JPQL 里不等于 null，必须显式转成 null 才能命中 ":keyword is null" 分支
        String k = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        return posts.search(s, k).stream()
                .map(p -> new PostSummary(
                        p.getId(), p.getSlug(), p.getTitle(), p.getDescription(),
                        splitTags(p.getTags()), p.getPublishDate(), p.getStatus(),
                        p.getUpdatedAt(), p.getPublishedAt(), p.getLastCommitUrl()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PostDetail detail(Long id) {
        return PostDetail.of(require(id));
    }

    @Transactional(readOnly = true)
    public Post require(Long id) {
        return posts.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "文章不存在：id=" + id));
    }

    @Transactional
    public PostDetail create(PostInput input) {
        String slug = uniqueSlug(resolveSlug(input.slug(), input.title()), null);

        Post post = new Post(slug, requireTitle(input.title()),
                input.markdown() == null ? "" : input.markdown());
        applyMetadata(post, input);

        Post saved = posts.save(post);
        log.info("新建文章 id={} slug={}", saved.getId(), saved.getSlug());
        return PostDetail.of(saved);
    }

    @Transactional
    public PostDetail update(Long id, PostInput input) {
        Post post = require(id);

        post.setTitle(requireTitle(input.title()));
        // slug 允许改：改了就等于改了将来线上的文件名。
        // 这里不做"自动重定向"，因为前台是按文件名生成路由的，
        // 旧网址失效是预期行为 —— 真在意的话应该在发布前就想好。
        String desired = resolveSlug(input.slug(), input.title());
        if (!desired.equals(post.getSlug())) {
            post.setSlug(uniqueSlug(desired, id));
        }
        if (input.markdown() != null) {
            post.setMarkdown(input.markdown());
        }
        applyMetadata(post, input);

        // 不改动 markdown 与元数据也要让 updatedAt 前进，否则列表的排序看起来是错的
        Post saved = posts.saveAndFlush(post);
        log.info("更新文章 id={} slug={}", saved.getId(), saved.getSlug());
        return PostDetail.of(saved);
    }

    @Transactional
    public void delete(Long id) {
        Post post = require(id);
        posts.delete(post);
        log.info("删除文章 id={} slug={}", id, post.getSlug());
    }

    /** 发布成功后由 PublishService 调用。 */
    @Transactional
    public void markPublished(Long id, String commitSha, String commitUrl) {
        Post post = require(id);
        post.markPublished(commitSha, commitUrl);
        posts.saveAndFlush(post);
    }

    // ────────────────────────────────────────────────────────────────

    private void applyMetadata(Post post, PostInput input) {
        post.setDescription(blankToNull(input.description()));
        post.setTagList(input.tags());
        post.setPublishDate(input.publishDate() == null ? LocalDate.now() : input.publishDate());
        post.setCoverImage(blankToNull(input.coverImage()));
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "标题不能为空");
        }
        return title.trim();
    }

    /**
     * 决定最终的 slug 基础值。
     *
     * <p>中文标题没法直接变成网址（需要拼音库），因此这里只做 ASCII 化：
     * 标题全是中文时 slugify 的结果是空串，此时回退成 post-时间戳。
     * 前端会在标题变化时自动填一个建议值，作者也可以自己改。
     */
    private static String resolveSlug(String slug, String title) {
        String base = (slug == null || slug.isBlank()) ? slugify(title) : slugify(slug);
        return base.isEmpty() ? "post-" + LocalDateTime.now().format(SLUG_TIME) : base;
    }

    /**
     * 把任意字符串变成可安全用作文件名与网址的片段。
     *
     * <p>规则：转小写 → 非字母数字的连续片段换成单个连字符 → 去掉首尾连字符。
     * 这样 {slug}.md 在 Windows / Linux / macOS 上都是合法文件名，
     * 也不会在 URL 里引入需要百分号编码的字符。
     */
    static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        // 过长会超出 posts.slug 的 120 字符上限
        return s.length() > 100 ? s.substring(0, 100).replaceAll("-+$", "") : s;
    }

    /** slug 已被占用时顺延成 -2、-3……比直接报错友好，也避免作者反复试。 */
    private String uniqueSlug(String base, Long excludeId) {
        String candidate = base;
        int suffix = 2;
        while (true) {
            boolean taken = (excludeId == null)
                    ? posts.existsBySlug(candidate)
                    : posts.existsBySlugAndIdNot(candidate, excludeId);
            if (!taken) {
                return candidate;
            }
            candidate = base + "-" + suffix;
            suffix++;
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
