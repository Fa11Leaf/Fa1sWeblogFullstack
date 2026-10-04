"""内部令牌校验。

为什么需要它：这个服务只监听回环地址，但"只监听回环"并不等于"只有后端能调用"——
本机上任何程序都能连 127.0.0.1:8000。加上共享令牌后，才算真的只有后端能调。

实现成一个 FastAPI 依赖，用 dependencies=[Depends(require_internal_token)] 挂在路由上。
"""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from .config import settings


async def require_internal_token(
    x_internal_token: str | None = Header(default=None),
) -> None:
    """校验 X-Internal-Token 请求头。不匹配则返回 401。"""
    if not settings.internal_token:
        # 服务端自己没配令牌，属于部署错误，用 500 而不是 401 表达。
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="服务未配置 PY_INTERNAL_TOKEN，拒绝所有请求",
        )

    # 用 compare_digest 做定时安全比较，避免通过响应时间逐字节猜出令牌。
    provided = x_internal_token or ""
    if not hmac.compare_digest(provided, settings.internal_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="内部令牌无效",
        )
