"""Python 能力服务入口。

启动方式见 README §9.2：
    uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload

三条设计约束：
  1. 只监听 127.0.0.1 —— 该服务没有面向浏览器的认证层
  2. 除根路径外，所有接口要求 X-Internal-Token
  3. 不配置 CORS —— 浏览器本来就不该直接调它，配了反而等于开了口子
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .config import settings
from .routers import health, images, render

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("python-service")


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """启动与关闭钩子。

    用 lifespan 而不是 @app.on_event：后者在新版 FastAPI 里已不推荐使用。
    """
    if settings.using_dev_token:
        logger.warning(
            "正在使用开发占位令牌 %r，上线前必须通过 PY_INTERNAL_TOKEN 覆盖",
            settings.DEV_TOKEN_PLACEHOLDER,
        )
    logger.info("python-service %s 就绪", settings.version)
    yield
    logger.info("python-service 已停止")


app = FastAPI(
    title="weblog python-service",
    description="Spring Boot 的内部能力服务：Markdown 渲染、图片处理、分词与索引生成。",
    version=settings.version,
    lifespan=lifespan,
)

app.include_router(health.router)
app.include_router(render.router)
app.include_router(images.router)


@app.get("/", include_in_schema=False)
async def root() -> dict[str, str]:
    """便于在浏览器里确认服务活着。不含任何数据，因此不要求令牌。"""
    return {
        "service": "weblog-python-service",
        "version": settings.version,
        "docs": "/docs",
    }
