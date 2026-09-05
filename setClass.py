# -*- coding: utf-8 -*-
import tkinter as tk
from tkinter import ttk, messagebox
import json
import os
import glob


def to_minutes(value):
    if isinstance(value, (list, tuple)) and len(value) >= 2:
        return int(value[0]) * 60 + int(value[1])
    if isinstance(value, str) and ":" in value:
        h, m = value.split(":", 1)
        return int(h) * 60 + int(m)
    return int(value)


def to_pair(minutes):
    return [minutes // 60, minutes % 60]


def lesson_count(day):
    return sum(1 for t in day if t != "|")


def adjust_times(starts, ends, target):
    """课表为准：时间对多了删末尾，少了按 10 分钟休息 + 40 分钟课补齐。"""
    s = [to_minutes(v) for v in starts]
    e = [to_minutes(v) for v in ends]
    while len(s) > target:
        s.pop()
        e.pop()
    if not s and target > 0:
        s = [7 * 60 + 10]
        e = [7 * 60 + 50]
    while len(s) < target:
        last_end = e[-1]
        start = last_end + 10
        s.append(start)
        e.append(start + 40)
    return [to_pair(v) for v in s], [to_pair(v) for v in e]


class ClassSelector:
    def __init__(self, root):
        self.root = root
        self.root.title("班级设置")
        self.root.geometry("640x640")
        self.current_class_data = None
        main = ttk.Frame(root, padding="20")
        main.pack(fill="both", expand=True)
        ttk.Label(main, text="选择班级配置", font=("Arial", 16, "bold")).pack(pady=(0, 16))
        select_frame = ttk.LabelFrame(main, text="班级选择", padding="10")
        select_frame.pack(fill="x", pady=(0, 10))
        ttk.Label(select_frame, text="选择班级:").pack(anchor="w")
        self.class_var = tk.StringVar()
        self.class_combo = ttk.Combobox(select_frame, textvariable=self.class_var, state="readonly", width=50)
        self.class_combo.pack(fill="x", pady=(4, 6))
        self.class_combo.bind("<<ComboboxSelected>>", self.on_class_selected)
        ttk.Button(select_frame, text="刷新列表", command=self.refresh_class_list).pack(anchor="w")
        preview_frame = ttk.LabelFrame(main, text="配置预览", padding="10")
        preview_frame.pack(fill="both", expand=True)
        self.preview_text = tk.Text(preview_frame, wrap="word")
        sb = ttk.Scrollbar(preview_frame, command=self.preview_text.yview)
        self.preview_text.configure(yscrollcommand=sb.set)
        self.preview_text.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        btns = ttk.Frame(main)
        btns.pack(fill="x", pady=(10, 0))
        ttk.Button(btns, text="应用配置", command=self.apply_config, style="Accent.TButton").pack(side="right")
        ttk.Button(btns, text="取消", command=self.root.quit).pack(side="right", padx=8)
        self.refresh_class_list()

    def refresh_class_list(self):
        d = "classes_frame"
        if not os.path.exists(d):
            os.makedirs(d)
            messagebox.showinfo("提示", "已创建 classes_frame 文件夹")
            return
        files = glob.glob(os.path.join(d, "*.json"))
        names = [os.path.splitext(os.path.basename(f))[0] for f in files]
        if not names:
            messagebox.showwarning("警告", "classes_frame 中未找到 JSON 文件")
            return
        self.class_combo["values"] = names
        self.class_combo.set(names[0])
        self.on_class_selected(None)

    def on_class_selected(self, _event):
        name = self.class_var.get()
        if not name:
            return
        try:
            with open(os.path.join("classes_frame", name + ".json"), encoding="utf-8") as f:
                self.current_class_data = json.load(f)
            self.update_preview()
        except Exception as exc:
            messagebox.showerror("错误", f"读取配置文件失败: {exc}")

    def update_preview(self):
        self.preview_text.delete("1.0", tk.END)
        if not self.current_class_data:
            return
        self.preview_text.insert(tk.END, "=== 日程表 ===\n")
        weekdays = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
        schedule = self.current_class_data.get("日程表", {})
        for i, day in enumerate(weekdays, 1):
            row = schedule.get(str(i), [])
            self.preview_text.insert(tk.END, f"{day}: " + " | ".join(row) + "\n")
        if "更换选项" in self.current_class_data:
            self.preview_text.insert(tk.END, "\n=== 更换选项 ===\n")
            self.preview_text.insert(tk.END, ", ".join(self.current_class_data["更换选项"]) + "\n")
        if "开始时间" in self.current_class_data or "结束时间" in self.current_class_data:
            self.preview_text.insert(tk.END, "\n=== 时间 ===\n")
            s = self.current_class_data.get("开始时间", [])
            e = self.current_class_data.get("结束时间", [])
            for a, b in zip(s, e):
                self.preview_text.insert(tk.END, f"{a[0]:02d}:{a[1]:02d} - {b[0]:02d}:{b[1]:02d}\n")
        else:
            self.preview_text.insert(tk.END, "\n(该班级文件未包含时间，将沿用现有时间并自动按课节数增删)\n")

    @staticmethod
    def validate_schedule(schedule):
        counts = {}
        for key in [str(i) for i in range(1, 8)]:
            if key not in schedule:
                raise ValueError(f"缺少第 {key} 天的课表")
            counts[key] = lesson_count(schedule[key])
        if len(set(counts.values())) != 1:
            raise ValueError("班级文件各天实际课节数不一致: " + json.dumps(counts, ensure_ascii=False))
        return counts["1"]

    def apply_config(self):
        if not self.current_class_data:
            messagebox.showwarning("警告", "请先选择班级")
            return
        try:
            with open("config.json", encoding="utf-8") as f:
                config = json.load(f)
            target_n = self.validate_schedule(self.current_class_data["日程表"])
            if "开始时间" in self.current_class_data and "结束时间" in self.current_class_data:
                starts = self.current_class_data["开始时间"]
                ends = self.current_class_data["结束时间"]
            else:
                starts = config.get("开始时间", [])
                ends = config.get("结束时间", [])
            old_n = min(len(starts), len(ends))
            starts, ends = adjust_times(starts, ends, target_n)
            config["日程表"] = self.current_class_data["日程表"]
            if "更换选项" in self.current_class_data:
                config["更换选项"] = self.current_class_data["更换选项"]
            config["开始时间"] = starts
            config["结束时间"] = ends
            with open("config.json", "w", encoding="utf-8") as f:
                json.dump(config, f, ensure_ascii=False, indent=2)
            if old_n != target_n:
                messagebox.showinfo(
                    "成功",
                    f"已应用 '{self.class_var.get()}'：课节 {old_n} → {target_n}，时间已自动增删。",
                )
            else:
                messagebox.showinfo("成功", f"已应用 '{self.class_var.get()}' 的配置")
        except Exception as exc:
            messagebox.showerror("应用失败", str(exc))


def main():
    root = tk.Tk()
    ClassSelector(root)
    root.mainloop()


if __name__ == "__main__":
    main()
