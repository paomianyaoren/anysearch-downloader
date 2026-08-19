# -*- coding: utf-8 -*-
"""AnySearch 链接解析器：把“网页 / 短链 / 跳转页”解析成可选的真实直链（纯标准库）

解决的问题：
  1. 跳转链：HTTP 302（自动跟随）、<meta http-equiv="refresh">、JS 跳转
     （var url = "..." / window.location.href = "..."，含三元表达式按 true/false 求值）；
  2. 页面内多个候选下载：zip / 7z / apk / exe 等多种文件（含 JS 变量里的签名直链）；
  3. 多清晰度媒体：JSON-LD（contentUrl + width/height）> og:image/og:video > srcset >
     <video>/<audio>/<a download> > 尺寸 token 枚举（_640/_1280、w=1200、@2x）+ HEAD 验证；
  4. 需要人机验证的下载页：识别表单与验证码图片，预取验证码字节后交给 interactive
     回调（GUI 弹窗 / CLI 输入），用户作答后带会话 Cookie 提交，继续解析。

设计要点：
  - 全程带 Cookie 会话（http.cookiejar），跳转链内 Referer 逐跳传递；
  - 所有网络连接注册到下载器的活动集合：取消/暂停时立即中断，最坏 8 秒收敛；
  - 页面读取限长（默认 2MB）、单请求限时、HEAD 验证并发可控（默认 4 连接）；
  - 选项去重排序：页面主媒体（JSON-LD/og）优先，图片按宽度降序，文件按扩展名白名单；
  - 解析结果不落盘、不进断点缓存；签名直链会过期，下载失败重试时请重新解析。
"""
import html as _html
import http.cookiejar
import json
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

from anysearch_downloader import (
    BROWSER_UA,
    DownloadCancelled,
    DownloadError,
    _interruptible_sleep,
    _read_with_cancel,
    _register_response,
    _set_read_timeout,
    _unregister_response,
    _headers,
)

MAX_HOPS_DEFAULT = 8
MAX_PAGE_BYTES_DEFAULT = 2 * 1024 * 1024
MAX_LINK_OPTIONS = 40

# <a>/JS 变量直链的文件扩展名白名单（长后缀优先匹配，如 tar.gz）
FILE_EXT_WHITELIST = (
    "apk", "xapk", "apks", "zip", "7z", "rar", "exe", "msi", "dmg", "pkg", "deb",
    "rpm", "iso", "img", "tgz", "gz", "bz2", "xz",
    "zst", "jar", "bin", "crx", "torrent", "unitypackage",
    "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "mp3", "wav",
    "flac", "ogg", "m4a", "aac", "opus",
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "avif", "heic",
    "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "csv", "txt", "md", "rtf", "chm", "djvu",
)

# 明确排除的“非下载”主机（应用商店/官网跳转等）
EXCLUDE_HOSTS = (
    "itunes.apple.com", "apps.apple.com", "play.google.com",
    "mp.weixin.qq.com", "weibo.com", "weixin.qq.com", "t.me", "discord.com",
    "twitter.com", "x.com", "facebook.com", "instagram.com", "youtube.com",
    "bilibili.com", "zhihu.com", "douyin.com", "xiaohongshu.com",
)

# JS 变量名里暗示“这是下载/跳转目标”的关键词（按优先级）
VAR_NAME_KEYWORDS = (
    "download", "down", "url", "link", "href", "file", "src",
    "apk", "zip", "win", "mac", "pc", "arm", "android", "ios", "harmony", "exe",
)

# 图片尺寸枚举候选（token 改写：_640、w=1200、@2x）
IMAGE_SIZE_TOKENS = (340, 480, 640, 960, 1280, 1920, 2048, 2560, 3840, 4096)
IMAGE_W_PARAMS = (320, 480, 640, 800, 1024, 1200, 1260, 1600, 1920, 2560, 3840, 4096)

# 验证码相关关键词（字段名/图片 URL/表单元素）
CAPTCHA_KEYWORDS = ("captcha", "verify", "verif", "code", "yzm", "vcode", "seccode",
                    "rand", "kaptcha", "authimg", "security", "challenge")

# ==================== 站点规则扩展点 ====================
# 某个网站改版、新增接口、或通用规则拿不到直链时，只需在此注册一条规则，
# GUI / CLI / 下载引擎 均无需改动。
# 每条规则 = (主机名正则, 处理函数)：
#   handler(html_text, base_url) -> list[dict] | None
#     返回候选列表，每项 {"url": 必填, "label": 可选, "kind": 可选(image/video/audio/file),
#                        "width": 可选, "height": 可选, "note": 可选}
#     返回 None/[] 表示该规则未命中；抛异常会被忽略（不影响其它规则与通用解析）。
SITE_RULES = []


def _extract_nested_urls(html_text, base_url, page_id):
    """通用：从页面里"参数嵌套的直链"中提取候选（URL 编码/协议相对均可）。

    很多网站把真实下载地址藏在跳转或合作链接的参数里：
      <a href="https://canva.com/...?image-url=https%3A%2F%2Fhost%2Fget%2F...">
      <a href="/go?url=https://host/file.zip">
    本函数扫描 url=/link=/target=/src=/href=/redirect=/download_url=/image-url= 等参数值，
    反 HTML 实体 + 百分号解码后提取其中的绝对 http(s) 直链。
    若页面路径以 -<数字id> 结尾，只保留上下文（±600 字符）含该 id 的候选
    （该模式通常表示"主内容 id 关联"）；否则全部保留（上限 20）。
    """
    param_re = re.compile(
        r"""(?:download[_\-]?url|image[_\-]?url|redirect|target|href|src|link|url)\s*=\s*["']?([^"'\s>]+)["']?""",
        re.I)
    found = {}
    for m in param_re.finditer(html_text):
        val = m.group(1)
        val = _html.unescape(val)
        try:
            val = urllib.parse.unquote(val)
        except Exception:
            pass
        # 只有值里确实嵌套了第二个绝对 URL 才提取（纯直链 href 由 <a>/og 等步骤负责，
        # 避免重复与截断）
        positions = [um.start() for um in re.finditer(r"https?://", val)]
        if len(positions) < 2:
            continue
        u = val[positions[-1]:]                     # 取最内层的绝对地址
        u = re.split(r"[&\"'<>\s]", u, 1)[0]        # 首参数分隔处截断（剥离外层站点的附加参数）
        u = u.rstrip('.,;:!?)]}')
        ctx = html_text[max(0, m.start() - 600):m.end() + 600]
        hit_id = bool(page_id) and (page_id in ctx)
        score = 2 if hit_id else 0
        if score > found.get(u, -1):
            found[u] = score
    items = sorted(found.items(), key=lambda kv: -kv[1])
    if page_id and any(s > 0 for _u, s in items):
        items = [(u, s) for u, s in items if s > 0]
    return [u for u, _s in items[:20]]

_RESOLVE_HEADERS = {
    "User-Agent": BROWSER_UA,
    "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,"
               "image/avif,image/webp,*/*;q=0.8"),
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Accept-Encoding": "identity",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
}


class LinkOption:
    """一个可下载候选。kind: image/video/audio/file/other。

    referer：候选所属的页面地址——下载该候选时作为 Referer 发送（抗防盗链）。
    """
    __slots__ = ("url", "label", "kind", "width", "height", "note", "source", "referer")

    def __init__(self, url, label="", kind="file", width=None, height=None,
                 note="", source="", referer=None):
        self.url = url
        self.label = label or url
        self.kind = kind
        self.width = width
        self.height = height
        self.note = note
        self.source = source
        self.referer = referer

    def __repr__(self):  # 便于 CLI 打印
        return "LinkOption(%r, label=%r, kind=%r, %sx%s)" % (
            self.url, self.label, self.kind, self.width, self.height)


class CaptchaChallenge:
    """识别到的人机验证：调用方展示 captcha 图片，收集 answers 后提交。"""
    __slots__ = ("action", "method", "fields", "captcha_img_url", "captcha_bytes",
                 "captcha_content_type", "page_url", "hint")

    def __init__(self, action, method="POST", fields=None, captcha_img_url="",
                 captcha_bytes=b"", captcha_content_type="", page_url="", hint=""):
        self.action = action
        self.method = method.upper() or "POST"
        # fields: [{"name":..., "value":..., "needs_input": bool}]
        self.fields = fields or []
        self.captcha_img_url = captcha_img_url
        self.captcha_bytes = captcha_bytes
        self.captcha_content_type = captcha_content_type
        self.page_url = page_url
        self.hint = hint


class ResolveResult:
    __slots__ = ("final_url", "options", "title", "hops", "captcha", "is_page", "note")

    def __init__(self, final_url, options=None, title="", hops=None, captcha=None,
                 is_page=False, note=""):
        self.final_url = final_url
        self.options = options or []
        self.title = title
        self.hops = hops or []
        self.captcha = captcha
        self.is_page = is_page
        self.note = note


class ResolveError(DownloadError):
    """链接解析失败（网络错误 / 被拒绝 / 超跳转上限等）。"""


# ==================== 基础抓取 ====================

def _build_opener(custom_headers=None):
    """带 Cookie 会话的 opener；自动继承环境代理设置。"""
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def _make_headers(custom_headers=None):
    h = dict(_RESOLVE_HEADERS)
    h.update(_headers())          # 带上用户自定义请求头（优先级最高）
    if custom_headers:
        h.update(custom_headers)
    return h


def _looks_like_html(content_type, head_bytes):
    ct = (content_type or "").lower()
    if "text/html" in ct:
        return True
    low = head_bytes[:1024].lstrip().lower()
    return (low.startswith(b"<!doctype html") or low.startswith(b"<html")
            or b"<head" in low)


def _detect_charset(headers, body_bytes):
    ct = (headers.get("Content-Type") or "")
    m = re.search(r"charset\s*=\s*[\"']?([\w-]+)", ct, re.I)
    if m:
        return m.group(1)
    head = body_bytes[:2048].decode("ascii", "ignore")
    m = re.search(r"charset\s*=\s*[\"']?([\w-]+)", head, re.I)
    if m:
        return m.group(1)
    return "utf-8"


def _fetch(opener, url, timeout, cancel_event, max_bytes, headers,
           referer=None, method="GET", data=None):
    """抓取一页：返回 (status, final_url, content_type, body_bytes, resp_headers)。

    全程走调用方传入的会话 opener（携带 CookieJar），并注册到下载器活动集合，
    取消时立即中断；连接阶段限时 ≤8 秒。
    """
    if cancel_event is not None and cancel_event.is_set():
        raise DownloadCancelled()
    hdrs = dict(headers)
    if referer:
        hdrs["Referer"] = referer
    if data is not None and "Content-Type" not in hdrs:
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        resp = opener.open(req, timeout=min(timeout, 8))
    except urllib.error.HTTPError as e:
        # HTTPError 本身是响应对象：保留状态码与正文，供上层判断（如 403）
        try:
            _register_response(e, cancel_event)
            try:
                _set_read_timeout(e, 5.0)
                body = e.read(max_bytes)
            finally:
                _unregister_response(e)
                try:
                    e.close()
                except Exception:
                    pass
        except Exception:
            body = b""
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        return (e.code, e.geturl() or url, e.headers.get("Content-Type") or "",
                body, e.headers)
    except urllib.error.URLError as e:
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        raise ResolveError("无法访问 %s：%s" % (url, e.reason))
    except OSError as e:
        # Python 3.10+ socket.timeout 是 TimeoutError（OSError 子类），
        # 连接阶段超时可能裸抛（不被包成 URLError）
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        raise ResolveError("无法访问 %s：%s" % (url, e))
    with resp:
        _register_response(resp, cancel_event)
        try:
            _set_read_timeout(resp, 5.0)
            content_type = resp.headers.get("Content-Type") or ""
            chunks = []
            total = 0
            while total < max_bytes:
                if cancel_event is not None and cancel_event.is_set():
                    raise DownloadCancelled()
                chunk = _read_with_cancel(resp, min(65536 if total else 8192,
                                                    max_bytes - total),
                                          cancel_event)
                if not chunk:
                    break
                chunks.append(chunk)
                total += len(chunk)
                # 首块（8KB）读完即可判定：非网页 → 直链，立即停止读取，
                # 避免把整个文件预读一遍（小文件等于重复下载两次）
                if len(chunks) == 1 and not _looks_like_html(content_type, chunks[0]):
                    break
            return (getattr(resp, "status", 200) or 200, resp.geturl() or url,
                    content_type, b"".join(chunks), resp.headers)
        finally:
            _unregister_response(resp)


# ==================== 页面分析：标题 / 跳转 / 选项 / 验证码 ====================

def _extract_title(html_text):
    m = re.search(r"<title[^>]*>(.*?)</title>", html_text, re.S | re.I)
    if m:
        return _html.unescape(m.group(1)).strip()[:120]
    return ""


def _parse_js_vars(html_text, base_url):
    """解析页面内联 JS 的变量赋值，找出下载/跳转类 URL。

    支持：var x = true/false 标记；var y = x ? "A" : "B" 三元（按标记取分支）；
    var y = "http..." 直接赋值。返回 [(url, var_name, score)]，按可信度排序。
    """
    flags = {}
    for m in re.finditer(r"var\s+(\w+)\s*=\s*(true|false)\s*;", html_text, re.I):
        flags[m.group(1).lower()] = (m.group(2).lower() == "true")
    found = {}  # url -> [score, name]
    # 取值：引号串按整体原子匹配（引号内可含分号/冒号），其余字符不含分号
    value_re = r"""(?:[^;"'\n]|"[^"]*"|'[^']*')*(?:"[^"]*"|'[^']*')?"""
    for m in re.finditer(r"var\s+(\w+)\s*=\s*(" + value_re + r")\s*;", html_text, re.I):
        name = m.group(1)
        val = m.group(2).strip()
        tm = re.match(r"^(\w+)\s*\?\s*(?:\"([^\"]*)\"|'([^']*)')\s*:\s*(?:\"([^\"]*)\"|'([^']*)')$", val)
        if tm:
            cond = flags.get(tm.group(1).lower())
            a, b = tm.group(2) or tm.group(3), tm.group(4) or tm.group(5)
            url = a if cond is not False else b
            if cond is None and not a:
                url = b
        else:
            sm = re.search(r"[\"'](https?://[^\"']*)[\"']", val)
            url = sm.group(1) if sm else ""
        if not url or not url.startswith(("http://", "https://")):
            continue
        url = _html.unescape(url).strip()
        low = name.lower()
        score = 0
        for kw in VAR_NAME_KEYWORDS:
            if kw in low:
                score = len(VAR_NAME_KEYWORDS) - VAR_NAME_KEYWORDS.index(kw)
                break
        if score > found.get(url, [0, name])[0]:
            found[url] = [score, name]
    # window.location 直接字面量（同引号闭合，URL 内可含分号等字符）
    for m in re.finditer(r"""(?:window\.)?location(?:\.href)?\s*=\s*(["'])(.*?)\1""",
                         html_text, re.I):
        url = _html.unescape(m.group(2)).strip()
        if url.startswith(("http://", "https://")):
            if len(VAR_NAME_KEYWORDS) > found.get(url, [0, "location"])[0]:
                found[url] = [len(VAR_NAME_KEYWORDS), "location"]
    items = sorted(found.items(), key=lambda kv: -kv[1][0])
    return [(urllib.parse.urljoin(base_url, u), name, score)
            for u, (score, name) in items if u]


def _find_meta_refresh(html_text, base_url):
    # HTML 属性顺序任意：分别提取 http-equiv 与 content 后再组合判定
    for m in re.finditer(r"<meta\b[^>]*>", html_text, re.I | re.S):
        tag = m.group(0)
        if not re.search(r"http-equiv\s*=\s*[\"']?refresh[\"']?", tag, re.I):
            continue
        cm = re.search(r"""content\s*=\s*["']?\s*[\d.]+\s*;\s*url\s*=\s*["']?([^"'>\s]+)""",
                       tag, re.I)
        if cm:
            return urllib.parse.urljoin(base_url, _html.unescape(cm.group(1)))
    return ""


def _kind_and_size(url, guess=False):
    """按扩展名判断 kind，并尽力从 URL 猜出尺寸 token（如 _1280 / w=1200 / @2x）。"""
    path = urllib.parse.urlparse(url).path.lower()
    ext = path.rsplit(".", 1)[-1] if "." in path else ""
    width = None
    for tok in sorted(IMAGE_SIZE_TOKENS, reverse=True):
        if re.search(r"[_\-@]%dx?$" % tok, path.split("?")[0]) or \
                re.search(r"[_\-]%d\b" % tok, path.split("?")[0]):
            width = tok
            break
    if width is None:
        q = urllib.parse.urlparse(url).query.lower()
        m = re.search(r"(?:^|&)w=(\d{2,5})(?:&|$)", q)
        if m:
            width = int(m.group(1))
    if ext in ("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff",
               "avif", "heic"):
        return "image", ext, width
    if ext in ("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts"):
        return "video", ext, None
    if ext in ("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus"):
        return "audio", ext, None
    if ext in ("apk", "xapk", "apks", "zip", "7z", "rar", "exe", "msi", "dmg",
               "pkg", "deb", "rpm", "iso", "img", "tgz", "gz", "bz2", "xz",
               "zst", "jar", "bin", "crx", "torrent", "unitypackage"):
        return "file", ext, None
    if ext in ("pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx",
               "ppt", "pptx", "csv", "txt", "md", "rtf", "chm", "djvu"):
        return "file", ext, None
    if guess:
        return "file", ext, None
    return None, ext, None


def _valid_media_url(url):
    if not url.startswith(("http://", "https://")):
        return False
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    if any(host == h or host.endswith("." + h) for h in EXCLUDE_HOSTS):
        return False
    path = urllib.parse.urlparse(url).path.lower()
    if re.search(r"\.(css|js|json|woff2?|ttf|eot|ico)(\?|$)", path):
        return False
    return True


def _extract_options(html_text, base_url, title):
    """从 HTML 提取候选下载项。返回 [LinkOption]，按可信度大致排序。"""
    seen = {}
    main_stem = ""   # 主媒体文件名干（去掉 _尺寸 后缀），用于过滤相似缩略图/相关推荐

    def add(url, label, kind, width=None, height=None, note="", source="", referer=None):
        if url.startswith("//"):
            url = "https:" + url  # 协议相对地址统一补 https
        if not _valid_media_url(url):
            return
        if url in seen:
            return
        # 页面里找到的候选默认携带页面地址作为 Referer（抗防盗链）
        seen[url] = LinkOption(url, label, kind, width, height, note, source,
                               referer if referer is not None else base_url)

    def stem_of(u):
        p = urllib.parse.urlparse(u).path
        name = p.rsplit("/", 1)[-1]
        name = re.sub(r"__?\d{2,5}(?=\.\w+$)", "", name)
        name = re.sub(r"@\dx(?=\.\w+$)", "", name)
        return name.rsplit(".", 1)[0]

    # 1) JSON-LD（可信度最高：contentUrl + 尺寸 + 主图标记）
    jsonld = []
    for m in re.finditer(r"""<script[^>]+application/ld\+json[^>]*>(.*?)</script>""",
                         html_text, re.S | re.I):
        try:
            jsonld.append(json.loads(m.group(1)))
        except Exception:
            continue

    def walk_jsonld(obj, is_rep=False):
        if isinstance(obj, dict):
            t = str(obj.get("@type", ""))
            content = obj.get("contentUrl") or obj.get("embedUrl") or ""
            w = obj.get("width")
            h = obj.get("height")

            def _to_int(v):
                # JSON-LD 的 width/height 可能是数字、字符串或对象（如 {"value": 6000}）
                if isinstance(v, bool):
                    return None
                if isinstance(v, (int, float)):
                    return int(v)
                if isinstance(v, str) and v.strip().isdigit():
                    return int(v.strip())
                if isinstance(v, dict):
                    return _to_int(v.get("value"))
                return None

            wv = _to_int(w)
            hv = _to_int(h)
            if content and isinstance(content, str):
                k, _e, kw = _kind_and_size(content)
                rep = bool(obj.get("representativeOfPage")) or is_rep
                if k == "image" and rep and not main_stem:
                    stem = stem_of(content)
                    if stem:
                        main_stem_set[0] = stem
                label = (obj.get("name") or "")
                note = ""
                if wv and hv:
                    note = ("页面标注原图 %s×%s（原图需站点签发/登录，工具已列出全部公开档位）"
                            % (wv, hv)) if (kw or 0) < max(wv, hv) else ""
                add(content, label, k or "file", kw or wv, hv, note,
                    "jsonld" + ("·主图" if rep else ""))
            for v in obj.values():
                walk_jsonld(v, is_rep or bool(obj.get("representativeOfPage")))
        elif isinstance(obj, list):
            for v in obj:
                walk_jsonld(v, is_rep)

    main_stem_set = [""]
    for obj in jsonld:
        walk_jsonld(obj)
    main_stem = main_stem_set[0]

    # 2) og:image / og:video / og:audio / twitter:image
    meta_media = re.findall(
        r"""<meta[^>]+(?:property|name)=["'](og:image|og:video|og:audio|twitter:image|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""",
        html_text, re.I)
    for prop, url in meta_media:
        url = _html.unescape(url).strip()
        if url.startswith("/"):
            url = urllib.parse.urljoin(base_url, url)
        kind_map = {"og:image": "image", "og:video": "video", "og:audio": "audio",
                    "twitter:image": "image", "twitter:player:stream": "video"}
        k = kind_map.get(prop.lower(), "file")
        _k, _e, kw = _kind_and_size(url)
        stem = stem_of(url)
        if not main_stem and stem:
            main_stem_set[0] = stem
            main_stem = stem
        add(url, title, _k or k, kw, None, "", "og")

    # 3) srcset / <img src> / <video> / <audio> / <source>
    img_tags = re.findall(r"<img\b[^>]*>", html_text, re.I | re.S)
    srcset_all = []
    for tag in img_tags:
        sm = re.search(r"""srcset=["']([^"']+)["']""", tag, re.I)
        if sm:
            for entry in sm.group(1).split(","):
                entry = entry.strip()
                if not entry:
                    continue
                parts = entry.rsplit(" ", 1)
                url = _html.unescape(parts[0].strip())
                w = None
                if len(parts) == 2:
                    desc = parts[1].strip()
                    wm = re.match(r"^(\d{2,5})w$", desc)
                    if wm:
                        w = int(wm.group(1))
                srcset_all.append((urllib.parse.urljoin(base_url, url), w))
        srcm = re.search(r"""src=["']([^"']+)["']""", tag, re.I)
        if srcm:
            srcset_all.append((urllib.parse.urljoin(base_url, _html.unescape(srcm.group(1))), None))
    for url, w in srcset_all:
        k, _e, kw = _kind_and_size(url)
        if k != "image":
            continue
        stem = stem_of(url)
        # 只保留主图相关（同文件名干）或较大图，过滤头像/相关推荐缩略图
        # 只保留主图（文件名干一致），过滤头像/相关推荐缩略图（主图由 jsonld/og 兜底）
        if main_stem and stem != main_stem:
            continue
        add(url, "", "image", w or kw, None, "", "srcset")
    for tag in re.findall(r"<(?:video|audio|source)\b[^>]*>", html_text, re.I | re.S):
        sm = re.search(r"""src=["']([^"']+)["']""", tag, re.I)
        if sm:
            url = urllib.parse.urljoin(base_url, _html.unescape(sm.group(1)))
            k, _e, _w = _kind_and_size(url)
            if k in ("video", "audio"):
                add(url, "", k, None, None, "", "media")

    # 4) <a href>：download 属性、扩展名白名单、锚文本含“下载”
    for m in re.finditer(r"""<a\b[^>]*href=["']([^"']+)["']([^>]*)>(.*?)</a>""",
                         html_text, re.I | re.S):
        href, attrs, text = m.groups()
        url = urllib.parse.urljoin(base_url, _html.unescape(href).strip())
        if not _valid_media_url(url):
            continue
        k, ext, w = _kind_and_size(url)
        if k is None and ext not in FILE_EXT_WHITELIST:
            # 无扩展名：仅当带 download 属性或锚文本明确是“下载”才收
            low_text = re.sub(r"<[^>]+>", "", text).strip().lower()
            if "download" not in attrs.lower() and "下载" not in low_text and "download" not in low_text:
                continue
            k = "file"
        note = ""
        if "download" in attrs.lower():
            note = "页面下载按钮"
        add(url, re.sub(r"<[^>]+>", "", text).strip(), k, w, None, note, "a")

    # 5) 内联 JS 变量（adl.netease 类签名直链）；无文件扩展名的（官网/商店回退地址）不收
    for url, name, score in _parse_js_vars(html_text, base_url):
        k, ext, w = _kind_and_size(url)
        if k is None and ext not in FILE_EXT_WHITELIST:
            continue
        add(url, "", k, w, None, "", "js:%s" % name)

    # 6) 参数嵌套直链（通用）：url=/image-url=/target= 等参数里的真实下载地址
    page_id_m = re.search(r"-(\d+)/?$", urllib.parse.urlparse(base_url).path)
    page_id = page_id_m.group(1) if page_id_m else ""
    for u in _extract_nested_urls(html_text, base_url, page_id):
        k, ext, w = _kind_and_size(u)
        if k is None and ext not in FILE_EXT_WHITELIST:
            continue
        add(u, "", k, w, None, "", "nested")

    # 7) 站点规则扩展点（SITE_RULES）：为个别站点补充定制解析
    host = (urllib.parse.urlparse(base_url).hostname or "").lower()
    for host_re, handler in SITE_RULES:
        if re.search(host_re, host):
            try:
                for item in handler(html_text, base_url) or []:
                    if isinstance(item, dict) and item.get("url"):
                        add(item["url"], item.get("label", ""),
                            item.get("kind", "file"), item.get("width"),
                            item.get("height"), item.get("note", ""),
                            item.get("source", "site_rule"),
                            item.get("referer"))
            except Exception:
                pass  # 站点规则失败不影响通用解析

    opts = list(seen.values())
    # 图片优先；同类型按来源可信度（jsonld/og=0 < srcset=1 < media=2 < a/js=3 < nested=4）；
    # 图片内按宽度降序；其余保持出现顺序
    def rank(o):
        src_prio = {"jsonld": 0, "og": 0, "srcset": 1, "media": 2, "a": 3, "js": 3,
                    "nested": 4}.get(o.source.split("·")[0], 4)
        img = 1 if o.kind == "image" else 0
        w = o.width or 0
        return (-img, src_prio, -w)
    opts.sort(key=rank)
    return opts[:MAX_LINK_OPTIONS]


# ==================== 尺寸枚举 + HEAD 验证 ====================

def _enumerate_sizes(url):
    """从一张图 URL 生成常见尺寸变体（_640/w=1200/@2x 改写）。"""
    parts = urllib.parse.urlparse(url)
    variants = set()
    path = parts.path
    # pixabay 类：name_1280.jpg / name__340.jpg
    m = re.match(r"^(.*?)(__?\d{2,5})(\.\w+)$", path)
    if m:
        base, _tok, ext = m.groups()
        for tok in IMAGE_SIZE_TOKENS:
            variants.add(urllib.parse.urlunparse(
                parts._replace(path="%s_%d%s" % (base, tok, ext))))
        # 原图候选：去掉尺寸后缀（部分站点原图公开；需登录/签发的会被 HEAD 过滤）
        variants.add(urllib.parse.urlunparse(parts._replace(path=base + ext)))
    # pexels 类：?w=1200&...
    q = urllib.parse.parse_qs(parts.query, keep_blank_values=True)
    if "w" in q:
        for w in IMAGE_W_PARAMS:
            q2 = dict(q)
            q2["w"] = [str(w)]
            variants.add(urllib.parse.urlunparse(parts._replace(
                query=urllib.parse.urlencode(q2, doseq=True))))
        # 原图：去掉 w/h/fit/crop/dpr 等裁剪参数，保留 cs=srgb
        q3 = {k: v for k, v in q.items() if k not in ("w", "h", "fit", "crop", "dpr", "auto")}
        if "cs" not in q3:
            q3["cs"] = ["srgb"]
        variants.add(urllib.parse.urlunparse(parts._replace(
            query=urllib.parse.urlencode(q3, doseq=True))))
    # @2x / @3x
    m = re.match(r"^(.*?)(@\dx)(\.\w+)$", path)
    if m:
        base, _d, ext = m.groups()
        variants.add(urllib.parse.urlunparse(parts._replace(path=base + ext)))
    return [v for v in variants if v != url]


def _head_ok(opener, url, headers, referer, timeout, cancel_event):
    """验证候选 URL 是否存在（HEAD，失败回退 Range GET）。"""
    if cancel_event is not None and cancel_event.is_set():
        raise DownloadCancelled()
    hdrs = dict(headers)
    if referer:
        hdrs["Referer"] = referer
    for attempt in (0, 1):
        try:
            if attempt == 0:
                req = urllib.request.Request(url, headers=hdrs, method="HEAD")
            else:
                req = urllib.request.Request(url, headers={**hdrs, "Range": "bytes=0-0"})
            resp = opener.open(req, timeout=min(timeout, 5))
            _register_response(resp, cancel_event)
            try:
                status = getattr(resp, "status", 200) or 200
                if status in (200, 206):
                    return True
                return False
            finally:
                _unregister_response(resp)
                try:
                    resp.close()
                except Exception:
                    pass
        except urllib.error.HTTPError as e:
            try:
                if attempt == 0 and e.code in (405, 501):
                    continue        # 服务器不支持 HEAD → 换 Range GET
                return False
            finally:
                try:
                    e.close()
                except Exception:
                    pass
        except Exception:
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            return False
    return False


def _verify_options(opener, options, headers, referer, timeout, cancel_event,
                    head_conns, max_variants, progress_callback):
    """对图片选项做尺寸枚举并用 HEAD 并发验证；返回扩展后的选项列表。"""
    result = list(options)
    # 枚举目标：前 2 个图片候选 + 站点规则产出的签发直链（上限 3 个，控制 HEAD 数量）
    enum_targets = [o for o in options if o.kind == "image"][:2]
    for o in options:
        if (o.kind == "image"
                and o.source.split("·")[0] in ("site_rule", "nested")
                and o not in enum_targets):
            enum_targets.append(o)
            if len(enum_targets) >= 3:
                break
    candidates = []  # (variant_url, base_option)
    for o in enum_targets:
        for v in _enumerate_sizes(o.url)[:max_variants]:
            if v != o.url:
                candidates.append((v, o))
    if not candidates:
        return result
    if progress_callback:
        progress_callback("verify", "正在验证 %d 个尺寸候选…" % len(candidates))
    ok = []
    with ThreadPoolExecutor(max_workers=max(1, min(head_conns, 16))) as ex:
        futs = {ex.submit(_head_ok, opener, v, headers, referer, timeout,
                          cancel_event): (v, o) for v, o in candidates}
        for f in as_completed(futs):
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            v, o = futs[f]
            try:
                if f.result():
                    ok.append((v, o))
            except DownloadCancelled:
                raise
            except Exception:
                pass
    seen = {o.url for o in result}
    for v, o in ok:
        if v in seen:
            continue
        seen.add(v)
        kind, ext, w = _kind_and_size(v)
        result.append(LinkOption(v, "", kind, w or o.width, o.height,
                                 o.note, o.source + "·枚举", o.referer))
    return result


# ==================== 验证码 ====================

def _find_captcha(html_text, base_url):
    """识别“需要人机验证”的表单；返回 CaptchaChallenge 或 None。"""
    # 单次正则同时捕获 form 属性与内容，避免两次独立匹配在畸形 HTML 下错位
    for attrs, inner in re.findall(r"<form\b([^>]*)>(.*?)</form>", html_text, re.I | re.S):
        action = ""
        m = re.search(r"""action=["']([^"']+)["']""", attrs, re.I)
        if m:
            action = urllib.parse.urljoin(base_url, _html.unescape(m.group(1)))
        method = "POST"
        m = re.search(r"""method=["']([^"']+)["']""", attrs, re.I)
        if m:
            method = m.group(1).upper()
        fields = []
        needs = []
        for im in re.finditer(r"<input\b([^>]*?)/?>", inner, re.I | re.S):
            a = im.group(1)
            nm = re.search(r"""name=["']([^"']+)["']""", a, re.I)
            ty = re.search(r"""type=["']([^"']+)["']""", a, re.I)
            va = re.search(r"""value=["']([^"']*)["']""", a, re.I)
            name = _html.unescape(nm.group(1)) if nm else ""
            itype = (ty.group(1) if ty else "text").lower()
            value = _html.unescape(va.group(1)) if va else ""
            if not name:
                continue
            if itype in ("hidden", "text", "password", "number", "captcha"):
                is_need = (itype in ("password", "captcha", "number")
                           or any(k in name.lower() for k in CAPTCHA_KEYWORDS))
                fields.append({"name": name, "value": value, "needs_input": is_need})
                if is_need:
                    needs.append(name)
        # 验证码图片：src 含验证码关键词，或表单里唯一的 img
        cap_url = ""
        imgs = re.findall(r"<img\b[^>]*>", inner, re.I | re.S)
        for tag in imgs:
            sm = re.search(r"""src=["']([^"']+)["']""", tag, re.I)
            if not sm:
                continue
            src = urllib.parse.urljoin(base_url, _html.unescape(sm.group(1)))
            if any(k in src.lower() for k in CAPTCHA_KEYWORDS):
                cap_url = src
                break
        if not cap_url and needs and len(imgs) == 1:
            sm = re.search(r"""src=["']([^"']+)["']""", imgs[0], re.I)
            if sm:
                cap_url = urllib.parse.urljoin(base_url, _html.unescape(sm.group(1)))
        if needs:
            return CaptchaChallenge(action=action or base_url, method=method,
                                    fields=fields, captcha_img_url=cap_url,
                                    page_url=base_url,
                                    hint="该页面需要人机验证，请输入图片中的验证码后继续")
    return None


def _fetch_captcha_image(challenge, opener, headers, referer, timeout, cancel_event):
    if not challenge.captcha_img_url:
        return challenge
    try:
        status, _f, ct, body, _h = _fetch(opener, challenge.captcha_img_url, timeout,
                                          cancel_event, 512 * 1024, headers,
                                          referer=referer)
        if status in (200, 206) and body:
            challenge.captcha_bytes = body
            challenge.captcha_content_type = ct
    except DownloadCancelled:
        raise
    except Exception:
        pass  # 图片拿不到时由调用方给链接，用户自行打开
    return challenge


def _valid_action(challenge):
    """验证码提交地址只允许 http(s)（防 javascript:/data: 等注入与明显异常目标）。"""
    return bool(challenge.action.startswith(("http://", "https://")))


def _submit_challenge(challenge, answers, opener, headers, referer, timeout,
                      cancel_event, max_bytes):
    """用用户答案提交验证表单，返回 (status, final_url, content_type, body, headers)。"""
    if not _valid_action(challenge):
        raise ResolveError("验证码表单提交地址无效：%s" % challenge.action)
    pairs = []
    for f in challenge.fields:
        if f["needs_input"]:
            pairs.append((f["name"], answers.get(f["name"], "")))
        else:
            pairs.append((f["name"], f["value"]))
    data = urllib.parse.urlencode(pairs).encode("utf-8")
    if challenge.method == "GET":
        sep = "&" if "?" in challenge.action else "?"
        action = challenge.action + sep + data.decode("utf-8")
        return _fetch(opener, action, timeout, cancel_event, max_bytes, headers,
                      referer=referer, method="GET")
    return _fetch(opener, challenge.action, timeout, cancel_event, max_bytes,
                  headers, referer=referer, method="POST", data=data)


# ==================== 主入口 ====================

def _fetch_hop(opener, url, timeout, cancel_event, max_bytes, hdrs, referer, retries):
    """带瞬态重试的抓取：网络错误/5xx 自动重试 retries 次；4xx（风控等）不重试。

    返回与 _fetch 相同；重试期间可用取消事件打断。
    """
    for attempt in range(retries + 1):
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        try:
            status, final_url, ct, body, resp_headers = _fetch(
                opener, url, timeout, cancel_event, max_bytes, hdrs, referer=referer)
        except ResolveError:
            # 网络类错误（URLError/OSError）：视为瞬态，重试
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            if attempt >= retries:
                raise
            _interruptible_sleep(1.5, cancel_event)
            continue
        if 500 <= status < 600 and attempt < retries:
            _interruptible_sleep(1.5, cancel_event)
            continue
        return status, final_url, ct, body, resp_headers
    # 理论不可达（最后一轮循环必 return/raise）
    raise ResolveError("抓取失败：%s" % url)


def resolve(url, timeout=15, max_hops=MAX_HOPS_DEFAULT,
            max_bytes=MAX_PAGE_BYTES_DEFAULT, headers=None, cancel_event=None,
            interactive=None, progress_callback=None, max_variants=16,
            head_timeout=4, head_conns=4, enum_sizes=True, resolve_retries=1):
    """解析一个 URL，返回 ResolveResult。

    interactive(challenge, cancel_event) -> dict 或 None：
        遇到人机验证时被调用（在工作线程中），返回 {字段名: 用户输入}；
        返回 None 表示用户放弃 → 抛 DownloadCancelled。
    progress_callback(stage, detail)：stage ∈ fetch/analyze/verify/captcha/done。
    resolve_retries：瞬时网络错误（连接失败/5xx）的自动重试次数（默认 1）；
        4xx（风控/被拒）不自动重试，直接报错供用户手动处理。
    """
    if not url.startswith(("http://", "https://")):
        raise ResolveError("只支持 http/https 链接：%s" % url)
    opener = _build_opener(headers)
    hdrs = _make_headers(headers)
    current = url
    referer = None
    hops = []
    for _ in range(max_hops):
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        if progress_callback:
            progress_callback("fetch", "正在获取 %s" % current)
        status, final_url, ct, body, resp_headers = _fetch_hop(
            opener, current, timeout, cancel_event, max_bytes, hdrs, referer,
            resolve_retries)
        hops.append(final_url)
        referer = final_url
        if status >= 400:
            raise ResolveError("访问被拒绝（HTTP %d）：%s" % (status, final_url))
        if not _looks_like_html(ct, body):
            # 已经是直链文件
            kind, ext, w = _kind_and_size(final_url, guess=True)
            label = urllib.parse.urlparse(final_url).path.rsplit("/", 1)[-1]
            opt = LinkOption(final_url, label, kind or "file", w, None, "", "direct")
            return ResolveResult(final_url, [opt], title=label, hops=hops)
        charset = _detect_charset(resp_headers, body)
        try:
            html_text = body.decode(charset, errors="replace")
        except Exception:
            html_text = body.decode("utf-8", errors="replace")
        title = _extract_title(html_text)
        if progress_callback:
            progress_callback("analyze", "正在分析页面 %s" % title)
        # 0) Cloudflare / 纯 JS 挑战页：无法自动通过，明确告知
        low_head = html_text[:4000].lower()
        if ("just a moment" in low_head
                or "cf-browser-verification" in html_text.lower()
                or 'id="challenge-form"' in low_head
                or "cf-chl-" in low_head):
            return ResolveResult(
                final_url, title=title or "人机验证", hops=hops, is_page=True,
                note="该站点开启了 Cloudflare 等 JS 人机验证，本工具无法自动通过。\n"
                     "建议：稍后重试；若持续出现此提示，请停止使用本工具下载该站点，\n"
                     "改用浏览器打开页面并手动获取真实直链（右键复制下载链接）。")
        # 1) 人机验证优先处理（有 interactive 就交互，没有就原样返回让调用方决定）
        captcha = _find_captcha(html_text, final_url)
        if captcha and not _valid_action(captcha):
            captcha = None  # 提交地址非法（javascript: 等）：不交互，走通用解析
        if captcha:
            if progress_callback:
                progress_callback("captcha", "检测到人机验证")
            captcha = _fetch_captcha_image(captcha, opener, hdrs, referer, timeout,
                                           cancel_event)
            if interactive is None:
                return ResolveResult(final_url, title=title, hops=hops,
                                     captcha=captcha, is_page=True,
                                     note="需要人机验证")
            answers = interactive(captcha, cancel_event)
            if answers is None:
                raise DownloadCancelled()
            if progress_callback:
                progress_callback("captcha", "正在提交验证码…")
            status, final_url2, ct2, body2, h2 = _submit_challenge(
                captcha, answers, opener, hdrs, referer, timeout, cancel_event,
                max_bytes)
            hops.append(final_url2)
            referer = final_url2
            # 提交后响应作为新页面继续下一跳分析
            current = final_url2
            if _looks_like_html(ct2, body2):
                try:
                    cs2 = _detect_charset(h2, body2)
                    html_text = body2.decode(cs2, errors="replace")
                except Exception:
                    html_text = body2.decode("utf-8", errors="replace")
                title = _extract_title(html_text) or title
            else:
                kind, ext, w = _kind_and_size(final_url2, guess=True)
                label = urllib.parse.urlparse(final_url2).path.rsplit("/", 1)[-1]
                return ResolveResult(final_url2,
                                     [LinkOption(final_url2, label, kind or "file",
                                                 w, None, "", "direct")],
                                     title=title, hops=hops)
            continue
        # 2) 提取候选选项
        options = _extract_options(html_text, final_url, title)
        if enum_sizes:
            try:
                options = _verify_options(opener, options, hdrs, referer, head_timeout,
                                          cancel_event, head_conns, max_variants,
                                          progress_callback)
            except DownloadCancelled:
                raise
            except Exception:
                pass  # 枚举失败不影响已找到的选项
        if options:
            if progress_callback:
                progress_callback("done", "找到 %d 个下载选项" % len(options))
            return ResolveResult(final_url, options, title=title, hops=hops,
                                 is_page=True)
        # 3) 没有选项 → 跟随 meta/JS 跳转继续
        redirect = _find_meta_refresh(html_text, final_url)
        if not redirect:
            js = _parse_js_vars(html_text, final_url)
            redirect = js[0][0] if js else ""
        if redirect and redirect not in hops:
            current = redirect
            continue
        return ResolveResult(final_url, [], title=title, hops=hops, is_page=True,
                             note="页面中未找到可下载的链接")
    raise ResolveError("跳转次数超过 %d 次，已停止（可能存在跳转环）" % max_hops)


def resolve_links(url, **kwargs):
    """便捷别名：只返回选项 URL 列表。"""
    return [o.url for o in resolve(url, **kwargs).options]


__all__ = ["LinkOption", "CaptchaChallenge", "ResolveResult", "ResolveError",
           "resolve", "resolve_links"]
