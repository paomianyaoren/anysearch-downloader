# -*- coding: utf-8 -*-
"""AnySearch 下载器 v1.2.0：多线程分块下载 + 断点重试 + 断点续传（纯标准库）

v1.2.0 引擎特性：
  - 自动分块：按 文件大小 / (线程数 × 4) 动态计算块大小（1MB ~ 16MB）；
    手动分块：由调用方指定 chunk_size（1MB ~ 64MB）。
  - 分块内断点重试：某分块失败时，重试从该块已写入的字节处续传
    （Range: bytes=(start+written)-(end)），不再整块重下；退避 1s→2s→4s 封顶 8s。
  - 暂停/继续：分块自动重试 max_retries 次仍失败 → 整体暂停（保留 .part 与
    .meta 断点），抛 DownloadPaused；用户再次调用 download_file 即自动续传。
  - 跨会话断点续传：下载写 <文件名>.part + <文件名>.part.meta；再次下载同一
    URL 时校验 ETag/Last-Modified/大小一致则只补未完成块，不一致则从头开始。
  - 手动取消（cancel_event）同样保留断点，下次下载自动续传；
    需要彻底放弃时调用 discard_download() 删除 .part/.meta。

基础特性（沿用 v1.1）：
  - 先探测服务器是否支持 Range；不支持或文件过小自动回退单线程
  - 返回 HTML 网页的链接视为“假直链”并报错，不保存
  - 动态任务队列（谁完成谁继续领下一块），避免慢分片拖累
  - 下载完成校验文件大小，不完整/损坏的文件绝不保留

不支持的范围（请勿期望本工具处理）：
  - 需要登录 / Cookie / 验证码 / 客户端签名的网盘链接（百度网盘、夸克网盘、阿里云盘等）
  - 网盘分享页（返回的是 HTML 网页，不是文件直链）
  - 需要浏览器插件、动态签名或客户端配合才能下载的链接
  - 本工具只面向“无需鉴权、浏览器直接访问即可下载”的真实文件直链（CDN / 官网 / GitHub Release 等）

多线程/并行建议（总连接数 = 并行任务数 × 每任务线程数）：
  - 下载单个大文件：1 任务 × 16 线程
  - 同时下载多个文件：2 任务 × 8 线程
  - 带宽小 / 服务器容易封 IP：2 任务 × 4 线程
  - 连接数不是越多越好：受限于本机带宽和服务器限速，超过承受能力可能封 IP 或损坏文件。
"""
import json
import os
import re
import socket
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

# 自定义请求头（由 set_custom_headers 设置，下载请求会带上）
_CUSTOM_HEADERS = {}


def set_custom_headers(headers):
    """设置自定义请求头（dict），会合并到所有下载请求中。"""
    global _CUSTOM_HEADERS
    _CUSTOM_HEADERS = dict(headers or {})


MIN_MULTITHREAD_SIZE = 1 * 1024 * 1024      # 小于 1MB 直接用单线程，分块反而慢
MIN_CHUNK_SIZE = 1 * 1024 * 1024            # 自动/手动分块的下限（1MB）
MAX_AUTO_CHUNK_SIZE = 16 * 1024 * 1024      # 自动分块上限（16MB）
MAX_MANUAL_CHUNK_SIZE = 64 * 1024 * 1024    # 手动分块上限（64MB）
CHUNKS_PER_THREAD = 4                       # 自动分块时保证每线程至少有 4 块可领
READ_TIMEOUT = 2.0                          # 读取网络数据的 socket 超时（秒），用于快速响应取消
MAX_CONSECUTIVE_TIMEOUTS = 30               # 连续无数据超时上限，防止死循环
META_SAVE_INTERVAL = 1.0                    # .meta 断点文件的最小落盘间隔（秒）

# 当前所有活动的下载响应；取消/暂停时立即 close 它们以中断阻塞的 read()
_active_responses = set()
_active_responses_lock = threading.Lock()
# 按任务（cancel_event）隔离的连接登记：单任务取消只关自己的连接，不误伤其它任务
_responses_by_event = {}   # id(cancel_event) -> set(resp)
_resp_event_id = {}        # id(resp) -> id(cancel_event)


def _register_response(resp, cancel_event=None):
    with _active_responses_lock:
        _active_responses.add(resp)
        if cancel_event is not None:
            eid = id(cancel_event)
            _responses_by_event.setdefault(eid, set()).add(resp)
            _resp_event_id[id(resp)] = eid


def _unregister_response(resp):
    with _active_responses_lock:
        _active_responses.discard(resp)
        eid = _resp_event_id.pop(id(resp), None)
        if eid is not None:
            bucket = _responses_by_event.get(eid)
            if bucket is not None:
                bucket.discard(resp)
                if not bucket:
                    _responses_by_event.pop(eid, None)


def cancel_active_downloads(cancel_event=None):
    """立即关闭活动网络连接，使阻塞中的 read() 立刻中断。

    cancel_event 为 None：关闭全部连接（“暂停全部”/程序退出用）；
    传入任务的 cancel_event：只关闭该任务的连接（单任务暂停/取消用，不误伤其它任务）。
    """
    if cancel_event is None:
        with _active_responses_lock:
            responses = list(_active_responses)
    else:
        with _active_responses_lock:
            responses = list(_responses_by_event.get(id(cancel_event), ()))
    for resp in responses:
        try:
            resp.close()
        except Exception:
            pass
    # 被强制关闭的连接已不可再用：直接从登记表中清除，防极端路径下的残留累积
    with _active_responses_lock:
        for resp in responses:
            _active_responses.discard(resp)
            eid = _resp_event_id.pop(id(resp), None)
            if eid is not None:
                bucket = _responses_by_event.get(eid)
                if bucket is not None:
                    bucket.discard(resp)
                    if not bucket:
                        _responses_by_event.pop(eid, None)


def _set_read_timeout(resp, seconds=READ_TIMEOUT):
    """把底层 socket 的读取超时调短，使取消/中断能快速生效。"""
    try:
        sock = resp.fp.raw._sock
        sock.settimeout(seconds)
    except Exception:
        pass


def _read_with_cancel(resp, amt, cancel_event, pause_event=None):
    """读取 amt 字节；阻塞不超过 READ_TIMEOUT，支持取消与暂停。"""
    consecutive = 0
    while True:
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        if pause_event is not None and pause_event.is_set():
            raise DownloadPaused()
        try:
            return resp.read(amt)
        except socket.timeout:
            consecutive += 1
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            if pause_event is not None and pause_event.is_set():
                raise DownloadPaused()
            if consecutive > MAX_CONSECUTIVE_TIMEOUTS:
                raise DownloadError("网络持续无数据，已停止读取。")
            continue
        except OSError:
            # 连接被对端关闭/重置（如取消时 socket 被 close）：取消/暂停优先，否则如实报错
            # 注意：必须放在 socket.timeout 之后（Python 3.10+ timeout 是 OSError 子类）
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            if pause_event is not None and pause_event.is_set():
                raise DownloadPaused()
            raise DownloadError("网络连接异常中断。")


def _interruptible_sleep(seconds, cancel_event, pause_event=None):
    """可被取消/暂停立即打断的等待：Event.wait 支持超时且能被 set() 唤醒，
    保证用户点击暂停/取消后马上响应，而不是等退避 sleep 结束。"""
    end = time.time() + seconds
    while time.time() < end:
        if cancel_event is not None and cancel_event.is_set():
            return
        if pause_event is not None and pause_event.is_set():
            return
        remaining = max(0.0, end - time.time())
        if cancel_event is not None:
            cancel_event.wait(min(0.1, remaining))
        else:
            time.sleep(min(0.1, remaining))


class DownloadCancelled(Exception):
    """用户主动暂停下载（断点已保留，再次下载将自动续传）。"""


class DownloadPaused(Exception):
    """下载已暂停：某分块重试耗尽或用户暂停，断点已保存，可再次调用续传。"""


class DownloadError(RuntimeError):
    pass


class HtmlPageError(DownloadError):
    """目标 URL 返回的是网页（text/html）而不是文件。

    属于 DownloadError 子类（既有捕获逻辑不受影响）。GUI/CLI 用它作为触发条件：
    直链下载失败且是网页时，自动启用链接解析器寻找真实直链/多清晰度选项。
    """


def _headers():
    h = {"User-Agent": BROWSER_UA, "Accept": "*/*"}
    h.update(_CUSTOM_HEADERS)
    return h


# ==================== .part / .meta 断点持久化 ====================

def _part_path(dest: Path) -> Path:
    return dest.with_name(dest.name + ".part")


def _meta_path(dest: Path) -> Path:
    return dest.with_name(dest.name + ".part.meta")


def _load_meta(meta_path: Path):
    """读取断点元数据；不存在或损坏返回 None。"""
    try:
        if meta_path.exists():
            return json.loads(meta_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    return None


def _meta_valid(meta, url, total, etag, last_modified):
    """校验断点是否仍适用于本次下载：URL 必须一致；ETag/Last-Modified/大小有值则必须一致。"""
    if not meta or meta.get("url") != url:
        return False
    if meta.get("total") is not None and total is not None and meta["total"] != total:
        return False
    if etag and meta.get("etag") and meta["etag"] != etag:
        return False
    if last_modified and meta.get("last_modified") and meta["last_modified"] != last_modified:
        return False
    return True


def _save_meta(dest: Path, url, total, etag, last_modified, supports_range,
               filename, chunk_size, num_threads, segments, done, block_written):
    """把断点信息写入 .meta（JSON）。done/written 与 segments 一一对应。"""
    meta = {
        "version": 1,
        "url": url,
        "total": total,
        "etag": etag,
        "last_modified": last_modified,
        "supports_range": supports_range,
        "filename": filename,
        "chunk_size": chunk_size,
        "num_threads": num_threads,
        "segments": segments,
        "done": done,
        "block_written": block_written,
    }
    try:
        _meta_path(dest).write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass


def discard_download(url=None, out_dir=None, filename=None):
    """彻底放弃下载：删除 .part/.meta 断点文件。

    url + out_dir：扫描 out_dir 下所有 .part.meta，删除 URL 匹配的断点（不联网）。
    filename：直接指定文件名（含后缀）删除其断点。
    """
    out_path = Path(out_dir) if out_dir else Path(".")
    if filename:
        for p in (_part_path(out_path / filename), _meta_path(out_path / filename)):
            try:
                p.unlink(missing_ok=True)
            except Exception:
                pass
        return
    if url:
        try:
            for mp in out_path.glob("*.part.meta"):
                try:
                    meta = _load_meta(mp)
                    if meta and meta.get("url") == url:
                        part = mp.with_name(mp.name[: -len(".meta")])
                        mp.unlink(missing_ok=True)
                        part.unlink(missing_ok=True)
                except Exception:
                    pass
        except Exception:
            pass


def _cleanup_cache_estimate(out_dir) -> int:
    """估算 out_dir 下 .part 断点文件占用的字节数（设置页“清理缓存”展示用）。"""
    total = 0
    try:
        for p in Path(out_dir).glob("*.part"):
            try:
                total += p.stat().st_size
            except Exception:
                pass
    except Exception:
        pass
    return total


def cleanup_cache(out_dir) -> int:
    """删除 out_dir 下所有 .part/.meta 断点文件，返回释放的字节数。"""
    freed = _cleanup_cache_estimate(out_dir)
    try:
        for pattern in ("*.part", "*.part.meta"):
            for p in Path(out_dir).glob(pattern):
                try:
                    p.unlink(missing_ok=True)
                except Exception:
                    pass
    except Exception:
        pass
    return freed


# ==================== 探测与文件名 ====================

def _parse_content_disposition_filename(cd):
    """从 Content-Disposition 头中解析文件名（纯正则手工解析，零模块依赖）。

    兼容性设计（目标：尽可能多的设备/环境）：
      - 不依赖 cgi（Python 3.13 起已移除），也不依赖 email（部分精简发行版
        可能裁剪，如手机上的 Pydroid 3 / Termux / 嵌入式 Python）；
      - 只用 re 与 urllib.parse.unquote，Python 3.x 全系列均可运行；
      - 解析失败一律返回 None，由调用方回退到 URL 文件名，绝不抛异常。

    按 RFC 6266 优先级：
      1) filename*=UTF-8''<百分号编码>   （RFC 5987，优先于 filename，做百分号解码）
      2) filename*=ISO-8859-1''...       （其它 charset 标签：取裸值，不做解码）
      3) filename="..."                  （带引号）
      4) filename=...                    （裸值）
    注：filename*0= / filename*1= 分段式（RFC 2231 续行）极少出现，未处理，会回退 URL 文件名。
    """
    if not cd:
        return None

    # 1) RFC 5987：filename*=UTF-8''%E4%B8%AD%E6%96%87.zip
    m = re.search(r'filename\*\s*=\s*(?:UTF-8\'\'|utf-8\'\')\s*"?([^";]+)"?', cd, re.IGNORECASE)
    if m:
        name = m.group(1).strip().strip('"')
        if name:
            try:
                from urllib.parse import unquote
                return unquote(name)
            except Exception:
                return name

    # 2) 其它 charset 标签：filename*=ISO-8859-1''...
    m = re.search(r'filename\*\s*=\s*[A-Za-z0-9._-]+\'\'\s*"?([^";]+)"?', cd, re.IGNORECASE)
    if m:
        name = m.group(1).strip().strip('"')
        if name:
            return name

    # 3) filename="..."
    m = re.search(r'filename\s*=\s*"([^"]*)"', cd, re.IGNORECASE)
    if m:
        name = m.group(1).strip()
        if name:
            return name

    # 4) filename=裸值
    m = re.search(r'filename\s*=\s*([^;]+)', cd, re.IGNORECASE)
    if m:
        name = m.group(1).strip().strip('"')
        if name:
            return name
    return None


def _probe(url, timeout, cancel_event=None, referer=None):
    """探测文件大小、Range 支持、ETag/Last-Modified，同时判断是否为 HTML 假直链。

    返回 (total, supports_range, first_chunk, final_url, content_type, etag, last_modified)。
    探测单独限时（≤10 秒，死链快速识别）；连接注册到活动集合（按 cancel_event 隔离），
    用户暂停/取消时 cancel_active_downloads() 可立即中断，不会干等超时。
    referer：解析出的直链所属页面地址（抗防盗链）。
    """
    if cancel_event is not None and cancel_event.is_set():
        raise DownloadCancelled()
    headers = {**_headers(), "Range": "bytes=0-2047"}
    if referer:
        headers.setdefault("Referer", referer)
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=min(timeout, 6)) as resp:
        _register_response(resp, cancel_event)
        try:
            content_type = (resp.headers.get("Content-Type") or "").lower()
            data = resp.read(2048)
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            lower = data[:512].lower()
            if (
                "text/html" in content_type
                or b"<!doctype html" in lower
                or b"<html" in lower
                or b"<head" in lower
            ):
                raise HtmlPageError(
                    "目标 URL 返回的是网页（text/html）而不是文件，可能不是直链。\n"
                    "请使用真实的文件直链（例如 CDN 链接），或先打开该页面获取真实下载地址。"
                )
            # 206 时 Content-Length 只是分片长度，真实总大小要从 Content-Range 解析
            total = None
            content_range = resp.headers.get("Content-Range") or ""
            if content_range and "/" in content_range:
                total_str = content_range.rsplit("/", 1)[-1].strip()
                if total_str.isdigit():
                    total = int(total_str)
            # 206 且 Content-Range 无总大小（"bytes 0-2047/*"）时，绝不能把
            # Content-Length（分片长度）当总大小，否则大文件会被误判成小文件
            if total is None and resp.status != 206:
                cl = resp.headers.get("Content-Length")
                total = int(cl) if cl and cl.isdigit() else None
            accepts_ranges = (resp.headers.get("Accept-Ranges") or "").lower() == "bytes"
            # 206 说明服务器接受了 Range；200 说明忽略了 Range。
            # 有些 CDN（如 CloudFront）不返回 Accept-Ranges 头，但 206 本身就是支持 Range 的证据。
            partial_ok = resp.status == 206
            supports_range = partial_ok or accepts_ranges
            etag = resp.headers.get("ETag") or ""
            last_modified = resp.headers.get("Last-Modified") or ""
            return total, supports_range, data, resp.geturl(), content_type, etag, last_modified
        finally:
            _unregister_response(resp)


# ==================== 下载核心 ====================

def _download_range(url, start, end, dest, timeout, max_retries, cancel_event,
                    pause_event, lock, done_bytes, total, progress_callback,
                    written_box, mark_piece_done, maybe_save_meta, split_box,
                    push_extra, split_check=None, referer=None):
    """下载 [start, end_box[0]] 区间（慢时可被拆分缩短）到 dest 文件对应偏移。

    written_box：本次任务已写字节（重试续传沿用）。
    慢块拆分：split_check(downloaded, current_end) 由调用方定期采样判定，
    决定拆分时置 split_box[0]=新end，本任务收缩到 [start, new_end]，
    并把 (new_end+1, 原 end) 通过 push_extra 交给其它线程下载（线程优先配给快的部分）。
    完成时调用 mark_piece_done(start, end_box[0]) 更新该块的覆盖前缀。
    """
    end_box = [end]
    attempt = 0
    while True:
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        if pause_event is not None and pause_event.is_set():
            raise DownloadPaused()
        downloaded = written_box[0]
        try:
            headers = {**_headers(), "Range": "bytes=%d-%d" % (start + downloaded, end_box[0])}
            if referer:
                headers.setdefault("Referer", referer)
            req = urllib.request.Request(url, headers=headers)
            # 连接阶段无法注册 socket 中断，故限时 8s：取消最坏 8s 内收敛；读阶段可即时中断
            with urllib.request.urlopen(req, timeout=min(timeout, 8)) as resp:
                _register_response(resp, cancel_event)
                try:
                    # 多线程分块必须得到 206；若服务器忽略 Range 返回 200，
                    # 继续写入会把整个文件写到当前偏移，造成文件损坏和进度超过 100%。
                    if resp.status == 200:
                        raise DownloadError("服务器忽略了 Range 请求（返回 200），已停止该分块。")
                    if resp.status != 206:
                        raise DownloadError("服务器返回异常状态码：%s" % resp.status)
                    _set_read_timeout(resp)
                    with open(dest, "r+b") as f:
                        f.seek(start + downloaded)
                        while downloaded < (end_box[0] - start + 1):
                            if cancel_event is not None and cancel_event.is_set():
                                raise DownloadCancelled()
                            if pause_event is not None and pause_event.is_set():
                                raise DownloadPaused()
                            # 慢块拆分：采样判定 → 收缩本任务，后半段交给其它线程
                            if split_check is not None:
                                split_check(downloaded, end_box[0])
                            if split_box[0] is not None and split_box[0] < end_box[0]:
                                new_end = split_box[0]
                                split_box[0] = None
                                push_extra(new_end + 1, end_box[0])
                                end_box[0] = new_end
                                continue
                            chunk = _read_with_cancel(resp, 256 * 1024, cancel_event, pause_event)
                            if not chunk:
                                break
                            # 严格校验：绝不多写请求范围之外的数据，否则文件损坏
                            if downloaded + len(chunk) > (end_box[0] - start + 1):
                                raise DownloadError("服务器返回数据超过分块范围（%d/%d）"
                                                    % (downloaded + len(chunk), end_box[0] - start + 1))
                            f.write(chunk)
                            downloaded += len(chunk)
                            written_box[0] = downloaded
                            with lock:
                                done_bytes[0] += len(chunk)
                                if progress_callback:
                                    progress_callback(done_bytes[0], total)
                            maybe_save_meta()
                    if downloaded != (end_box[0] - start + 1):
                        raise DownloadError("分块下载不完整：%d/%d" % (downloaded, end_box[0] - start + 1))
                finally:
                    _unregister_response(resp)
            mark_piece_done(start, end_box[0])
            return
        except DownloadCancelled:
            raise
        except DownloadPaused:
            raise
        except Exception as e:
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            if pause_event is not None and pause_event.is_set():
                raise DownloadPaused()
            attempt += 1
            if attempt > max_retries:
                # 该块重试耗尽：置暂停（不强制断连其它并发任务；读超时 2s 内自然退出）
                if pause_event is not None:
                    pause_event.set()
                raise DownloadError("分片 %d-%d 下载失败：%s" % (start, end_box[0], e))
            _interruptible_sleep(min(1.0 * (2 ** (attempt - 1)), 8.0), cancel_event, pause_event)


def _download_single(url, dest, timeout, max_retries, cancel_event, progress_callback,
                     written_offset=0, referer=None):
    """单线程下载（服务器不支持 Range 或文件过小时使用）。

    written_offset>0 时从该偏移续传（Range: bytes=N-，服务器忽略 Range 返回 200 时
    自动截断从头重写）；重试同样从已写入字节继续。
    """
    downloaded = int(written_offset or 0)
    attempt = 0
    while True:
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        try:
            headers = _headers()
            if downloaded > 0:
                headers["Range"] = "bytes=%d-" % downloaded
            if referer:
                headers.setdefault("Referer", referer)
            req = urllib.request.Request(url, headers=headers)
            # 连接阶段无法注册 socket 中断，故限时 8s：取消最坏 8s 内收敛；读阶段可即时中断
            with urllib.request.urlopen(req, timeout=min(timeout, 8)) as resp:
                _register_response(resp, cancel_event)
                try:
                    _set_read_timeout(resp)
                    first = b""
                    total = None
                    if downloaded == 0:
                        content_type = (resp.headers.get("Content-Type") or "").lower()
                        first = _read_with_cancel(resp, 2048, cancel_event)
                        lower = first[:512].lower()
                        if (
                            "text/html" in content_type
                            or b"<!doctype html" in lower
                            or b"<html" in lower
                            or b"<head" in lower
                        ):
                            raise HtmlPageError("目标 URL 返回的是网页（text/html），可能不是直链。")
                        total_header = resp.headers.get("Content-Length")
                        total = int(total_header) if total_header and total_header.isdigit() else None
                    mode = "wb" if downloaded == 0 else "r+b"
                    with open(dest, mode) as f:
                        if downloaded > 0:
                            f.seek(downloaded)
                            if resp.status == 200:
                                # 服务器忽略 Range：截断从头重写
                                f.truncate(0)
                                downloaded = 0
                                f.seek(0)
                        done = downloaded
                        if first:
                            f.write(first)
                            done += len(first)
                            if progress_callback:
                                progress_callback(done, total)
                        while True:
                            if cancel_event is not None and cancel_event.is_set():
                                raise DownloadCancelled()
                            chunk = _read_with_cancel(resp, 256 * 1024, cancel_event)
                            if not chunk:
                                break
                            f.write(chunk)
                            done += len(chunk)
                            if progress_callback:
                                progress_callback(done, total)
                        downloaded = done
                finally:
                    _unregister_response(resp)
                return
        except DownloadCancelled:
            raise
        except Exception as e:
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            attempt += 1
            if attempt > max_retries:
                raise DownloadError("下载失败：%s" % e)
            _interruptible_sleep(1.0, cancel_event)


def _download_small_resumable(base_url, dest, out_path, filename, url, total, etag,
                              last_modified, timeout, max_retries, cancel_event,
                              progress_callback, mode_callback, referer=None):
    """≤1MB 但服务器支持 Range 的文件：单线程下载 + .part/.meta 断点续传。

    与多线程路径同一套断点约定（分块表固定为单块 0..total-1），
    暂停保留断点，再次下载自动续传；取消（放弃）由调用方 discard_download 清理。
    """
    part = _part_path(dest)
    meta_path = _meta_path(dest)
    written = 0
    meta = _load_meta(meta_path)
    single_seg = [[0, total - 1]]
    if (_meta_valid(meta, url, total, etag, last_modified) and part.exists()
            and meta.get("chunk_size") == 1 and meta.get("segments") == single_seg):
        try:
            sz = part.stat().st_size
            if 0 <= sz <= total:
                written = sz
        except Exception:
            written = 0
    if written >= total:
        # 断点已完整：直接收尾
        if dest.exists():
            dest = out_path / (dest.stem + "_" + str(int(time.time())) + dest.suffix)
        try:
            part.rename(dest)
        finally:
            meta_path.unlink(missing_ok=True)
        if mode_callback:
            mode_callback("resume", 1, total)
        if progress_callback:
            progress_callback(total, total)
        return dest
    if mode_callback:
        mode_callback("resume" if written > 0 else "single", 1, total)
    if progress_callback:
        progress_callback(written, total)
    try:
        _download_single(base_url, part, timeout, max_retries, cancel_event,
                         progress_callback, written_offset=written, referer=referer)
    except BaseException:
        if cancel_event is not None and cancel_event.is_set():
            # 暂停：保留断点（.part/.meta），下次自动续传
            try:
                sz = part.stat().st_size if part.exists() else 0
                _save_meta(dest, url, total, etag, last_modified, True, filename,
                           1, 1, single_seg, [sz >= total], [min(sz, total)])
            except Exception:
                pass
            raise
        # 失败：不残留半截文件
        try:
            part.unlink(missing_ok=True)
        except Exception:
            pass
        raise
    # 完成：大小校验 → 改名 → 清 meta
    try:
        actual = part.stat().st_size  # 先取值再删除，避免错误信息被掩盖
        if actual != total:
            part.unlink(missing_ok=True)
            meta_path.unlink(missing_ok=True)
            raise DownloadError("文件大小校验失败：期望 %d 字节，实际 %d 字节" % (total, actual))
    except FileNotFoundError:
        raise DownloadError("下载文件缺失")
    if dest.exists():
        dest = out_path / (dest.stem + "_" + str(int(time.time())) + dest.suffix)
    try:
        part.rename(dest)
    finally:
        meta_path.unlink(missing_ok=True)
    return dest


def _compute_chunk_size(total, threads, auto_chunk, chunk_size):
    """计算分块大小。

    auto_chunk=True：块 = total / (threads×4)，按 1MB 向下取整，夹在 [1MB, 16MB]。
    auto_chunk=False：用 chunk_size，夹在 [1MB, 64MB]。
    """
    if auto_chunk:
        chunk = int(total // (threads * CHUNKS_PER_THREAD))
        chunk = max(MIN_CHUNK_SIZE, min(MAX_AUTO_CHUNK_SIZE, chunk))
        return max(MIN_CHUNK_SIZE, chunk - (chunk % MIN_CHUNK_SIZE) or MIN_CHUNK_SIZE)
    cs = int(chunk_size or MIN_CHUNK_SIZE)
    return max(MIN_CHUNK_SIZE, min(MAX_MANUAL_CHUNK_SIZE, cs))


def build_mirror_urls(url, mirrors):
    """把镜像模板展开为候选 URL（模板需含 {url} 占位符）。"""
    out = []
    for m in (mirrors or []):
        m = (m or "").strip()
        if "{url}" in m:
            out.append(m.replace("{url}", url))
    return out


def download_file(
    url,
    out_dir,
    num_threads=4,
    timeout=30,
    progress_callback=None,
    cancel_event=None,
    max_retries=3,
    mode_callback=None,
    auto_chunk=True,
    chunk_size=None,
    resume=True,
    mirrors=None,
    mirror_strategy="direct",
    mirror_speed_limit=0,
    mirror_probe_conns=3,
    referer=None,
):
    """下载 url 到 out_dir，返回最终文件路径。

    progress_callback(done_bytes, total_bytes)：done 为已完成的字节总量
      （含续传恢复的进度），total 为 None 表示未知。
    cancel_event：threading.Event，设置后停止下载并保留断点（再次下载自动续传）。
    mode_callback(mode, threads, total)：mode 为 "multithread" / "single" /
      "resume" / "source_switch"（切换镜像源）/ "source_fail"（源不可用）。
    auto_chunk：True 自动计算块大小；False 使用 chunk_size（1MB~64MB）。
    resume：True 时若存在有效断点（.part/.meta 且 ETag/大小匹配）自动续传。
    mirrors：镜像模板列表（含 {url} 占位符），如 ["https://ghfast.top/{url}"]。
    mirror_strategy："direct" 仅直连 / "mirror_first" 镜像优先（下载立即开始，
      后台测速镜像，测出明显更快的镜像后自动切换）/
      "auto_fallback" 直连优先、持续低速自动切换镜像。
    mirror_speed_limit：auto_fallback 的切换阈值（字节/秒，5 秒窗口平均低于它即切换；
      0 = 不因速度切换）。
    mirror_probe_conns：镜像测速并发数（默认 3，避免测速压力过大）。
    """
    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)
    if not url.startswith(("http://", "https://")):
        raise DownloadError("仅支持 http/https 链接：%s" % url)

    mirror_urls = build_mirror_urls(url, mirrors)

    # 探测直连（会跟随重定向，拿到最终真实文件 URL、类型、ETag/Last-Modified）；
    # 直连失败（404/连接失败/假直链）时，若配置了镜像，则依次探测镜像取第一个可用者兜底
    probe_error = None
    try:
        total, supports_range, _first, final_url, content_type, etag, last_modified = _probe(url, timeout, cancel_event, referer)
    except DownloadCancelled:
        raise
    except Exception as e:
        probe_error = e
        total = supports_range = final_url = content_type = etag = last_modified = None
    base_url = url
    if isinstance(probe_error, HtmlPageError):
        # 链接是网页：直接上抛，供调用方启用链接解析（对网页 URL 试镜像无意义）
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        raise probe_error
    if probe_error is not None and mirror_urls and mirror_strategy != "direct":
        for m in mirror_urls:
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            try:
                total, supports_range, _f, final_url, content_type, etag, last_modified = _probe(m, min(timeout, 5), cancel_event, referer)
                base_url = m
                probe_error = None
                break
            except DownloadCancelled:
                raise
            except Exception:
                continue
    if probe_error is not None:
        # 全部源不可用：优先如实反映用户取消，再报真实错误
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        raise DownloadError("直连与全部镜像源均不可用：%s" % probe_error)

    filename = _guess_filename_from_final(final_url or url, content_type)
    dest = out_path / filename

    threads = num_threads if (num_threads and num_threads > 1) else 4

    # ---- 单线程路径（不支持 Range / 大小未知 / 文件过小）----
    if not supports_range or total is None or total <= MIN_MULTITHREAD_SIZE:
        if supports_range and total is not None and resume:
            # 小文件但支持 Range：单线程下载 + .part/.meta 断点续传（暂停可续传）
            return _download_small_resumable(
                base_url, dest, out_path, filename, url, total, etag, last_modified,
                timeout, max_retries, cancel_event, progress_callback, mode_callback,
                referer)
        # 单线程无法续传：清理可能存在的旧断点（属过期残留）
        discard_download(filename=filename, out_dir=out_dir)
        if dest.exists():
            dest = out_path / (dest.stem + "_" + str(int(time.time())) + dest.suffix)
        if mode_callback:
            mode_callback("single", 1, total)
        if progress_callback:
            progress_callback(0, total)
        try:
            _download_single(base_url, dest, timeout, max_retries, cancel_event,
                             progress_callback, referer=referer)
        except BaseException:
            # 单线程不支持断点：取消/失败时不残留半截文件
            try:
                dest.unlink(missing_ok=True)
            except Exception:
                pass
            raise
        try:
            if total is not None:
                actual = dest.stat().st_size  # 先取值再删除，否则错误信息会变成“文件缺失”
                if actual != total:
                    dest.unlink(missing_ok=True)
                    raise DownloadError("文件大小校验失败：期望 %d 字节，实际 %d 字节" % (total, actual))
        except FileNotFoundError:
            raise DownloadError("下载文件缺失")
        return dest

    # ---- 多线程分块（静态分块表 + 动态领取队列 + 断点续传 + 镜像源）----
    chunk = _compute_chunk_size(total, threads, auto_chunk, chunk_size)
    segments = []
    pos = 0
    while pos < total:
        end = min(pos + chunk - 1, total - 1)
        segments.append([pos, end])
        pos = end + 1

    part = _part_path(dest)
    meta_path = _meta_path(dest)
    done = [False] * len(segments)
    block_written = [0] * len(segments)
    resumed = False

    # 源顺序：direct=仅直连；mirror_first/auto_fallback=直连立即开始
    # （下载启动不等测速：mirror_first 在下载进行中后台测速镜像，测出更快的源再切换）
    if mirror_strategy in ("mirror_first", "auto_fallback"):
        sources = [base_url] + [m for m in mirror_urls if m != base_url]
    else:
        sources = [base_url]

    # mirror_first：后台测速镜像（不阻塞下载启动；每源限时 3s、样本 256KB）
    mirror_speed_done = threading.Event()
    mirror_speeds = {}
    if mirror_strategy == "mirror_first" and mirror_urls:
        def _probe_mirrors():
            try:
                results = {}
                if cancel_event is not None and cancel_event.is_set():
                    return
                with ThreadPoolExecutor(max_workers=max(1, min(mirror_probe_conns, len(mirror_urls)))) as ex:
                    futs = {ex.submit(measure_speed, s, min(timeout, 3), 256 * 1024, cancel_event, referer): s for s in mirror_urls}
                    for f in futs:
                        if cancel_event is not None and cancel_event.is_set():
                            break
                        try:
                            results[futs[f]] = f.result(timeout=timeout + 5)
                        except Exception:
                            results[futs[f]] = (0, None, False, futs[f], "")
                if cancel_event is not None and cancel_event.is_set():
                    # 取消后未开始的测速任务不再执行（已开始的会在连接关闭时中断）
                    for f in futs:
                        f.cancel()
                mirror_speeds.update(results)
            finally:
                mirror_speed_done.set()

        threading.Thread(target=_probe_mirrors, daemon=True).start()

    if resume:
        meta = _load_meta(meta_path)
        if _meta_valid(meta, url, total, etag, last_modified) and part.exists():
            if (meta.get("chunk_size") == chunk
                    and meta.get("segments") == segments
                    and len(meta.get("done", [])) == len(segments)
                    and len(meta.get("block_written", [])) == len(segments)):
                done = [bool(x) for x in meta["done"]]
                block_written = [int(x) for x in meta["block_written"]]
                # 兼容：块完成则 written 视为满块
                for i, seg in enumerate(segments):
                    if done[i]:
                        block_written[i] = seg[1] - seg[0] + 1
                resumed = any(done) or any(w > 0 for w in block_written)
            else:
                # 分块参数变化：断点不可用，清理重下
                part.unlink(missing_ok=True)
                meta_path.unlink(missing_ok=True)

    if not resumed:
        if dest.exists():
            dest = out_path / (dest.stem + "_" + str(int(time.time())) + dest.suffix)
            part = _part_path(dest)
            meta_path = _meta_path(dest)
        part.touch()

    lock = threading.Lock()
    done_bytes = [sum(block_written)]
    if progress_callback:
        progress_callback(done_bytes[0], total)

    # 镜像切换监测 + 慢块拆分的全局状态
    switch_event = threading.Event()
    fast_switch = [False]  # 本次切换是否因“镜像优先测速选快”触发（用于提示区分）
    speed_samples = []  # [(t, done), ...]
    global_t0 = [time.time()]

    for si, src_url in enumerate(sources):
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled()
        fast_switch[0] = False
        switch_event.clear()  # 每个源重新判定，防止上一源的切换标记引发“连环切换”

        if si > 0:
            # 切换源：探测并校验大小/ETag 一致才允许续传（探测限时 5s，死源快速跳过）
            try:
                st_total, st_sr, _d, _fu, _ct, st_etag, st_lm = _probe(src_url, min(timeout, 5), cancel_event)
            except Exception:
                if mode_callback:
                    mode_callback("source_fail", 0, total)
                continue
            if st_total != total or not st_sr:
                if mode_callback:
                    mode_callback("source_fail", 0, total)
                continue
            if (st_etag and etag and st_etag != etag) or (st_lm and last_modified and st_lm != last_modified):
                # 内容不一致：重置断点，从该源重下
                done = [False] * len(segments)
                block_written = [0] * len(segments)
                done_bytes[0] = 0
                part.unlink(missing_ok=True)
                part.touch()
                if progress_callback:
                    progress_callback(0, total)
                etag, last_modified = st_etag, st_lm
            if mode_callback:
                mode_callback("source_switch", threads, total)
        else:
            if mode_callback:
                mode_callback("multithread" if not resumed else "resume", threads, total)

        pause_event = threading.Event()
        worker_errors = []

        # ---- 覆盖前缀（拆分模型）：每块维护已完成区间，前缀连续即计入 block_written ----
        covered = [None] * len(segments)
        covered_lock = threading.Lock()

        def _mark_piece_done(idx, s, e):
            with covered_lock:
                lst = covered[idx]
                if lst is None:
                    covered[idx] = [[s, e]]
                else:
                    lst.append([s, e])
                    lst.sort()
                seg_start = segments[idx][0]
                seg_len = segments[idx][1] - seg_start + 1
                cursor = seg_start
                for (a, b) in covered[idx]:
                    if a <= cursor <= b:
                        cursor = b + 1
                    elif a > cursor:
                        break
                block_written[idx] = cursor - seg_start
                if block_written[idx] >= seg_len:
                    done[idx] = True

        # ---- 待领取队列：静态未完成块 + 拆分出来的额外子块 ----
        # 用条件变量：空闲 worker 等待新拆分任务出现，而不是立刻退出
        pending = [i for i in range(len(segments)) if not done[i]]
        next_index = [0]
        extra_tasks = []      # (idx, s, e, written_box, split_box)
        extra_next = [0]
        task_cond = threading.Condition()
        in_flight = [0]
        last_save = [0.0]
        meta_save_lock = threading.Lock()

        def maybe_save_meta():
            now = time.time()
            with meta_save_lock:
                if now - last_save[0] >= META_SAVE_INTERVAL:
                    last_save[0] = now
                    # 与 _mark_piece_done 的 covered_lock 同序快照，避免存到半更新状态
                    with covered_lock:
                        _save_meta(dest, url, total, etag, last_modified, supports_range,
                                   filename, chunk, threads, segments, done, block_written)

        def push_extra(idx, s, e):
            with task_cond:
                extra_tasks.append((idx, s, e, [0], [None]))
                task_cond.notify_all()

        def _get_task():
            with task_cond:
                while True:
                    if extra_next[0] < len(extra_tasks):
                        t = extra_tasks[extra_next[0]]
                        extra_next[0] += 1
                        in_flight[0] += 1
                        return t
                    if next_index[0] < len(pending):
                        idx = pending[next_index[0]]
                        next_index[0] += 1
                        seg = segments[idx]
                        in_flight[0] += 1
                        return (idx, seg[0], seg[1], [block_written[idx]], [None])
                    if in_flight[0] == 0:
                        return None
                    task_cond.wait(timeout=0.5)  # 等待新拆分任务或其它任务完成

        def _task_done():
            with task_cond:
                in_flight[0] -= 1
                task_cond.notify_all()

        # ---- 镜像切换监测（仅直连首源）----
        limit = int(mirror_speed_limit or 0)
        watch_lock = threading.Lock()  # speed_watch 被多个 worker 线程并发调用，保护共享采样/源列表

        def speed_watch(done_now, _total_now):
            if si != 0 or len(sources) <= 1:
                return
            with watch_lock:
                now = time.time()
                speed_samples.append((now, done_now))
                while speed_samples and now - speed_samples[0][0] > 5.0:
                    speed_samples.pop(0)
                dt = now - speed_samples[0][0]
                if dt < 3.0:
                    return
                avg = (done_now - speed_samples[0][1]) / dt
                if mirror_strategy == "auto_fallback":
                    if limit > 0 and avg < limit:
                        switch_event.set()
                        pause_event.set()
                elif mirror_strategy == "mirror_first" and mirror_speed_done.is_set():
                    # 测速完成后：选“最快的且明显快于直连（×1.2）”的镜像切换
                    best = None
                    best_sp = 0.0
                    for s in sources[1:]:
                        sp, tot, sr, _fu, _ct = mirror_speeds.get(s, (0, None, False, s, ""))
                        if tot and sr and sp > avg * 1.2 and sp > best_sp:
                            best = s
                            best_sp = sp
                    if best is not None:
                        others = [s for s in sources[1:] if s != best]
                        sources[1:] = [best] + others
                        fast_switch[0] = True
                        switch_event.set()
                        pause_event.set()

        def make_task_callbacks(idx, start, written_box, split_box):
            block_t0 = [time.time()]
            block_d0 = [written_box[0]]

            def progress_wrap(d, t):
                if progress_callback:
                    progress_callback(d, t)
                speed_watch(d, t)

            def split_check(downloaded, current_end):
                # 慢块拆分：块速远低于全局均速（<30%）且剩余 > 2MB → 拆半交给其它线程
                now = time.time()
                if now - block_t0[0] < 2.0:
                    return
                bs = (downloaded - block_d0[0]) / max(0.001, now - block_t0[0])
                block_t0[0] = now
                block_d0[0] = downloaded
                gs = done_bytes[0] / max(0.001, now - global_t0[0])
                remaining = current_end - (start + downloaded) + 1
                if bs > 0 and bs < gs * 0.3 and remaining > 2 * 1024 * 1024:
                    mid = start + downloaded + remaining // 2
                    if mid < current_end:
                        split_box[0] = mid

            return progress_wrap, split_check

        def worker():
            while True:
                if cancel_event is not None and cancel_event.is_set():
                    return
                if pause_event.is_set():
                    return
                task = _get_task()
                if task is None:
                    return
                idx, s, e, wbox, sbox = task
                try:
                    progress_wrap, split_check = make_task_callbacks(idx, s, wbox, sbox)
                    mark = lambda ss, ee: _mark_piece_done(idx, ss, ee)
                    push = lambda ss, ee: push_extra(idx, ss, ee)
                    _download_range(src_url, s, e, part, timeout, max_retries, cancel_event,
                                    pause_event, lock, done_bytes, total, progress_wrap,
                                    wbox, mark, maybe_save_meta, sbox,
                                    push, split_check, referer)
                    maybe_save_meta()
                except DownloadCancelled:
                    _task_done()
                    return
                except DownloadPaused:
                    _task_done()
                    return
                except Exception as ex:
                    worker_errors.append(ex)
                    pause_event.set()
                    _task_done()
                    return
                _task_done()

        ts = [threading.Thread(target=worker) for _ in range(min(threads, len(pending)))]
        for t in ts:
            t.start()
        for t in ts:
            t.join()

        # 落盘最终断点（无论何种退出方式）
        _save_meta(dest, url, total, etag, last_modified, supports_range,
                   filename, chunk, threads, segments, done, block_written)

        if cancel_event is not None and cancel_event.is_set():
            # 手动暂停：保留断点，下次自动续传
            raise DownloadCancelled()

        # 低速切换 / 源失败 / 镜像更优：换下一个源继续（断点保留）
        if (switch_event.is_set() or worker_errors or pause_event.is_set()) and si + 1 < len(sources):
            if mode_callback:
                if fast_switch[0]:
                    # 镜像优先：测速选中了更快的镜像，提示带域名
                    import urllib.parse as _up
                    host = _up.urlsplit(sources[si + 1]).netloc or sources[si + 1]
                    mode_callback("source_fast_switch:" + host, threads, total)
                else:
                    mode_callback("source_switch", threads, total)
            continue

        if worker_errors or pause_event.is_set():
            detail = str(worker_errors[0]) if worker_errors else ""
            raise DownloadPaused(
                "下载已暂停：%d/%d 分块完成%s；断点已保存，再次下载将自动续传。"
                % (sum(1 for d in done if d), len(segments),
                   ("（%s）" % detail) if detail else "")
            )
        break

    # 最终大小校验：不完整/多余的文件绝不保留
    try:
        actual = part.stat().st_size  # 先取值再删除，否则错误信息会变成“文件缺失”
        if actual != total:
            part.unlink(missing_ok=True)
            meta_path.unlink(missing_ok=True)
            raise DownloadError("文件大小校验失败：期望 %d 字节，实际 %d 字节" % (total, actual))
    except FileNotFoundError:
        raise DownloadError("下载文件缺失")

    # 完成：part -> 正式文件名，清理 meta
    try:
        if dest.exists():
            dest = out_path / (dest.stem + "_" + str(int(time.time())) + dest.suffix)
        part.rename(dest)
    finally:
        meta_path.unlink(missing_ok=True)
    return dest


_CONTENT_TYPE_EXT = {
    "video/mp4": ".mp4",
    "video/webm": ".webm",
    "video/quicktime": ".mov",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/gif": ".gif",
    "image/webp": ".webp",
    "application/zip": ".zip",
    "application/x-zip-compressed": ".zip",
    "application/pdf": ".pdf",
    "application/json": ".json",
    "text/plain": ".txt",
    "text/markdown": ".md",
    "application/octet-stream": ".bin",
}


def measure_speed(url, timeout=30, sample_bytes=1024 * 1024, cancel_event=None,
                  referer=None):
    """对 URL 做一次快速测速（下载 sample_bytes 字节计时）。

    返回 (bytes_per_second, total_size, supports_range, final_url, content_type)。
    测速失败返回 (0, None, False, url, "")。cancel_event 用于取消中断与按任务登记连接。
    """
    try:
        total, supports_range, _data, final_url, content_type, _etag, _lm = _probe(
            url, timeout, cancel_event, referer)
        headers = {**_headers(), "Range": "bytes=0-%d" % (sample_bytes - 1)}
        if referer:
            headers.setdefault("Referer", referer)
        req = urllib.request.Request(url, headers=headers)
        start = time.time()
        with urllib.request.urlopen(req, timeout=min(timeout, 8)) as resp:
            _register_response(resp, cancel_event)
            try:
                _set_read_timeout(resp, 5.0)
                read = 0
                while read < sample_bytes:
                    chunk = _read_with_cancel(resp, 256 * 1024, cancel_event)
                    if not chunk:
                        break
                    read += len(chunk)
            finally:
                _unregister_response(resp)
        elapsed = time.time() - start
        speed = read / elapsed if elapsed > 0 else 0
        return speed, total, supports_range, final_url, content_type
    except DownloadCancelled:
        raise
    except Exception:
        return 0, None, False, url, ""


def _guess_filename_from_final(url, content_type):
    """按最终跳转后的 URL 取文件名；没有后缀时按 Content-Type 补扩展名。"""
    import posixpath
    import urllib.parse

    path = urllib.parse.urlsplit(url).path
    name = posixpath.basename(path.rstrip("/")) or "download"
    if "?" in name:
        name = name.split("?", 1)[0]
    if not name:
        name = "download"

    # 已有后缀（含点且点后非空）则直接使用
    if "." in name:
        return name

    # 无后缀：按 Content-Type 补扩展名
    ext = _CONTENT_TYPE_EXT.get((content_type or "").split(";")[0].strip().lower(), "")
    return name + ext if ext else name
