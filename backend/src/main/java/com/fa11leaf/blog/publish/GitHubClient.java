package com.fa11leaf.blog.publish;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;
import com.fa11leaf.blog.common.Json;
import com.fa11leaf.blog.config.GitHubProperties;

/**
 * GitHub Git Data API 的最小封装。
 *
 * <p><b>为什么用 Git Data API 而不是 Contents API：</b>一篇文章 = 1 个 .md + N 张图。
 * Contents API 每个文件都要单独提交一次，于是仓库历史里会出现 N+1 条"上传图片"的提交；
 * Git Data API 允许把多个文件组装进同一棵树、一次提交，做到<b>一篇 = 一个 commit</b>，
 * 历史干净且可以整体回滚。
 *
 * <p><b>代价是调用链更长：</b>读 ref → 读 commit → 逐个建 blob → 建 tree → 建 commit → 更新 ref，
 * 一共 N+3 次请求。任何一步失败都必须让整次发布失败，绝不能半途更新 ref ——
 * 那样仓库会处于"半个提交"的状态。
 *
 * <p>所有返回值都用 Map 解析而不是自定义 DTO：GitHub 的响应字段我们只用其中两三个，
 * 为它们各写一个类，改起来反而更麻烦。
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private static final String API_VERSION = "2022-11-28";

    /** 文本文件的 blob 用 utf-8 编码；二进制必须用 base64，否则字节会被 JSON 字符串破坏。 */
    private static final String ENCODING_UTF8 = "utf-8";
    private static final String ENCODING_BASE64 = "base64";

    private final RestClient client;
    private final GitHubProperties properties;

    public GitHubClient(RestClient.Builder builder, GitHubProperties properties) {
        this.properties = properties;
        this.client = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                // GitHub 要求请求必须带 User-Agent，缺了会直接 403
                .defaultHeader("User-Agent", "weblog-writing-console")
                .defaultHeader("Authorization",
                        properties.configured() ? "Bearer " + properties.token() : "")
                .build();
    }

    /** 待写入仓库的一个文件。 */
    public record RepoFile(String path, byte[] content, boolean binary) {
    }

    /** 一次提交的结果。 */
    public record CommitResult(String sha, String htmlUrl) {
    }

    /**
     * 把若干文件作为一次提交推到目标分支。
     *
     * @param files   文件列表，路径是仓库内的相对路径
     * @param message 提交信息
     */
    public CommitResult commitFiles(List<RepoFile> files, String message) {
        if (!properties.configured()) {
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "没有配置 GITHUB_TOKEN，无法发布。请在项目根目录的 .env 里填入一个有 "
                            + "contents:write 权限的 Personal Access Token，然后重启后端。");
        }

        String branch = properties.branch();
        String baseCommitSha = headCommitSha(branch);
        String baseTreeSha = commitTreeSha(baseCommitSha);

        List<String> entries = new ArrayList<>();
        for (RepoFile file : files) {
            String blobSha = createBlob(file);
            entries.add(Json.obj(
                    "path", Json.string(file.path()),
                    "mode", Json.string("100644"),   // 普通文件（非可执行、非符号链接）
                    "type", Json.string("blob"),
                    "sha", Json.string(blobSha)));
        }

        // base_tree 是关键：不传的话这棵树里就只有本次的文件，
        // 一次提交会把仓库里其余所有文件都"删掉"。
        String treeSha = post("/repos/{o}/{r}/git/trees",
                Json.obj("base_tree", Json.string(baseTreeSha),
                        "tree", Json.arr(entries)),
                "tree.sha");

        // 不传 author/committer 时 GitHub 会用 token 所属账号；
        // 显式指定是为了让提交记录显示成你自己的名字。
        String commitBody = Json.obj(
                "message", Json.string(message),
                "tree", Json.string(treeSha),
                "parents", Json.arr(List.of(Json.string(baseCommitSha))),
                "author", identityJson(),
                "committer", identityJson());

        Map<String, Object> commit = postRaw("/repos/{o}/{r}/git/commits", commitBody);
        String commitSha = str(commit.get("sha"));
        String htmlUrl = str(commit.get("html_url"));

        // 最后一步才是移动分支指针。前面任何一步抛异常，线上都不会有任何变化。
        patch("/repos/{o}/{r}/git/refs/heads/{branch}",
                Json.obj("sha", Json.string(commitSha), "force", Json.bool(false)), branch);

        log.info("已提交到 {}/{}@{}：{} 个文件，commit={}",
                properties.owner(), properties.repo(), branch, files.size(), commitSha);

        return new CommitResult(commitSha, htmlUrl);
    }

    /** 当前分支头部的提交哈希。 */
    public String headCommitSha(String branch) {
        Map<String, Object> body = get("/repos/{o}/{r}/git/ref/heads/{branch}", branch);
        Object object = body.get("object");
        if (!(object instanceof Map<?, ?> obj) || obj.get("sha") == null) {
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "读不到分支 " + branch + " 的头部提交，返回内容不符合预期");
        }
        return String.valueOf(obj.get("sha"));
    }

    /** 某个提交指向的树。 */
    public String commitTreeSha(String commitSha) {
        Map<String, Object> body = get("/repos/{o}/{r}/git/commits/{sha}", commitSha);
        Object tree = body.get("tree");
        if (!(tree instanceof Map<?, ?> t) || t.get("sha") == null) {
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "读不到提交 " + commitSha + " 的树对象");
        }
        return String.valueOf(t.get("sha"));
    }

    private String createBlob(RepoFile file) {
        // 二进制必须用 base64，而且必须是单行 —— GitHub 不接受带换行的格式
        String content = file.binary()
                ? Json.string(Base64.getEncoder().encodeToString(file.content()))
                : Json.string(new String(file.content(), StandardCharsets.UTF_8));

        return post("/repos/{o}/{r}/git/blobs",
                Json.obj("content", content,
                        "encoding", Json.string(file.binary() ? ENCODING_BASE64 : ENCODING_UTF8)),
                "sha");
    }

    /** 提交者身份。GitHub 要求提交必须有邮箱，缺失时回退到占位地址（见 GitHubProperties）。 */
    private String identityJson() {
        return Json.obj(
                "name", Json.string(properties.authorName()),
                "email", Json.string(properties.effectiveAuthorEmail()));
    }

    // ── HTTP 细节 ────────────────────────────────────────────────────

    /**
     * 把 {o}/{r} 补进占位符参数。
     *
     * <p>调用方只传自己那一段，仓库信息由这里统一补上。否则每处调用都要重复写一遍
     * owner/repo，而且很容易漏一个导致 URI 模板参数个数对不上——
     * 那种错误在运行期才会以 IllegalArgumentException 的形式冒出来。
     */
    private Object[] fullVars(Object... extra) {
        Object[] vars = new Object[extra.length + 2];
        vars[0] = properties.owner();
        vars[1] = properties.repo();
        System.arraycopy(extra, 0, vars, 2, extra.length);
        return vars;
    }

    private Map<String, Object> get(String uri, Object... extra) {
        Object[] vars = fullVars(extra);
        return call(() -> client.get().uri(uri, vars).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                }), uri);
    }

    /** 请求体是【已拼好的 JSON 字符串】：见 Json 类里对"为什么不用序列化器"的说明。 */
    private Map<String, Object> postRaw(String uri, String jsonBody) {
        Object[] vars = fullVars();
        return call(() -> client.post().uri(uri, vars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                }), uri);
    }

    private String post(String uri, String jsonBody, String fieldPath) {
        return extract(postRaw(uri, jsonBody), fieldPath);
    }

    private void patch(String uri, String jsonBody, Object... extra) {
        Object[] vars = fullVars(extra);
        call(() -> client.patch().uri(uri, vars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .toBodilessEntity(), uri);
    }

    /**
     * 统一处理失败。
     *
     * <p>把 GitHub 的原始错误原样带出去，而不是包成"发布失败"这种没用的提示：
     * 401 是令牌无效、403 是没权限、404 是仓库名或分支写错、409 是并发冲突，
     * 处理方式完全不同，把状态码和响应体藏起来等于让排查从零开始。
     */
    private <T> T call(java.util.function.Supplier<T> action, String uri) {
        try {
            return action.get();
        } catch (RestClientResponseException ex) {
            String detail = ex.getResponseBodyAsString();
            if (detail != null && detail.length() > 400) {
                detail = detail.substring(0, 400) + "…";
            }
            log.error("GitHub API 调用失败 {} → HTTP {} {}", uri, ex.getStatusCode().value(), detail);
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "GitHub 返回 HTTP " + ex.getStatusCode().value() + "：" + detail, ex);
        } catch (RestClientException ex) {
            log.error("无法访问 GitHub API：{}", ex.getMessage());
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "无法访问 GitHub API（网络或代理问题）：" + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extract(Map<String, Object> body, String fieldPath) {
        Object current = body;
        for (String part : fieldPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                        "GitHub 返回内容里没有 " + fieldPath);
            }
            current = ((Map<String, Object>) map).get(part);
        }
        if (current == null) {
            throw new BusinessException(ErrorCode.GITHUB_API_FAILED,
                    "GitHub 返回内容里没有 " + fieldPath);
        }
        return String.valueOf(current);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
