package com.fa11leaf.blog.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;

/**
 * 把外部已有的 <code>.md</code> 文件导入成一篇草稿。
 *
 * <p>典型场景是"以前在别处写过、现在要搬过来"：把那篇 Markdown 直接拖进后台，
 * 元数据自动填好，不用手工再抄一遍。
 *
 * <p><b>为什么解析放在 Java 侧而不是 Python：</b>Front Matter 就是一小段 YAML，
 * 而 Spring Boot 本来就把 SnakeYAML 带在类路径上（`application.yml` 靠它解析）。
 * 为了它多跑一次 HTTP、还要给 Python 补一个 PyYAML 依赖，不划算。
 * 图片处理与 Markdown 渲染留给 Python，是因为那些库 Java 生态确实弱；
 * 这一件不是。
 *
 * <p><b>导入只产生草稿，绝不影响线上。</b>即使文件里写着 {@code draft: false}，
 * 也只是本地多一篇草稿 —— 发布这个动作必须由人明确点一下。
 */
@Service
public class MarkdownImportService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownImportService.class);

    /** 单个文件的体积上限。一篇纯文本文章远超这个数就是搞错了文件。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 认得的扩展名。 */
    private static final List<String> ALLOWED_SUFFIXES = List.of(".md", ".markdown", ".mdown");

    /** 文件名不能长过 slug 列的长度上限（120），留出后缀的余量。 */
    private static final int MAX_SLUG_LENGTH = 100;

    private final PostService postService;

    public MarkdownImportService(PostService postService) {
        this.postService = postService;
    }

    /**
     * 导入结果。
     *
     * @param post           新建出来的草稿
     * @param sourceFilename 上传时的原始文件名
     * @param bytes          文件字节数
     * @param warnings       需要作者知道、但不影响导入的提醒
     */
    public record ImportResult(PostService.PostDetail post, String sourceFilename,
                               int bytes, List<String> warnings) {
    }

    @Transactional
    public ImportResult importFile(MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        byte[] raw = readBytes(file, filename);

        String text = new String(raw, StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM。Windows 记事本和不少编辑器会在开头写 BOM，
        // 留着的话第一行就不是 "---"，Front Matter 会被整个当成正文。
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件是空的");
        }

        List<String> warnings = new ArrayList<>();
        Section section = splitFrontMatter(text);
        Map<String, Object> meta = parseYaml(section.frontMatter(), warnings);

        String stem = stemOf(filename);
        String title = stringOf(meta.get("title"));
        if (isBlank(title)) {
            title = firstHeading(section.body());
            if (!isBlank(title)) {
                warnings.add("Front Matter 里没有 title，已从正文开头的一级标题推断。");
            } else {
                title = stem;
                warnings.add("没有 Front Matter 也没有一级标题，已用文件名当作标题。");
            }
        }

        String slug = isBlank(stringOf(meta.get("slug"))) ? stem : stringOf(meta.get("slug"));
        String slugified = PostService.slugify(slug);
        if (slugified.isEmpty()) {
            warnings.add("文件名全是非 ASCII 字符（中文），无法直接当网址用，"
                    + "已自动生成一个 post-时间戳 形式的 slug，发布前建议改成有意义的英文名。");
        }

        String body = stripDuplicateHeading(section.body(), title, warnings);
        if (!section.hadFrontMatter()) {
            warnings.add("文件里没有 Front Matter，日期用了今天；发布前请核对。");
        }

        LocalDate date = parseDate(meta.get("date"), warnings);
        List<String> tags = parseTags(meta.get("tags"), warnings);

        if (Boolean.FALSE.equals(asBoolean(meta.get("draft")))) {
            warnings.add("文件里的 draft 是 false，但导入只会生成草稿 —— "
                    + "要上线还需要在编辑器里点一次「发布到线上」。");
        }

        PostService.PostDetail created = postService.create(new PostService.PostInput(
                title,
                slugified,
                stringOf(meta.get("description")),
                tags,
                date,
                body,
                stringOf(meta.get("coverImage"))));

        // PostService 在 slug 撞车时会自动加 -2、-3 后缀，这里把这件事说出来，
        // 否则作者会奇怪"我明明叫 a，怎么变成 a-2 了"。
        if (slugified != null && !slugified.isEmpty() && !slugified.equals(created.slug())) {
            warnings.add("已存在同名的 slug，本次导入保存为 " + created.slug() + "。");
        }

        log.info("导入 Markdown 成功：{} -> 草稿 id={} slug={}（{} 字节，{} 条提醒）",
                filename, created.id(), created.slug(), raw.length, warnings.size());

        return new ImportResult(created, filename == null ? "(未命名)" : filename,
                raw.length, warnings);
    }

    // ────────────────────────────────────────────────────────────────

    /**
     * 拆出 Front Matter 与正文。
     *
     * <p>只认最标准的写法：文件第一行是 {@code ---}，再找到下一行 {@code ---} 收尾。
     * 这样可以接受任意 YAML，而不是自己写一个半吊子的 key: value 解析器。
     */
    private record Section(String frontMatter, String body, boolean hadFrontMatter) {
    }

    private static Section splitFrontMatter(String text) {
        // 用 \\R 兼容 CRLF：Windows 上编辑过的文件几乎都是 CRLF，
        // 只按 \n 切会让每行末尾都带一个 \r，YAML 恰好能容忍，但第一行的比较会失败。
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        if (lines.length == 0 || !lines[0].isBlank() && !"---".equals(lines[0].trim())) {
            return new Section("", normalized, false);
        }
        if (!"---".equals(lines[0].trim())) {
            return new Section("", normalized, false);
        }

        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ("---".equals(trimmed) || "...".equals(trimmed)) {
                String fm = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, i));
                String body = String.join("\n",
                        java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
                return new Section(fm, body.replaceAll("^\\n+", ""), true);
            }
        }

        // 有开头的 --- 却没有收尾的：当成"没有 Front Matter"，把整篇留给正文。
        return new Section("", normalized, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yamlText, List<String> warnings) {
        if (yamlText == null || yamlText.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = new Yaml().load(yamlText);
            if (parsed == null) {
                return Map.of();
            }
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            warnings.add("Front Matter 不是一组键值对，已忽略。");
            return Map.of();
        } catch (RuntimeException ex) {
            // Front Matter 写坏了属于作者能自己修的问题，所以是 40002 而不是 500
            throw new BusinessException(ErrorCode.FRONT_MATTER_INVALID,
                    "Front Matter 的 YAML 解析失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 日期解析。
     *
     * <p>SnakeYAML 会把 {@code 2026-10-04} 这种裸日期解析成 {@link Date}，
     * 而且按 UTC 处理。这里显式用 UTC 转回 LocalDate，否则在东八区会莫名其妙少一天。
     */
    private static LocalDate parseDate(Object value, List<String> warnings) {
        if (value == null) {
            return LocalDate.now();
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        String text = String.valueOf(value).trim();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            warnings.add("date 字段（" + text + "）不是 YYYY-MM-DD 格式，已用今天代替。");
            return LocalDate.now();
        }
    }

    /** 标签既可能是 YAML 列表，也可能是 "a, b" 这样的字符串，两种都收。 */
    private static List<String> parseTags(Object value, List<String> warnings) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(v -> String.valueOf(v).trim())
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.startsWith("[")) {
            warnings.add("tags 看起来像 JSON 数组，YAML 里请写成每行一个「- 标签」。已按文本处理。");
            text = text.replaceAll("[\\[\\]\"]", "");
        }
        return java.util.Arrays.stream(text.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 去掉正文开头那个和标题重复的一级标题。
     *
     * <p>很多人写 Markdown 的习惯是开头写 {@code # 标题}。但本站的标题来自 Front Matter，
     * 正文里再来一个 h1，页面上就会出现两个一级标题（既不好看，对读屏软件也不友好）。
     */
    private static String stripDuplicateHeading(String body, String title, List<String> warnings) {
        if (body == null) {
            return "";
        }
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].startsWith("# ")) {
            return body.strip();
        }

        String heading = lines[i].substring(2).trim();
        if (heading.equalsIgnoreCase(title.strip())) {
            warnings.add("正文开头的一级标题与 title 重复，已自动去掉（标题以 Front Matter 为准）。");
        } else {
            warnings.add("正文开头的一级标题「" + heading + "」已去掉 —— "
                    + "标题统一由 Front Matter 提供，正文请从小标题开始写。");
        }

        return String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length)).strip();
    }

    private static String firstHeading(String body) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
            // 只在前几行里找：正文深处的标题不该被当成文章标题
            if (!trimmed.isEmpty()) {
                return null;
            }
        }
        return null;
    }

    private byte[] readBytes(MultipartFile file, String filename) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "没有收到文件");
        }

        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (ALLOWED_SUFFIXES.stream().noneMatch(lower::endsWith)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "只接受 Markdown 文件（" + String.join(" / ", ALLOWED_SUFFIXES)
                            + "），收到的是：" + filename);
        }

        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB，不像是纯文本文章");
        }

        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取上传内容失败", ex);
        }
    }

    /**
     * 从文件名推 slug。
     *
     * <p>本站的规矩是「文件名即网址」（content/posts/{slug}.md），
     * 所以已经有 .md 文件的人，文件名本来就是对的，直接沿用最省事。
     * 很多工具（Hexo、Hugo、Jekyll）导出的文件名还带日期前缀，这里顺手去掉。
     */
    private static String stemOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);

        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }

        // 去掉 2026-10-04- 或 20261004- 这样的日期前缀
        name = name.replaceAll("^\\d{4}-\\d{2}-\\d{2}[-_]", "")
                .replaceAll("^\\d{8}[-_]", "");

        String slug = PostService.slugify(name);
        return slug.length() > MAX_SLUG_LENGTH
                ? slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "")
                : slug;
    }

    private static String stringOf(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "no".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
