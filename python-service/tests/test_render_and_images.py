"""Markdown 渲染与图片处理的测试。

这两个模块是纯函数，不需要起服务、不需要数据库，因此测试写得很快，
也正因为它们纯，测试价值最高 —— 覆盖的是"输入什么、输出什么"，不会因为环境不同而飘。
"""

from __future__ import annotations

import base64
import io

import pytest
from PIL import Image

from app.services import image_optimizer, markdown_renderer


# ── Markdown ────────────────────────────────────────────────────────────


def test_render_basic_markdown() -> None:
    result = markdown_renderer.render("# 标题\n\n正文一段。\n\n- 一\n- 二\n")
    assert "<h1>" in result.html
    assert "<ul>" in result.html
    assert result.headings == 1


def test_render_escapes_raw_html() -> None:
    """裸 HTML 必须被转义。

    这是预览接口的安全底线：后台把渲染结果塞进页面里显示（v-html），
    如果允许裸 HTML，作者（或任何拿到令牌的人）就能在后台页面执行任意脚本。
    """
    result = markdown_renderer.render('<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n')
    assert "<script>" not in result.html
    assert "onerror" not in result.html or "&lt;img" in result.html
    assert "&lt;script&gt;" in result.html


def test_render_gfm_table_and_strikethrough() -> None:
    result = markdown_renderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~删掉~~\n")
    assert "<table>" in result.html
    assert "<s>" in result.html or "<del>" in result.html


def test_render_code_block_is_highlighted() -> None:
    result = markdown_renderer.render("```python\nprint('hi')\n```\n")
    assert "<pre>" in result.html
    # Pygments 的 token span。返回的是 nowrap 片段，外层 pre/code 由 markdown-it 加，
    # 因此这里不能再出现第二层 pre。
    assert 'class="k"' in result.html or 'class="nb"' in result.html
    assert result.html.count("<pre>") == 1
    assert result.has_code is True


def test_render_unknown_language_does_not_fail() -> None:
    """不认识的语言标记不该让整篇渲染失败，退回纯文本即可。"""
    result = markdown_renderer.render("```不存在的语言\nhello\n```\n")
    assert "<pre>" in result.html


def test_word_count_counts_chinese_by_character() -> None:
    """中文必须按字算。用 split() 会把整段中文算成一个词。"""
    assert markdown_renderer.count_words("你好世界") == 4
    assert markdown_renderer.count_words("hello world") == 2


def test_word_count_ignores_code_fences() -> None:
    """代码块不计入字数：读者不会逐字读代码，算进去会得出吓人的阅读时长。"""
    with_code = markdown_renderer.render("正文\n\n```python\n" + "x = 1\n" * 100 + "```\n")
    without_code = markdown_renderer.render("正文\n")
    assert with_code.words == without_code.words


def test_render_empty_input() -> None:
    result = markdown_renderer.render("")
    assert result.html.strip() == ""
    assert result.words == 0
    assert result.reading_minutes == 0


# ── 图片 ────────────────────────────────────────────────────────────────


def _png(width: int, height: int, mode: str = "RGB") -> bytes:
    image = Image.new(mode, (width, height), (200, 30, 30) if mode == "RGB" else (200, 30, 30, 128))
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def test_optimize_converts_to_webp() -> None:
    result = image_optimizer.optimize(_png(800, 600), max_width=1600)
    assert result.content_type == "image/webp"
    assert Image.open(io.BytesIO(result.data)).format == "WEBP"
    assert (result.width, result.height) == (800, 600)
    assert result.resized is False


def test_optimize_shrinks_wide_image_preserving_ratio() -> None:
    result = image_optimizer.optimize(_png(3200, 1600), max_width=1600)
    assert result.width == 1600
    # 宽高比必须保持 2:1，不能只缩宽度
    assert result.height == 800
    assert result.resized is True
    assert (result.original_width, result.original_height) == (3200, 1600)


def test_optimize_keeps_transparency() -> None:
    """调色板/带透明通道的图先转 RGBA，否则透明区域会变黑。"""
    source = _png(64, 64, mode="RGBA")
    result = image_optimizer.optimize(source, max_width=1600)
    decoded = Image.open(io.BytesIO(result.data))
    assert decoded.mode in ("RGBA", "RGB")
    if decoded.mode == "RGBA":
        assert decoded.getpixel((0, 0))[3] == 128


def test_optimize_rejects_non_image() -> None:
    with pytest.raises(image_optimizer.ImageError):
        image_optimizer.optimize(b"this is definitely not an image", max_width=1600)


def test_optimize_rejects_empty() -> None:
    with pytest.raises(image_optimizer.ImageError):
        image_optimizer.optimize(b"", max_width=1600)
