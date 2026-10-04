"""探活接口的测试。

跑法：cd python-service && pytest
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app

client = TestClient(app)


def test_lifespan_starts_and_stops() -> None:
    """用 with 进入客户端才会真正执行 startup / shutdown。

    这个用例是补上来的，原因值得记一笔：
    TestClient 不放在 with 里时【不会运行 lifespan】，直接 client.get(...) 只是
    调用路由。曾经因为 Settings.DEV_TOKEN_PLACEHOLDER 写成了模块级常量，
    启动钩子抛 AttributeError、uvicorn 一起就退出，而前面所有用例照样全绿 ——
    直到真正启动服务才暴露。所以启动钩子必须有这样一个独立的用例兜着。
    """
    with TestClient(app) as live:
        assert live.get("/").status_code == 200


def test_root_is_open() -> None:
    """根路径不需要令牌，用来确认服务活着。"""
    response = client.get("/")
    assert response.status_code == 200
    assert response.json()["service"] == "weblog-python-service"


def test_health_rejects_missing_token() -> None:
    """不带令牌必须被拒。这是这个服务唯一的安全边界，值得单独断言。"""
    response = client.get("/health")
    assert response.status_code == 401


def test_health_rejects_wrong_token() -> None:
    response = client.get("/health", headers={"X-Internal-Token": "definitely-wrong"})
    assert response.status_code == 401


def test_health_accepts_correct_token() -> None:
    # 从 settings 取值而不是硬编码：若本机设了 PY_INTERNAL_TOKEN，
    # 测试依然能通过，不会因为环境差异而假失败。
    response = client.get(
        "/health",
        headers={"X-Internal-Token": settings.internal_token},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["version"] == settings.version


def test_settings_exposes_placeholder() -> None:
    """把刚才那个 bug 钉死：这个属性必须存在且可访问。"""
    assert isinstance(settings.DEV_TOKEN_PLACEHOLDER, str)
    assert settings.DEV_TOKEN_PLACEHOLDER
    assert settings.using_dev_token in (True, False)
