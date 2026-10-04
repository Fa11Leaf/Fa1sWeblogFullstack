"""探活接口。"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ..config import settings
from ..security import require_internal_token

router = APIRouter(tags=["system"])


@router.get("/health", dependencies=[Depends(require_internal_token)])
async def health() -> dict[str, str]:
    """返回服务状态与版本。

    刻意也要求令牌：它是 Spring Boot 判断"Python 是否可用"的唯一依据，
    顺手验证令牌链路是否通，比单独再写一个免鉴权的探针更有价值。
    """
    return {"status": "ok", "version": settings.version}
