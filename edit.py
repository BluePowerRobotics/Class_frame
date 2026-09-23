import tkinter as tk
from tkinter import ttk, Scrollbar, messagebox, simpledialog, Menu
import json
import datetime

import positions as P

#row+1为实际行数


def _flag_of(config, key):
    """读布尔型旧键（值可能是 [0]/[1] 或裸 True/False）。"""
    value = config.get(key, False)
    if isinstance(value, (list, tuple)):
        value = value[0] if value else False
    return bool(value)


def _pos_of(config, key):
    """读旧的位置键，兼容 a/b/c/f/u 与数组写法。"""
    value = config.get(key, "upper")
    if isinstance(value, (list, tuple)):
        value = value[0] if value else "upper"
    mapping = {"a": "left", "b": "upper", "c": "right", "f": "center", "u": "upper"}
    return mapping.get(str(value), str(value))

def save_settings(entries):
    """保存参数设置到字典"""
    flags = {"下课显示倒计时", "下课置顶", "上课置顶", "上课显示倒计条"}
    numbers = {
        "文字大小",
        "竖直显示的文字大小",
        "进度条宽度",
        "上课提示时长",
        "下课提示时长",
        "时间偏移（秒）",
        "left上课缩放",
        "left下课缩放",
        "upper上课缩放",
        "upper下课缩放",
        "center缩放",
    }
    positions = {"上课默认位置", "下课默认位置"}
    for key, entry in entries.items():
        raw = entry.get()
        if key in ("全局日程·上课", "全局日程·下课"):
            style = P.from_label(raw) or P.UPPER_TABLE
            config.setdefault("全局日程", {})[key.split("·")[1]] = style
            # 同步旧键，保证旧版 Python / 旧数据仍读得懂
            on_class = key.endswith("上课")
            config["上课默认位置" if on_class else "下课默认位置"] = [P.dock_of(style)]
            config["上课默认使用secondStyle" if on_class else "下课默认使用secondStyle"] = \
                P.second_style_of(style)
        elif key in ("上课默认使用secondStyle", "下课默认使用secondStyle"):
            config[key] = raw == "是"
        elif key in positions:
            config[key] = [DOCK_TO_KEY.get(raw, raw)]
        elif key in flags:
            try:
                config[key] = [int(raw)]
            except Exception:
                config[key] = [1 if raw == "是" else 0]
        elif key in numbers:
            try:
                value = int(raw)
            except Exception:
                try:
                    value = float(raw)
                except Exception:
                    value = raw
            config[key] = [value]
        else:
            config[key] = [raw]
    with open("config.json", "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
    messagebox.showinfo("保存成功", "参数设置已保存！")

def save_schedule():
    global periods,config
    """保存课表"""
    schedule_data = {'1':[],'2':[],'3':[],'4':[],'5':[],'6':[],'7':[]}
    
    # 遍历所有行
    for row_idx, period in enumerate(periods):
        if period!="|":
            # 遍历所有列
            for i in range(7):
                widget = comboboxes[row_idx+1][i+1]
                if widget is None or not hasattr(widget, "get"):
                    continue
                value = widget.get()
                # 添加数据
                schedule_data[str(i+1)].append(value)
        else:
            for i in range(7):
                schedule_data[str(i+1)].append("|")
    #写入文件
    config["日程表"]=schedule_data
    file = open("config.json", "w", encoding='utf-8')
    json.dump(config,file,indent=4,ensure_ascii=False)
    file.close()
    # 显示保存成功消息
    messagebox.showinfo("保存成功", "课表数据已保存！")


DOCKS = ["left", "upper", "right", "center"]
DOCK_OPTIONS = [("左侧", "left"), ("上方", "upper"), ("右侧", "right"), ("居中", "center")]
DOCK_TO_KEY = dict(DOCK_OPTIONS)
DOCK_TO_TEXT = {v: k for k, v in DOCK_OPTIONS}


def migrate_config(cfg):
    pos_map = {"a": "left", "b": "upper", "c": "right", "f": "center", "u": "upper"}
    for key in ("上课默认位置", "下课默认位置"):
        if key in cfg:
            old = cfg[key][0] if isinstance(cfg[key], list) and cfg[key] else cfg[key]
            cfg[key] = [pos_map.get(old, old if old in DOCKS else "upper")]
    old_global_style = cfg.get("secondStyle", False)
    if isinstance(old_global_style, (list, tuple)):
        old_global_style = old_global_style[0] if old_global_style else False
    cfg.pop("secondStyle", None)
    cfg.setdefault("上课默认使用secondStyle", bool(old_global_style))
    cfg.setdefault("下课默认使用secondStyle", bool(old_global_style))
    cfg.setdefault("上课提示时长", [6])
    cfg.setdefault("下课提示时长", [6])
    cfg.setdefault("时间偏移（秒）", [0])
    # 需求确认：拖入区域的默认样式不再参与三层体系，直接从配置里去掉
    cfg.pop("拖动默认样式", None)
    for d in DOCKS:
        cfg.pop("拖入%s时使用secondStyle" % d, None)
    # 三层位置表：①全局由旧键迁移，②每日/③每课缺失时补成"全默认"
    cfg.setdefault("全局日程", {
        "上课": P.from_legacy(_pos_of(cfg, "上课默认位置"),
                             _flag_of(cfg, "上课默认使用secondStyle")),
        "下课": P.from_legacy(_pos_of(cfg, "下课默认位置"),
                             _flag_of(cfg, "下课默认使用secondStyle")),
    })
    lessons = len(cfg.get("开始时间") or [])
    for table_key in ("每日日程", "单课日程"):
        if table_key not in cfg:
            cfg[table_key] = P.write_table([[P.DEFAULT] * lessons for _ in range(7)],
                                           lessons)
        else:
            cfg[table_key] = P.write_table(P.read_table(cfg[table_key], lessons), lessons)
    old_scale = 1.0
    if "上课放大倍率" in cfg:
        v = cfg["上课放大倍率"]
        old_scale = v[0] if isinstance(v, list) and v else v
    for key, default in (
        ("left上课缩放", old_scale),
        ("left下课缩放", 1.0),
        ("upper上课缩放", old_scale),
        ("upper下课缩放", 1.0),
        ("center缩放", 1.0),
    ):
        cfg.setdefault(key, [default])
    cfg.setdefault("进度条宽度", [8])
    return cfg


def nth_lesson_pos(day, k):
    """返回第 k(0 起) 个非 '|' 课节的下标；不存在返回 None。"""
    count = 0
    for i, t in enumerate(day):
        if t != "|":
            if count == k:
                return i
            count += 1
    return None


def schedule_lesson_count(day):
    return sum(1 for t in day if t != "|")


def insert_schedule_row_after(lesson_idx):
    """在 config 七天课表的第 lesson_idx 节课后直接插入一行 '无'（不移动 '|'）。"""
    for key in [str(i) for i in range(1, 8)]:
        day = list(config["日程表"][key])
        pos = nth_lesson_pos(day, lesson_idx)
        if pos is None:
            continue
        day.insert(pos + 1, "无")
        config["日程表"][key] = day


def delete_schedule_row(lesson_idx):
    """删除 config 七天课表第 lesson_idx 节课。"""
    for key in [str(i) for i in range(1, 8)]:
        day = list(config["日程表"][key])
        pos = nth_lesson_pos(day, lesson_idx)
        if pos is not None:
            del day[pos]
            config["日程表"][key] = day


def lesson_context(lesson_idx):
    lines = []
    for key in [str(i) for i in range(1, 8)]:
        day = config["日程表"][key]
        pos = nth_lesson_pos(day, lesson_idx)
        if pos is None:
            lines.append(f"周{key}：无")
        else:
            lines.append(f"周{key}：{day[pos]}")
    return "\n".join(lines)


def parse_time(text):
    try:
        h, m = str(text).strip().split(":")
        return [int(h), int(m)]
    except Exception:
        return None


def fmt_time(pair):
    try:
        return f"{int(pair[0]):02d}:{int(pair[1]):02d}"
    except Exception:
        return str(pair)
    

_daily_open = False
_daily_day = 1
_daily_widgets = []


def _daily_style(day, lesson):
    """②每日里某天某节的形态；没有覆盖时返回 default。"""
    lessons = len(config.get("开始时间") or [])
    table = config.get("每日日程") or {}
    row = P.read_row(table.get(str(day)), lessons)
    return row[lesson] if 0 <= lesson < len(row) else P.DEFAULT


def _set_daily_style(day, lesson, style, label_widget):
    table = config.setdefault("每日日程", {})
    lessons = len(config.get("开始时间") or [])
    row = P.read_row(table.get(str(day)), lessons)
    if lesson < 0 or lesson >= lessons:
        return
    row[lesson] = style
    table[str(day)] = P.write_row(row, lessons)
    label_widget.set(P.label_of(style))
    with open("config.json", "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)


def _toggle_daily_column():
    global _daily_open
    _daily_open = not _daily_open
    _rebuild_schedule()


def _choose_daily_day(day):
    global _daily_day, _daily_open
    _daily_day = day
    _daily_open = True
    _rebuild_schedule()


def _rebuild_schedule():
    """重建课表网格（②每日那一列开合时也走这里）。"""
    global comboboxes
    for widget in _daily_widgets:
        try:
            widget.destroy()
        except Exception:
            pass
    _daily_widgets.clear()
    # 清掉旧网格
    for row in comboboxes:
        for widget in row:
            if widget is not None:
                try:
                    widget.destroy()
                except Exception:
                    pass
    comboboxes = [[None for _ in range(len(days) + 2)] for _ in range(len(periods) + 1)]

    label = ttk.Label(scrollable_frame, text=" ", width=10, relief="solid", padding=5)
    label.grid(row=0, column=0, sticky="nsew")
    comboboxes[0][0] = label
    for col, day in enumerate(days):
        label = ttk.Label(scrollable_frame, text=day, width=10, relief="solid", padding=5)
        label.grid(row=0, column=col + 1, sticky="nsew")
        comboboxes[0][col + 1] = label

    # 周日的右边：②每日那一列的开关（默认收起）
    daily_header = ttk.Label(
        scrollable_frame,
        text=("× 周" + P.WEEK_NAMES[_daily_day - 1]) if _daily_open else "每日",
        width=10, relief="solid", padding=5,
    )
    daily_header.grid(row=0, column=8, sticky="nsew")
    daily_header.bind("<Button-1>", lambda e: _toggle_daily_column() if _daily_open
                      else _choose_daily_day_menu())
    _daily_widgets.append(daily_header)

    lesson = -1
    for row, period in enumerate(periods):
        if period != "|":
            lesson += 1
            period_label = ttk.Label(scrollable_frame, text=period, relief="solid", padding=5)
            period_label.grid(row=row + 1, column=0, sticky="nsew")
            period_label.bind("<Button-1>", lambda e, row=row: show_period_menu(e, row))
            comboboxes[row + 1][0] = period_label

            for col in range(7):
                combobox = ttk.Combobox(scrollable_frame, values=options,
                                        state="readonly", width=8)
                combobox.grid(row=row + 1, column=col + 1, padx=1, pady=1, sticky="nsew")
                combobox.set(config["日程表"][str(col + 1)][row])
                comboboxes[row + 1][col + 1] = combobox

            if _daily_open:
                cell = ttk.Combobox(scrollable_frame, values=P.STYLE_LABELS_WITH_DEFAULT,
                                    state="readonly", width=8)
                cell.set(P.label_of(_daily_style(_daily_day, lesson)))
                cell.grid(row=row + 1, column=8, padx=1, pady=1, sticky="nsew")
                cell.bind("<<ComboboxSelected>>",
                          lambda e, w=cell, l=lesson: _set_daily_style(
                              _daily_day, l, P.STYLES_WITH_DEFAULT[
                                  P.STYLE_LABELS_WITH_DEFAULT.index(w.get())], w))
                _daily_widgets.append(cell)
        else:
            for col in range(9):
                sepLine = ttk.Separator(scrollable_frame, orient='horizontal')
                sepLine.grid(row=row + 1, column=col, columnspan=1, sticky='ew', pady=(10, 10))
                comboboxes[row + 1][col] = sepLine


def _choose_daily_day_menu():
    win = tk.Toplevel()
    win.title("②每日：选择要编辑的星期")
    for i, name in enumerate(P.WEEK_NAMES):
        ttk.Button(win, text="周" + name, width=10,
                   command=lambda d=i + 1, w=win: (w.destroy(), _choose_daily_day(d))).pack(
            padx=12, pady=3)


def create_schedule_tab(parent, text):
    global comboboxes, periods, days, scrollable_frame, canvas
    # 创建表格内容
    num = 1
    periods = []
    days = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    for i in config["日程表"]["1"]:
        if i != '|':
            periods.append(str(num))
            num += 1
        else:
            periods.append("|")
    options = config["更换选项"]
    comboboxes = [[None for _ in range(len(days) + 2)] for _ in range(len(periods) + 1)]

    # 创建外层Frame
    frame = ttk.Frame(parent)

    # 创建Canvas和滚动条
    canvas = tk.Canvas(frame)
    scrollbar = ttk.Scrollbar(frame, orient="vertical", command=canvas.yview)
    scrollable_frame = ttk.Frame(canvas)

    # 配置Canvas
    canvas.configure(yscrollcommand=scrollbar.set)
    canvas.create_window((0, 0), window=scrollable_frame, anchor="nw")

    _rebuild_schedule()

    # 配置网格权重
    for i in range(9):
        scrollable_frame.grid_columnconfigure(i, weight=1)
    for i in range(8):
        scrollable_frame.grid_rowconfigure(i, weight=1)

    # 布局Canvas和滚动条
    canvas.grid(row=0, column=0, sticky="nsew")
    scrollbar.grid(row=0, column=1, sticky="ns")

    # 配置Frame权重
    frame.grid_rowconfigure(0, weight=1)
    frame.grid_columnconfigure(0, weight=1)

    # 更新可滚动区域
    scrollable_frame.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))

    #创建保存按钮
    save_button = ttk.Button(frame, text="保存", command=save_schedule, width=15)
    save_button.grid(row=1, column=0, padx=10, pady=10, sticky="se")

    return frame


def show_period_menu(event, row_index):
    """显示节次标签的右键菜单"""
    menu = Menu(event.widget, tearoff=0)
    menu.add_command(
        label="添加课程", 
        command=lambda: add_course(row_index)
    )
    menu.add_command(
        label="删除课程行", 
        command=lambda: del_course(row_index)
    )
    menu.add_command(
        label="添加分割线", 
        command=lambda: add_separator(row_index)
    )
    menu.add_command(
        label="删除分割线", 
        command=lambda: del_separator(row_index)
    )
    menu.post(event.x_root, event.y_root)


def _renumber_periods():
    """按 periods 里的课程行重新编号。"""
    num = 1
    for i, item in enumerate(periods):
        if item == "|":
            continue
        periods[i] = str(num)
        num += 1


def add_separator(row_index):
    """在指定行下方添加分割线。"""
    periods.insert(row_index + 1, "|")
    _rebuild_schedule()
    messagebox.showinfo("添加成功", "已在下发添加新分割线")


def del_separator(row_index):
    """删除指定行下方的分割线。"""
    if row_index + 1 < len(periods) and periods[row_index + 1] == "|":
        del periods[row_index + 1]
        _renumber_periods()
        _rebuild_schedule()
    else:
        messagebox.showwarning("无法删除", "这一行下方不是分割线")


def add_course(row_index):
    """在指定行下方添加新课程行。"""
    periods.insert(row_index + 1, "")
    _renumber_periods()
    _rebuild_schedule()
    messagebox.showinfo("添加成功", "已添加新的课程行")


def del_course(row_index):
    """删除指定课程行。"""
    if len([p for p in periods if p != "|"]) <= 1:
        messagebox.showwarning("无法删除", "至少保留一行")
        return
    del periods[row_index]
    _renumber_periods()
    _rebuild_schedule()
    messagebox.showinfo("添加成功", "已删除该课程行")

def create_time_settings(parent):
    """时间设置：支持成对插入（同时写入课表一整行“无”）与成对删除。"""
    frame = ttk.Frame(parent, padding=20)
    ttk.Label(frame, text="上课时间", font=("Arial", 10, "bold")).grid(row=0, column=0, padx=5, pady=5)
    ttk.Label(frame, text="下课时间", font=("Arial", 10, "bold")).grid(row=0, column=2, padx=5, pady=5)
    ttk.Label(
        frame,
        text="插入/删除会成对作用于两个时间列表，并在课表同一位置加入/移除一整行。\n“|”只作为分隔符，不计入课节序号。",
        foreground="#666666",
    ).grid(row=2, column=0, columnspan=4, pady=8)

    def make_listbox(container, values):
        lb = tk.Listbox(container, width=20, height=14, selectmode=tk.SINGLE)
        sb = ttk.Scrollbar(container, orient="vertical", command=lb.yview)
        lb.configure(yscrollcommand=sb.set)
        for v in values:
            lb.insert(tk.END, v)
        lb.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb.pack(side=tk.RIGHT, fill=tk.Y)
        return lb

    start_container = ttk.Frame(frame)
    start_container.grid(row=1, column=0, padx=8, pady=5, sticky="nsew")
    start_lb = make_listbox(start_container, [fmt_time(t) for t in config["开始时间"]])

    end_container = ttk.Frame(frame)
    end_container.grid(row=1, column=2, padx=8, pady=5, sticky="nsew")
    end_lb = make_listbox(end_container, [fmt_time(t) for t in config["结束时间"]])

    def refresh_lists(keep=0):
        start_lb.delete(0, tk.END)
        end_lb.delete(0, tk.END)
        for t in config["开始时间"]:
            start_lb.insert(tk.END, fmt_time(t))
        for t in config["结束时间"]:
            end_lb.insert(tk.END, fmt_time(t))
        if 0 <= keep < start_lb.size():
            start_lb.selection_set(keep)
        if 0 <= keep < end_lb.size():
            end_lb.selection_set(keep)

    def current_index():
        sel = start_lb.curselection() or end_lb.curselection()
        return sel[0] if sel else None

    def persist():
        with open("config.json", "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)

    def insert_pair():
        idx = current_index()
        if idx is None:
            messagebox.showwarning("未选择", "请先在任一列表中选中一个时间，再在其后插入。")
            return
        new_start = simpledialog.askstring("插入", "请输入新的上课开始时间（HH:MM）：")
        new_end = simpledialog.askstring("插入", "请输入新的下课时间（HH:MM）：")
        if new_start is None or new_end is None:
            return
        p1 = parse_time(new_start)
        p2 = parse_time(new_end)
        if p1 is None or p2 is None:
            messagebox.showerror("格式错误", "时间格式应为 HH:MM")
            return
        pos = idx + 1
        config["开始时间"].insert(pos, p1)
        config["结束时间"].insert(pos, p2)
        insert_schedule_row_after(idx)
        persist()
        refresh_lists(pos)
        messagebox.showinfo("成功", f"已在第 {idx + 1} 节之后插入 {fmt_time(p1)}~{fmt_time(p2)}，并加入一行“无”。")

    def delete_pair():
        idx = current_index()
        if idx is None:
            messagebox.showwarning("未选择", "请先选择要删除的时间。")
            return
        if idx >= len(config["开始时间"]) or idx >= len(config["结束时间"]):
            return
        detail = (
            f"将删除第 {idx + 1} 节：{fmt_time(config['开始时间'][idx])}~{fmt_time(config['结束时间'][idx])}\n\n"
            f"七天对应课表：\n{lesson_context(idx)}\n\n确认删除？"
        )
        if not messagebox.askyesno("确认删除", detail):
            return
        del config["开始时间"][idx]
        del config["结束时间"][idx]
        delete_schedule_row(idx)
        persist()
        refresh_lists()
        messagebox.showinfo("成功", "已删除该时间对与对应课表行。")

    def edit_side(side):
        idx = current_index()
        if idx is None:
            messagebox.showwarning("未选择", "请先选择要编辑的时间。")
            return
        current = config["开始时间" if side == "start" else "结束时间"][idx]
        new = simpledialog.askstring(
            "编辑",
            "请输入新的%s时间（HH:MM）：" % ("上课" if side == "start" else "下课"),
            initialvalue=fmt_time(current),
        )
        if not new:
            return
        p = parse_time(new)
        if p is None:
            messagebox.showerror("格式错误", "时间格式应为 HH:MM")
            return
        if side == "start":
            config["开始时间"][idx] = p
        else:
            config["结束时间"][idx] = p
        persist()
        refresh_lists(idx)
        messagebox.showinfo("成功", "已修改。")

    start_btns = ttk.Frame(frame)
    start_btns.grid(row=1, column=1, padx=5, sticky="ns")
    end_btns = ttk.Frame(frame)
    end_btns.grid(row=1, column=3, padx=5, sticky="ns")
    for btn_frame, side, label in (
        (start_btns, "start", "上课"),
        (end_btns, "end", "下课"),
    ):
        ttk.Button(btn_frame, text=f"编辑{label}时间", command=lambda s=side: edit_side(s), width=10).pack(pady=4)
        ttk.Button(btn_frame, text="其后插入（成对）", command=insert_pair, width=13).pack(pady=4)
        ttk.Button(btn_frame, text="删除（成对）", command=delete_pair, width=13).pack(pady=4)

    frame.columnconfigure(0, weight=1)
    frame.columnconfigure(1, weight=1)
    frame.columnconfigure(2, weight=1)
    frame.columnconfigure(3, weight=1)
    frame.rowconfigure(1, weight=1)
    return frame


def create_parameter_settings(parent):
    """参数：显示与置顶/字号/停靠位置/拖入样式。"""
    outer = ttk.Frame(parent)
    canvas = tk.Canvas(outer, highlightthickness=0)
    scrollbar = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
    frame = ttk.Frame(canvas, padding=12)
    canvas.configure(yscrollcommand=scrollbar.set)
    canvas.create_window((0, 0), window=frame, anchor="nw")
    canvas.grid(row=0, column=0, sticky="nsew")
    scrollbar.grid(row=0, column=1, sticky="ns")
    outer.grid_rowconfigure(0, weight=1)
    outer.grid_columnconfigure(0, weight=1)
    frame.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
    sections = [
        ("显示与置顶", [
            ("上课显示倒计条", "上课时显示课堂进度条", "bool"),
            ("下课显示倒计时", "课间/放学时显示倒计时", "bool"),
            ("上课置顶", "上课时窗口置顶", "bool"),
            ("下课置顶", "课间/放学时窗口置顶", "bool"),
            ("上课提示时长", "上课提示显示时长（秒）", "number"),
            ("下课提示时长", "下课提示显示时长（秒）", "number"),
        ]),
        ("字号与尺寸", [
            ("文字大小", "横向基准字号（上方/居中）", "number"),
            ("竖直显示的文字大小", "侧边基准字号（左/右）", "number"),
            ("进度条宽度", "第二样式进度条厚度", "number"),
            ("left上课缩放", "左侧·上课中缩放", "number"),
            ("left下课缩放", "左侧·课间/放学缩放", "number"),
            ("upper上课缩放", "上方·上课中缩放", "number"),
            ("upper下课缩放", "上方·课间/放学缩放", "number"),
            ("center缩放", "居中窗口缩放", "number"),
        ]),
        # ①全局：替代原来的"上课/下课默认位置 + 使用secondStyle"，
        # 也取代"拖入区域时默认使用的样式"（新三层不再依赖它）
        ("① 全局（未设置②每日/③每课时使用）", [
            ("全局日程·上课", "上课时形态", "style"),
            ("全局日程·下课", "课间/放学时形态", "style"),
        ]),
        ("提示文字", [
            ("开始提示", "上课前提示文字", "text"),
            ("结束提示", "下课时提示文字", "text"),
            ("结尾提示", "放学提示文字", "text"),
        ]),
        ("时间校正", [
            ("时间偏移（秒）", "系统时间偏移（秒，默认0；非联网教室时钟错位时使用）", "number"),
        ]),
    ]

    entries = {}
    grid_row = 0

    def value_of(key):
        val = config.get(key, False)
        if isinstance(val, (list, tuple)):
            val = val[0] if val else False
        return val

    for title, items in sections:
        ttk.Label(frame, text=title, font=("Arial", 10, "bold")).grid(
            row=grid_row, column=0, columnspan=4, padx=5, pady=(8, 2), sticky="w"
        )
        grid_row += 1
        col = 0
        for key, label, kind in items:
            ttk.Label(frame, text=label + ":", anchor="e").grid(
                row=grid_row, column=col, padx=5, pady=2, sticky="e"
            )
            if kind == "bool":
                current = value_of(key)
                cb = ttk.Combobox(frame, width=5, state="readonly", values=["否", "是"])
                cb.set("是" if current else "否")
                cb.grid(row=grid_row, column=col + 1, padx=5, pady=2, sticky="w")
                entries[key] = cb
            elif kind == "style":
                # ①全局：没有"全局日程"时由旧的默认位置 + secondStyle 迁移
                on_class = key.endswith("上课")
                global_cfg = config.get("全局日程")
                if isinstance(global_cfg, dict) and key.split("·")[1] in global_cfg:
                    current = P.normalize(global_cfg[key.split("·")[1]])
                else:
                    current = P.from_legacy(
                        _pos_of(config, "上课默认位置" if on_class else "下课默认位置"),
                        _flag_of(config,
                                 "上课默认使用secondStyle" if on_class
                                 else "下课默认使用secondStyle"),
                    )
                cb = ttk.Combobox(frame, width=8, state="readonly",
                                  values=P.STYLE_LABELS)
                cb.set(P.label_of(current))
                cb.grid(row=grid_row, column=col + 1, padx=5, pady=2, sticky="w")
                entries[key] = cb
            elif kind == "dock":
                current = value_of(key)
                cb = ttk.Combobox(
                    frame, width=10, state="readonly", values=[k for k in DOCK_TO_KEY]
                )
                cb.set(DOCK_TO_TEXT.get(str(current), "上方"))
                cb.grid(row=grid_row, column=col + 1, padx=5, pady=2, sticky="w")
                entries[key] = cb
            else:
                val = config.get(key, [""])
                text = str(val[0] if isinstance(val, list) and val else val)
                entry = ttk.Entry(frame, width=14)
                entry.insert(0, text)
                entry.grid(row=grid_row, column=col + 1, padx=5, pady=2, sticky="w")
                entries[key] = entry
            col = 2 if col == 0 else 0
            if col == 0:
                grid_row += 1
        if col == 2:
            grid_row += 1

    ttk.Button(
        frame,
        text="保存参数",
        command=lambda: save_settings(entries),
        width=15,
    ).grid(row=grid_row, column=3, padx=10, pady=10, sticky="se")
    ttk.Button(
        frame,
        text="③每课（文本框整表编辑）",
        command=show_per_lesson_editor,
        width=22,
    ).grid(row=grid_row + 1, column=0, columnspan=3, padx=10, pady=(0, 10), sticky="w")
    for i in range(4):
        frame.columnconfigure(i, weight=1)
    return outer


def show_per_lesson_editor():
    """③每课：文本框整表编辑（不做图形界面）。每行一天，逗号分隔，用内部值。"""
    lessons = len(config.get("开始时间") or [])
    rows = per_lesson_rows()
    win = tk.Toplevel()
    win.title("③每课（每行一天，逗号分隔；默认 = 跟随②每日）")
    win.geometry("720x320")
    text = tk.Text(win, wrap="word")
    for day in range(7):
        row = rows[day] if day < len(rows) else []
        values = [row[i] if i < len(row) else P.DEFAULT for i in range(lessons)]
        text.insert("end", "周%s:%s\n" % (P.WEEK_NAMES[day], ",".join(values)))
    text.pack(fill="both", expand=True, padx=8, pady=8)

    def apply():
        for line in text.get("1.0", "end").splitlines():
            line = line.strip()
            if not line:
                continue
            if ":" in line:
                day_text, rest = line.split(":", 1)
            elif "：" in line:
                day_text, rest = line.split("：", 1)
            else:
                continue
            day_text = day_text.strip().replace("周", "")
            if day_text not in P.WEEK_NAMES:
                continue
            day = P.WEEK_NAMES.index(day_text) + 1
            parts = [p.strip() for p in rest.replace("，", ",").split(",")]
            row = P.read_row(parts, lessons)
            config.setdefault("单课日程", {})[str(day)] = P.write_row(row, lessons)
        with open("config.json", "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        messagebox.showinfo("保存成功", "③每课已保存")
        win.destroy()

    ttk.Button(win, text="保存", command=apply, width=15).pack(pady=(0, 8))


def create_settings_tab(parent):
    """创建设置页面的Notebook（包含时间和参数两个子页）"""
    # 创建Notebook作为设置页
    settings_notebook = ttk.Notebook(parent)
    
    # 创建时间设置页
    time_frame = create_time_settings(settings_notebook)
    settings_notebook.add(time_frame, text="时间")
    
    # 创建参数设置页
    param_frame = create_parameter_settings(settings_notebook)
    settings_notebook.add(param_frame, text="参数")
    
    return settings_notebook



def create_swap_tab(parent):
    """创建课程对调页面"""
    global left_year_var, left_month_var, left_day_var, left_schedule_frame
    global right_year_var, right_month_var, right_day_var, right_schedule_frame

    frame = ttk.Frame(parent, padding=20)
    
    # 创建左右分栏
    left_frame = ttk.Frame(frame)
    right_frame = ttk.Frame(frame)
    
    # 获取今天的日期
    today = datetime.date.today()
    
    # 左侧日期选择
    left_date_frame = ttk.Frame(left_frame)
    left_date_frame.pack(pady=10)
    
    # 年份选择
    left_year_var = tk.StringVar(value=str(today.year))
    left_year_combo = ttk.Combobox(left_date_frame, textvariable=left_year_var, width=8, state="readonly")
    left_year_combo['values'] = [str(year) for year in range(today.year-1, today.year+3)]
    left_year_combo.pack(side=tk.LEFT, padx=5)
    
    # 月份选择
    left_month_var = tk.StringVar(value=str(today.month))
    left_month_combo = ttk.Combobox(left_date_frame, textvariable=left_month_var, width=6, state="readonly")
    left_month_combo['values'] = [str(month) for month in range(1, 13)]
    left_month_combo.pack(side=tk.LEFT, padx=5)
    
    # 日期选择
    left_day_var = tk.StringVar(value=str(today.day))
    left_day_combo = ttk.Combobox(left_date_frame, textvariable=left_day_var, width=6, state="readonly")
    left_day_combo['values'] = [str(day) for day in range(1, 32)]
    left_day_combo.pack(side=tk.LEFT, padx=5)
    
    # 左侧课表显示区域（完全按照课表页面的方式）
    left_schedule_container = ttk.Frame(left_frame)
    left_schedule_container.pack(fill="both", expand=True, pady=10)
    
    # 创建Canvas和滚动条（完全按照课表页面的方式）
    left_canvas = tk.Canvas(left_schedule_container)
    left_scrollbar = ttk.Scrollbar(left_schedule_container, orient="vertical", command=left_canvas.yview)
    left_schedule_frame = ttk.Frame(left_canvas)
    
    # 配置Canvas（完全按照课表页面的方式）
    left_canvas.configure(yscrollcommand=left_scrollbar.set)
    left_canvas.create_window((0, 0), window=left_schedule_frame, anchor="nw")
    
    # 布局Canvas和滚动条（完全按照课表页面的方式）
    left_canvas.grid(row=0, column=0, sticky="nsew")
    left_scrollbar.grid(row=0, column=1, sticky="ns")
    
    # 配置网格权重（完全按照课表页面的方式）
    left_schedule_container.grid_rowconfigure(0, weight=1)
    left_schedule_container.grid_columnconfigure(0, weight=1)
    
    # 右侧日期选择
    right_date_frame = ttk.Frame(right_frame)
    right_date_frame.pack(pady=10)
    
    # 年份选择
    right_year_var = tk.StringVar(value=str(today.year))
    right_year_combo = ttk.Combobox(right_date_frame, textvariable=right_year_var, width=8, state="readonly")
    right_year_combo['values'] = [str(year) for year in range(today.year-1, today.year+3)]
    right_year_combo.pack(side=tk.LEFT, padx=5)
    
    # 月份选择
    right_month_var = tk.StringVar(value=str(today.month))
    right_month_combo = ttk.Combobox(right_date_frame, textvariable=right_month_var, width=6, state="readonly")
    right_month_combo['values'] = [str(month) for month in range(1, 13)]
    right_month_combo.pack(side=tk.LEFT, padx=5)
    
    # 日期选择
    right_day_var = tk.StringVar(value=str(today.day))
    right_day_combo = ttk.Combobox(right_date_frame, textvariable=right_day_var, width=6, state="readonly")
    right_day_combo['values'] = [str(day) for day in range(1, 32)]
    right_day_combo.pack(side=tk.LEFT, padx=5)
    
    # 右侧课表显示区域（完全按照课表页面的方式）
    right_schedule_container = ttk.Frame(right_frame)
    right_schedule_container.pack(fill="both", expand=True, pady=10)
    
    # 创建Canvas和滚动条（完全按照课表页面的方式）
    right_canvas = tk.Canvas(right_schedule_container)
    right_scrollbar = ttk.Scrollbar(right_schedule_container, orient="vertical", command=right_canvas.yview)
    right_schedule_frame = ttk.Frame(right_canvas)
    
    # 配置Canvas（完全按照课表页面的方式）
    right_canvas.configure(yscrollcommand=right_scrollbar.set)
    right_canvas.create_window((0, 0), window=right_schedule_frame, anchor="nw")
    
    # 布局Canvas和滚动条（完全按照课表页面的方式）
    right_canvas.grid(row=0, column=0, sticky="nsew")
    right_scrollbar.grid(row=0, column=1, sticky="ns")
    
    # 配置网格权重（完全按照课表页面的方式）
    right_schedule_container.grid_rowconfigure(0, weight=1)
    right_schedule_container.grid_columnconfigure(0, weight=1)
    
    # 底部按钮
    button_frame = ttk.Frame(frame)
    ttk.Button(button_frame, text="对调课程", command=lambda: swap_selected_courses()).pack(pady=20)
    
    # 布局
    left_frame.grid(row=0, column=0, padx=20, sticky="nsew")
    right_frame.grid(row=0, column=1, padx=20, sticky="nsew")
    button_frame.grid(row=1, column=0, columnspan=2, pady=20)
    
    frame.grid_rowconfigure(0, weight=1)
    frame.grid_columnconfigure(0, weight=1)
    frame.grid_columnconfigure(1, weight=1)
    
    # 绑定日期变化事件
    left_year_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('left'))
    left_month_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('left'))
    left_day_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('left'))
    right_year_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('right'))
    right_month_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('right'))
    right_day_combo.bind('<<ComboboxSelected>>', lambda e: update_schedule_display('right'))
    
    # 初始显示今天的课表
    update_schedule_display('left')
    update_schedule_display('right')
    
    return frame


def load_schedule_for_date(date_str):
    """根据日期加载课表"""
    # 从config.json读取基础课表
    with open('config.json', 'r', encoding='utf-8') as f:
        config = json.load(f)
    
    # 计算星期几
    date_obj = datetime.datetime.strptime(date_str, '%Y-%m-%d')
    weekday = date_obj.weekday() + 1  # 1-7对应周一到周日
    
    # 获取基础课表
    base_schedule = ["周"] + [["一","二","三","四","五","六","日"][weekday-1]] + ["|"] + config["日程表"][str(weekday)]
    
    # 从data.json读取修改记录
    try:
        with open('data.json', 'r', encoding='utf-8') as f:
            class_change = json.load(f)
        
        # 查找是否有该日期的修改记录
        for record in class_change:
            if record[0] == date_str:
                return record[1]
    except:
        pass
    
    return base_schedule


def load_positions_for_date(date_str):
    """该日期记录里的位置行；没有记录或没有第 3 项时返回 None。"""
    try:
        with open('data.json', 'r', encoding='utf-8') as f:
            class_change = json.load(f)
    except Exception:
        return None
    for record in class_change:
        if isinstance(record, (list, tuple)) and len(record) > 2 and record[0] == date_str:
            lessons = len(config.get("开始时间") or [])
            return P.read_row(record[2], lessons)
    return None


def global_style(which):
    """①全局：优先读"全局日程"，缺失时由旧的默认位置 + secondStyle 迁移。"""
    global_cfg = config.get("全局日程") or {}
    if which in global_cfg:
        return P.normalize(global_cfg[which])
    on_class = which == "上课"
    return P.from_legacy(_pos_of(config, "上课默认位置" if on_class else "下课默认位置"),
                         _flag_of(config,
                                  "上课默认使用secondStyle" if on_class
                                  else "下课默认使用secondStyle"))


def _lesson_rows(table_key):
    lessons = len(config.get("开始时间") or [])
    return P.read_table(config.get(table_key), lessons)


def daily_style_rows():
    """②每日：7 天 × 课节数。"""
    return _lesson_rows("每日日程")


def per_lesson_rows():
    """③每课：7 天 × 课节数。"""
    return _lesson_rows("单课日程")


def token_to_lesson(tokens, token_index):
    """token 下标 → 第几节课（0 起）；特殊 token 返回 None。"""
    seen = -1
    for i, token in enumerate(tokens):
        if token in SPECIAL_TEXT:
            continue
        seen += 1
        if i == token_index:
            return seen
    return None




def update_schedule_display(side):
    """更新指定侧的课表显示"""
    try:
        if side == 'left':
            date_str = f"{left_year_var.get()}-{left_month_var.get().zfill(2)}-{left_day_var.get().zfill(2)}"
            frame = left_schedule_frame
        else:
            date_str = f"{right_year_var.get()}-{right_month_var.get().zfill(2)}-{right_day_var.get().zfill(2)}"
            frame = right_schedule_frame
        
        # 清除现有内容
        for widget in frame.winfo_children():
            widget.destroy()
        
        # 获取课表
        schedule = load_schedule_for_date(date_str)
        
        # 创建单选按钮组
        if side == 'left':
            if not hasattr(update_schedule_display, 'left_radio_var'):
                update_schedule_display.left_radio_var = tk.StringVar()
            radio_var = update_schedule_display.left_radio_var
        else:
            if not hasattr(update_schedule_display, 'right_radio_var'):
                update_schedule_display.right_radio_var = tk.StringVar()
            radio_var = update_schedule_display.right_radio_var
        
        # 显示课表
        for i, course in enumerate(schedule):
            if course not in ['周', '|'] and course not in ['一', '二', '三', '四', '五', '六', '日']:
                # 创建课程行
                course_frame = ttk.Frame(frame)
                course_frame.pack(fill="x", pady=2)
                
                # 单选按钮
                radio = ttk.Radiobutton(course_frame, variable=radio_var, value=str(i), text=course)
                radio.pack(side="left", padx=5)
        
        # 添加"全部"选项
        all_frame = ttk.Frame(frame)
        all_frame.pack(fill="x", pady=5)
        
        all_radio = ttk.Radiobutton(all_frame, variable=radio_var, value="all", text="全部")
        all_radio.pack(side="left", padx=5)
        
        # 更新滚动区域（完全按照课表页面的方式）
        frame.bind("<Configure>", lambda e: frame.master.configure(scrollregion=frame.master.bbox("all")))
        
    except Exception as e:
        print(f"更新课表显示失败: {e}")


def swap_selected_courses():
    """对调选中的课程"""
    try:
        # 获取左右两侧的日期
        left_date = f"{left_year_var.get()}-{left_month_var.get().zfill(2)}-{left_day_var.get().zfill(2)}"
        right_date = f"{right_year_var.get()}-{right_month_var.get().zfill(2)}-{right_day_var.get().zfill(2)}"
        
        # 验证日期格式
        datetime.datetime.strptime(left_date, '%Y-%m-%d')
        datetime.datetime.strptime(right_date, '%Y-%m-%d')
        
        # 获取两个日期的课表
        left_schedule = load_schedule_for_date(left_date)
        right_schedule = load_schedule_for_date(right_date)
        
        # 获取选中的课程
        left_selected = update_schedule_display.left_radio_var.get()
        right_selected = update_schedule_display.right_radio_var.get()
        
        # 检查是否选择了课程
        if not left_selected and not right_selected:
            messagebox.showwarning("警告", "请至少选择一侧的课程进行对调")
            return
        
        # 对调选中的课程
        # 位置也要跟着课走：写成解析后的真实值，不写 "default"
        left_positions = load_positions_for_date(left_date)
        right_positions = load_positions_for_date(right_date)
        lessons = len(config.get("开始时间") or [])
        left_day = datetime.datetime.strptime(left_date, '%Y-%m-%d').isoweekday()
        right_day = datetime.datetime.strptime(right_date, '%Y-%m-%d').isoweekday()
        left_effective = [P.resolve(left_positions, per_lesson_rows(), daily_style_rows(),
                                    i, left_day, False, global_style("上课"),
                                    global_style("下课")) for i in range(lessons)]
        right_effective = [P.resolve(right_positions, per_lesson_rows(), daily_style_rows(),
                                     i, right_day, False, global_style("上课"),
                                     global_style("下课")) for i in range(lessons)]

        if left_selected == "all" and right_selected == "all":
            # 对调整天的课程
            left_schedule, right_schedule = right_schedule, left_schedule
            left_effective, right_effective = right_effective, left_effective
        elif left_selected == "all":
            # 左侧选择全部，右侧选择特定课程
            messagebox.showwarning("警告", "不能同时选择\"全部\"和特定课程")
            return
        elif right_selected == "all":
            # 右侧选择全部，左侧选择特定课程
            messagebox.showwarning("警告", "不能同时选择\"全部\"和特定课程")
            return
        else:
            # 对调特定课程
            left_idx = int(left_selected)
            right_idx = int(right_selected)
            
            if left_idx < len(left_schedule) and right_idx < len(right_schedule):
                left_schedule[left_idx], right_schedule[right_idx] = right_schedule[right_idx], left_schedule[left_idx]
                li = token_to_lesson(left_schedule, left_idx)
                ri = token_to_lesson(right_schedule, right_idx)
                if li is not None and ri is not None:
                    tmp_style = left_effective[li]
                    left_effective[li] = right_effective[ri]
                    right_effective[ri] = tmp_style
            else:
                messagebox.showerror("错误", "课程索引超出范围")
                return
        
        # 读取现有的修改记录
        try:
            with open('data.json', 'r', encoding='utf-8') as f:
                class_change = json.load(f)
        except:
            class_change = []
        
        # 更新或添加记录
        # 先删除已存在的记录
        class_change = [record for record in class_change if record[0] not in [left_date, right_date]]
        
        # 添加新的对调记录
        class_change.append([left_date, left_schedule, left_effective])
        class_change.append([right_date, right_schedule, right_effective])
        
        # 写回文件
        with open('data.json', 'w', encoding='utf-8') as f:
            json.dump(class_change, f, ensure_ascii=False, indent=2)
        
        messagebox.showinfo("成功", f"已对调 {left_date} 和 {right_date} 的选中课程！")
        
        # 刷新显示
        update_schedule_display('left')
        update_schedule_display('right')
        
    except ValueError as e:
        messagebox.showerror("错误", "日期格式不正确，请检查年月日选择")
    except Exception as e:
        messagebox.showerror("错误", f"对调失败：{str(e)}")


#读取json文件
with open("config.json", "r", encoding="utf-8") as file:
    config = migrate_config(json.load(file))
class_start_times = []
class_end_times = []
settings_data = {}
# 创建主窗口
root = tk.Tk()
root.title("课程表编辑")
root.geometry("960x640")

# 创建Notebook（多页控件）
notebook = ttk.Notebook(root)
notebook.pack(fill="both", expand=True, padx=10, pady=10)

# 创建课表页
schedule_frame = create_schedule_tab(notebook, config)
notebook.add(schedule_frame, text="课表")

# 创建设置页
settings_frame = create_settings_tab(notebook)
notebook.add(settings_frame, text="设置")

# 创建课程对调页
swap_frame = create_swap_tab(notebook)
notebook.add(swap_frame, text="对调")

# 运行主循环
root.mainloop()
