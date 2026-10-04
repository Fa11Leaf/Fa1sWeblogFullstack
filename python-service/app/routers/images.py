"""图片处理接口。

图片走 multipart 而不是 JSON：JSON 里传二进制必须先 base64，体积凭空涨三分之一，
而图片正是这里最大的负载。返回方向用 base64 是可以接受的 —— 那时已经是压缩后的小图。
"""

from __future__ import annotations

import base64

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from pydantic import BaseModel

from ..security import require_internal_token
from ..services import image_optimizer

router = APIRouter(tags=["images"], dependencies=[Depends(require_internal_token)])

# 单张图的字节上限。后端也有一道（spring.servlet.multipart），两道都留着：
# 后端那道拦住的是请求体，这道拦住的是解压后可能膨胀的内容。
MAX_BYTES = 20 * 1024 * 1024


class OptimizeResponse(BaseModel):
    data_base64: str
    content_type: str
    width: int
    height: int
    size: int
    original_size: int
    original_width: int
    original_height: int
    resized: bool


@router.post("/images/optimize", response_model=OptimizeResponse, summary="压缩并转成 WebP")
async def optimize_image(
    file: UploadFile = File(...),
    max_width: int = Form(1600),
) -> OptimizeResponse:
    raw = await file.read()
    if len(raw) > MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"图片超过 {MAX_BYTES // 1024 // 1024}MB 上限",
        )

    try:
        result = image_optimizer.optimize(raw, max_width=max_width)
    except image_optimizer.ImageError as exc:
        # 是"你给的图有问题"，不是"服务坏了"，所以是 400 而不是 500
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return OptimizeResponse(
        data_base64=base64.b64encode(result.data).decode("ascii"),
        content_type=result.content_type,
        width=result.width,
        height=result.height,
        size=len(result.data),
        original_size=len(raw),
        original_width=result.original_width,
        original_height=result.original_height,
        resized=result.resized,
    )
