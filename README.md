# AS_Tool · AnySearch 工具

> 项目名 **AS_Tool**，当前版本 **v1.2.1**（桌面版与安卓版同步）。
> **声明：本项目由 AI（DeepSeek）辅助开发制作。** 代码与文档由 AI 生成并经过人工测试修复，可能存在未发现的错误，使用前请自行审查。
> 本项目为**非官方**开源客户端，与 AnySearch 官方无关联、亦未获其任何背书。

基于 [AnySearch](https://anysearch.com) API 的通用搜索 / 网页抓取 / 多线程下载工具集，包含桌面版与安卓版。

## 功能

- 关键词搜索（1~10 条，保存原始 JSON）
- 网页正文抓取（Markdown），提取页面内全部链接（去重）
- 单文件多线程下载（1~16 线程、Range 分块、假直链拦截、大小校验、进度/速度/剩余时间、取消）
- 批量并行下载（每任务单独取消、总连接数限制、可突破限制并二次确认）
- 链接自动解析（v1.2.1）：网页/短链/跳转页自动跟随跳转并解析出真实直链，支持多清晰度/多文件选择、人机验证输入、Referer 防盗链
- 镜像源：`{url}` 模板、三种策略（仅直连 / 镜像优先 / 直连优先低速切换）、测速选优
- HTTP/HTTPS 代理、自定义请求头（API 与下载共用）
- API Key 本地保存（应用私有目录，不上传）

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `desktop/` | 桌面版（Python 3.8+，纯标准库无第三方依赖） |
| `android/` | 安卓版（Kotlin + Jetpack Compose，minSdk 26） |

- 桌面版使用说明：`desktop/使用说明.md`（人类用）、`desktop/README_AI.md`（AI/脚本用）
- 安卓版使用说明：`android/README-安卓.md`

## 快速开始

### 桌面版

```bash
# 1. 配置 Key（可选，不配则匿名调用、限流更严）
cp desktop/anysearch_api_key.example.txt desktop/anysearch_api_key.txt
#    编辑该文件，填入你的 Key

# 2. 使用
cd desktop
python anysearch_asset_search.py "关键词" -m 5        # 搜索
python anysearch_asset_search.py -e "https://..."      # 抓取网页
python anysearch_asset_search.py -l "https://..."      # 提取链接
python anysearch_asset_search.py -d "https://..."      # 下载直链
python anysearch_asset_search.py --resolve "https://..."  # 解析网页/短链并列出真实下载链接
python anysearch_gui.py                                # 图形界面
```

### 安卓版

用 Android Studio 2026.1+ 打开 `android/` 目录，同步后 **Build → Build APK(s)**。
详细步骤见 `android/README-安卓.md`。

## API Key 与安全

- 真实 Key **不要**写进任何会提交到仓库的文件；
- 仓库中的 `desktop/anysearch_api_key.example.txt` 是模板，`desktop/anysearch_api_key.txt` 已被 `.gitignore` 忽略；
- 安卓版 Key 在 App 内输入并保存于应用私有目录，不随源码分发。

## 许可证

本项目采用 [MIT License]([LICENSE](https://github.com/paomianyaoren/AS-Tool/blob/main/AS_Tool%201.2.1/LICENSE)) 开源。使用本工具请遵守目标网站服务条款与相关法律法规，详见各目录下的免责声明。

## 更新记录

- 桌面版更新记录见 `desktop/使用说明.md`「十一、更新日志」
- 安卓版更新记录见 `android/README-安卓.md`「六、更新记录」

