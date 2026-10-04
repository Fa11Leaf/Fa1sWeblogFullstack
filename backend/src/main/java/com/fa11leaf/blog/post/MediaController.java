package com.fa11leaf.blog.post;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fa11leaf.blog.common.ApiResponse;

/**
 * 媒体库接口。
 *
 * <p>上传是 {@code multipart/form-data}，字段名固定为 {@code file}。
 * 前端用 FormData 直接塞 File 对象即可，不需要自己读成 base64。
 */
@RestController
@RequestMapping("/api/admin/media")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping
    public ApiResponse<MediaService.UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(media.upload(file));
    }

    @GetMapping
    public ApiResponse<List<MediaService.MediaView>> list() {
        return ApiResponse.ok(media.list());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        media.delete(id);
        return ApiResponse.ok(null);
    }
}
