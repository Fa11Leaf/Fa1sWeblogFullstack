package com.fa11leaf.blog.post;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** slug 是否被【别的】文章占用——改名时用得上。 */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /**
     * 有多少篇文章的正文里出现了这段文本。
     *
     * <p>用来在删除图片前确认没有文章还在引用它。这里用 like 而不是精确匹配，
     * 因为正文里写的是 markdown 图片语法，路径总是嵌在里面的。
     */
    long countByMarkdownContaining(String text);

    /**
     * 列表查询。
     *
     * <p>刻意不返回 Post 实体本身，而是只取列表页需要的列：
     * 一篇文章的 markdown 可能有几十 KB，列表页一次拉十几篇就是几百 KB 的无用流量。
     * 用投影（interface-based projection）让 SQL 只 SELECT 这几列。
     */
    @Query("""
            select p.id as id, p.slug as slug, p.title as title, p.description as description,
                   p.tags as tags, p.publishDate as publishDate, p.status as status,
                   p.updatedAt as updatedAt, p.publishedAt as publishedAt,
                   p.lastCommitUrl as lastCommitUrl
            from Post p
            where (:status is null or p.status = :status)
              and (:keyword is null
                   or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(p.slug)  like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.tags, '')) like lower(concat('%', :keyword, '%')))
            order by p.updatedAt desc
            """)
    List<PostSummaryProjection> search(@Param("status") String status,
                                       @Param("keyword") String keyword);

    /** 列表行。只包含列表页真正会显示的字段。 */
    interface PostSummaryProjection {
        Long getId();

        String getSlug();

        String getTitle();

        String getDescription();

        String getTags();

        java.time.LocalDate getPublishDate();

        String getStatus();

        java.time.LocalDateTime getUpdatedAt();

        java.time.LocalDateTime getPublishedAt();

        String getLastCommitUrl();
    }
}
