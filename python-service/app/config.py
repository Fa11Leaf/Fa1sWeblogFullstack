"""集中读取环境变量。

所有可调项都在这里，业务代码不直接碰 os.getenv —— 否则想知道"这个服务受哪些环境变量影响"
就得全仓库搜索。
"""

from __future__ import annotations

import os


class Settings:
    """运行期配置。在模块加载时读取一次。"""

    # 开发期的占位令牌。启动时据此打警告，也是判断"是否还没配真实令牌"的依据。
    # 注意它是【类属性】而不是模块级变量：main.py 里用的是 settings.DEV_TOKEN_PLACEHOLDER。
    # 早期版本这里写成了模块级常量，导致启动钩子抛 AttributeError、
    # uvicorn 直接退出，而 TestClient 不在 with 里时不会跑 lifespan，所以单测漏掉了。
    DEV_TOKEN_PLACEHOLDER: str = "dev-internal-token"

    def __init__(self) -> None:
        # 默认只绑回环地址。这个服务没有认证层，绑 0.0.0.0 等于把它暴露给整个局域网。
        self.host: str = os.getenv("PY_HOST", "127.0.0.1")
        self.port: int = int(os.getenv("PY_PORT", "8000"))

        # 与 Spring Boot 共享的内部令牌，通过 X-Internal-Token 头校验。
        self.internal_token: str = os.getenv("PY_INTERNAL_TOKEN", self.DEV_TOKEN_PLACEHOLDER)

        # 只用于探活时回报，方便确认前后两端版本是否配套。
        self.version: str = "0.1.0"

    @property
    def using_dev_token(self) -> bool:
        """是否仍在使用开发占位令牌。启动时会据此打警告。"""
        return self.internal_token == self.DEV_TOKEN_PLACEHOLDER


settings = Settings()
