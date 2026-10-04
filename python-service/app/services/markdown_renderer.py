"""Markdown 渲染。

放在 Python 侧的理由：Markdown 解析器在 Python 生态里更成熟，而且"文本处理"本来就
归 Python。这样 Java 侧一个 Markdown 库都不用引。

**两条必须记住的安全设定：**

1. ``html=False`` —— 原文里的裸 HTML 会被转义成文本，而不是原样输出。
   写博客的人不该需要知道 XSS，但后台的预览是把 HTML 塞进页面的，
   一旦允许裸 HTML，作者（或任何拿到令牌的人）就能在后台页面里执行任意脚本。
   关掉它，正文就只能是正文。

2. **没有开启 linkify** —— linkify 会把看起来像网址的文本自动变成 ``<a>``，
   这需要额外的 linkify-it-py 依赖，而且会让"我明明没写链接却出现了链接"这种
   意外发生。宁可少一点便利。
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

from markdown_it import MarkdownIt

# Pygments 用于代码高亮。它在 Java 侧的同类库存在感很弱，
# 这也是"文本处理归 Python"这条分工最直接的例子。
from pygments import highlight
from pygments.formatters import HtmlFormatter
from pygments.lexers import get_lexer_by_name, guess_lexer
from pygments.util import ClassNotFound

# 中日韩统一表意文字。中文的"字数"不能用空格分词来算。
_CJK = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")
_LATIN_WORD = re.compile(r"[A-Za-z0-9_]+")
_HEADING = re.compile(r"^#{1,6}\s+\S", re.MULTILINE)
_FENCE = re.compile(r"^```.*?^```", re.MULTILINE | re.DOTALL)

# 中文阅读速度按每分钟 400 字估算（比英文的 200 词高，因为中文信息密度更大）
_WORDS_PER_MINUTE = 400


def _highlight(code: str, lang: str, _attrs: str) -> str:
    """fenced code 的高亮回调。

    返回的是**不带外层标签**的片段（nowrap=True），由 markdown-it 自己包
    ``<pre><code>``。若返回带 ``<pre>`` 的完整片段，markdown-it 会再包一层，
    页面上就会出现嵌套的 pre —— 它只认以 ``<pre`` 开头的结果，而 Pygments
    的默认输出是以 ``<div`` 开头的。
    """
    try:
        lexer = get_lexer_by_name(lang) if lang else guess_lexer(code)
    except ClassNotFound:
        # 不认识的语言标记（比如 ```text-xxx）不该让整篇渲染失败
        lexer = get_lexer_by_name("text")

    return highlight(code, lexer, HtmlFormatter(nowrap=True))


def build_renderer() -> MarkdownIt:
    """构造解析器。

    ``commonmark`` 预设最严格，默认关闭表格与删除线；这两条在写技术笔记时太常用，
    所以单独打开。刻意不用 ``gfm-like``：那个预设会一并打开 linkify 等开关，
    引入本模块顶部说明的那些副作用。
    """
    md = MarkdownIt("commonmark", {"html": False, "typographer": False})
    md.enable("table")
    md.enable("strikethrough")
    md.options["highlight"] = _highlight

    # 插件是可选的：装不上只损失功能，不该让整个服务起不来。
    try:
        from mdit_py_plugins.footnote import footnote_plugin

        md.use(footnote_plugin)
    except ImportError:  # pragma: no cover - 依赖缺失时的降级路径
        pass

    try:
        from mdit_py_plugins.tasklists import tasklists_plugin

        md.use(tasklists_plugin)
    except ImportError:  # pragma: no cover
        pass

    return md


_renderer = build_renderer()

# 供后台预览引用的一次性样式。用 Pygments 自己的 CSS 而不是手写一套，
# 保证配色跟着 style 参数走。
PYGMENTS_STYLE = "native"
PYGMENTS_CSS = HtmlFormatter(style=PYGMENTS_STYLE).get_style_defs(".prose pre code")


@dataclass(frozen=True)
class RenderResult:
    html: str
    words: int
    reading_minutes: int
    headings: int
    has_code: bool


def count_words(text: str) -> int:
    """估算字数。

    中文按字算、西文按词算 —— 用 ``len(text.split())`` 去算中文会得出
    "整段算一个词"的荒谬结果。代码块整体剔除：读者不会逐字读代码，
    把它算进阅读时长会让"预计 30 分钟"这种吓人的数字出现。
    """
    plain = _FENCE.sub(" ", text)
    return len(_CJK.findall(plain)) + len(_LATIN_WORD.findall(plain))


def render(markdown: str) -> RenderResult:
    text = markdown or ""
    html = _renderer.render(text)
    words = count_words(text)

    return RenderResult(
        html=html,
        words=words,
        # 至少 1 分钟：几十个字的便签显示"0 分钟阅读"很怪
        reading_minutes=max(1, math.ceil(words / _WORDS_PER_MINUTE)) if words else 0,
        headings=len(_HEADING.findall(text)),
        has_code="<pre>" in html,
    )
