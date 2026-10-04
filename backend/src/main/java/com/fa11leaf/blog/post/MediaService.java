package com.fa11leaf.blog.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;
import com.fa11leaf.blog.config.MediaProperties;
import com.fa11leaf.blog.python.PythonClient;

/**
 * 图片上传与管理。
 *
 * <p>流程是「原始字节 → Python 压缩转 WebP → 落本机磁盘 → 记一行元数据」。
 * 压缩不在这里做，是因为本项目的分工约定是"文本与图片处理归 Python"，
 * Java 侧只负责路径、事务和元数据。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** 文件名里保留的摘要前缀长度。8 个十六进制字符足够区分同一目录下的图片。 */
    private static final int HASH_PREFIX = 8;

    private final MediaAssetRepository assets;
    private final PostRepository posts;
    private final PythonClient python;
    private final MediaProperties properties;

    public MediaService(MediaAssetRepository assets, PostRepository posts,
                        PythonClient python, MediaProperties properties) {
        this.assets = assets;
        this.posts = posts;
        this.python = python;
        this.properties = properties;
    }

    public record MediaView(Long id, String filename, String url, String originalName,
                            String contentType, Integer sizeBytes, Integer width, Integer height,
                            LocalDateTime createdAt) {

        static MediaView of(MediaAsset a) {
            return new MediaView(a.getId(), a.getFilename(), a.getUrl(), a.getOriginalName(),
                    a.getContentType(), a.getSizeBytes(), a.getWidth(), a.getHeight(),
                    a.getCreatedAt());
        }
    }

    /**
     * 上一次上传的结果。
     *
     * @param deduplicated 命中内容摘要、直接复用了已有图片
     */
    public record UploadResult(MediaView asset, int originalBytes, int storedBytes,
                               boolean deduplicated) {
    }

    @Transactional
    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "没有收到文件");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "只接受图片文件，收到的是：" + contentType);
        }

        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取上传内容失败", ex);
        }

        String digest = sha256Hex(original);
        String originalName = file.getOriginalFilename();

        // 同一张图传两次是常事（截图重发、换电脑重传）。
        // 命中摘要就复用，既省一次压缩，也避免前台出现两张一模一样的图。
        MediaAsset existing = assets.findBySha256(digest).orElse(null);
        if (existing != null && Files.isReadable(Paths.get(existing.getLocalPath()))) {
            log.info("图片内容已存在，直接复用：{}", existing.getFilename());
            return new UploadResult(MediaView.of(existing), original.length,
                    existing.getSizeBytes() == null ? 0 : existing.getSizeBytes(), true);
        }

        PythonClient.OptimizedImage optimized =
                python.optimizeImage(original, baseName(originalName), properties.maxWidth());

        String filename = buildFilename(baseName(originalName), digest, optimized.contentType());
        Path target = storageDir().resolve(filename);

        try {
            Files.write(target, optimized.bytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "写入图片失败：" + target, ex);
        }

        MediaAsset asset = new MediaAsset(
                filename, originalName, target.toAbsolutePath().toString(),
                "/images/" + filename, optimized.contentType(), optimized.bytes().length,
                optimized.width(), optimized.height(), digest);
        MediaAsset saved = assets.save(asset);

        log.info("图片已保存 {}（{} B → {} B，{}×{}）",
                filename, original.length, optimized.bytes().length,
                optimized.width(), optimized.height());

        return new UploadResult(MediaView.of(saved), original.length,
                optimized.bytes().length, false);
    }

    @Transactional(readOnly = true)
    public List<MediaView> list() {
        return assets.findAll().stream()
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(MediaView::of)
                .toList();
    }

    /**
     * 删除图片。
     *
     * <p>删之前检查有没有文章正文还在引用它。没有这道检查，作者在媒体库里
     * 顺手清理一下，线上就会出现裂图——而且要在下次构建后才发现。
     */
    @Transactional
    public void delete(Long id) {
        MediaAsset asset = assets.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND, "图片不存在：id=" + id));

        long referencing = posts.countByMarkdownContaining(asset.getUrl());
        if (referencing > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE,
                    "还有 " + referencing + " 篇文章在引用这张图（" + asset.getUrl() + "），不能删除");
        }

        try {
            Files.deleteIfExists(Paths.get(asset.getLocalPath()));
        } catch (IOException ex) {
            // 文件删不掉不该阻断数据清理：留下一个孤儿文件比留下一条指向不存在文件的记录好。
            log.warn("本地文件删除失败，仅移除数据库记录：{}", asset.getLocalPath(), ex);
        }

        assets.delete(asset);
        log.info("已删除图片 {}", asset.getFilename());
    }

    // ────────────────────────────────────────────────────────────────

    private Path storageDir() {
        Path dir = Paths.get(properties.localDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法创建图片目录：" + dir, ex);
        }
        return dir;
    }

    /**
     * 生成最终文件名：{原名的 ASCII 形式}-{摘要前 8 位}.{扩展名}。
     *
     * <p>保留原名的可读部分，是为了以后在仓库里直接翻 public/images/ 时，
     * 还能大致看出哪张图是什么；摘要后缀保证不同内容不会撞名。
     */
    private static String buildFilename(String base, String digest, String contentType) {
        String stem = PostService.slugify(base);
        if (stem.isEmpty()) {
            stem = "image";
        }
        return stem + "-" + digest.substring(0, HASH_PREFIX) + extensionFor(contentType);
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return ".webp";
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            default -> ".webp";
        };
    }

    private static String baseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        // 去掉目录部分，再去掉扩展名：后面的摘要后缀会重新补上正确的扩展名
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", ex);
        }
    }
}
