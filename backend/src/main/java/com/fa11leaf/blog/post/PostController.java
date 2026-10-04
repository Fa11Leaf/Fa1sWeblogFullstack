package com.fa11leaf.blog.post;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fa11leaf.blog.common.ApiResponse;
import com.fa11leaf.blog.python.PythonClient;

import jakarta.validation.constraints.NotBlank;

/**
 * 文章管理接口（写作后台用，全部需要登录）。
 */
@RestController
@RequestMapping("/api/admin")
public class PostController {

    private final PostService posts;
    private final PythonClient python;
    private final MarkdownImportService importer;

    public PostController(PostService posts, PythonClient python, MarkdownImportService importer) {
        this.posts = posts;
        this.python = python;
        this.importer = importer;
    }

    public record PostRequest(
            @NotBlank(message = "标题不能为空") String title,
            String slug,
            String description,
            List<String> tags,
            LocalDate publishDate,
            String markdown,
            String coverImage) {

        PostService.PostInput toInput() {
            return new PostService.PostInput(
                    title, slug, description, tags, publishDate, markdown, coverImage);
        }
    }

    /** 预览请求。 */
    public record RenderRequest(String markdown) {
    }

    @GetMapping("/posts")
    public ApiResponse<List<PostService.PostSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(posts.list(status, keyword));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<PostService.PostDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(posts.detail(id));
    }

    @PostMapping("/posts")
    public ApiResponse<PostService.PostDetail> create(@RequestBody @jakarta.validation.Valid PostRequest request) {
        return ApiResponse.ok(posts.create(request.toInput()));
    }

    @PutMapping("/posts/{id}")
    public ApiResponse<PostService.PostDetail> update(@PathVariable Long id,
                                                      @RequestBody @jakarta.validation.Valid PostRequest request) {
        return ApiResponse.ok(posts.update(id, request.toInput()));
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        posts.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * 导入一篇已有的 Markdown 作为草稿。
     *
     * <p>{@code multipart/form-data}，字段名固定为 {@code file}。
     * 文件里的 Front Matter 会被解析成标题、摘要、日期、标签；
     * 文件名（去掉日期前缀后）就是 slug —— 因为本站的规矩本来就是"文件名即网址"。
     *
     * <p>导入<b>只产生草稿</b>：即使文件里写着 {@code draft: false}，也不会直接上线。
     */
    @PostMapping("/posts/import")
    public ApiResponse<MarkdownImportService.ImportResult> importMarkdown(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importer.importFile(file));
    }

    /**
     * 渲染预览。
     *
     * <p>刻意做成"把 markdown 发上去、拿 HTML 回来"，而不是在前端引一个 Markdown 库。
     * 理由是：预览必须和最终发布用的渲染器是<b>同一个</b>。前端用 marked、
     * 线上用 markdown-it 的话，预览看起来对、线上出来不一样，这种问题极难排查。
     */
    @PostMapping("/render")
    public ApiResponse<PythonClient.RenderResult> render(@RequestBody RenderRequest request) {
        return ApiResponse.ok(python.render(request.markdown()));
    }
}
