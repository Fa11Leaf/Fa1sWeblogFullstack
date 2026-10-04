"""图片处理。

跟 Markdown 渲染一样属于"纯计算"：给一批字节，返回另一批字节，不碰数据库、不发外部请求。
这类代码几乎没有状态，因而极易测试 —— 这也是把图片处理放 Python 而不是 Java 的另一个理由。

做三件事：
  1. 修正 EXIF 方向（手机竖拍的照片在网页上会横过来，这是最常见的一类"页面有 bug"）
  2. 超过上限的宽度等比缩小（避免把 4000px 的手机原图直接发到线上）
  3. 统一转成 WebP（同样的画质下通常比 JPEG 小 25%～35%）
"""

from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

# WebP 的质量参数。82 是肉眼几乎看不出损失、体积又明显小于无损的取值。
DEFAULT_QUALITY = 82

# 单边像素上限。Pillow 对超大图会抛 DecompressionBombWarning，
# 这里主动设一个比它更严的上限，也算是对上传内容的一点防御。
MAX_PIXELS = 50_000_000


class ImageError(ValueError):
    """图片无法处理。调用方据此返回 400 而不是 500。"""


@dataclass(frozen=True)
class OptimizedImage:
    data: bytes
    content_type: str
    width: int
    height: int
    original_width: int
    original_height: int
    resized: bool


def optimize(data: bytes, max_width: int, quality: int = DEFAULT_QUALITY) -> OptimizedImage:
    """把任意常见位图转成 WebP，必要时缩小。"""
    if not data:
        raise ImageError("文件内容为空")

    try:
        image = Image.open(io.BytesIO(data))
        # 必须显式 load：Image.open 是惰性的，不 load 就拿不到真实的尺寸与像素，
        # 而下面要读 EXIF、要做缩放，都依赖真实像素。
        image.load()
    except UnidentifiedImageError as exc:
        # SVG 走不到这里（Pillow 不认 SVG），会同样落到这个分支
        raise ImageError("无法识别的图片格式。若上传的是 SVG，请直接放到 public/ 目录再引用") from exc
    except OSError as exc:
        raise ImageError(f"图片损坏或不完整：{exc}") from exc

    if image.width * image.height > MAX_PIXELS:
        raise ImageError(
            f"图片像素过多（{image.width}×{image.height}），请先缩小再上传"
        )

    # ① 按 EXIF 里的方向信息旋转回来。少了这一步，竖拍的手机照片在网页上就是横的。
    image = ImageOps.exif_transpose(image)

    original_width, original_height = image.width, image.height
    resized = False

    # ② 等比缩小。thumbnail 会原地修改且保持宽高比，比手算 resize 更不容易出错。
    if max_width and image.width > max_width:
        ratio = max_width / image.width
        target = (max_width, max(1, round(image.height * ratio)))
        image = image.resize(target, Image.LANCZOS)
        resized = True

    # ③ WebP 支持透明通道，但调色板模式（GIF 转来的）必须先转成 RGBA，
    #    否则保存时透明区域会变黑。
    if image.mode in ("P", "LA"):
        image = image.convert("RGBA")
    elif image.mode not in ("RGB", "RGBA", "L"):
        image = image.convert("RGB")

    buffer = io.BytesIO()
    image.save(buffer, format="WEBP", quality=quality, method=6)

    return OptimizedImage(
        data=buffer.getvalue(),
        content_type="image/webp",
        width=image.width,
        height=image.height,
        original_width=original_width,
        original_height=original_height,
        resized=resized,
    )
