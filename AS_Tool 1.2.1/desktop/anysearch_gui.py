# -*- coding: utf-8 -*-
"""AnySearch 图形工具（tkinter 标准库，无第三方依赖）

功能：
  - 搜索：输入关键词，调用 AnySearch 搜索并显示结果
  - 网页抓取：输入 URL，抓取完整正文（Markdown）
  - 文件下载：输入直链 URL，下载到本地目录

用法：
  python anysearch_gui.py

说明：
  - API Key 自动读取同目录 anysearch_api_key.txt，也可在窗口内修改并保存。
  - 网络请求在后台线程执行，窗口不会卡死。
  - 本工具仅供学习与合法用途使用；请勿抓取/下载违反版权、隐私或法律法规的内容。
"""
import sys

sys.dont_write_bytecode = True  # 不生成 __pycache__ 字节码缓存

import json
import queue
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk

import anysearch_asset_search as api
import anysearch_downloader
import anysearch_link_resolver as resolver

KEY_FILE = Path(__file__).with_name("anysearch_api_key.txt")
SETTINGS_FILE = Path(__file__).with_name("anysearch_settings.json")


class _StaleResolved(Exception):
    """上次解析出的直链已失效（签名过期等）：触发重新解析。"""


class _BlockedRetry(Exception):
    """直链下载被站点拒绝（403/429 等）：尝试用解析器（完整浏览器头）解析页面。"""


def _is_block_error(msg):
    msg = str(msg)
    return ("403" in msg or "429" in msg or "forbidden" in msg.lower()
            or "禁止" in msg or "拒绝" in msg)

DEFAULT_SETTINGS = {
    "max_display_len": 80000,   # 文本框最多显示字符数
    "chunk_size": 4000,         # 分块渲染每块字符数
    "api_timeout": 60,          # 搜索/抓取 API 超时（秒）
    "download_timeout": 60,     # 下载超时（秒）
    "default_threads": 8,       # 默认下载线程数
    "use_proxy": False,         # 是否使用代理
    "proxy_url": "",            # 代理地址，如 http://127.0.0.1:7890
    "custom_headers": "",       # 自定义请求头，每行一个：名称: 值
    "max_total_connections": 16,   # 批量下载总连接数上限（并行任务数 × 每任务线程数）
    "allow_break_limit": False,     # 是否允许突破总连接数上限（有风险）
    "auto_chunk": True,             # 下载分块：True 自动分块，False 用手动分块大小
    "chunk_size_mb": 4,             # 手动分块大小（MB，1~64）
    "mirrors": [],                  # 镜像模板列表（含 {url} 占位符），如 ["https://ghfast.top/{url}"]
    "mirror_strategy": "direct",    # direct 仅直连 / mirror_first 镜像优先 / auto_fallback 直连优先自动切换
    "mirror_speed_limit_kbps": 0,   # auto_fallback 低速切换阈值（KB/s，0=不因速度切换）
    "mirror_probe_conns": 3,        # 镜像测速并发连接数（默认 3）
    "resolve_links": True,          # 下载前自动解析链接（网页/短链/跳转页 → 真实直链）
    "resolve_auto_best": False,     # 解析出多个选项时自动选最佳（True 不弹窗）
}


def load_settings():
    try:
        if SETTINGS_FILE.exists():
            data = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
            merged = dict(DEFAULT_SETTINGS)
            merged.update(data)
            return merged
    except Exception:
        pass
    return dict(DEFAULT_SETTINGS)


def save_settings(settings):
    SETTINGS_FILE.write_text(
        json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    # 设置文件可能含代理地址/请求头等敏感信息：POSIX 收紧权限（Windows 下无副作用）
    try:
        import os
        os.chmod(SETTINGS_FILE, 0o600)
    except Exception:
        pass


class AnySearchGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("AnySearch 通用搜索工具")
        # 窗口尺寸自适应屏幕（不再写死），最小尺寸放宽以便小屏使用
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        self.root.geometry("%dx%d" % (min(860, int(sw * 0.92)), min(720, int(sh * 0.9))))
        self.root.minsize(560, 460)

        self.settings = load_settings()
        api.apply_proxy(self.settings.get("use_proxy", False), self.settings.get("proxy_url", ""))
        _headers = self._parse_headers(self.settings.get("custom_headers", ""))
        api.set_custom_headers(_headers)
        anysearch_downloader.set_custom_headers(_headers)
        self.max_display_len = int(self.settings.get("max_display_len", 80000))
        self.chunk_size = int(self.settings.get("chunk_size", 4000))

        self.msg_queue = queue.Queue()
        self.current_output = None
        self.cancel_event = None
        self._dl_state = "idle"   # idle / running / paused
        self._dl_resolved_urls = None  # 本次任务解析出的直链：暂停后续传复用（签名直链每次解析都不同）
        self._dl_referer = None        # 解析出直链的页面地址：下载时作为 Referer（抗防盗链）
        self._dl_abort = False    # 取消（删除文件）标记，区别于暂停
        self._dl_last = (0, 0.0)
        self._dl_progress_buf = (0, None)
        self._dl_progress_pending = False
        self._batch_progress_buf = {}
        self._batch_progress_pending = set()
        self._batch_progress_lock = threading.Lock()  # 保护批量进度缓冲（worker 写 / UI 线程读）
        self._full_texts = {"search": "", "extract": "", "download": ""}
        self.batch_stop_event = None
        self._batch_sem = None  # 批量并行信号量：初始任务与“继续”共用，保证不突破并行上限
        self._links_trees = {}
        self._links_data = {}

        self._build_ui()
        self._poll_queue()
        # 关窗前检查未保存的设置
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        # ---- 顶部：API Key ----
        key_frame = ttk.LabelFrame(self.root, text="API Key", padding=6)
        key_frame.pack(fill="x", padx=8, pady=(8, 4))

        self.key_var = tk.StringVar(value=self._load_key())
        ttk.Entry(key_frame, textvariable=self.key_var, width=70).pack(side="left", fill="x", expand=True)
        ttk.Button(key_frame, text="保存 Key", command=self._save_key).pack(side="left", padx=(8, 0))

        # ---- 中间：功能选项卡 ----
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill="both", expand=True, padx=8, pady=4)

        self._build_search_tab()
        self._build_extract_tab()
        self._build_download_tab()
        self._build_settings_tab()

        # ---- 底部：免责声明 + 状态栏 ----
        bottom = ttk.Frame(self.root, padding=6)
        bottom.pack(fill="x", padx=8, pady=(0, 8))
        ttk.Label(
            bottom,
            text="免责声明：本工具仅供学习与合法用途使用；请勿抓取/下载违反版权、隐私或法律法规的内容。",
            foreground="#888888",
            wraplength=780,
        ).pack(fill="x")
        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(bottom, textvariable=self.status_var, relief="sunken", anchor="w").pack(fill="x", pady=(4, 0))

    # ---------- 搜索页 ----------
    def _build_search_tab(self):
        tab = ttk.Frame(self.notebook, padding=8)
        self.notebook.add(tab, text="搜索")

        row = ttk.Frame(tab)
        row.pack(fill="x")
        ttk.Label(row, text="关键词：").pack(side="left")
        self.search_query = tk.StringVar()
        ttk.Entry(row, textvariable=self.search_query, width=50).pack(side="left", fill="x", expand=True)
        ttk.Label(row, text="条数：").pack(side="left", padx=(8, 0))
        self.search_max = tk.StringVar(value="5")
        ttk.Spinbox(row, from_=1, to=10, textvariable=self.search_max, width=4).pack(side="left")

        btn_row = ttk.Frame(tab)
        btn_row.pack(fill="x", pady=(6, 4))
        ttk.Button(btn_row, text="开始搜索", command=self._start_search).pack(side="left")
        ttk.Button(btn_row, text="保存结果文本", command=self._save_result_text).pack(side="left", padx=(8, 0))
        ttk.Button(btn_row, text="清空", command=self._clear_search).pack(side="left", padx=(8, 0))

        self.search_output = scrolledtext.ScrolledText(tab, height=20, wrap="word", font=("Microsoft YaHei", 9))
        self.search_output.pack(fill="both", expand=True)
        self.search_output.insert("1.0", "输入关键词后点击“开始搜索”。\n")
        self._build_links_area(tab, "search")

    # ---------- 网页抓取页 ----------
    def _build_extract_tab(self):
        tab = ttk.Frame(self.notebook, padding=8)
        self.notebook.add(tab, text="网页抓取")

        row = ttk.Frame(tab)
        row.pack(fill="x")
        ttk.Label(row, text="URL：").pack(side="left")
        self.extract_url = tk.StringVar()
        ttk.Entry(row, textvariable=self.extract_url, width=70).pack(side="left", fill="x", expand=True)

        btn_row = ttk.Frame(tab)
        btn_row.pack(fill="x", pady=(6, 4))
        ttk.Button(btn_row, text="开始抓取", command=self._start_extract).pack(side="left")
        ttk.Button(btn_row, text="提取链接", command=self._start_extract_links).pack(side="left", padx=(8, 0))
        ttk.Button(btn_row, text="保存结果 Markdown", command=self._save_extract_md).pack(side="left", padx=(8, 0))
        ttk.Button(btn_row, text="清空", command=self._clear_extract).pack(side="left", padx=(8, 0))

        self.extract_output = scrolledtext.ScrolledText(tab, height=20, wrap="word", font=("Microsoft YaHei", 9))
        self.extract_output.pack(fill="both", expand=True)
        self.extract_output.insert("1.0", "输入网页 URL 后点击“开始抓取”或“提取链接”。\n")
        self._build_links_area(tab, "extract")

    # ---------- 下载页 ----------
    def _build_download_tab(self):
        tab = ttk.Frame(self.notebook, padding=8)
        self.notebook.add(tab, text="文件下载")

        row1 = ttk.Frame(tab)
        row1.pack(fill="x")
        ttk.Label(row1, text="直链 URL：").pack(side="left")
        self.download_url = tk.StringVar()
        ttk.Entry(row1, textvariable=self.download_url, width=70).pack(side="left", fill="x", expand=True)

        row2 = ttk.Frame(tab)
        row2.pack(fill="x", pady=(6, 0))
        ttk.Label(row2, text="保存目录：").pack(side="left")
        self.download_dir = tk.StringVar(value=str((Path(__file__).parent / "downloads").resolve()))
        ttk.Entry(row2, textvariable=self.download_dir, width=50).pack(side="left", fill="x", expand=True)
        ttk.Button(row2, text="浏览…", command=self._choose_dir).pack(side="left", padx=(8, 0))

        row3 = ttk.Frame(tab)
        row3.pack(fill="x", pady=(6, 0))
        ttk.Label(row3, text="线程数：").pack(side="left")
        self.download_threads = tk.StringVar(value=str(self.settings.get("default_threads", 8)))
        ttk.Spinbox(row3, from_=1, to=16, textvariable=self.download_threads, width=3).pack(side="left")

        row3b = ttk.Frame(tab)
        row3b.pack(fill="x", pady=(6, 0))
        self.download_auto_chunk = tk.BooleanVar(value=bool(self.settings.get("auto_chunk", True)))
        ttk.Checkbutton(
            row3b,
            text="自动分块（按文件大小自动计算；取消勾选则使用设置里的手动分块大小）",
            variable=self.download_auto_chunk,
        ).pack(side="left")

        btn_row = ttk.Frame(tab)
        btn_row.pack(fill="x", pady=(6, 4))
        self.start_btn = ttk.Button(btn_row, text="开始下载", command=self._start_download)
        self.start_btn.pack(side="left")
        self.pause_btn = ttk.Button(btn_row, text="暂停", command=self._pause_download, state="disabled")
        self.pause_btn.pack(side="left", padx=(8, 0))
        self.abort_btn = ttk.Button(btn_row, text="取消", command=self._abort_download, state="disabled")
        self.abort_btn.pack(side="left", padx=(8, 0))
        self.download_progress = ttk.Progressbar(btn_row, length=220, mode="determinate")
        self.download_progress.pack(side="left", padx=(12, 8))

        # 进度文字独立一行：整行宽度自适应，长文本（含“剩余时间”）不再被截断遮挡
        label_row = ttk.Frame(tab)
        label_row.pack(fill="x", pady=(2, 0))
        self.download_progress_label = tk.StringVar(value="0%")
        ttk.Label(label_row, textvariable=self.download_progress_label, anchor="w").pack(fill="x")

        self.download_output = scrolledtext.ScrolledText(tab, height=8, wrap="word", font=("Microsoft YaHei", 9))
        self.download_output.pack(fill="x")
        self.download_output.insert(
            "1.0",
            "输入文件直链、网页或短链 URL 后点击“开始下载”。\n\n"
            "【支持】真实文件直链（CDN、官网、GitHub Release 等）：直接下载，不经过解析；\n"
            "        网页/短链/跳转页：检测到是网页时自动解析出直链与多清晰度选项，弹出列表供你选择；\n"
            "        需要人机验证的下载页：拉取验证码图片，输入后自动提交继续。\n"
            "【不支持】百度网盘、夸克网盘、阿里云盘等需要登录/客户端/JS 人机验证的链接；\n"
            "        纯 JS 渲染、页面里没有直链的网页（会提示未找到可下载内容）。\n",
        )

        # ---------- 批量下载入口 ----------
        batch_btn_row = ttk.Frame(tab)
        batch_btn_row.pack(fill="x", pady=(6, 0))
        ttk.Button(batch_btn_row, text="打开批量下载窗口", command=self._open_batch_window).pack(side="left")
        ttk.Label(
            batch_btn_row,
            text="批量下载建议：并行任务数 × 每任务线程数 = 总连接数，默认上限 16。\n大文件：1×16；多文件：2×8；带宽小/服务器易封：2×4。",
            foreground="#888888",
        ).pack(side="left", padx=(12, 0))

    # ---------- 设置页 ----------
    def _build_settings_tab(self):
        tab = ttk.Frame(self.notebook)
        self.notebook.add(tab, text="设置")

        # 可滚动容器：窗口较小时可用滚轮滚动查看全部设置（含底部镜像源等）
        canvas = tk.Canvas(tab, highlightthickness=0)
        scrollbar = ttk.Scrollbar(tab, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas, padding=10)
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        self._settings_window = canvas.create_window((0, 0), window=inner, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)

        def _sync_width(event):
            canvas.itemconfigure(self._settings_window, width=event.width)

        canvas.bind("<Configure>", _sync_width)

        def _on_wheel(event):
            if event.delta:
                canvas.yview_scroll(int(-event.delta / 120), "units")

        def _bind_wheel(_e):
            canvas.bind_all("<MouseWheel>", _on_wheel)

        def _unbind_wheel(_e):
            canvas.unbind_all("<MouseWheel>")

        canvas.bind("<Enter>", _bind_wheel)
        canvas.bind("<Leave>", _unbind_wheel)

        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # 后续控件全部放进滚动容器
        tab = inner

        ttk.Label(tab, text="显示与性能参数", font=("Microsoft YaHei", 11, "bold")).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 6))

        ttk.Label(tab, text="最大显示字符数（0 = 不限制）").grid(row=1, column=0, sticky="w", pady=3)
        self.set_max_display = tk.StringVar(value=str(self.settings.get("max_display_len", 80000)))
        ttk.Entry(tab, textvariable=self.set_max_display, width=20).grid(row=1, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="分块渲染大小（字符/块）").grid(row=2, column=0, sticky="w", pady=3)
        self.set_chunk_size = tk.StringVar(value=str(self.settings.get("chunk_size", 4000)))
        ttk.Entry(tab, textvariable=self.set_chunk_size, width=20).grid(row=2, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="API 超时（秒）").grid(row=3, column=0, sticky="w", pady=3)
        self.set_api_timeout = tk.StringVar(value=str(self.settings.get("api_timeout", 60)))
        ttk.Entry(tab, textvariable=self.set_api_timeout, width=20).grid(row=3, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="下载超时（秒）").grid(row=4, column=0, sticky="w", pady=3)
        self.set_download_timeout = tk.StringVar(value=str(self.settings.get("download_timeout", 60)))
        ttk.Entry(tab, textvariable=self.set_download_timeout, width=20).grid(row=4, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="默认下载线程数（1~16）").grid(row=5, column=0, sticky="w", pady=3)
        self.set_default_threads = tk.StringVar(value=str(self.settings.get("default_threads", 8)))
        ttk.Spinbox(tab, from_=1, to=16, textvariable=self.set_default_threads, width=20).grid(row=5, column=1, sticky="w", pady=3)

        ttk.Separator(tab).grid(row=6, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="代理服务器", font=("Microsoft YaHei", 11, "bold")).grid(row=7, column=0, columnspan=2, sticky="w")

        self.set_use_proxy = tk.BooleanVar(value=bool(self.settings.get("use_proxy", False)))
        ttk.Checkbutton(tab, text="使用代理服务器", variable=self.set_use_proxy).grid(row=8, column=0, columnspan=2, sticky="w", pady=3)

        ttk.Label(tab, text="代理地址").grid(row=9, column=0, sticky="w", pady=3)
        self.set_proxy_url = tk.StringVar(value=str(self.settings.get("proxy_url", "")))
        ttk.Entry(tab, textvariable=self.set_proxy_url, width=40).grid(row=9, column=1, sticky="w", pady=3)

        ttk.Separator(tab).grid(row=10, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="自定义请求头", font=("Microsoft YaHei", 11, "bold")).grid(row=11, column=0, columnspan=2, sticky="w")

        ttk.Label(tab, text="每行一个：名称: 值").grid(row=12, column=0, sticky="nw", pady=(4, 0))
        self.set_custom_headers = scrolledtext.ScrolledText(tab, width=46, height=5, font=("Microsoft YaHei", 9))
        self.set_custom_headers.grid(row=12, column=1, sticky="w", pady=(4, 0))
        self.set_custom_headers.insert("1.0", str(self.settings.get("custom_headers", "")))

        ttk.Label(
            tab,
            text="提示：最大显示字符数设 0 表示不限制（超大页面仍可能卡）；\n"
            "分块越小界面越流畅，但渲染稍慢。\n"
            "代理仅支持 HTTP/HTTPS 代理，例如 http://127.0.0.1:7890；SOCKS 代理暂不支持。\n"
            "自定义请求头会同时用于 API 与下载（可填 Referer、Accept-Language、你自己的 Cookie 等）；\n"
            "请只填写你有权使用的头，保存后会明文存到 settings.json。\n"
            "保存后立即对搜索/抓取/下载生效。",
            foreground="#888888",
            wraplength=600,
        ).grid(row=14, column=0, columnspan=2, sticky="w", pady=(6, 0))

        ttk.Separator(tab).grid(row=15, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="下载连接限制", font=("Microsoft YaHei", 11, "bold")).grid(row=16, column=0, columnspan=2, sticky="w")

        ttk.Label(tab, text="总连接数上限（并行任务数 × 每任务线程数）").grid(row=17, column=0, sticky="w", pady=3)
        self.set_max_connections = tk.StringVar(value=str(self.settings.get("max_total_connections", 16)))
        ttk.Spinbox(tab, from_=1, to=128, textvariable=self.set_max_connections, width=8).grid(row=17, column=1, sticky="w", pady=3)

        self.set_allow_break = tk.BooleanVar(value=bool(self.settings.get("allow_break_limit", False)))
        ttk.Checkbutton(tab, text="允许突破连接数上限（危险：可能封 IP、服务器拒绝、文件损坏）", variable=self.set_allow_break).grid(row=18, column=0, columnspan=2, sticky="w", pady=3)
        ttk.Label(
            tab,
            text="建议组合：大文件 1 任务 × 16 线程；多个文件 2 任务 × 8 线程；\n带宽小或服务器易封 2 任务 × 4 线程。总连接数不要超过你的网络和服务器的承受能力。",
            foreground="#888888",
            wraplength=600,
        ).grid(row=19, column=0, columnspan=2, sticky="w", pady=(6, 0))

        ttk.Separator(tab).grid(row=20, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="下载分块", font=("Microsoft YaHei", 11, "bold")).grid(row=21, column=0, columnspan=2, sticky="w")

        self.set_auto_chunk = tk.BooleanVar(value=bool(self.settings.get("auto_chunk", True)))
        ttk.Checkbutton(tab, text="默认自动分块",
                        variable=self.set_auto_chunk).grid(row=22, column=0, columnspan=2, sticky="w", pady=3)

        ttk.Label(
            tab,
            text="自动分块规则：块大小 = 文件大小 ÷ (线程数 × 4)，限制在 1MB~16MB 之间；\n"
            "文件 ≤1MB 或服务器不支持 Range 时自动单线程下载。\n"
            "手动分块时使用下方设定的固定块大小，与自动分块互斥。",
            foreground="#888888",
            wraplength=600,
        ).grid(row=23, column=0, columnspan=2, sticky="w", pady=(0, 4))

        ttk.Label(tab, text="手动分块大小（MB，1~64）").grid(row=24, column=0, sticky="w", pady=3)
        self.set_chunk_mb = tk.StringVar(value=str(self.settings.get("chunk_size_mb", 4)))
        ttk.Spinbox(tab, from_=1, to=64, textvariable=self.set_chunk_mb, width=8).grid(row=24, column=1, sticky="w", pady=3)

        ttk.Separator(tab).grid(row=25, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="下载断点缓存", font=("Microsoft YaHei", 11, "bold")).grid(row=26, column=0, columnspan=2, sticky="w")

        self.cache_size_var = tk.StringVar(value="（待计算）")
        ttk.Label(tab, textvariable=self.cache_size_var, foreground="#888888").grid(row=27, column=0, sticky="w", pady=3)
        ttk.Button(tab, text="清理断点缓存", command=self._clean_cache).grid(row=27, column=1, sticky="w", pady=3)
        self._refresh_cache_size()

        ttk.Separator(tab).grid(row=28, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="镜像源", font=("Microsoft YaHei", 11, "bold")).grid(row=29, column=0, columnspan=2, sticky="w")

        ttk.Label(tab, text="镜像策略").grid(row=30, column=0, sticky="w", pady=3)
        self._mirror_strategy_names = {
            "仅直连（不使用镜像）": "direct",
            "镜像优先（下载立即开始，后台测速择快切换）": "mirror_first",
            "直连优先（过慢自动切换镜像）": "auto_fallback",
        }
        cur_strategy = self.settings.get("mirror_strategy", "direct")
        cur_name = "仅直连（不使用镜像）"
        for name, val in self._mirror_strategy_names.items():
            if val == cur_strategy:
                cur_name = name
        self.set_mirror_strategy = ttk.Combobox(tab, state="readonly", width=32,
                                                values=list(self._mirror_strategy_names.keys()))
        self.set_mirror_strategy.set(cur_name)
        self.set_mirror_strategy.grid(row=30, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="低速切换阈值（KB/s，0=不切换）").grid(row=31, column=0, sticky="w", pady=3)
        self.set_mirror_speed = tk.StringVar(value=str(self.settings.get("mirror_speed_limit_kbps", 0)))
        ttk.Entry(tab, textvariable=self.set_mirror_speed, width=20).grid(row=31, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="镜像测速并发数（1~8，默认 3）").grid(row=32, column=0, sticky="w", pady=3)
        self.set_mirror_probe = tk.StringVar(value=str(self.settings.get("mirror_probe_conns", 3)))
        ttk.Spinbox(tab, from_=1, to=8, textvariable=self.set_mirror_probe, width=8).grid(row=32, column=1, sticky="w", pady=3)

        ttk.Label(tab, text="镜像模板（每行一个，含 {url} 占位符）").grid(row=33, column=0, sticky="nw", pady=(4, 0))
        self.set_mirrors = scrolledtext.ScrolledText(tab, width=46, height=4, font=("Microsoft YaHei", 9))
        self.set_mirrors.grid(row=33, column=1, sticky="w", pady=(4, 0))
        self.set_mirrors.insert("1.0", "\n".join(self.settings.get("mirrors") or []))

        ttk.Label(
            tab,
            text="示例（GitHub 加速，第三方服务请自行甄别）：\n"
            "  https://ghfast.top/{url}\n"
            "  https://gh-proxy.com/{url}\n"
            "镜像同样支持断点续传；内容与直连不一致的源会被自动跳过。\n"
            "“镜像优先”会在下载中测速全部镜像并选最快的；“直连优先”按列表顺序切换，"
            "建议把更快的镜像排在前面。",
            foreground="#888888",
            wraplength=600,
        ).grid(row=34, column=0, columnspan=2, sticky="w", pady=(6, 0))

        ttk.Separator(tab).grid(row=35, column=0, columnspan=2, sticky="we", pady=6)
        ttk.Label(tab, text="链接解析", font=("Microsoft YaHei", 11, "bold")).grid(row=36, column=0, columnspan=2, sticky="w")

        self.set_resolve_links = tk.BooleanVar(value=bool(self.settings.get("resolve_links", True)))
        ttk.Checkbutton(tab, text="下载到网页时自动解析真实直链（下载选项/多清晰度/验证码）",
                        variable=self.set_resolve_links).grid(row=37, column=0, columnspan=2, sticky="w", pady=3)
        self.set_resolve_auto_best = tk.BooleanVar(value=bool(self.settings.get("resolve_auto_best", False)))
        ttk.Checkbutton(tab, text="解析出多个下载选项时自动选择最佳（不弹窗询问）",
                        variable=self.set_resolve_auto_best).grid(row=38, column=0, columnspan=2, sticky="w", pady=3)
        ttk.Label(
            tab,
            text="直链直接下载，不经过解析；只有下载目标返回网页（假直链）时才自动解析：\n"
            "跟随 HTTP/meta/JS 跳转、提取页面里的直链（多清晰度图片、zip/7z/apk 等），\n"
            "并识别需要人机验证的下载页（弹出验证码输入框，提交后继续）。",
            foreground="#888888",
            wraplength=600,
        ).grid(row=39, column=0, columnspan=2, sticky="w", pady=(6, 0))

        # 保存设置按钮固定在页面最底部
        ttk.Button(tab, text="保存设置", command=self._save_settings).grid(
            row=40, column=0, columnspan=2, sticky="w", pady=(14, 4))

    def _refresh_cache_size(self):
        try:
            out_dir = self.download_dir.get().strip() or "downloads"
            size = anysearch_downloader._cleanup_cache_estimate(out_dir)
            self.cache_size_var.set("断点缓存占用：%s" % _fmt(size))
        except Exception:
            self.cache_size_var.set("断点缓存占用：无法统计")

    def _clean_cache(self):
        out_dir = self.download_dir.get().strip() or "downloads"
        freed = anysearch_downloader.cleanup_cache(out_dir)
        self._refresh_cache_size()
        messagebox.showinfo("完成", "已清理断点缓存，释放 %s。" % _fmt(freed))

    def _save_settings(self):
        try:
            self.settings["max_display_len"] = max(0, int(self.set_max_display.get()))
            self.settings["chunk_size"] = max(100, int(self.set_chunk_size.get()))
            self.settings["api_timeout"] = max(5, int(self.set_api_timeout.get()))
            self.settings["download_timeout"] = max(5, int(self.set_download_timeout.get()))
            threads = min(16, max(1, int(self.set_default_threads.get())))
            self.settings["default_threads"] = threads
            self.settings["use_proxy"] = bool(self.set_use_proxy.get())
            self.settings["proxy_url"] = self.set_proxy_url.get().strip()
            self.settings["custom_headers"] = self.set_custom_headers.get("1.0", "end").strip()
            custom_headers = self._parse_headers(self.settings["custom_headers"])
            self.settings["max_total_connections"] = max(1, int(self.set_max_connections.get()))
            self.settings["allow_break_limit"] = bool(self.set_allow_break.get())
            self.settings["auto_chunk"] = bool(self.set_auto_chunk.get())
            chunk_mb = min(64, max(1, int(self.set_chunk_mb.get())))
            self.settings["chunk_size_mb"] = chunk_mb
            mirrors = [line.strip() for line in self.set_mirrors.get("1.0", "end").splitlines()
                       if line.strip() and "{" in line]
            self.settings["mirrors"] = mirrors
            self.settings["mirror_strategy"] = self._mirror_strategy_names.get(
                self.set_mirror_strategy.get(), "direct")
            self.settings["mirror_speed_limit_kbps"] = max(0, int(self.set_mirror_speed.get()))
            self.settings["mirror_probe_conns"] = min(8, max(1, int(self.set_mirror_probe.get())))
            self.settings["resolve_links"] = bool(self.set_resolve_links.get())
            self.settings["resolve_auto_best"] = bool(self.set_resolve_auto_best.get())
        except ValueError:
            messagebox.showwarning("提示", "请输入有效数值。")
            return False
        save_settings(self.settings)
        api.apply_proxy(self.settings["use_proxy"], self.settings["proxy_url"])
        api.set_custom_headers(custom_headers)
        anysearch_downloader.set_custom_headers(custom_headers)
        self.max_display_len = self.settings["max_display_len"]
        self.chunk_size = self.settings["chunk_size"]
        self.download_threads.set(str(threads))
        messagebox.showinfo("成功", f"设置已保存：{SETTINGS_FILE}")
        return True

    def _settings_changed(self):
        """比较当前控件值与上次保存的设置，判断是否有未保存的修改。"""
        try:
            if str(self.set_max_display.get()) != str(self.settings.get("max_display_len", 80000)):
                return True
            if str(self.set_chunk_size.get()) != str(self.settings.get("chunk_size", 4000)):
                return True
            if str(self.set_api_timeout.get()) != str(self.settings.get("api_timeout", 60)):
                return True
            if str(self.set_download_timeout.get()) != str(self.settings.get("download_timeout", 60)):
                return True
            if str(self.set_default_threads.get()) != str(self.settings.get("default_threads", 8)):
                return True
            if bool(self.set_use_proxy.get()) != bool(self.settings.get("use_proxy", False)):
                return True
            if self.set_proxy_url.get().strip() != str(self.settings.get("proxy_url", "")).strip():
                return True
            if self.set_custom_headers.get("1.0", "end").strip() != str(self.settings.get("custom_headers", "")).strip():
                return True
            if str(self.set_max_connections.get()) != str(self.settings.get("max_total_connections", 16)):
                return True
            if bool(self.set_allow_break.get()) != bool(self.settings.get("allow_break_limit", False)):
                return True
            if bool(self.set_auto_chunk.get()) != bool(self.settings.get("auto_chunk", True)):
                return True
            if str(self.set_chunk_mb.get()) != str(self.settings.get("chunk_size_mb", 4)):
                return True
            if self._mirror_strategy_names.get(self.set_mirror_strategy.get(), "direct") \
                    != self.settings.get("mirror_strategy", "direct"):
                return True
            if str(self.set_mirror_speed.get()) != str(self.settings.get("mirror_speed_limit_kbps", 0)):
                return True
            if str(self.set_mirror_probe.get()) != str(self.settings.get("mirror_probe_conns", 3)):
                return True
            if bool(self.set_resolve_links.get()) != bool(self.settings.get("resolve_links", True)):
                return True
            if bool(self.set_resolve_auto_best.get()) != bool(self.settings.get("resolve_auto_best", False)):
                return True
            mirrors_now = [line.strip() for line in self.set_mirrors.get("1.0", "end").splitlines()
                           if line.strip() and "{" in line]
            if mirrors_now != (self.settings.get("mirrors") or []):
                return True
        except Exception:
            return True
        return False

    def _on_close(self):
        """关闭窗口前：先优雅停止下载（立即中断连接、保留断点），再检查未保存的设置。"""
        # 立即停止进行中的下载：断点落盘由 worker 的 finally 路径完成
        try:
            if self._dl_state != "idle" and self.cancel_event is not None:
                self.cancel_event.set()
                anysearch_downloader.cancel_active_downloads(self.cancel_event)
            for row in getattr(self, "_batch_rows", {}).values():
                row.get("cancel_event") and row["cancel_event"].set()
            if getattr(self, "_batch_rows", None):
                anysearch_downloader.cancel_active_downloads()
        except Exception:
            pass
        # 给 worker 至多 1.5 秒收尾（保存断点元数据），期间保持界面响应
        deadline = time.time() + 1.5
        try:
            while time.time() < deadline and self._dl_state != "idle":
                self.root.update()
                time.sleep(0.02)
        except Exception:
            pass
        if not self._settings_changed():
            self.root.destroy()
            return
        win = tk.Toplevel(self.root)
        win.title("提示")
        win.resizable(False, False)
        win.transient(self.root)
        win.grab_set()
        try:
            x = self.root.winfo_rootx() + 80
            y = self.root.winfo_rooty() + 80
            win.geometry("+%d+%d" % (x, y))
        except Exception:
            pass
        ttk.Label(win, text="设置未保存，是否关闭？", padding=(20, 14)).pack()
        btns = ttk.Frame(win, padding=(8, 4, 8, 10))
        btns.pack()
        ttk.Button(btns, text="是", width=10,
                   command=lambda: (win.destroy(), self.root.destroy())).pack(side="left", padx=6)
        ttk.Button(btns, text="保存设置", width=10,
                   command=lambda: self._save_settings_and_close(win)).pack(side="left", padx=6)

    def _save_settings_and_close(self, win):
        if self._save_settings():
            win.destroy()
            self.root.destroy()

    @staticmethod
    def _parse_headers(text):
        """把多行文本 '名称: 值' 解析成 dict；非法头（名称含非法字符、值含换行）跳过。"""
        import re as _re
        result = {}
        for line in (text or "").splitlines():
            line = line.strip()
            if not line:
                continue
            if ":" in line:
                name, value = line.split(":", 1)
                name = name.strip()
                value = value.strip()
                # 头部名称只允许字母/数字/连字符；值中不允许 CR/LF（防请求头注入）
                if _re.match(r"^[A-Za-z0-9-]+$", name) and "\r" not in value and "\n" not in value:
                    result[name] = value
        return result

    # ---------- 工具方法 ----------
    def _load_key(self):
        try:
            if KEY_FILE.exists():
                return KEY_FILE.read_text(encoding="utf-8").strip()
        except Exception:
            pass
        return ""

    def _save_key(self):
        try:
            KEY_FILE.write_text(self.key_var.get().strip(), encoding="utf-8")
            messagebox.showinfo("成功", f"Key 已保存到：{KEY_FILE}")
        except Exception as e:
            messagebox.showerror("失败", str(e))

    def _current_output(self, tab_name):
        mapping = {"search": self.search_output, "extract": self.extract_output, "download": self.download_output}
        return mapping[tab_name]

    def _set_status(self, text):
        self.status_var.set(text)

    # ---------- 链接列表（一键复制） ----------
    def _build_links_area(self, parent, tab_name):
        """在页面底部构建“链接列表”区：双击复制单条、一键复制全部（不影响主文本区批量复制）。"""
        frame = ttk.LabelFrame(parent, text="链接列表", padding=4)
        frame.pack(fill="x", pady=(6, 0))

        tree = ttk.Treeview(frame, columns=("idx", "url"), show="headings", height=6)
        tree.heading("idx", text="#")
        tree.heading("url", text="URL")
        tree.column("idx", width=40, anchor="center", stretch=False)
        tree.column("url", width=640, anchor="w")
        scroll = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scroll.set)
        tree.pack(side="left", fill="x", expand=True)
        scroll.pack(side="left", fill="y")

        # 按钮与提示纵向排列（右侧栏，不横排）
        side = ttk.Frame(frame)
        side.pack(side="right", fill="y", padx=(6, 0))
        ttk.Button(side, text="复制全部", command=lambda t=tab_name: self._copy_all_links(t)).pack(fill="x")
        ttk.Button(side, text="清空列表", command=lambda t=tab_name: self._clear_links(t)).pack(fill="x", pady=(4, 0))
        ttk.Label(
            side,
            text="    双击复制",
            foreground="#888888",
            justify="center",
        ).pack(fill="x", pady=(6, 0))

        tree.bind("<Double-1>", lambda e, t=tab_name: self._copy_selected_link(t))
        self._links_trees[tab_name] = tree
        self._links_data[tab_name] = []

    def _set_links_list(self, tab_name, text):
        tree = self._links_trees.get(tab_name)
        if tree is None:
            return
        tree.delete(*tree.get_children())
        links = api.extract_links(text or "")
        self._links_data[tab_name] = links
        for i, link in enumerate(links, 1):
            tree.insert("", "end", values=(i, link))

    def _copy_to_clipboard(self, text):
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
            self.root.update()
            return True
        except Exception:
            return False

    def _copy_all_links(self, tab_name):
        links = self._links_data.get(tab_name) or []
        if not links:
            messagebox.showwarning("提示", "当前没有可复制的链接。")
            return
        if self._copy_to_clipboard("\n".join(links)):
            self._set_status("已复制 %d 条链接" % len(links))
        else:
            messagebox.showwarning("提示", "复制到剪贴板失败。")

    def _copy_selected_link(self, tab_name):
        tree = self._links_trees.get(tab_name)
        if tree is None:
            return
        sel = tree.selection()
        if not sel:
            messagebox.showwarning("提示", "请先选中一条链接。")
            return
        link = tree.item(sel[0], "values")[1]
        if self._copy_to_clipboard(link):
            self._set_status("已复制：%s" % link)
        else:
            messagebox.showwarning("提示", "复制到剪贴板失败。")

    def _clear_links(self, tab_name):
        tree = self._links_trees.get(tab_name)
        if tree is not None:
            tree.delete(*tree.get_children())
        self._links_data[tab_name] = []

    def _show_text(self, tab, text):
        """把长文本分块显示到指定页，并保留完整文本供保存。

        解决整页一次性插入文本框导致的卡顿。
        """
        self._full_texts[tab] = text or ""
        self.msg_queue.put(("clear", tab))
        if not text:
            return
        display = text
        limit = self.max_display_len
        if limit and len(text) > limit:
            display = text[:limit]
            self.msg_queue.put(
                (
                    "append",
                    (
                        tab,
                        "[内容过长，以下仅显示前 %d 字符；完整内容可用“保存”功能保存到文件。]\n\n"
                        % limit,
                    ),
                )
            )
        chunk = self.chunk_size or 4000
        for i in range(0, len(display), chunk):
            self.msg_queue.put(("append", (tab, display[i : i + chunk])))
        self.msg_queue.put(("append", (tab, "\n")))

    def _poll_queue(self):
        # 保护壳：任何单次处理的异常都不应中断 after 链（否则 UI 消息泵永久停摆）
        try:
            self._poll_queue_tick()
        except Exception:
            pass
        self.root.after(100, self._poll_queue)

    def _poll_queue_tick(self):
        # 高频进度走缓冲合并（每个 UI 周期只取最新值），避免队列积压拖慢按钮/状态响应
        if self._dl_progress_pending:
            self._dl_progress_pending = False
            self._update_download_progress(*self._dl_progress_buf)
        if self._batch_progress_pending:
            with self._batch_progress_lock:
                for iid in list(self._batch_progress_pending):
                    self._batch_progress_pending.discard(iid)
                    buf = self._batch_progress_buf.get(iid)
                    if buf is not None:
                        done, total = buf
                        if total:
                            pct = min(100.0, done * 100.0 / total)
                            self._update_batch_row(iid, pct="%.1f%%" % pct)
                        else:
                            self._update_batch_row(iid, pct=_fmt(done))
        # 控制消息全部处理（上限 256 防极端洪峰）；append 为有界分块，无独立限制
        processed = 0
        try:
            while processed < 256:
                kind, payload = self.msg_queue.get_nowait()
                processed += 1
                if kind == "append":
                    tab, text = payload
                    self._current_output(tab).insert("end", text)
                    self._current_output(tab).see("end")
                elif kind == "clear":
                    self._current_output(payload).delete("1.0", "end")
                elif kind == "status":
                    self._set_status(payload)
                elif kind == "mode":
                    self._current_output("download").insert("end", payload + "\n")
                    self._current_output("download").see("end")
                elif kind == "batch_status":
                    iid, status, extra = payload
                    self._update_batch_row(iid, status=status, extra=extra)
                elif kind == "batch_speed":
                    iid, speed = payload
                    self._update_batch_row(iid, speed="%s/s" % _fmt(speed))
                elif kind == "dl_state":
                    self._apply_dl_state(payload)
                elif kind == "dl_label":
                    self.download_progress_label.set(payload)
                elif kind == "dl_progress_reset":
                    self.download_progress["value"] = 0
                    self.download_progress_label.set(payload)
                elif kind == "links":
                    tab, text = payload
                    self._set_links_list(tab, text)
                elif kind == "captcha":
                    challenge, box = payload
                    self._show_captcha_dialog(challenge, box)
                elif kind == "resolve_options":
                    rr, box = payload
                    self._show_options_window(rr, box)
                elif kind == "resolve_note":
                    self._current_output("download").insert("end", payload + "\n")
                    self._current_output("download").see("end")
                    self._set_status(payload)
                elif kind == "error":
                    messagebox.showerror("错误", payload)
                    self._set_status("出错")
                elif kind == "done":
                    self._set_status(payload)
                    self.download_progress["value"] = 100
                    self.download_progress_label.set("100%")
        except queue.Empty:
            pass

    def _run_thread(self, target):
        threading.Thread(target=target, daemon=True).start()

    # ---------- 搜索逻辑 ----------
    def _start_search(self):
        query = self.search_query.get().strip()
        if not query:
            messagebox.showwarning("提示", "请输入搜索关键词。")
            return
        try:
            max_results = int(self.search_max.get())
            if not 1 <= max_results <= 10:
                raise ValueError
        except ValueError:
            messagebox.showwarning("提示", "条数必须是 1~10 的整数。")
            return
        self._run_thread(lambda: self._search_worker(query, max_results))

    def _search_worker(self, query, max_results):
        self.msg_queue.put(("status", f"正在搜索：{query} ..."))
        try:
            key = self.key_var.get().strip()
            obj, _ = api.call(key, "search", {"query": query, "max_results": max_results}, timeout=self.settings.get("api_timeout", 60))
            if obj is None:
                self._show_text("search", "[响应不是 JSON]\n")
            elif obj.get("error"):
                self.msg_queue.put(("error", str(obj.get("error"))))
            else:
                text = api.extract_result_text(obj) or json.dumps(obj, ensure_ascii=False, indent=2)
                self._show_text("search", text + "\n")
                self.msg_queue.put(("links", ("search", text)))
            self.msg_queue.put(("done", "搜索完成"))
        except Exception as e:
            self.msg_queue.put(("error", str(e)))

    def _save_result_text(self):
        content = self._full_texts.get("search") or self.search_output.get("1.0", "end").strip()
        if not content:
            messagebox.showwarning("提示", "当前没有可保存的搜索结果。")
            return
        path = filedialog.asksaveasfilename(defaultextension=".txt", filetypes=[("文本文件", "*.txt"), ("所有文件", "*.*")])
        if path:
            try:
                Path(path).write_text(content, encoding="utf-8")
                messagebox.showinfo("成功", f"已保存：{path}")
            except Exception as e:
                messagebox.showerror("失败", str(e))

    def _clear_search(self):
        self.search_output.delete("1.0", "end")
        self._full_texts["search"] = ""  # 同步清空缓存，避免“保存”存到旧内容
        self._clear_links("search")

    # ---------- 抓取逻辑 ----------
    def _start_extract(self):
        url = self.extract_url.get().strip()
        if not url:
            messagebox.showwarning("提示", "请输入网页 URL。")
            return
        self._run_thread(lambda: self._extract_worker(url))

    def _start_extract_links(self):
        url = self.extract_url.get().strip()
        if not url:
            messagebox.showwarning("提示", "请输入网页 URL。")
            return
        self._run_thread(lambda: self._extract_links_worker(url))

    def _extract_links_worker(self, url):
        self.msg_queue.put(("clear", "extract"))
        self.msg_queue.put(("status", f"正在抓取并提取链接：{url} ..."))
        try:
            key = self.key_var.get().strip()
            obj, _ = api.call(key, "extract", {"url": url}, timeout=self.settings.get("api_timeout", 60))
            if obj is None:
                self.msg_queue.put(("append", ("extract", "[响应不是 JSON]\n")))
            elif obj.get("error"):
                self.msg_queue.put(("error", str(obj.get("error"))))
            else:
                text = api.extract_result_text(obj) or ""
                links = api.extract_links(text)
                self.msg_queue.put(("append", ("extract", "共提取到 %d 个链接：\n\n" % len(links))))
                for i, link in enumerate(links, 1):
                    self.msg_queue.put(("append", ("extract", "%d. %s\n" % (i, link))))
                self.msg_queue.put(("links", ("extract", text)))
            self.msg_queue.put(("done", "链接提取完成"))
        except Exception as e:
            self.msg_queue.put(("error", str(e)))

    def _extract_worker(self, url):
        self.msg_queue.put(("clear", "extract"))
        self.msg_queue.put(("status", f"正在抓取：{url} ..."))
        try:
            key = self.key_var.get().strip()
            obj, _ = api.call(key, "extract", {"url": url}, timeout=self.settings.get("api_timeout", 60))
            if obj is None:
                self._show_text("extract", "[响应不是 JSON]\n")
            elif obj.get("error"):
                self.msg_queue.put(("error", str(obj.get("error"))))
            else:
                text = api.extract_result_text(obj) or json.dumps(obj, ensure_ascii=False, indent=2)
                self._show_text("extract", text + "\n")
                self.msg_queue.put(("links", ("extract", text)))
            self.msg_queue.put(("done", "抓取完成"))
        except Exception as e:
            self.msg_queue.put(("error", str(e)))

    def _save_extract_md(self):
        content = self._full_texts.get("extract") or self.extract_output.get("1.0", "end").strip()
        if not content:
            messagebox.showwarning("提示", "当前没有可保存的抓取内容。")
            return
        path = filedialog.asksaveasfilename(defaultextension=".md", filetypes=[("Markdown", "*.md"), ("文本文件", "*.txt"), ("所有文件", "*.*")])
        if path:
            try:
                Path(path).write_text(content, encoding="utf-8")
                messagebox.showinfo("成功", f"已保存：{path}")
            except Exception as e:
                messagebox.showerror("失败", str(e))

    def _clear_extract(self):
        self.extract_output.delete("1.0", "end")
        self._full_texts["extract"] = ""  # 同步清空缓存，避免“保存”存到旧内容
        self._clear_links("extract")

    # ---------- 下载逻辑 ----------
    def _choose_dir(self):
        d = filedialog.askdirectory(initialdir=self.download_dir.get())
        if d:
            self.download_dir.set(d)

    def _apply_dl_state(self, state):
        """按下载状态切换 开始/继续、暂停、取消 三个按钮。"""
        self._dl_state = state
        if state == "running":
            self.start_btn.config(text="开始下载", state="disabled")
            self.pause_btn.config(text="暂停", state="normal")
            self.abort_btn.config(text="取消", state="normal")
        elif state == "paused":
            self.start_btn.config(text="继续下载", state="normal")
            self.pause_btn.config(state="disabled")
            self.abort_btn.config(text="取消", state="normal")
        else:
            self.start_btn.config(text="开始下载", state="normal")
            self.pause_btn.config(state="disabled")
            self.abort_btn.config(state="disabled")

    def _start_download(self):
        url = self.download_url.get().strip()
        out_dir = self.download_dir.get().strip()
        if not url:
            messagebox.showwarning("提示", "请输入 URL（支持文件直链、网页、短链）。")
            return
        if not out_dir:
            out_dir = "downloads"
        try:
            threads = int(self.download_threads.get())
            if threads < 1:
                threads = 1
        except ValueError:
            threads = 4

        self._dl_abort = False
        self.cancel_event = threading.Event()
        self._dl_last = (0, time.time())
        self.download_progress["value"] = 0
        self.download_progress_label.set("0%")
        self.msg_queue.put(("dl_state", "running"))
        auto_chunk = bool(self.download_auto_chunk.get())
        chunk_size = int(self.settings.get("chunk_size_mb", 4)) * 1024 * 1024
        # 暂停后“继续下载”：优先复用上次解析出的直链（签名直链每次解析都不同，重新解析会丢断点）
        chosen = None
        referer = None
        if self._dl_state == "paused" and self._dl_resolved_urls:
            chosen = list(self._dl_resolved_urls)
            referer = self._dl_referer
        self._run_thread(lambda: self._download_worker(url, out_dir, threads, self.cancel_event,
                                                       auto_chunk, chunk_size, chosen, referer))

    def _pause_download(self):
        """暂停：立即中断网络并保留断点，按钮立即切换为“继续下载”（不等 worker 收敛）。"""
        if self.cancel_event is not None:
            self.cancel_event.set()
            # 关闭 socket 放到后台线程：避免 UI 线程被逐个 close 阻塞造成卡顿；
            # 只关闭本任务的连接（按 cancel_event 隔离），不误伤批量窗口的其它任务
            self._run_thread(
                lambda: anysearch_downloader.cancel_active_downloads(self.cancel_event))
            # 立即反馈：按钮状态先行切换，worker 收敛后的消息为幂等确认
            self._apply_dl_state("paused")
            self.download_progress_label.set("已暂停")
            self._set_status("已暂停（断点保留，点击“继续下载”续传）")

    def _abort_download(self):
        """取消：立即中断并删除未完成文件与断点；按钮立即禁用，worker 收敛后恢复。

        暂停状态下没有活动 worker：在此直接收尾（否则永远停在“取消中…”）。
        """
        self._dl_abort = True
        if self.cancel_event is not None:
            self.cancel_event.set()
            # 关闭 socket 放到后台线程：避免 UI 线程被逐个 close 阻塞造成卡顿；
            # 只关闭本任务的连接，不误伤批量窗口的其它任务
            self._run_thread(
                lambda: anysearch_downloader.cancel_active_downloads(self.cancel_event))
        if self._dl_state == "paused":
            self._finish_abort_now()
            return
        # 立即反馈：不等 worker 退出
        self.start_btn.config(state="disabled")
        self.pause_btn.config(state="disabled")
        self.abort_btn.config(state="disabled")
        self.download_progress_label.set("取消中…")
        self._set_status("正在取消…（将删除已下载内容）")

    def _finish_abort_now(self):
        """暂停态取消：worker 已退出，直接丢弃断点并恢复界面（幂等）。"""
        out_dir = self.download_dir.get().strip() or "downloads"
        urls = list(self._dl_resolved_urls or []) + [self.download_url.get().strip()]
        for u in urls:
            if u:
                try:
                    anysearch_downloader.discard_download(url=u, out_dir=out_dir)
                except Exception:
                    pass
        self._dl_resolved_urls = None
        self._dl_referer = None
        self.msg_queue.put(("append", ("download", "已取消下载，未完成的文件与断点已删除。\n")))
        self.msg_queue.put(("dl_progress_reset", "已取消"))
        self.msg_queue.put(("status", "已取消"))
        self.msg_queue.put(("dl_state", "idle"))

    def _download_worker(self, url, out_dir, threads, cancel_event, auto_chunk, chunk_size,
                         chosen_urls=None, referer=None):
        self.msg_queue.put(("clear", "download"))
        self.msg_queue.put(("status", f"正在下载：{url} ..."))

        def progress(done, total):
            # 高频进度走缓冲合并（UI 线程每周期取最新值），不刷队列
            self._dl_progress_buf = (done, total)
            self._dl_progress_pending = True

        def mode(mode_name, thread_count, total):
            if mode_name == "multithread":
                text = "服务器支持分块下载，正在使用 %d 线程多线程下载。" % thread_count
            elif mode_name == "resume":
                text = "检测到未完成下载，从断点续传。"
            elif mode_name.startswith("source_fast_switch:"):
                host = mode_name.split(":", 1)[1]
                text = "测速发现更快的镜像源（%s），已切换下载。" % host
            elif mode_name == "source_switch":
                text = "直连不可用或过慢，已切换到镜像源继续下载…"
            elif mode_name == "source_fail":
                text = "镜像源不可用（404/内容不一致），已跳过。"
            else:
                text = "服务器不支持分块下载（或文件太小），已回退单线程下载。"
            self.msg_queue.put(("mode", text))

        def finish(state):
            self.msg_queue.put(("dl_state", state))

        def cancel_out():
            # 取消/暂停收尾（解析阶段与下载阶段共用）
            if self._dl_abort:
                self._dl_resolved_urls = None
                self.msg_queue.put(("append", ("download", "已取消下载，未完成的文件与断点已删除。\n")))
                self.msg_queue.put(("status", "已取消"))
                self.msg_queue.put(("dl_progress_reset", "已取消"))
                finish("idle")
            else:
                self.msg_queue.put(("append", ("download", "已暂停（断点已保留，点击“继续下载”从断点续传）。\n")))
                self.msg_queue.put(("status", "已暂停"))
                self.msg_queue.put(("dl_label", "已暂停"))
                finish("paused")

        def run_targets(target_urls, stale_recover=False, resolved_attempt=False, referer=None):
            """依次下载多个 URL；全部完成返回 True；网页错误/失效直链/被拒直链向上抛；其余就地收尾返回 False。"""
            for target in target_urls:
                if cancel_event.is_set():
                    # 取消发生在真正开始下载之前：同样要收尾并恢复按钮状态，不能静默退出
                    cancel_out()
                    return False
                try:
                    path = anysearch_downloader.download_file(
                        target,
                        out_dir,
                        num_threads=threads,
                        timeout=self.settings.get("download_timeout", 60),
                        progress_callback=progress,
                        cancel_event=cancel_event,
                        mode_callback=mode,
                        auto_chunk=auto_chunk,
                        chunk_size=chunk_size,
                        mirrors=self.settings.get("mirrors") or [],
                        mirror_strategy=self.settings.get("mirror_strategy", "direct"),
                        mirror_speed_limit=int(self.settings.get("mirror_speed_limit_kbps", 0)) * 1024,
                        mirror_probe_conns=int(self.settings.get("mirror_probe_conns", 3)),
                        referer=referer,
                    )
                    self.msg_queue.put(("append", ("download", f"下载完成：{path}\n")))
                except anysearch_downloader.HtmlPageError:
                    raise
                except anysearch_downloader.DownloadCancelled:
                    if self._dl_abort:
                        anysearch_downloader.discard_download(url=target, out_dir=out_dir)
                    cancel_out()
                    return False
                except anysearch_downloader.DownloadPaused as e:
                    self.msg_queue.put(("append", ("download", "%s\n" % e)))
                    self.msg_queue.put(("status", "已暂停（点击“继续下载”从断点续传）"))
                    self.msg_queue.put(("dl_label", "已暂停"))
                    finish("paused")
                    return False
                except Exception as e:
                    if stale_recover:
                        # 上次解析出的直链失效（签名过期等）：自动重新解析
                        self.msg_queue.put(("append", ("download",
                                                       "上次解析的直链已失效（%s），正在重新解析…\n" % e)))
                        raise _StaleResolved()
                    if (not resolved_attempt
                            and self.settings.get("resolve_links", True)
                            and _is_block_error(str(e))):
                        # 直链下载被站点拒绝：解析器用完整浏览器头可能能拿到页面
                        self.msg_queue.put(("append", ("download",
                                                       "直连被拒绝（%s），尝试解析页面获取真实直链…\n" % e)))
                        raise _BlockedRetry()
                    if resolved_attempt:
                        self.msg_queue.put(("append", ("download",
                                                       "%s\n该直链可能绑定浏览器会话或已失效："
                                                       "请用浏览器打开页面，点击下载并复制链接后重试。\n" % e)))
                    self.msg_queue.put(("error", str(e)))
                    finish("idle")
                    return False
            return True

        # ---------- 直链优先：直接下载；只有返回网页（假直链）或被拒绝才启用解析 ----------
        target_urls = chosen_urls if chosen_urls is not None else [url]
        stale_recover = chosen_urls is not None and self.settings.get("resolve_links", True)
        try:
            if run_targets(target_urls, stale_recover=stale_recover,
                           resolved_attempt=chosen_urls is not None, referer=referer):
                self._dl_resolved_urls = None
                self._dl_referer = None
                self.msg_queue.put(("done", "下载完成"))
                finish("idle")
            return
        except anysearch_downloader.HtmlPageError:
            if not self.settings.get("resolve_links", True):
                self.msg_queue.put(("error", "目标链接返回的是网页而不是文件（已关闭自动解析，请改用真实直链）。"))
                finish("idle")
                return
            chosen_urls = None  # 进入下方重新解析流程
        except _StaleResolved:
            chosen_urls = None  # 旧直链失效：重新解析
        except _BlockedRetry:
            chosen_urls = None  # 直连被拒：用解析器重试

        # ---------- 解析阶段：找出真实直链 / 下载选项 ----------
        self.msg_queue.put(("status", "检测到网页链接，正在解析真实下载地址…"))
        try:
            rr = resolver.resolve(
                url, timeout=15, cancel_event=cancel_event,
                progress_callback=lambda s, d: self.msg_queue.put(("status", d)),
                interactive=self._captcha_interactive)
            if not rr.options:
                self.msg_queue.put(("resolve_note", rr.note or "页面中未找到可下载的链接。"))
                finish("idle")
                return
            if len(rr.options) == 1:
                target_urls = [rr.options[0].url]
                self.msg_queue.put(("append", ("download",
                                               "解析完成：%s\n" % self._option_text(rr.options[0]))))
            elif self.settings.get("resolve_auto_best", False):
                best = rr.options[0]
                target_urls = [best.url]
                self.msg_queue.put(("append", ("download",
                                               "解析出 %d 个选项，已自动选择最佳：%s\n"
                                               % (len(rr.options), self._option_text(best)))))
            else:
                target_urls = self._ask_options(rr, cancel_event)
                if not target_urls:
                    self.msg_queue.put(("status", "未选择下载选项"))
                    finish("idle")
                    return
        except anysearch_downloader.DownloadCancelled:
            cancel_out()
            return
        except resolver.ResolveError as e:
            msg = str(e)
            if "403" in msg or "429" in msg or "拒绝" in msg:
                advice = ("该站点拒绝了自动抓取（可能有人机验证或风控）。\n"
                          "建议：用浏览器打开该页面，手动获取真实下载直链后粘贴到下载框重试；\n"
                          "若持续被拒，请停止使用本工具下载该站点。")
            else:
                advice = ("解析失败（瞬时网络错误已自动重试 1 次）。\n"
                          "请检查网络/代理设置，或改用真实直链后手动重试。")
            self.msg_queue.put(("resolve_note", "链接解析失败：%s\n%s" % (msg, advice)))
            finish("idle")
            return

        # ---------- 用解析出的地址下载 ----------
        self._dl_resolved_urls = list(target_urls)  # 记住解析结果：暂停后续传复用同一地址
        self._dl_referer = rr.final_url             # 下载时带页面 Referer（抗防盗链）
        self.msg_queue.put(("status", "正在下载…"))
        try:
            if run_targets(target_urls, resolved_attempt=True, referer=rr.final_url):
                self._dl_resolved_urls = None
                self._dl_referer = None
                self.msg_queue.put(("done", "下载完成"))
                finish("idle")
        except anysearch_downloader.HtmlPageError:
            self.msg_queue.put(("resolve_note", "解析出的地址仍是网页，请手动提供真实直链。"))
            finish("idle")

    def _option_text(self, opt):
        """把 LinkOption 转成一行可读描述。"""
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
            return "图片 %s · %s%s" % (size, name[:50], note)
        if opt.kind == "video":
            return "视频 · %s%s" % (name[:50], note)
        if opt.kind == "audio":
            return "音频 · %s%s" % (name[:50], note)
        return "文件 · %s%s" % (name[:50], note)

    def _ask_options(self, rr, cancel_event):
        """（worker 线程）等待 UI 线程弹出“下载选项”窗口，返回选中的 URL 列表；放弃返回 None。"""
        box = queue.Queue()
        self.msg_queue.put(("resolve_options", (rr, box)))
        try:
            return box.get(timeout=300)
        except queue.Empty:
            return None

    def _captcha_interactive(self, challenge, cancel_event):
        """（worker 线程）等待 UI 线程弹出验证码输入框，返回 {字段: 输入}；放弃返回 None。"""
        box = queue.Queue()
        self.msg_queue.put(("captcha", (challenge, box)))
        try:
            return box.get(timeout=300)
        except queue.Empty:
            return None

    def _show_options_window(self, rr, box):
        """“下载选项”窗口：列表选择 + 下载选中/全部下载/复制链接/取消。

        布局要点：按钮放底部横排（长链接不会把按钮挤出窗口）；
        树带横向+纵向滚动条，URL 列自适应宽度。
        """
        win = tk.Toplevel(self.root)
        win.title("下载选项 · %s" % (rr.title or "选择"))
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        win.geometry("%dx%d" % (min(940, max(560, sw - 80)), min(560, max(340, sh - 120))))
        win.minsize(560, 340)
        win.transient(self.root)
        # 不设 grab_set：模态锁定会让主窗口“取消下载”点不动（表现为卡顿）；
        # 本窗口自带“取消”按钮，并有 cancel_event 轮询兜底（watch_cancel）
        # （主窗口取消时 200ms 内自动关闭本窗口）

        frame = ttk.Frame(win, padding=(8, 8, 8, 0))
        frame.pack(fill="both", expand=True)
        tree = ttk.Treeview(frame, columns=("idx", "desc", "url"), show="headings",
                            selectmode="extended")
        tree.heading("idx", text="#")
        tree.heading("desc", text="选项")
        tree.heading("url", text="URL（过长可横向滚动）")
        tree.column("idx", width=40, anchor="center", stretch=False)
        tree.column("desc", width=280, anchor="w", stretch=False)
        tree.column("url", width=420, anchor="w", stretch=True)
        yscroll = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
        xscroll = ttk.Scrollbar(frame, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=yscroll.set, xscrollcommand=xscroll.set)
        tree.grid(row=0, column=0, sticky="nsew")
        yscroll.grid(row=0, column=1, sticky="ns")
        xscroll.grid(row=1, column=0, sticky="ew")
        frame.rowconfigure(0, weight=1)
        frame.columnconfigure(0, weight=1)
        for i, opt in enumerate(rr.options, 1):
            tree.insert("", "end", values=(i, self._option_text(opt), opt.url))

        btns = ttk.Frame(win, padding=(8, 6, 8, 8))
        btns.pack(fill="x", side="bottom")

        def selected_urls():
            return [rr.options[int(tree.item(s, "values")[0]) - 1].url
                    for s in tree.selection()]

        def pick():
            urls = selected_urls()
            if not urls:
                messagebox.showwarning("提示", "请先选中要下载的选项（可多选）。", parent=win)
                return
            win.destroy()
            box.put(urls)

        def pick_all():
            win.destroy()
            box.put([o.url for o in rr.options])

        def copy_sel():
            urls = selected_urls()
            if not urls:
                messagebox.showwarning("提示", "请先选中要复制的选项（可多选）。", parent=win)
                return
            if self._copy_to_clipboard("\n".join(urls)):
                self._set_status("已复制 %d 条选中链接" % len(urls))

        def cancel():
            win.destroy()
            box.put(None)

        ttk.Button(btns, text="下载选中", command=pick).pack(side="left")
        ttk.Button(btns, text="全部下载", command=pick_all).pack(side="left", padx=(8, 0))
        ttk.Button(btns, text="复制选中链接", command=copy_sel).pack(side="left", padx=(8, 0))
        ttk.Button(btns, text="取消", command=cancel).pack(side="left", padx=(8, 0))
        ttk.Label(btns, text="双击行 = 下载选中", foreground="#888888").pack(side="right")
        tree.bind("<Double-1>", lambda e: pick())
        win.protocol("WM_DELETE_WINDOW", cancel)

        def watch_cancel():
            # 主窗口“取消下载”也要能关闭本窗口（worker 正在等待选择）
            if self.cancel_event is not None and self.cancel_event.is_set():
                box.put(None)
                win.destroy()
                return
            if win.winfo_exists():
                win.after(200, watch_cancel)

        win.after(200, watch_cancel)

    def _show_captcha_dialog(self, challenge, box):
        """人机验证窗口：显示验证码图片 + 输入框，确定后把答案交给 worker。"""
        win = tk.Toplevel(self.root)
        win.title("人机验证")
        win.transient(self.root)
        try:
            win.grab_set()
        except Exception:
            pass
        ttk.Label(win, text=challenge.hint or "该页面需要人机验证，请输入验证码后继续",
                  wraplength=480, justify="left").pack(padx=14, pady=(12, 8))
        if challenge.captcha_bytes:
            ctype = (challenge.captcha_content_type or "").lower()
            shown = False
            if "png" in ctype or "gif" in ctype:
                try:
                    import base64
                    photo = tk.PhotoImage(data=base64.b64encode(challenge.captcha_bytes).decode("ascii"))
                    lab = ttk.Label(win, image=photo)
                    lab.image = photo  # 防回收
                    lab.pack(padx=14)
                    shown = True
                except Exception:
                    shown = False
            if not shown:
                try:
                    import os
                    import tempfile
                    ext = (".jpg" if "jpeg" in ctype else ".png" if "png" in ctype
                           else ".gif" if "gif" in ctype else ".png")
                    path = os.path.join(tempfile.gettempdir(), "anysearch_captcha" + ext)
                    with open(path, "wb") as f:
                        f.write(challenge.captcha_bytes)
                    try:
                        os.startfile(path)
                    except Exception:
                        pass
                    ttk.Label(win, text="验证码图片已用系统图片查看器打开：\n%s" % path,
                              wraplength=480).pack(padx=14)
                except Exception:
                    ttk.Label(win, text="验证码图片地址：%s" % challenge.captcha_img_url,
                              wraplength=480).pack(padx=14)
        entries = {}
        for f in challenge.fields:
            if f["needs_input"]:
                rowf = ttk.Frame(win)
                rowf.pack(fill="x", padx=14, pady=4)
                ttk.Label(rowf, text="%s：" % f["name"]).pack(side="left")
                var = tk.StringVar()
                ttk.Entry(rowf, textvariable=var, width=32).pack(side="left", fill="x", expand=True)
                entries[f["name"]] = var

        def ok(*_):
            box.put({k: v.get().strip() for k, v in entries.items()})
            win.destroy()

        def cancel(*_):
            box.put(None)
            win.destroy()

        btns = ttk.Frame(win)
        btns.pack(fill="x", padx=14, pady=(10, 14))
        ttk.Button(btns, text="提交", width=10, command=ok).pack(side="left")
        ttk.Button(btns, text="取消", width=10, command=cancel).pack(side="left", padx=(8, 0))
        win.bind("<Return>", ok)
        win.bind("<Escape>", cancel)
        win.protocol("WM_DELETE_WINDOW", cancel)

        def watch_cancel():
            # 主窗口点了“取消下载”时自动关闭验证码窗口（worker 正在等待答案）
            if self.cancel_event is not None and self.cancel_event.is_set():
                box.put(None)
                win.destroy()
                return
            if win.winfo_exists():
                win.after(200, watch_cancel)

        win.after(200, watch_cancel)

    # ---------- 批量下载逻辑 ----------
    def _open_batch_window(self):
        win = tk.Toplevel(self.root)
        win.title("批量下载")
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        win.geometry("%dx%d" % (min(760, int(sw * 0.85)), min(560, int(sh * 0.85))))
        win.minsize(520, 400)

        top = ttk.Frame(win, padding=8)
        top.pack(fill="x")
        ttk.Label(top, text="每行一个 URL（支持文件直链、网页、短链，自动解析）：").pack(anchor="w")
        self.batch_links = scrolledtext.ScrolledText(top, height=6, width=80, font=("Microsoft YaHei", 9))
        self.batch_links.pack(fill="x")

        ctrl = ttk.Frame(win, padding=8)
        ctrl.pack(fill="x")
        ttk.Label(ctrl, text="并行任务数：").pack(side="left")
        self.batch_parallel = tk.StringVar(value="2")
        ttk.Spinbox(ctrl, from_=1, to=5, textvariable=self.batch_parallel, width=3).pack(side="left")
        ttk.Label(ctrl, text="每任务线程数：").pack(side="left", padx=(10, 0))
        self.batch_threads = tk.StringVar(value=str(self.settings.get("default_threads", 8)))
        ttk.Spinbox(ctrl, from_=1, to=16, textvariable=self.batch_threads, width=3).pack(side="left")
        ttk.Button(ctrl, text="开始批量下载", command=self._start_batch).pack(side="left", padx=(12, 0))
        ttk.Button(ctrl, text="暂停全部", command=self._stop_batch).pack(side="left", padx=(8, 0))
        ttk.Button(ctrl, text="全部继续", command=self._resume_all).pack(side="left", padx=(8, 0))
        self.batch_total_label = ttk.Label(ctrl, text="")
        self.batch_total_label.pack(side="left", padx=(12, 0))

        def update_total_label(*_):
            try:
                parallel = max(1, int(self.batch_parallel.get()))
                threads = max(1, int(self.batch_threads.get()))
            except ValueError:
                return
            total_conn = parallel * threads
            limit = int(self.settings.get("max_total_connections", 16))
            if total_conn > limit:
                self.batch_total_label.config(
                    text="总连接数 %d（超过上限 %d）" % (total_conn, limit),
                    foreground="#cc0000",
                )
            else:
                self.batch_total_label.config(text="总连接数 %d" % total_conn, foreground="#008800")

        self.batch_parallel.trace_add("write", update_total_label)
        self.batch_threads.trace_add("write", update_total_label)
        update_total_label()

        # 任务列表：每行 = 链接 + 状态 + 百分比 + 速率 + 单独取消按钮
        self.batch_list = ttk.Frame(win, padding=8)
        self.batch_list.pack(fill="both", expand=True)

    def _start_batch(self):
        text = self.batch_links.get("1.0", "end")
        urls = []
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                urls.append(line)
        if not urls:
            messagebox.showwarning("提示", "请先粘贴至少一个 URL（每行一个）。")
            return
        try:
            parallel = max(1, min(5, int(self.batch_parallel.get())))
        except ValueError:
            parallel = 2
        try:
            threads = max(1, min(16, int(self.batch_threads.get())))
        except ValueError:
            threads = 8

        # 总连接数限制：并行任务数 × 每任务线程数
        limit = max(1, int(self.settings.get("max_total_connections", 16)))
        total_conn = parallel * threads
        if total_conn > limit:
            if not self.settings.get("allow_break_limit", False):
                messagebox.showwarning(
                    "连接数超限",
                    "总连接数 %d 超过上限 %d。\n请在设置中调低参数，或到“设置”里勾选“允许突破连接数上限”。"
                    % (total_conn, limit),
                )
                return
            if not messagebox.askyesno(
                "警告",
                "你已允许突破连接数上限：当前将同时建立 %d 个连接，\n可能导致服务器拒绝服务、IP 被封或文件损坏。\n确认继续？" % total_conn,
            ):
                return

        # 主线程读取设置，避免 worker 线程操作 Tk
        out_dir = self.download_dir.get().strip() or "downloads"
        timeout = int(self.settings.get("download_timeout", 60))
        auto_chunk = bool(self.settings.get("auto_chunk", True))
        chunk_size = int(self.settings.get("chunk_size_mb", 4)) * 1024 * 1024
        mirrors = self.settings.get("mirrors") or []
        mstrategy = self.settings.get("mirror_strategy", "direct")
        mlimit = int(self.settings.get("mirror_speed_limit_kbps", 0)) * 1024
        mprobe = int(self.settings.get("mirror_probe_conns", 3))
        self._batch_ctx = (out_dir, threads, timeout, auto_chunk, chunk_size,
                           mirrors, mstrategy, mlimit, mprobe)

        # 清空旧任务行
        for child in self.batch_list.winfo_children():
            child.destroy()
        self._batch_rows = {}
        # 同步清空进度缓冲：旧 iid 的残留条目会导致消息泵刷新时碰到已销毁的行
        with self._batch_progress_lock:
            self._batch_progress_buf = {}
            self._batch_progress_pending = set()

        # 主线程为每个链接创建一行：链接 + 状态 + 百分比 + 速率 + 单独取消按钮
        tasks = []
        for i, url in enumerate(urls):
            row = ttk.Frame(self.batch_list)
            row.pack(fill="x", pady=1)
            iid = str(i)
            link_text = url[:48] + ("..." if len(url) > 48 else "")
            ttk.Label(row, text=link_text, width=48, anchor="w").pack(side="left")
            status_label = ttk.Label(row, text="等待", width=8, anchor="center")
            status_label.pack(side="left", padx=(4, 0))
            pct_label = ttk.Label(row, text="", width=10, anchor="center")
            pct_label.pack(side="left", padx=(4, 0))
            speed_label = ttk.Label(row, text="", width=12, anchor="center")
            speed_label.pack(side="left", padx=(4, 0))
            cancel_event = threading.Event()
            pause_btn = ttk.Button(row, text="暂停", width=6, command=lambda rid=iid: self._batch_pause_action(rid))
            pause_btn.pack(side="left", padx=(4, 0))
            cancel_btn = ttk.Button(row, text="取消", width=6, command=lambda rid=iid: self._batch_cancel_action(rid))
            cancel_btn.pack(side="left", padx=(4, 0))
            self._batch_rows[iid] = {
                "url": url,
                "status": status_label,
                "pct": pct_label,
                "speed": speed_label,
                "cancel_event": cancel_event,
                "abort": threading.Event(),
                "pause_btn": pause_btn,
                "cancel_btn": cancel_btn,
            }
            tasks.append((iid, url, cancel_event))

        self._batch_sem = threading.Semaphore(parallel)
        self._run_thread(
            lambda: self._batch_scheduler(tasks, parallel,
                                          out_dir, threads, timeout, auto_chunk, chunk_size,
                                          mirrors, mstrategy, mlimit, mprobe)
        )

    def _batch_pause_action(self, iid):
        """暂停/继续：暂停保留断点（按钮变“继续”）；已暂停/失败时点击继续续传。"""
        row = self._batch_rows.get(iid)
        if not row:
            return
        status = row["status"].cget("text")
        if status in ("已暂停", "失败"):
            self._retry_batch_task(iid, row)
        elif status in ("等待", "排队中", "解析中", "下载中"):
            row["cancel_event"].set()
            # 只关闭本行任务的连接（按 cancel_event 隔离），不误伤其它行
            ev = row["cancel_event"]
            self._run_thread(lambda: anysearch_downloader.cancel_active_downloads(ev))
            self._update_batch_row(iid, status="暂停中")

    def _batch_cancel_action(self, iid):
        """取消：立即中断并删除该任务的未完成文件与断点。"""
        row = self._batch_rows.get(iid)
        if not row:
            return
        row["abort"].set()
        row["cancel_event"].set()
        if row["status"].cget("text") == "已暂停":
            # 暂停态：worker 已退出，直接丢弃断点并标记已取消（否则永远停在“取消中”）
            out_dir = self._batch_ctx[0] if self._batch_ctx else "downloads"
            for u in (row.get("resolved") or []) + [row["url"]]:
                try:
                    anysearch_downloader.discard_download(url=u, out_dir=out_dir)
                except Exception:
                    pass
            row["resolved"] = None
            row["referer"] = None
            self._update_batch_row(iid, status="已取消")
            return
        row["resolved"] = None  # 取消=放弃：清除解析记忆
        row["referer"] = None
        # 只关闭本行任务的连接，不误伤其它行
        ev = row["cancel_event"]
        self._run_thread(lambda: anysearch_downloader.cancel_active_downloads(ev))
        self._update_batch_row(iid, status="取消中")

    def _retry_batch_task(self, iid, row):
        new_ev = threading.Event()
        row["cancel_event"] = new_ev
        row["abort"].clear()
        if self._batch_ctx is None:
            # 防御：未发起过批量任务时点“重试”（正常流程不会走到）
            self._update_batch_row(iid, status="失败", pct="", speed="")
            return
        out_dir, threads, timeout, auto_chunk, chunk_size, mirrors, mstrategy, mlimit, mprobe = self._batch_ctx
        # 先标“排队中”：只有真正拿到信号量开始下载时才显示“下载中”
        self._update_batch_row(iid, status="排队中", pct="", speed="")
        sem = self._batch_sem

        def run():
            if sem is None:
                return
            sem.acquire()
            try:
                # 排队期间被暂停/取消：不再开始
                if new_ev.is_set():
                    row2 = self._batch_rows.get(iid)
                    if row2 is not None and row2.get("abort") is not None and row2["abort"].is_set():
                        anysearch_downloader.discard_download(url=row["url"], out_dir=out_dir)
                        self.msg_queue.put(("batch_status", (iid, "已取消", "")))
                    else:
                        self.msg_queue.put(("batch_status", (iid, "已暂停", "")))
                    return
                self._batch_download(
                    iid, row["url"], new_ev, out_dir, threads, timeout,
                    auto_chunk, chunk_size, mirrors, mstrategy, mlimit, mprobe)
            finally:
                sem.release()

        self._run_thread(run)

    def _update_batch_row(self, iid, status=None, pct=None, speed=None, extra=None):
        row = self._batch_rows.get(iid)
        if not row:
            return
        if status is not None:
            row["status"].config(text=status)
            pause_btn = row.get("pause_btn")
            cancel_btn = row.get("cancel_btn")
            if pause_btn is not None and cancel_btn is not None:
                if status in ("等待", "排队中", "解析中", "下载中", "暂停中", "取消中"):
                    pause_btn.config(text="暂停", state="normal")
                    cancel_btn.config(text="取消", state="normal")
                elif status == "已暂停":
                    pause_btn.config(text="继续", state="normal")
                    cancel_btn.config(text="取消", state="normal")
                elif status == "失败":
                    pause_btn.config(text="重试", state="normal")
                    cancel_btn.config(state="disabled")
                else:  # 完成 / 已取消
                    pause_btn.config(state="disabled")
                    cancel_btn.config(state="disabled")
        if pct is not None:
            pct_label = row.get("pct")
            if pct_label is not None:
                pct_label.config(text=pct)
        if speed is not None:
            speed_label = row.get("speed")
            if speed_label is not None:
                speed_label.config(text=speed)
        if extra:
            # “完成”时显示文件名（截断以适配列宽）
            pct_label = row.get("pct")
            if pct_label is not None:
                pct_label.config(text=str(extra)[:18])

    def _stop_batch(self):
        """暂停全部：保留所有断点，可逐行“继续”或一键“全部继续”。"""
        for row in self._batch_rows.values():
            if row["status"].cget("text") in ("等待", "排队中", "解析中", "下载中"):
                row["cancel_event"].set()
        # 只关闭批量各行的连接（按事件隔离），不触碰下载页单任务的连接
        events = [row["cancel_event"] for row in self._batch_rows.values()]
        self._run_thread(
            lambda: [anysearch_downloader.cancel_active_downloads(ev) for ev in events])
        self._set_status("正在暂停全部任务…（断点已保留，可“全部继续”）")

    def _resume_all(self):
        """全部继续：恢复所有“已暂停”任务（受并行数限制，不会突破上限）。"""
        resumed = 0
        for iid, row in self._batch_rows.items():
            if row["status"].cget("text") == "已暂停":
                self._retry_batch_task(iid, row)
                resumed += 1
        if resumed:
            self._set_status("已恢复 %d 个任务（受并行数限制）" % resumed)
        else:
            self._set_status("没有可继续的任务")

    def _batch_scheduler(self, tasks, parallel, out_dir, threads, timeout, auto_chunk, chunk_size,
                         mirrors, mstrategy, mlimit, mprobe):
        # 按输入顺序开始；等待的任务显示“排队中”；暂停由每任务自己的 cancel_event 控制
        self.msg_queue.put(("status", "批量下载开始"))
        semaphore = self._batch_sem or threading.Semaphore(parallel)
        remaining = [len(tasks)]
        lock = threading.Lock()

        def worker(iid, url, cancel_event):
            try:
                self.msg_queue.put(("batch_status", (iid, "排队中", "")))
                semaphore.acquire()
                if cancel_event.is_set():
                    self.msg_queue.put(("batch_status", (iid, "已暂停", "")))
                    return
                self._batch_download(iid, url, cancel_event, out_dir, threads, timeout,
                                     auto_chunk, chunk_size, mirrors, mstrategy, mlimit, mprobe)
            finally:
                semaphore.release()
                with lock:
                    remaining[0] -= 1
                    if remaining[0] == 0:
                        self.msg_queue.put(("status", "批量下载结束"))

        for iid, url, cancel_event in tasks:
            threading.Thread(target=worker, args=(iid, url, cancel_event), daemon=True).start()

    def _batch_download(self, iid, url, cancel_event, out_dir, threads, timeout,
                        auto_chunk, chunk_size, mirrors, mstrategy, mlimit, mprobe):
        last = [0, time.time()]

        def progress(done, total_):
            now = time.time()
            elapsed = now - last[1]
            if elapsed >= 0.5:
                speed = (done - last[0]) / elapsed
                last[0], last[1] = done, now
                self.msg_queue.put(("batch_speed", (iid, speed)))
            # 高频进度走缓冲合并，不刷队列
            with self._batch_progress_lock:
                self._batch_progress_buf[iid] = (done, total_)
                self._batch_progress_pending.add(iid)

        def finish_aborted(target):
            row = self._batch_rows.get(iid)
            if row is not None and row.get("abort") is not None and row["abort"].is_set():
                if row is not None:
                    row["resolved"] = None  # 取消=放弃：清除解析记忆
                    row["referer"] = None
                anysearch_downloader.discard_download(url=target, out_dir=out_dir)
                self.msg_queue.put(("batch_status", (iid, "已取消", "")))
            else:
                self.msg_queue.put(("batch_status", (iid, "已暂停", "")))

        def run_targets(targets, stale_recover=False, resolved_attempt=False, referer=None):
            """依次下载多个 URL；全部完成返回 True；网页错误/失效直链/被拒直链向上抛；其余就地收尾返回 False。"""
            for target in targets:
                if cancel_event.is_set():
                    finish_aborted(target)
                    return False
                try:
                    path = anysearch_downloader.download_file(
                        target,
                        out_dir,
                        num_threads=threads,
                        timeout=timeout,
                        progress_callback=progress,
                        cancel_event=cancel_event,
                        auto_chunk=auto_chunk,
                        chunk_size=chunk_size,
                        mirrors=mirrors,
                        mirror_strategy=mstrategy,
                        mirror_speed_limit=mlimit,
                        mirror_probe_conns=mprobe,
                        referer=referer,
                    )
                    if cancel_event.is_set():
                        finish_aborted(target)
                        return False
                    self.msg_queue.put(("batch_status", (iid, "完成", path.name)))
                except anysearch_downloader.HtmlPageError:
                    raise
                except anysearch_downloader.DownloadCancelled:
                    finish_aborted(target)
                    return False
                except anysearch_downloader.DownloadPaused:
                    self.msg_queue.put(("batch_status", (iid, "已暂停", "")))
                    return False
                except Exception as e:
                    if stale_recover:
                        # 上次解析出的直链失效（签名过期等）：自动重新解析
                        self.msg_queue.put(("batch_status", (iid, "解析中", "直链失效")))
                        raise _StaleResolved()
                    if (not resolved_attempt and resolve_enabled and _is_block_error(str(e))):
                        self.msg_queue.put(("batch_status", (iid, "解析中", "直连被拒")))
                        raise _BlockedRetry()
                    if resolved_attempt:
                        self.msg_queue.put(("batch_status", (iid, "失败", "直链需浏览器会话")))
                        return False
                    if cancel_event.is_set():
                        finish_aborted(target)
                    else:
                        self.msg_queue.put(("batch_status", (iid, "失败", str(e)[:120])))
                    return False
            return True

        # ---------- 直链优先：直接下载；只有返回网页才启用解析 ----------
        row = self._batch_rows.get(iid) or {}
        resolve_enabled = self.settings.get("resolve_links", True)
        chosen = row.get("resolved")  # 上次解析出的直链：续传时直接复用（签名直链每次解析都不同）
        ref = row.get("referer")
        if chosen:
            self.msg_queue.put(("batch_status", (iid, "下载中", "")))
            try:
                if run_targets(list(chosen), stale_recover=resolve_enabled,
                               resolved_attempt=True, referer=ref):
                    row["resolved"] = None
                    row["referer"] = None
                return
            except anysearch_downloader.HtmlPageError:
                if not resolve_enabled:
                    self.msg_queue.put(("batch_status", (iid, "失败", "链接是网页")))
                    return
            except _StaleResolved:
                pass  # 重新解析
        else:
            self.msg_queue.put(("batch_status", (iid, "下载中", "")))
            try:
                run_targets([url])
                return
            except anysearch_downloader.HtmlPageError:
                if not resolve_enabled:
                    self.msg_queue.put(("batch_status", (iid, "失败", "链接是网页")))
                    return
            except _BlockedRetry:
                pass  # 直连被拒：用解析器重试

        # ---------- 解析阶段 ----------
        self.msg_queue.put(("batch_status", (iid, "解析中", "")))
        try:
            rr = resolver.resolve(url, timeout=15, cancel_event=cancel_event,
                                  interactive=self._captcha_interactive)
            if not rr.options:
                self.msg_queue.put(("batch_status",
                                    (iid, "失败", (rr.note or "未找到可下载链接")[:120])))
                return
            if len(rr.options) == 1 or self.settings.get("resolve_auto_best", False):
                targets = [rr.options[0].url]
            else:
                targets = self._ask_options(rr, cancel_event)
                if not targets:
                    self.msg_queue.put(("batch_status", (iid, "已跳过", "")))
                    return
        except anysearch_downloader.DownloadCancelled:
            row = self._batch_rows.get(iid)
            if row is not None and row.get("abort") is not None and row["abort"].is_set():
                self.msg_queue.put(("batch_status", (iid, "已取消", "")))
            else:
                self.msg_queue.put(("batch_status", (iid, "已暂停", "")))
            return
        except resolver.ResolveError as e:
            msg = str(e)
            if "403" in msg or "429" in msg or "拒绝" in msg:
                msg += "｜站点拒绝自动抓取，请手动获取直链"
            else:
                msg += "｜请手动获取直链重试"
            self.msg_queue.put(("batch_status", (iid, "失败", msg[:120])))
            return

        # ---------- 用解析出的地址下载 ----------
        row = self._batch_rows.get(iid) or {}
        row["resolved"] = list(targets)  # 记住解析结果：暂停后续传复用同一地址
        row["referer"] = rr.final_url    # 下载时带页面 Referer（抗防盗链）
        self.msg_queue.put(("batch_status", (iid, "下载中", "")))
        try:
            if run_targets(targets, resolved_attempt=True, referer=rr.final_url):
                row["resolved"] = None
                row["referer"] = None
        except anysearch_downloader.HtmlPageError:
            self.msg_queue.put(("batch_status", (iid, "失败", "解析结果仍是网页")))

    def _update_download_progress(self, done, total):
        now = time.time()
        elapsed = now - self._dl_last[1]
        if elapsed < 0.05:
            return
        speed = (done - self._dl_last[0]) / elapsed
        self._dl_last = (done, now)
        if total:
            pct = min(100.0, done * 100.0 / total)
            self.download_progress["value"] = pct
            remain = (total - done) / speed if speed > 0 else 0
            self.download_progress_label.set(
                "%.1f%%  %s/%s  %s/s  剩余%s"
                % (pct, _fmt(done), _fmt(total), _fmt(speed), _fmt_time(remain))
            )
        else:
            self.download_progress["value"] = 0
            self.download_progress_label.set("已下载 %s" % _fmt(done))


def _fmt(n):
    n = float(n or 0)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.2f%s" % (n, unit)
        n /= 1024


def _fmt_time(seconds):
    seconds = max(0, int(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    if h:
        return "%d:%02d:%02d" % (h, m, s)
    return "%d:%02d" % (m, s)


def main():
    root = tk.Tk()
    AnySearchGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
