"""Markdown 渲染接口。

为什么这个接口由【后端】转发而不是浏览器直接调：本服务没有面向浏览器的认证层，
令牌一旦发到前端就等于公开。浏览器只认识 Spring Boot，由后端用内部令牌调这里。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..security import require_internal_token
from ..services import markdown_renderer

router = APIRouter(tags=["render"], dependencies=[Depends(require_internal_token)])


class RenderRequest(BaseModel):
    markdown: str = ""
    # 保留字段：目前渲染结果里已经带了统计信息，这个开关是为了将来
    # "只要 HTML、不要统计"的场景。
    with_meta: bool = False


class RenderResponse(BaseModel):
    html: str
    words: int
    reading_minutes: int
    headings: int
    has_code: bool


@router.post("/render", response_model=RenderResponse, summary="Markdown 转 HTML")
async def render(request: RenderRequest) -> RenderResponse:
    """把 Markdown 渲染成 HTML 并附上字数统计。

    作者在编辑器里每敲一会儿就会调一次，所以这条路径要尽量短：
    解析是纯 CPU 操作，没有 IO，实测一篇 3000 字的文章在几毫秒内完成。
    """
    result = markdown_renderer.render(request.markdown)
    return RenderResponse(
        html=result.html,
        words=result.words,
        reading_minutes=result.reading_minutes,
        headings=result.headings,
        has_code=result.has_code,
    )


@router.get("/render/style.css", include_in_schema=False)
async def pygments_css() -> dict[str, str]:
    """代码高亮的配色。

    后台预览把这段 CSS 注入一次即可。不放进 /render 的响应里，
    是因为它是固定的、几百字节的常量，没必要每次预览都传一遍。
    """
    return {"css": markdown_renderer.PYGMENTS_CSS}
