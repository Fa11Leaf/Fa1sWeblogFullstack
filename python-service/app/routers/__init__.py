"""路由分组。

按能力划分模块，每个模块只管自己那组接口：
  health.py  探活
  render.py  Markdown 渲染（P1）
  prepare.py 发布前处理：压缩图片、摘要、字数（P3）
  search.py  生成静态搜索索引（P5）
  feed.py    RSS 与 sitemap（P6）
"""
