# -*- coding: utf-8 -*-
"""AnySearch API 通用搜索 / 网页抓取 / 文件下载工具（纯标准库，无第三方依赖）

功能：
  - search：调用 AnySearch 搜索并返回结果摘要
  - extract：抓取指定网页的完整正文（Markdown）
  - download：下载直链文件到本地目录

用法：
  $env:ANYSEARCH_API_KEY='as_sk_...'
  python anysearch_asset_search.py "open source license list" --max-results 5
  python anysearch_asset_search.py "cc0 texture concrete floor" --save-out results.json
  python anysearch_asset_search.py --extract "https://example.com/page" 
  python anysearch_asset_search.py --extract-links "https://example.com/page"
  python anysearch_asset_search.py --download "https://example.com/file.zip" -o downloads

协议（官方 AnySearch Interface Specification）：
  POST https://api.anysearch.com/mcp
  JSON-RPC 2.0, method="tools/call", Authorization: Bearer <KEY>

免责声明：
  本工具仅供学习与合法用途使用。使用 AnySearch API 须遵守其服务条款；
  请勿将 API Key 硬编码进代码或公开泄露；请勿抓取/下载违反版权、隐私或
  法律法规的内容。作者不对使用本工具产生的任何后果负责。
"""
import argparse
import json
import os
import re
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

import anysearch_downloader

ENDPOINT = "https://api.anysearch.com/mcp"

# 自定义请求头（由 set_custom_headers 设置，API 请求会带上）
_CUSTOM_HEADERS = {}


def set_custom_headers(headers):
    """设置自定义请求头（dict），会合并到所有 API 请求中。"""
    global _CUSTOM_HEADERS
    _CUSTOM_HEADERS = dict(headers or {})


def build_body(tool: str, arguments: dict) -> bytes:
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {
            "name": tool,
            "arguments": arguments,
        },
    }
    # json.dumps 输出纯 ASCII/UTF-8，无 BOM
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def call(api_key: str, tool: str, arguments: dict, timeout: int = 60):
    body = build_body(tool, arguments)
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "X-Anysearch-Client": "mcp/1.0.0",
        "User-Agent": "anysearch-python-client/0.1",
    }
    headers.update(_CUSTOM_HEADERS)
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    req = urllib.request.Request(ENDPOINT, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        # API 返回 4xx/5xx：不崩溃，返回可读错误文本
        try:
            detail = e.read().decode("utf-8", errors="replace")[:2000]
        except Exception:
            detail = str(e)
        return None, "[HTTP %d] %s" % (e.code, detail or str(e))
    except urllib.error.URLError as e:
        return None, "[网络错误] %s" % e.reason
    try:
        return json.loads(raw), raw
    except json.JSONDecodeError:
        return None, raw


def apply_proxy(use_proxy, proxy_url):
    """应用 HTTP/HTTPS 代理到全局 urllib 请求（API 与下载共用）。

    use_proxy：True 启用代理；False 禁用环境/系统代理（使用直连）。
    proxy_url：例如 http://127.0.0.1:7890
    """
    import urllib.request

    if use_proxy and proxy_url:
        proxy_url = proxy_url.strip()
        if not proxy_url.lower().startswith(("http://", "https://")):
            proxy_url = "http://" + proxy_url
        handler = urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url})
        opener = urllib.request.build_opener(handler)
    else:
        # 不启用代理：禁用环境变量代理，确保走直连
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    urllib.request.install_opener(opener)


def extract_result_text(obj):
    """MCP 工具结果常见结构：result.content[].text"""
    if not isinstance(obj, dict):
        return None
    result = obj.get("result")
    if isinstance(result, dict):
        content = result.get("content")
        if isinstance(content, list) and content and isinstance(content[0], dict):
            text = content[0].get("text")
            if text:
                return text
    return None


def extract_links(text):
    """从文本/Markdown 中提取所有 http(s) URL（去重、保持出现顺序）。

    说明：只提取静态文本/Markdown 里的链接；JS 动态渲染的链接拿不到。
    链接尾部的常见标点（. , ; : ! ? 及中文标点等）会被自动剥掉。
    """
    if not text:
        return []
    # 排除集包含空白、<>、中英文括号/引号及常见中英文标点，防止把标点和中文文字吞进链接
    pattern = re.compile(r"https?://[^\s<>()\[\]\"'，。；：！？、（）【】《》「」『』]+")
    seen = set()
    links = []
    for m in pattern.finditer(text):
        url = m.group(0).rstrip('.,;:!?)]}）】》"，。；！？、\'"')
        if url and url not in seen:
            seen.add(url)
            links.append(url)
    return links


# ==================== 链接解析（--resolve / --download 前置） ====================

def _opt_line(opt):
    """LinkOption → 一行可读描述。"""
    from urllib.parse import urlparse
    name = (opt.label or "").strip() or urlparse(opt.url).path.rsplit("/", 1)[-1]
    note = ("（%s）" % opt.note) if opt.note else ""
    if opt.kind == "image":
        if opt.width and opt.height:
            size = "%s×%s" % (opt.width, opt.height)
        elif opt.width:
            size = "%spx" % opt.width
        else:
            size = "图片"
        return "图片 %s · %s%s" % (size, name[:60], note)
    if opt.kind == "video":
        return "视频 · %s%s" % (name[:60], note)
    if opt.kind == "audio":
        return "音频 · %s%s" % (name[:60], note)
    return "文件 · %s%s" % (name[:60], note)


def cli_interactive(challenge, cancel_event):
    """CLI 验证码交互：保存图片并提示输入（返回 {字段: 输入}，放弃返回 None）。"""
    print("\n[人机验证] %s" % (challenge.hint or "该页面需要人机验证"))
    if challenge.captcha_bytes:
        ct = (challenge.captcha_content_type or "").lower()
        ext = (".jpg" if "jpeg" in ct or "jpg" in ct else
               ".gif" if "gif" in ct else ".png")
        path = os.path.join(tempfile.gettempdir(), "anysearch_captcha" + ext)
        try:
            with open(path, "wb") as f:
                f.write(challenge.captcha_bytes)
            print("验证码图片已保存到：%s（请打开查看）" % path)
        except Exception:
            print("验证码图片地址：%s" % challenge.captcha_img_url)
    elif challenge.captcha_img_url:
        print("验证码图片地址：%s（请用浏览器打开查看）" % challenge.captcha_img_url)
    answers = {}
    for f in challenge.fields:
        if f["needs_input"]:
            try:
                answers[f["name"]] = input("请输入 %s： " % f["name"]).strip()
            except (EOFError, KeyboardInterrupt):
                return None
    return answers


def _cmd_resolve(url, timeout):
    """--resolve / --list-options：打印跳转链与下载选项。"""
    import anysearch_link_resolver as lr

    try:
        rr = lr.resolve(url, timeout=timeout, interactive=cli_interactive,
                        progress_callback=lambda s, d: print("  [%s] %s" % (s, d)))
        if rr.hops:
            print("[跳转链] %s" % " -> ".join(rr.hops))
        if rr.captcha and not rr.options:
            print("[提示] %s" % (rr.note or "需要人机验证"))
            return 1
        if rr.options:
            print("[结果] 共 %d 个下载选项：" % len(rr.options))
            for i, o in enumerate(rr.options, 1):
                print("%2d. %s" % (i, _opt_line(o)))
                print("    %s" % o.url)
        else:
            print("[结果] 未找到可下载内容。%s" % (rr.note or ""))
        return 0
    except lr.DownloadCancelled:
        print("[已取消]")
        return 130
    except Exception as e:
        print("[解析失败] %s" % e)
        return 1


def main():
    parser = argparse.ArgumentParser(description="AnySearch 通用搜索 / 网页抓取 / 文件下载工具")
    parser.add_argument("query", nargs="?", help="搜索关键词，例如：open source license list、cc0 texture、技术文档等")
    parser.add_argument("--max-results", "-m", type=int, default=5, help="1-10，默认 5")
    parser.add_argument("--api-key", default=None, help="覆盖环境变量 ANYSEARCH_API_KEY")
    parser.add_argument("--save-out", default=None, help="把原始 JSON 保存到文件")
    parser.add_argument("--extract", "-e", default=None, help="抓取指定 URL 的完整页面内容（Markdown）")
    parser.add_argument("--extract-links", "-l", default=None, help="抓取指定 URL 页面并提取其中所有链接（去重）")
    parser.add_argument("--download", "-d", default=None, help="下载指定直链 URL")
    parser.add_argument("--out-dir", "-o", default="downloads", help="下载目录，默认 downloads")
    parser.add_argument("--chunk-size", type=int, default=0, help="分块大小（MB），0=自动分块，1~64=手动分块")
    parser.add_argument("--discard", default=None, help="放弃指定 URL 的下载断点（配合 --out-dir 使用）")
    parser.add_argument("--mirror", action="append", default=None,
                        help="镜像模板（含 {url} 占位符），可多次指定，如 https://ghfast.top/{url}")
    parser.add_argument("--mirror-strategy", default="direct",
                        choices=["direct", "mirror_first", "auto_fallback"],
                        help="镜像策略：direct 仅直连 / mirror_first 立即开始下载后台测速择快切换 / auto_fallback 直连优先自动切换")
    parser.add_argument("--mirror-speed-limit", type=int, default=0,
                        help="auto_fallback 低速切换阈值（KB/s，0=不因速度切换）")
    parser.add_argument("--resolve", default=None, help="解析链接：跟随跳转并列出页面里的下载选项（不下载）")
    parser.add_argument("--list-options", default=None, help="同 --resolve：列出下载选项")
    parser.add_argument("--pick", default=None,
                        help="配合 --download 在多选项中选择：N（序号）/ best（最佳）/ URL")
    parser.add_argument("--no-resolve", action="store_true", help="下载前跳过链接解析（按普通直链处理）")
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()

    api_key = args.api_key or os.environ.get("ANYSEARCH_API_KEY", "")
    # 本地 Key 文件（与脚本同目录），没有环境变量时读取它
    key_file = Path(__file__).with_name("anysearch_api_key.txt")
    if not api_key and key_file.exists():
        api_key = key_file.read_text(encoding="utf-8").strip()
    if not api_key:
        print("[提示] 未提供 API Key，将匿名调用（限流更严）。可设置 $env:ANYSEARCH_API_KEY")

    if args.extract:
        print(f"[AnySearch] 抓取页面: {args.extract}")
        obj, raw = call(api_key, "extract", {"url": args.extract}, args.timeout)
        if obj is None:
            print("[响应不是 JSON，原文如下]"); print(raw[:4000]); sys.exit(3)
        if obj.get("error"):
            print(f"[API 错误] {obj.get('error')}"); sys.exit(4)
        text = extract_result_text(obj) or json.dumps(obj, ensure_ascii=False, indent=2)
        print(text[:20000])
        return

    if args.extract_links:
        print(f"[AnySearch] 抓取页面并提取链接: {args.extract_links}")
        obj, raw = call(api_key, "extract", {"url": args.extract_links}, args.timeout)
        if obj is None:
            print("[响应不是 JSON，原文如下]"); print(raw[:4000]); sys.exit(3)
        if obj.get("error"):
            print(f"[API 错误] {obj.get('error')}"); sys.exit(4)
        text = extract_result_text(obj) or ""
        links = extract_links(text)
        if links:
            print(f"[结果] 共提取到 {len(links)} 个链接：\n")
            for i, link in enumerate(links, 1):
                print(f"{i}. {link}")
        else:
            print("[结果] 未提取到任何链接。")
        return

    if args.discard:
        anysearch_downloader.discard_download(url=args.discard, out_dir=args.out_dir)
        print("[完成] 已放弃断点：%s" % args.discard)
        return

    if args.resolve or args.list_options:
        sys.exit(_cmd_resolve(args.resolve or args.list_options, args.timeout))

    if args.download:
        class _CliBlocked(Exception):
            pass

        def run_download(target, referer=None):
            try:
                chunk_mb = max(0, min(64, args.chunk_size))
                auto_chunk = chunk_mb <= 0
                chunk_size = None if auto_chunk else chunk_mb * 1024 * 1024
                download_file(
                    target, args.out_dir, args.timeout,
                    auto_chunk=auto_chunk, chunk_size=chunk_size,
                    mirrors=args.mirror or [],
                    mirror_strategy=args.mirror_strategy,
                    mirror_speed_limit=max(0, args.mirror_speed_limit) * 1024,
                    referer=referer,
                )
            except anysearch_downloader.DownloadCancelled:
                print("\n[已取消] 断点已保留，再次运行同一命令将从断点续传；")
                print("放弃断点请运行：--discard \"%s\" -o %s" % (target, args.out_dir))
                sys.exit(6)
            except anysearch_downloader.DownloadPaused as e:
                print("\n[已暂停] %s" % e)
                print("再次运行同一命令即从断点续传；放弃断点：--discard \"%s\" -o %s" % (target, args.out_dir))
                sys.exit(5)
            except anysearch_downloader.HtmlPageError:
                raise  # 网页链接：交给外层启用解析
            except Exception as e:
                msg = str(e)
                if referer is None and not args.no_resolve and _is_block_msg(msg):
                    raise _CliBlocked(msg)
                if referer is not None:
                    print("[下载失败] %s" % msg)
                    print("[建议] 该直链可能绑定浏览器会话或已失效：请用浏览器打开页面，")
                    print("       点击下载并复制得到的链接后重试。")
                else:
                    print("[下载失败] %s" % msg)
                sys.exit(8)

        def _is_block_msg(msg):
            return ("403" in msg or "429" in msg or "forbidden" in msg.lower()
                    or "禁止" in msg or "拒绝" in msg)

        # ---------- 直链优先：直接下载；只有返回网页/被拒绝才启用解析 ----------
        target = args.download
        try:
            run_download(target)
            return
        except anysearch_downloader.HtmlPageError as e:
            if args.no_resolve:
                print("[错误] %s" % e)
                sys.exit(8)
        except _CliBlocked as e:
            if args.no_resolve:
                print("[错误] %s" % e)
                sys.exit(8)
            print("[提示] 直连被拒绝（%s），尝试解析页面获取真实直链…" % e)

        import anysearch_link_resolver as lr

        try:
            rr = lr.resolve(args.download, timeout=args.timeout,
                            interactive=cli_interactive)
            if not rr.options:
                print(rr.note or "未找到可下载内容。")
                sys.exit(7)
            if len(rr.options) == 1:
                target = rr.options[0].url
            else:
                print("[结果] 解析出 %d 个下载选项：" % len(rr.options))
                for i, o in enumerate(rr.options, 1):
                    print("%2d. %s\n    %s" % (i, _opt_line(o), o.url))
                pick = (args.pick or "").strip()
                if not pick:
                    print("请用 --pick N / --pick best / --pick URL 选择后重新下载。")
                    sys.exit(7)
                if pick.lower() == "best":
                    target = rr.options[0].url
                elif pick.isdigit() and 1 <= int(pick) <= len(rr.options):
                    target = rr.options[int(pick) - 1].url
                else:
                    match = next((o for o in rr.options if o.url == pick), None)
                    if match is None:
                        print("[错误] 无效的 --pick 值：%s" % args.pick)
                        sys.exit(7)
                    target = match.url
            print("[直链] %s" % target)
        except lr.DownloadCancelled:
            print("[已取消]")
            sys.exit(130)
        except lr.ResolveError as e:
            msg = str(e)
            if "403" in msg or "429" in msg or "拒绝" in msg:
                print("[解析失败] %s" % msg)
                print("[建议] 站点拒绝自动抓取（可能有人机验证/风控）：请用浏览器打开页面，")
                print("       手动获取真实下载直链后重试；持续被拒请停止使用本工具下载该站点。")
            else:
                print("[解析失败] %s（瞬时网络错误已自动重试 1 次）" % msg)
                print("[建议] 请检查网络/代理设置，或改用真实直链后手动重试。")
            sys.exit(8)
        except Exception as e:
            # 兜底：解析器的任何未预期异常都不应让 CLI 打印 traceback
            print("[解析失败] %s" % e)
            print("[建议] 请改用真实直链后重试。")
            sys.exit(8)

        try:
            run_download(target, referer=rr.final_url)
        except anysearch_downloader.HtmlPageError:
            print("[错误] 解析出的地址仍是网页，请手动提供真实直链。")
            sys.exit(8)
        return

    if not args.query:
        parser.error("需要 search 关键词，或使用 --extract / --download")

    if not 1 <= args.max_results <= 10:
        print("[错误] --max-results 必须在 1-10")
        sys.exit(2)

    print(f"[AnySearch] 搜索: {args.query}  (max_results={args.max_results})")
    obj, raw = call(api_key, "search", {"query": args.query, "max_results": args.max_results}, args.timeout)

    if args.save_out:
        with open(args.save_out, "w", encoding="utf-8") as f:
            f.write(raw)
        print(f"[保存] 原始响应 -> {args.save_out}")

    if obj is None:
        print("[响应不是 JSON，原文如下]")
        print(raw[:4000])
        sys.exit(3)

    err = obj.get("error")
    if err:
        print(f"[API 错误] code={err.get('code')} message={err.get('message')}")
        sys.exit(4)

    text = extract_result_text(obj)
    if text:
        print("[结果文本]")
        print(text[:6000])
    else:
        print("[完整 JSON]")
        print(json.dumps(obj, ensure_ascii=False, indent=2)[:6000])

    if args.save_out:
        print(f"\n提示：搜索结果中的 URL 请先确认授权（优先 CC0/CC-BY 且署名清晰）再下载。")


def download_file(url: str, out_dir: str, timeout: int = 120, num_threads: int = 4,
                  auto_chunk: bool = True, chunk_size: int = None,
                  mirrors=None, mirror_strategy: str = "direct", mirror_speed_limit: int = 0,
                  referer: str = None):
    """下载直链文件到本地目录（多线程 + 进度显示）。

    auto_chunk：True 自动分块；False 使用 chunk_size（字节，1MB~64MB）。
    支持断点续传：中断/暂停后再次调用同一 URL 自动续传。
    mirrors：镜像模板列表（含 {url} 占位符）；mirror_strategy：镜像策略；
    mirror_speed_limit：低速切换阈值（字节/秒，0=不切换）。

    注意：本工具只支持“无需登录/鉴权的真实文件直链”。
    不支持：百度网盘、夸克网盘、阿里云盘等需要登录/验证码/客户端签名的网盘链接，
    也不支持返回 HTML 网页的分享页（这类链接会被检测为“假直链”并报错）。
    """
    last = [0, time.time()]

    def progress(done, total):
        now = time.time()
        if now - last[1] >= 1.0:
            speed = (done - last[0]) / (now - last[1])
            last[0], last[1] = done, now
            if total:
                pct = done * 100.0 / total
                print("\r[下载] %.1f%%  %s / %s  %s/s" % (
                    pct,
                    _fmt_size(done),
                    _fmt_size(total),
                    _fmt_size(speed),
                ), end="", flush=True)
            else:
                print("\r[下载] %s" % _fmt_size(done), end="", flush=True)

    def mode(mode_name, thread_count, total):
        if mode_name == "multithread":
            print("[下载] 服务器支持分块，使用 %d 线程多线程下载。" % thread_count)
        elif mode_name == "resume":
            print("[下载] 检测到未完成下载，从断点续传。")
        elif mode_name.startswith("source_fast_switch:"):
            host = mode_name.split(":", 1)[1]
            print("[下载] 测速发现更快的镜像源（%s），已切换下载。" % host)
        elif mode_name == "source_switch":
            print("[下载] 直连不可用或过慢，已切换到镜像源继续下载…")
        elif mode_name == "source_fail":
            print("[下载] 镜像源不可用（404/内容不一致），已跳过。")
        else:
            print("[下载] 服务器不支持分块（或文件较小），已回退单线程下载。")

    path = anysearch_downloader.download_file(
        url, out_dir, num_threads=num_threads, timeout=timeout, progress_callback=progress,
        mode_callback=mode, auto_chunk=auto_chunk, chunk_size=chunk_size,
        mirrors=mirrors or [], mirror_strategy=mirror_strategy,
        mirror_speed_limit=mirror_speed_limit, referer=referer,
    )
    print()
    print("[完成] %s" % path)
    print("提醒：使用前核对文件的授权信息（优先 CC0 / CC-BY 等明确许可），避免侵权。")


def _fmt_size(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return "%.2f %s" % (n, unit)
        n /= 1024


if __name__ == "__main__":
    main()
