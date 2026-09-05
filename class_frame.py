# -*- coding: utf-8 -*-
import os
import json
import time
import datetime
import math
from tkinter import Tk, Toplevel, Label, Button, Canvas


SCREEN_GAP = 10
ISLAND_GAP = 30
DOCKS = ("left", "upper", "right", "center")
WEEK_NAMES = ["一", "二", "三", "四", "五", "六", "日"]
SPECIAL_TEXT = {"周"} | set(WEEK_NAMES) | {"|"}


def _num(v, default=0):
    try:
        return int(v)
    except Exception:
        try:
            return float(v)
        except Exception:
            return default


def _flag(cfg, key, default=0):
    v = cfg.get(key, [default])
    if isinstance(v, (list, tuple)):
        v = v[0] if v else default
    return bool(v) if isinstance(v, bool) else bool(int(_num(v, default)))


def _text(cfg, key, default=""):
    v = cfg.get(key, [default])
    if isinstance(v, (list, tuple)):
        return v[0] if v else default
    return str(v)


def _pos_map(old):
    m = {"a": "left", "b": "upper", "c": "right", "f": "center", "u": "upper"}
    return m.get(old, old if old in DOCKS else "upper")


def migrate_config(cfg):
    """为老配置补上新字段，并兼容旧 a/b/c/f/u 位置写法。"""
    legacy_u = False
    for key in ("上课默认位置", "下课默认位置"):
        if key in cfg:
            old = _text(cfg, key, "upper")
            if old == "u":
                legacy_u = True
            cfg[key] = [_pos_map(old)]
    cfg.setdefault("secondStyle", bool(legacy_u))
    style_defaults = cfg.setdefault("拖动默认样式", {})
    for d in DOCKS:
        if d not in style_defaults:
            style_defaults[d] = bool(d == "upper" and legacy_u)
    scale = cfg.get("上课放大倍率", [1.0])
    scale = scale[0] if isinstance(scale, (list, tuple)) else scale
    for key, default in (
        ("left上课缩放", scale),
        ("left下课缩放", 1.0),
        ("upper上课缩放", scale),
        ("upper下课缩放", 1.0),
        ("center缩放", 1.0),
    ):
        if key not in cfg:
            cfg[key] = [default]
    cfg.setdefault("进度条宽度", [8])
    return cfg


def minutes_of(value):
    if isinstance(value, (list, tuple)):
        return int(value[0]) * 60 + int(value[1])
    if isinstance(value, str) and ":" in value:
        h, m = value.split(":", 1)
        return int(h) * 60 + int(m)
    return int(_num(value, 0))


def fmt_mmss(seconds):
    seconds = max(0, int(seconds))
    return "%02d:%02d" % (seconds // 60, seconds % 60)


class movements:
    """管理所有无边框悬浮窗口的位置与平滑动画。"""

    def __init__(self):
        probe = Tk()
        self.screen_gap = SCREEN_GAP
        self.island_gap = ISLAND_GAP
        self.screen_width = probe.winfo_screenwidth()
        self.screen_height = probe.winfo_screenheight()
        probe.destroy()
        self.isls = []

    def find(self, win):
        for i, r in enumerate(self.isls):
            if r["win"] == win:
                return i
        return -1

    def _new_rec(self, win):
        return {
            "win": win,
            "goal": [0, 0, 0, 0],
            "now": [0, 0, 0, 0],
            "prev": [0, 0, 0, 0],
            "group": "upper",
            "flush": False,
            "deleting": False,
            "dragging": False,
        }

    def to_isl(self, win, w, h, group="upper", flush=False, snap=False):
        w = max(1, int(round(w)))
        h = max(1, int(round(h)))
        idx = self.find(win)
        if idx == -1:
            win.overrideredirect(True)
            win.attributes("-alpha", 0.92)
            win.config(bg="black")
            rec = self._new_rec(win)
            self.isls.append(rec)
            rec["goal"] = [w, h, 0, 0]
            rec["now"] = [w, h, 0, 0]
            rec["prev"] = [w, h, 0, 0]
            idx = len(self.isls) - 1
        rec = self.isls[idx]
        rec["group"] = group
        rec["flush"] = bool(flush)
        rec["deleting"] = False
        rec["goal"][0] = w
        rec["goal"][1] = h
        if snap:
            rec["now"] = list(rec["goal"])
            rec["prev"] = list(rec["goal"])
        self.cal_pos()
        return idx

    def del_isl(self, win):
        idx = self.find(win)
        if idx != -1:
            self.isls[idx]["deleting"] = True
            rec = self.isls[idx]
            rec["goal"][0] = 1
            rec["goal"][1] = 1
            self.cal_pos()

    def set_drag_goal(self, win, x, y):
        idx = self.find(win)
        if idx != -1:
            self.isls[idx]["goal"][2] = x
            self.isls[idx]["goal"][3] = y

    def drag_now(self, win):
        idx = self.find(win)
        if idx != -1:
            self.isls[idx]["dragging"] = True

    def drag_end(self, win):
        idx = self.find(win)
        if idx != -1:
            self.isls[idx]["dragging"] = False
        self.cal_pos()

    def island_size(self, win):
        idx = self.find(win)
        if idx != -1:
            return list(self.isls[idx]["goal"][:2])
        return [1, 1]

    def current_size(self, win):
        idx = self.find(win)
        if idx != -1:
            return list(self.isls[idx]["now"][:2])
        return [1, 1]

    def cal_pos(self):
        # 每个 dock 单独计算，把窗口排成一组（避免和正在拖动的窗口抢位置）。
        for group in DOCKS:
            members = [r for r in self.isls if r["group"] == group and not r["deleting"] and not r["dragging"]]
            if not members:
                continue
            if group == "center":
                for r in members:
                    r["goal"][2] = (self.screen_width - r["goal"][0]) / 2
                    r["goal"][3] = (self.screen_height - r["goal"][1]) / 2
                continue
            if group in ("left", "right"):
                total = -self.island_gap
                for r in members:
                    total += r["goal"][1] + self.island_gap
                y = (self.screen_height - total) / 2
                for r in members:
                    edge = 0 if r["flush"] else self.screen_gap
                    if group == "left":
                        r["goal"][2] = edge
                    else:
                        r["goal"][2] = self.screen_width - r["goal"][0] - edge
                    r["goal"][3] = y
                    y += r["goal"][1] + self.island_gap
            else:  # upper
                total = -self.island_gap
                for r in members:
                    total += r["goal"][0] + self.island_gap
                x = (self.screen_width - total) / 2
                for r in members:
                    edge = 0 if r["flush"] else self.screen_gap
                    r["goal"][2] = x
                    r["goal"][3] = edge
                    x += r["goal"][0] + self.island_gap

    def refresh_isl(self):
        dead = []
        for r in self.isls:
            win = r["win"]
            goal = r["goal"]
            now = r["now"]
            prev = r["prev"]
            speed = [(now[k] - prev[k]) * 0.85 for k in range(4)]
            nxt = [0.035 * goal[k] + 0.965 * now[k] + 0.55 * speed[k] for k in range(4)]
            r["prev"] = list(now)
            r["now"] = nxt
            try:
                win.geometry(
                    "%dx%d+%d+%d"
                    % (
                        max(round(nxt[0]), 1),
                        max(round(nxt[1]), 1),
                        round(nxt[2]),
                        round(nxt[3]),
                    )
                )
                win.update()
            except Exception:
                pass
            if r["deleting"] and max(abs(goal[k] - nxt[k]) for k in range(2)) < 1.5:
                try:
                    win.destroy()
                except Exception:
                    pass
                dead.append(r)
        for r in dead:
            self.isls.remove(r)


class calendar:
    def __init__(self):
        self.labelsize = 40
        self.gaprate = 0.25
        self.classes = {}
        self.selects = []
        self.on = []
        self.off = []
        self.onw = ""
        self.offw = ""
        self.nowgroup = "upper"
        self.l_nowgroup = "upper"
        self.showt_afterclass = 0
        self.showt_onclass = 0
        self.ontop_afterclass = 0
        self.ontop_onclass = 0
        self.progress_width = 8
        self.b_size = 40
        self.ac_size = 28
        self.scale = {}
        self.second_style = False
        self.drag_defaults = {}
        self.class_change = []
        self.config = {}

        self.after_class = True
        self.closing = False
        self.state_index = None
        self.state_next = None
        self.total_sec = 0
        self.left_sec = 0
        self.highlight = None
        self.tick = 0
        self.layout_dirty = True

        self.today_class = []
        self.changing_class = []
        self.date_view = datetime.datetime.now()
        self.date_now = ""
        self.date_label_text = ""

        self.mainland = None
        self.isl_frame = None
        self.canvas = None
        self.clock_label = None
        self.labels = []
        self.select_list = []
        self.ml = None
        self.left_shift = None
        self.right_shift = None
        self.margin_left = None
        self.margin_right = None
        self.todate = None
        self.content_kind = None
        self.count_win = None
        self.count_label = None

        # 拖动状态
        self.dragging = False
        self.press_point = None
        self.drag_zone = None
        self.drag_ref = None
        self.drag_click = False
        self.drag_moved = 0

        # 编辑（拖课）状态
        self.moving_class = 0
        self.x_start = 0
        self.y_start = 0
        self.select_labels = []

    # ---------- 载入 ----------
    def load_class(self):
        today = datetime.datetime.now()
        self.to_week = datetime.date(today.year, today.month, today.day).weekday()
        with open("config.json", encoding="utf-8") as file:
            self.config = migrate_config(json.load(file))
        cfg = self.config
        self.classes = cfg["日程表"]
        self.selects = cfg["更换选项"]
        self.on = [minutes_of(t) for t in cfg["开始时间"]]
        self.off = [minutes_of(t) for t in cfg["结束时间"]]
        self.onw = _text(cfg, "开始提示", "准备上课")
        self.offw = _text(cfg, "结束提示", "下课时间")
        self.onclass_default_pos = _text(cfg, "上课默认位置", "upper")
        self.offclass_default_pos = _text(cfg, "下课默认位置", "upper")
        self.showt_afterclass = _flag(cfg, "下课显示倒计时", 1)
        self.showt_onclass = _flag(cfg, "上课显示倒计条", 1)
        self.ontop_afterclass = _flag(cfg, "下课置顶", 1)
        self.ontop_onclass = _flag(cfg, "上课置顶", 1)
        self.b_size = _num(cfg.get("文字大小", [40])[0], 40)
        self.ac_size = _num(cfg.get("竖直显示的文字大小", [28])[0], 28)
        self.progress_width = max(1, _num(cfg.get("进度条宽度", [8])[0], 8))
        self.second_style = bool(cfg.get("secondStyle", False))
        self.drag_defaults = cfg.get("拖动默认样式", {})
        for d in DOCKS:
            self.drag_defaults.setdefault(d, False)
        for key in (
            "left上课缩放",
            "left下课缩放",
            "upper上课缩放",
            "upper下课缩放",
            "center缩放",
        ):
            self.scale[key] = float(cfg.get(key, [1.0])[0])

        try:
            with open("data.json", "r", encoding="utf-8") as file:
                self.class_change = json.load(file)
        except Exception:
            self.class_change = []
        # 课节数不一致的旧调课记录直接忽略，避免渲染错位
        expected = len(self.on) + 2
        self.class_change = [
            rec
            for rec in self.class_change
            if isinstance(rec, (list, tuple))
            and len(rec) > 1
            and sum(1 for t in rec[1] if t != "|") == expected
        ]

    # ---------- 界面初始 ----------
    def start(self):
        self.mainland = Tk()
        self.mainland.config(bg="black")
        self.isl_frame = movements()
        self.isl_frame.progress_width = self.progress_width
        self.load_class()
        self.week = WEEK_NAMES
        today = datetime.datetime.now()
        self.date_now = today.strftime("%Y-%m-%d")
        to_week = datetime.date(today.year, today.month, today.day).weekday()
        self.today_class = ["周"] + [self.week[to_week]] + ["|"] + self.classes[str(to_week + 1)]
        for rec in self.class_change:
            if rec[0] == self.date_now:
                self.today_class = rec[1]
        self.changing_class = list(self.today_class)
        self.update_state(datetime.datetime.now())
        if self.after_class:
            self.nowgroup = self.offclass_default_pos
        else:
            self.nowgroup = self.onclass_default_pos
        self.l_nowgroup = self.nowgroup
        self.mainland.attributes("-topmost", bool(self.ontop_onclass))
        self.layout_dirty = True

    # ---------- 时间状态机 ----------
    def update_state(self, now_dt):
        now_min = now_dt.hour * 60 + now_dt.minute
        now_sec = now_dt.hour * 3600 + now_dt.minute * 60 + now_dt.second + now_dt.microsecond / 1e6
        starts = self.on
        ends = self.off
        n = min(len(starts), len(ends))
        # 放学 10 分钟关闭窗口
        if n:
            last_off_sec = ends[-1] * 60
            if last_off_sec <= now_sec < last_off_sec + 600:
                self.closing = True
                self.after_class = True
                self.state_index = None
                self.state_next = None
                self.highlight = None
                self.total_sec = 600.0
                self.left_sec = last_off_sec + 600 - now_sec
                return
            if now_sec >= last_off_sec + 600:
                self.quit_app()
                return
        # 上课中
        for i in range(n):
            if starts[i] * 60 <= now_sec < ends[i] * 60:
                self.closing = False
                self.after_class = False
                self.state_index = i
                self.state_next = None
                self.highlight = i
                self.total_sec = float(ends[i] - starts[i]) * 60
                self.left_sec = ends[i] * 60 - now_sec
                return
        # 课间 / 放学前后
        self.closing = False
        self.after_class = True
        self.state_index = None
        self.highlight = None
        next_i = None
        for i in range(n):
            if now_min < starts[i]:
                next_i = i
                break
        if next_i is not None:
            self.state_next = next_i
            self.highlight = next_i
            self.total_sec = float(starts[next_i] * 60 - (ends[next_i - 1] * 60 if next_i else 0))
            self.left_sec = starts[next_i] * 60 - now_sec
        else:
            self.state_next = None
            self.total_sec = 1.0
            self.left_sec = 0.0

    def quit_app(self):
        try:
            for win in (self.count_win, self.mainland):
                if win is not None:
                    try:
                        win.destroy()
                    except Exception:
                        pass
        finally:
            raise SystemExit

    # ---------- 字体与测量 ----------
    def font_px(self, dock=None, after=None):
        dock = dock or self.nowgroup
        if after is None:
            after = self.after_class
        if dock in ("left", "right"):
            base = self.ac_size
            key = "left下课缩放" if after else "left上课缩放"
        elif dock == "upper":
            base = self.b_size
            key = "upper下课缩放" if after else "upper上课缩放"
        else:
            base = self.b_size
            key = "center缩放"
        return max(2, int(round(base * self.scale.get(key, 1.0))))

    def _mk_label(self, text, fpx, vertical):
        if vertical:
            if text == "|":
                text = "—"
            font = ("幼圆", max(1, int(fpx / (max(len(text), 1) ** 0.7))))
            wrap = max(10, int(fpx * 1.7))
        else:
            font = ("幼圆", max(1, int(fpx / (max(len(text), 1) ** 0.5))))
            wrap = max(10, int(fpx * 1.5))
        lab = Label(self.mainland, text=text, font=font, fg="white", bg="black", wraplength=wrap)
        return lab

    def label_font(self, text, fpx, vertical):
        if vertical:
            return ("幼圆", max(1, int(fpx / (max(len(text), 1) ** 0.7))))
        return ("幼圆", max(1, int(fpx / (max(len(text), 1) ** 0.5))))

    def measure_tokens(self, tokens, fpx, vertical):
        labs = []
        for text in tokens:
            lab = self._mk_label(text, fpx, vertical)
            labs.append(lab)
        gap = fpx * self.gaprate
        total = 0.0
        side = 0.0
        for lab in labs:
            if vertical:
                total += lab.winfo_reqheight()
                side = max(side, lab.winfo_reqwidth())
            else:
                total += lab.winfo_reqwidth()
                side = max(side, lab.winfo_reqheight())
        for lab in labs:
            lab.destroy()
        if vertical:
            return [side + gap * 2, total + gap * 2]
        return [total + gap * 2, side + gap * 2]

    def lesson_position_map(self, tokens):
        """第几个课节 -> tokens 下标。tokens 不包含本周/分隔头。"""
        result = {}
        count = 0
        for i, t in enumerate(tokens):
            if t in SPECIAL_TEXT:
                continue
            result[count] = i
            count += 1
        return result, count

    def _highlight_index(self, tokens):
        if self.highlight is None:
            return None
        pos_map, _ = self.lesson_position_map(tokens)
        return pos_map.get(self.highlight)

    # ---------- 内容重建 ----------
    def clear_content(self):
        self.labels = []
        self.select_list = []
        self.select_labels = []
        self.ml = None
        self.left_shift = None
        self.right_shift = None
        self.margin_left = None
        self.margin_right = None
        self.todate = None
        self.clock_label = None
        self.canvas = None
        try:
            for w in list(self.mainland.winfo_children()):
                w.destroy()
        except Exception:
            pass

    def ensure_content(self):
        if self.nowgroup in ("left", "upper", "right"):
            kind = "edge_bar" if self.second_style else "edge_text"
        elif self.nowgroup == "center":
            kind = "center_simple" if not self.second_style else "center_editor"
        else:
            kind = "edge_text"
        if kind == self.content_kind:
            return
        self.clear_content()
        self.content_kind = kind
        if kind == "edge_bar":
            self.canvas = Canvas(self.mainland, bg="black", highlightthickness=0)
            self.canvas.pack(fill="both", expand=True)
        elif kind == "center_editor":
            self.build_editor_widgets()
        elif kind == "center_simple":
            self.build_center_widgets()
        else:
            self.build_edge_text_widgets()

    # 普通三侧文字
    def build_edge_text_widgets(self):
        vertical = self.nowgroup in ("left", "right")
        fpx = self.font_px()
        self.labels = []
        for text in self.today_class:
            lab = self._mk_label(text, fpx, vertical)
            self.labels.append(lab)
        self.canvas = Canvas(self.mainland, bg="black", highlightthickness=0)
        self.edge_text_geom = self.measure_text_widgets()
        self.layout_dirty = True

    def measure_text_widgets(self):
        vertical = self.nowgroup in ("left", "right")
        fpx = self.font_px()
        gap = fpx * self.gaprate
        total = 0.0
        maxside = 0.0
        for lab in self.labels:
            if vertical:
                total += lab.winfo_reqheight()
                maxside = max(maxside, lab.winfo_reqwidth())
            else:
                total += lab.winfo_reqwidth()
                maxside = max(maxside, lab.winfo_reqheight())
        if vertical:
            return [maxside + gap * 2, total + gap * 2, maxside, total]
        return [total + gap * 2, maxside + gap * 2, total, maxside]

    def place_text_widgets(self):
        vertical = self.nowgroup in ("left", "right")
        fpx = self.font_px()
        gap = fpx * self.gaprate
        hl = self._highlight_index(self.today_class)
        for i, lab in enumerate(self.labels):
            lab.config(fg="yellow" if i == hl else "white")
        # 测量布局的中心对齐
        if vertical:
            x = gap
            y = gap
            for lab in self.labels:
                lab.place(x=x, y=y)
                y += lab.winfo_reqheight()
        else:
            x = gap
            y = gap
            for lab in self.labels:
                lab.place(x=x, y=y)
                x += lab.winfo_reqwidth()
        # 隐藏普通文本模式下不用的时钟
        return

    # center 新布局：进度条 + 时间 + 课表
    def build_center_widgets(self):
        fpx = self.font_px("center")
        gap = max(4, fpx * 0.25)
        # 进度条画布
        self.canvas = Canvas(self.mainland, bg="black", highlightthickness=0)
        # 时钟
        self.clock_label = Label(
            self.mainland,
            text=datetime.datetime.now().strftime("%H:%M:%S"),
            font=("黑体", max(4, int(fpx * 2))),
            fg="white",
            bg="black",
        )
        # 课表文字
        self.labels = []
        for text in self.today_class:
            lab = self._mk_label(text, fpx, False)
            self.labels.append(lab)
        w = 0
        hmax = 0
        for lab in self.labels:
            w += lab.winfo_reqwidth()
            hmax = max(hmax, lab.winfo_reqheight())
        w += gap * 2
        hmax += gap * 2
        self.center_geom = [w, hmax]
        self.layout_dirty = True

    def place_center_widgets(self):
        if self.canvas is None:
            return
        fpx = self.font_px("center")
        gap = max(4, fpx * 0.25)
        hl = self._highlight_index(self.today_class)
        for i, lab in enumerate(self.labels):
            lab.config(fg="yellow" if i == hl else "white")
        bar_h = max(2, int(fpx / 3.0))
        clock_req_h = self.clock_label.winfo_reqheight()
        schedule_w, schedule_h = self.center_geom
        w = max(schedule_w, self.clock_label.winfo_reqwidth() + gap * 2)
        h = int(gap + bar_h + gap + clock_req_h + gap + schedule_h + gap)
        self.canvas.place(x=0, y=0, width=w, height=bar_h)
        self.clock_label.place(x=(w - self.clock_label.winfo_reqwidth()) / 2, y=gap + bar_h + gap)
        x = gap + max(0, (w - schedule_w) / 2)
        y = gap + bar_h + gap + clock_req_h + gap
        for lab in self.labels:
            lab.place(x=x, y=y)
            x += lab.winfo_reqwidth()
        self.center_window_geom = [w, h]
        return [w, h]

    # ---------- center 编辑器 ----------
    def build_editor_widgets(self):
        fpx = self.font_px("center")
        gap = max(4, fpx * 0.25)
        self.ml = Label(self.mainland)
        self.todate = Label(self.mainland)
        self.left_shift = Button(self.mainland, command=lambda: self.turn_date(-1))
        self.right_shift = Button(self.mainland, command=lambda: self.turn_date(1))
        self.select_list = []
        for text in self.selects:
            lab = Label(
                self.mainland,
                text=text,
                font=self.label_font(text, fpx, False),
                fg="white",
                bg="black",
                wraplength=fpx * 1.5,
            )
            self.select_list.append(lab)
            lab.bind("<ButtonPress-1>", lambda e, m=lab, n=text: self.editor_start(e, m, n))
            lab.bind("<B1-Motion>", lambda e, m=lab, n=text: self.editor_move(e, m, n))
            lab.bind("<ButtonRelease-1>", lambda e, m=lab, n=text: self.editor_release(e, m, n))
        # 编辑器课表标签（横向）
        self.labels = []
        self.date_label_text = self.date_now
        for text in self.today_class:
            lab = self._mk_label(text, fpx, False)
            self.labels.append(lab)
        # 拖动把手：日期行两侧空余
        self.margin_left = Label(self.mainland, bg="black", width=8)
        self.margin_right = Label(self.mainland, bg="black", width=8)
        self.layout_dirty = True

    def layout_editor(self):
        fpx = self.font_px("center")
        gap = max(4, fpx * 0.25)
        if self.left_shift is None:
            return [1, 1]
        # 高亮
        hl = self._highlight_index(self.changing_class)
        for i, lab in enumerate(self.labels):
            lab.config(fg="yellow" if i == hl else "white")
        # 先量好课表行
        schedule_w = gap
        schedule_h = gap
        for i, lab in enumerate(self.labels):
            schedule_w += lab.winfo_reqwidth()
            schedule_h = max(schedule_h, lab.winfo_reqheight() + gap)
        schedule_w += gap
        # 日期行
        self.left_shift.config(fg="white", bg="black", bd=0, font=("黑体", int(fpx * 0.5)))
        self.right_shift.config(fg="white", bg="black", bd=0, font=("黑体", int(fpx * 0.5)))
        self.todate.config(font=("黑体", int(fpx * 0.5)), fg="white", bg="black")
        row_w = (
            gap
            + self.left_shift.winfo_reqwidth()
            + 10
            + self.todate.winfo_reqwidth()
            + 10
            + self.right_shift.winfo_reqwidth()
            + gap
        )
        row_h = max(self.left_shift.winfo_reqheight(), self.todate.winfo_reqheight()) + gap
        width = max(schedule_w, row_w + gap * 2)
        # 候选课程 6 个一行
        sel_w = max((lab.winfo_reqwidth() for lab in self.select_list), default=fpx)
        per_row = 6
        width = max(width, gap * 2 + per_row * (sel_w + gap))
        y = gap + row_h + gap
        # 放置课表
        x = (width - schedule_w) / 2
        for lab in self.labels:
            lab.place(x=x, y=y)
            x += lab.winfo_reqwidth()
        y += schedule_h + gap
        # 放置候选
        sy = y
        for i, lab in enumerate(self.select_list):
            lab.place(x=gap + (i % per_row) * (sel_w + gap), y=sy)
            if i % per_row == per_row - 1:
                sy += lab.winfo_reqheight() + gap
        if self.select_list:
            sy += max(lab.winfo_reqheight() for lab in self.select_list) + gap
        # 日期行居中放在课表上方
        date_x = (width - row_w) / 2
        self.left_shift.place(x=date_x, y=gap)
        self.todate.place(x=date_x + self.left_shift.winfo_reqwidth() + 10, y=gap)
        self.right_shift.place(
            x=date_x + self.left_shift.winfo_reqwidth() + 10 + self.todate.winfo_reqwidth() + 10,
            y=gap,
        )
        self.margin_left.place(x=0, y=gap, width=max(1, (width - row_w) / 2), height=row_h)
        self.margin_right.place(
            x=width - max(1, (width - row_w) / 2), y=gap, width=max(1, (width - row_w) / 2), height=row_h
        )
        self.editor_geom = [width, sy]
        return [width, sy]

    def turn_date(self, t=0):
        self.date_view += datetime.timedelta(days=t)
        ds = self.date_view.strftime("%Y-%m-%d")
        self.todate.config(text=ds)
        to_week = datetime.date(self.date_view.year, self.date_view.month, self.date_view.day).weekday()
        tokens = ["周"] + [self.week[to_week]] + ["|"] + self.classes[str(to_week + 1)]
        for rec in self.class_change:
            if rec[0] == ds:
                tokens = rec[1]
        self.changing_class = tokens
        for i, lab in enumerate(self.labels):
            if i < len(tokens):
                lab.config(text=tokens[i])
        self.layout_dirty = True

    def editor_start(self, event, source, name):
        self.moving_class += 1
        self.x_start = event.x_root
        self.y_start = event.y_root
        if self.moving_class == 1:
            fpx = self.font_px("center")
            self.ml.tkraise()
            self.ml.config(
                text=name,
                font=self.label_font(name, fpx, False),
                fg="yellow",
                bg="black",
                wraplength=fpx * 1.5,
            )
            self.ml.place(x=source.winfo_x(), y=source.winfo_y())

    def editor_move(self, event, source, name):
        self.ml.place(x=source.winfo_x() + event.x_root - self.x_start, y=source.winfo_y() + event.y_root - self.y_start)

    def editor_release(self, event, source, name):
        self.moving_class = 0
        dx = self.ml.winfo_x()
        dy = self.ml.winfo_y()
        best = -1
        best_dist = 1e18
        for i, lab in enumerate(self.labels):
            if lab.cget("text") in SPECIAL_TEXT:
                continue
            d = (lab.winfo_x() - dx) ** 2 + (lab.winfo_y() - dy) ** 2
            if d < best_dist:
                best_dist = d
                best = i
        if best != -1 and best_dist < max(10000, self.font_px("center") ** 2 * 8):
            self.changing_class[best] = name
            self.labels[best].config(text=name)
            self.save_change(self.date_view.strftime("%Y-%m-%d"), list(self.changing_class))
        self.ml.place_forget()

    def save_change(self, date_s, tokens):
        new = []
        for rec in self.class_change:
            if rec[0] != date_s:
                new.append(rec)
        new.append([date_s, tokens])
        self.class_change = new
        try:
            with open("data.json", "w", encoding="utf-8") as file:
                json.dump(new, file, ensure_ascii=False, indent=2)
        except Exception:
            pass
        # 当天视图同步
        if date_s == self.date_now:
            self.today_class = list(tokens)
        self.layout_dirty = True

    # ---------- 进度条 ----------
    def draw_progress(self):
        if self.canvas is None:
            return
        try:
            self.canvas.delete("all")
        except Exception:
            return
        if self.total_sec <= 0:
            return
        ratio = max(0.0, min(1.0, self.left_sec / self.total_sec))
        kind = self.content_kind
        if kind == "edge_bar":
            try:
                w, h = self.isl_frame.current_size(self.mainland)
            except Exception:
                return
            if self.nowgroup == "upper":
                length = w * ratio
                self.canvas.create_rectangle((w - length) / 2, 0, (w + length) / 2, h, fill="white", width=0)
            else:
                length = h * ratio
                self.canvas.create_rectangle(0, (h - length) / 2, w, (h + length) / 2, fill="white", width=0)
        elif kind == "edge_text":
            if self.after_class or not self.showt_onclass:
                try:
                    self.canvas.place_forget()
                except Exception:
                    pass
                return
            try:
                w, h = self.isl_frame.current_size(self.mainland)
            except Exception:
                return
            pw = self.progress_width
            if self.nowgroup == "upper":
                length = w * ratio
                self.canvas.create_rectangle((w - length) / 2, 0, (w + length) / 2, pw, fill="grey", width=0)
                self.canvas.place(x=0, y=h - pw, relwidth=1.0, height=pw)
            else:
                length = h * ratio
                self.canvas.create_rectangle(0, (h - length) / 2, pw, (h + length) / 2, fill="grey", width=0)
                if self.nowgroup == "left":
                    self.canvas.place(x=w - pw, y=0, width=pw, relheight=1.0)
                else:
                    self.canvas.place(x=0, y=0, width=pw, relheight=1.0)
        elif kind == "center_simple":
            try:
                w, h = self.isl_frame.current_size(self.mainland)
            except Exception:
                return
            fpx = self.font_px("center")
            bar_h = max(2, int(fpx / 3.0))
            length = w * ratio
            self.canvas.delete("all")
            self.canvas.create_rectangle((w - length) / 2, 0, (w + length) / 2, bar_h, fill="white", width=0)
            self.canvas.place(x=0, y=0, relwidth=1.0, height=bar_h)

    # ---------- 下课倒计时窗口 ----------
    def show_countdown(self):
        if self.content_kind == "edge_bar" or self.nowgroup == "center":
            self.hide_countdown()
            return
        if self.count_win is None:
            self.count_win = Toplevel(self.mainland)
            self.count_win.config(bg="black")
            self.count_label = Label(self.count_win, font=("黑体", self.b_size), fg="yellow", bg="black")
            self.count_label.pack()
        if self.closing or (self.after_class and self.showt_afterclass):
            self.count_label.config(text=fmt_mmss(self.left_sec))
        else:
            self.count_label.config(text=self.offw)
        self.count_label.update_idletasks()
        w = self.count_label.winfo_reqwidth() + 20
        h = self.count_label.winfo_reqheight() + 20
        self.isl_frame.to_isl(self.count_win, w, h, self.nowgroup, flush=False)
        top = self.ontop_afterclass if self.after_class else self.ontop_onclass
        try:
            self.count_win.attributes("-topmost", bool(top))
        except Exception:
            pass

    def hide_countdown(self):
        if self.count_win is not None:
            self.isl_frame.del_isl(self.count_win)
            self.count_win = None
            self.count_label = None

    # ---------- 布局 / 目标位置 ----------
    def zone_of(self, x, y):
        if x < self.isl_frame.screen_width / 4:
            return "left"
        if x > self.isl_frame.screen_width * 3 / 4:
            return "right"
        if y < self.isl_frame.screen_height / 4:
            return "upper"
        return "center"

    def set_style(self, value, persist=False):
        value = bool(value)
        if value != self.second_style:
            self.second_style = value
            self.layout_dirty = True
        if persist:
            try:
                self.config["secondStyle"] = value
                with open("config.json", "w", encoding="utf-8") as file:
                    json.dump(self.config, file, ensure_ascii=False, indent=2)
            except Exception:
                pass

    def persist_style(self):
        self.set_style(self.second_style, persist=True)

    def drag_press(self, event):
        self.press_point = (event.x_root, event.y_root)
        # 以窗口当前 dock 为起始区域：单击切换不会因为点到了屏幕 1/4 分界线而误换位置，
        # 真正拖动并越过区域时才切换。
        self.drag_zone = self.nowgroup
        self.drag_ref = (event.x_root, event.y_root)
        self.drag_click = True
        self.drag_moved = 0
        self.dragging = True
        self.style_applied = False
        self.isl_frame.drag_now(self.mainland)
        self.layout_dirty = True

    def drag_motion(self, event):
        if not self.dragging:
            return
        mx, my = event.x_root, event.y_root
        if self.press_point:
            self.drag_moved = max(
                self.drag_moved,
                (mx - self.press_point[0]) ** 2 + (my - self.press_point[1]) ** 2,
            )
        zone = self.zone_of(mx, my)
        if zone != self.drag_zone:
            # 跨区：区域基准写入“起始点”，并按该区默认样式切换
            self.drag_zone = zone
            self.nowgroup = zone
            self.set_style(bool(self.drag_defaults.get(zone, False)), persist=True)
            self.style_applied = True
            size = self.isl_frame.island_size(self.mainland)
            base = self.base_center(zone, size[0], size[1])
            self.drag_ref = (base[0], base[1])
            self.layout_dirty = True
        if mx - self.press_point[0] > 3 or my - self.press_point[1] > 3:
            self.drag_click = False
        if not self.drag_click and not self.style_applied:
            self.set_style(bool(self.drag_defaults.get(self.drag_zone, False)), persist=False)
            self.style_applied = True
            self.layout_dirty = True
        self.recompute_drag_goal(mx, my)

    def base_center(self, dock, w, h):
        flush = self.second_style and dock in ("left", "upper", "right")
        if dock == "left":
            return [(0 if flush else self.isl_frame.screen_gap) + w / 2, self.isl_frame.screen_height / 2]
        if dock == "right":
            return [
                self.isl_frame.screen_width - (0 if flush else self.isl_frame.screen_gap) - w / 2,
                self.isl_frame.screen_height / 2,
            ]
        if dock == "upper":
            return [self.isl_frame.screen_width / 2, (0 if flush else self.isl_frame.screen_gap) + h / 2]
        return [self.isl_frame.screen_width / 2, self.isl_frame.screen_height / 2]

    def recompute_drag_goal(self, mx, my):
        size = self.isl_frame.island_size(self.mainland)
        w, h = size
        base = self.base_center(self.drag_zone, w, h)
        ref = self.drag_ref
        dx = mx - ref[0]
        dy = my - ref[1]
        fx = 0.1 / (abs(dx) / max(self.isl_frame.screen_width, 1) + 0.1)
        fy = 0.1 / (abs(dy) / max(self.isl_frame.screen_height, 1) + 0.1)
        cx = base[0] + dx * fx
        cy = base[1] + dy * fy
        x = cx - w / 2
        y = cy - h / 2
        self.isl_frame.set_drag_goal(self.mainland, x, y)

    def drag_release(self, event):
        if not self.dragging:
            return
        clicked = self.drag_click and self.drag_moved <= 9
        zone = self.drag_zone
        self.dragging = False
        self.isl_frame.drag_end(self.mainland)
        if clicked:
            # 边缘三侧单击任意处切换；center 编辑器由把手处理
            if zone in ("left", "upper", "right") or (
                zone == "center" and self.content_kind == "center_simple"
            ) or (
                zone == "center"
                and self.content_kind == "center_editor"
                and event.widget in (self.margin_left, self.margin_right)
            ):
                self.set_style(not self.second_style, persist=True)
        else:
            if self.style_applied:
                self.persist_style()
            else:
                self.set_style(bool(self.drag_defaults.get(zone, False)), persist=True)
        self.layout_dirty = True

    def bind_drag(self):
        if self.mainland is None:
            return
        targets = []
        if self.nowgroup == "center" and self.second_style:
            for m in (self.margin_left, self.margin_right):
                if m is not None:
                    targets.append(m)
        else:
            targets = [self.mainland, self.canvas] + list(self.labels)
            if self.clock_label is not None:
                targets.append(self.clock_label)
            targets = [t for t in targets if t is not None]
        for t in set(targets):
            t.bind("<ButtonPress-1>", self.drag_press)
            t.bind("<B1-Motion>", self.drag_motion)
            t.bind("<ButtonRelease-1>", self.drag_release)

    # ---------- 主渲染 ----------
    def apply_layout(self):
        cur_fpx = self.font_px()
        if (
            self.content_kind in ("edge_text", "center_simple", "center_editor")
            and getattr(self, "_last_fpx", None) is not None
            and cur_fpx != self._last_fpx
        ):
            self.clear_content()
            self.content_kind = None
        if (
            self.content_kind == "edge_text"
            and getattr(self, "_last_dock", None) != self.nowgroup
            and self._last_dock is not None
        ):
            self.clear_content()
            self.content_kind = None
        self._last_fpx = cur_fpx
        self._last_dock = self.nowgroup
        self.ensure_content()
        if self.content_kind == "edge_bar":
            vertical = self.nowgroup in ("left", "right")
            fpx = self.font_px()
            # 全长为“显示课表”时占用的长度，厚度与旧 u 一致
            full_w, full_h = self.measure_tokens(self.today_class, fpx, vertical)
            w = self.progress_width if vertical else full_w
            h = full_h if vertical else self.progress_width
            self.hide_countdown()
            self.canvas.place(x=0, y=0, relwidth=1.0, relheight=1.0)
            self.isl_frame.to_isl(self.mainland, w, h, self.nowgroup, flush=True)
        elif self.content_kind == "edge_text":
            if not self.labels:
                self.build_edge_text_widgets()
            self.place_text_widgets()
            w, h = self.edge_text_geom[0], self.edge_text_geom[1]
            self.isl_frame.to_isl(self.mainland, w, h, self.nowgroup, flush=False)
            if self.after_class or self.closing:
                self.show_countdown()
            else:
                self.hide_countdown()
        elif self.content_kind == "center_simple":
            if not self.labels:
                self.build_center_widgets()
            geom = self.place_center_widgets()
            w, h = geom
            self.hide_countdown()
            self.isl_frame.to_isl(self.mainland, w, h, "center", flush=False)
        else:
            geom = self.layout_editor()
            w, h = geom
            self.hide_countdown()
            self.isl_frame.to_isl(self.mainland, w, h, "center", flush=False)
        self.bind_drag()
        self.layout_dirty = False

    def update_clock(self):
        if self.clock_label is not None:
            text = datetime.datetime.now().strftime("%H:%M:%S")
            if self.clock_label.cget("text") != text:
                self.clock_label.config(text=text)

    def refresh(self):
        self.timer = time.time()
        while True:
            if os.path.exists(".stop_signal"):
                break
            now = datetime.datetime.now()
            self.update_state(now)
            self.tick += 1
            # 状态/下课切换变化会触发重排
            changed_state = (
                self.after_class != getattr(self, "_last_after", None)
                or self.closing != getattr(self, "_last_closing", None)
                or self.state_index != getattr(self, "_last_idx", None)
            )
            self._last_after = self.after_class
            self._last_closing = self.closing
            self._last_idx = self.state_index
            if changed_state:
                self.layout_dirty = True
            # 60Hz 逻辑、30Hz 渲染、布局最多 10Hz + 状态变化即时
            if self.layout_dirty or self.tick % 6 == 0:
                self.apply_layout()
            self.update_clock()
            if self.tick % 2 == 0:
                self.draw_progress()
                self.isl_frame.refresh_isl()
            sleep = max(0.0, 1 / 60 - (time.time() - self.timer))
            time.sleep(sleep)
            self.timer = time.time()


if os.path.exists(".stop_signal"):
    os.remove(".stop_signal")

app = calendar()
try:
    app.start()
    app.refresh()
except SystemExit:
    pass
