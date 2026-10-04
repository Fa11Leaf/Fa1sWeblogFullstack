package com.fa11leaf.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 发布目标：公开站点所在的 GitHub 仓库。
 *
 * <p>{@code token} 是能写你仓库的凭据，属于本项目里敏感度最高的一项。
 * 它只在服务端使用，绝不返回给浏览器；缺失时后台照常写作，
 * 只有点「发布」才会明确报出「未配置令牌」。
 *
 * @param owner       仓库属主
 * @param repo        仓库名
 * @param branch      发布分支
 * @param token       Personal Access Token（需 contents:write）
 * @param postsPath   文章在仓库中的目录，如 content/posts
 * @param imagesPath  图片在仓库中的目录，如 public/images
 * @param authorName  提交者显示名
 * @param authorEmail 提交者邮箱；GitHub 要求提交必须有邮箱，为空时回退到一个占位地址
 */
@ConfigurationProperties(prefix = "app.github")
public record GitHubProperties(
        String owner,
        String repo,
        String branch,
        String token,
        String postsPath,
        String imagesPath,
        String authorName,
        String authorEmail) {

    /** 是否具备发布能力。 */
    public boolean configured() {
        return token != null && !token.isBlank();
    }

    /** 供前端显示的仓库全名，不含任何凭据。 */
    public String repositoryFullName() {
        return owner + "/" + repo;
    }

    /** 提交者邮箱，缺失时给一个明确无意义的占位值，避免 GitHub 因空邮箱拒绝提交。 */
    public String effectiveAuthorEmail() {
        return (authorEmail == null || authorEmail.isBlank())
                ? "weblog-bot@users.noreply.github.com"
                : authorEmail;
    }

    /** 文章在仓库中的完整路径。 */
    public String postFilePath(String slug) {
        return postsPath + "/" + slug + ".md";
    }

    /** 图片在仓库中的完整路径。 */
    public String imageFilePath(String filename) {
        return imagesPath + "/" + filename;
    }
}
