import tkinter as tk
from tkinter import ttk, messagebox
import json
import os
import glob

class ClassSelector:
    def __init__(self, root):
        self.root = root
        self.root.title("班级设置")
        self.root.geometry("600x600")  # 增加窗口高度

        # 存储当前选择的班级数据
        self.current_class_data = None

        # 创建主框架
        main_frame = ttk.Frame(root, padding="20")
        main_frame.pack(fill="both", expand=True)

        # 标题
        title_label = ttk.Label(main_frame, text="选择班级配置", font=("Arial", 16, "bold"))
        title_label.pack(pady=(0, 20))

        # 班级选择框架
        select_frame = ttk.LabelFrame(main_frame, text="班级选择", padding="10")
        select_frame.pack(fill="x", pady=(0, 20))

        # 下拉菜单
        ttk.Label(select_frame, text="选择班级:").pack(anchor="w")
        self.class_var = tk.StringVar()
        self.class_combo = ttk.Combobox(select_frame, textvariable=self.class_var, 
                                       state="readonly", width=50)
        self.class_combo.pack(fill="x", pady=(5, 10))
        self.class_combo.bind('<<ComboboxSelected>>', self.on_class_selected)

        # 刷新按钮
        refresh_btn = ttk.Button(select_frame, text="刷新列表", command=self.refresh_class_list)
        refresh_btn.pack(anchor="w")

        # 预览框架
        preview_frame = ttk.LabelFrame(main_frame, text="配置预览", padding="10")
        preview_frame.pack(fill="both", expand=True, pady=(0, 1))

        # 创建滚动文本框
        text_frame = ttk.Frame(preview_frame)
        text_frame.pack(fill="both", expand=True)

        self.preview_text = tk.Text(text_frame, height=12, wrap="word")  # 减少高度
        scrollbar = ttk.Scrollbar(text_frame, orient="vertical", command=self.preview_text.yview)
        self.preview_text.configure(yscrollcommand=scrollbar.set)

        self.preview_text.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # 按钮框架
        button_frame = ttk.Frame(main_frame)
        button_frame.pack(fill="x", pady=(10, 0))  # 增加上边距

        # 应用按钮
        apply_btn = ttk.Button(button_frame, text="应用配置", command=self.apply_config, 
                              style="Accent.TButton")
        apply_btn.pack(side="right", padx=(10, 0))

        #取消按钮
        cancel_btn = ttk.Button(button_frame, text="取消", command=self.root.quit)
        cancel_btn.pack(side="right")

        # 初始化
        self.refresh_class_list()
    
    def refresh_class_list(self):
        """刷新班级列表"""
        classes_frame_dir = "classes_frame"
        if not os.path.exists(classes_frame_dir):
            os.makedirs(classes_frame_dir)
            messagebox.showinfo("提示", f"已创建 {classes_frame_dir} 文件夹")
            return
        
        # 查找所有JSON文件
        json_files = glob.glob(os.path.join(classes_frame_dir, "*.json"))
        class_names = []
        
        for json_file in json_files:
            # 提取文件名（不含扩展名）作为班级名称
            class_name = os.path.splitext(os.path.basename(json_file))[0]
            class_names.append(class_name)
        
        if not class_names:
            messagebox.showwarning("警告", f"在 {classes_frame_dir} 文件夹中未找到JSON文件")
            return
        
        # 更新下拉菜单
        self.class_combo['values'] = class_names
        if class_names:
            self.class_combo.set(class_names[0])
            self.on_class_selected(None)
    
    def on_class_selected(self, event):
        """当选择班级时更新预览"""
        selected_class = self.class_var.get()
        if not selected_class:
            return
        
        try:
            # 读取选择的班级配置文件
            json_file = os.path.join("classes_frame", f"{selected_class}.json")
            with open(json_file, 'r', encoding='utf-8') as f:
                self.current_class_data = json.load(f)
            
            # 更新预览文本
            self.update_preview()
            
        except Exception as e:
            messagebox.showerror("错误", f"读取配置文件失败: {str(e)}")
            self.current_class_data = None
    
    def update_preview(self):
        """更新预览文本"""
        if not self.current_class_data:
            self.preview_text.delete(1.0, tk.END)
            return
        
        # 清空文本框
        self.preview_text.delete(1.0, tk.END)
        
        # 显示日程表
        self.preview_text.insert(tk.END, "=== 日程表 ===\n")
        schedule = self.current_class_data.get("日程表", {})
        weekdays = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
        
        for i, day in enumerate(weekdays, 1):
            self.preview_text.insert(tk.END, f"{day}: ")
            if str(i) in schedule:
                self.preview_text.insert(tk.END, " | ".join(schedule[str(i)]) + "\n")
            else:
                self.preview_text.insert(tk.END, "无数据\n")
        
        # 显示更换选项
        self.preview_text.insert(tk.END, "\n=== 更换选项 ===\n")
        options = self.current_class_data.get("更换选项", [])
        self.preview_text.insert(tk.END, ", ".join(options) + "\n")
    
    def apply_config(self):
        """应用配置到config.json"""
        if not self.current_class_data:
            messagebox.showwarning("警告", "请先选择一个班级配置")
            return
        
        try:
            # 读取当前config.json
            with open('config.json', 'r', encoding='utf-8') as f:
                config = json.load(f)
            
            # 更新日程表和更换选项
            config["日程表"] = self.current_class_data["日程表"]
            config["更换选项"] = self.current_class_data["更换选项"]
            
            # 保存更新后的config.json
            with open('config.json', 'w', encoding='utf-8') as f:
                json.dump(config, f, ensure_ascii=False, indent=2)
            
            messagebox.showinfo("成功", f"已成功应用 '{self.class_var.get()}' 的配置到 config.json")
            
        except Exception as e:
            messagebox.showerror("错误", f"应用配置失败: {str(e)}")

def main():
    root = tk.Tk()
    app = ClassSelector(root)
    root.mainloop()

if __name__ == "__main__":
    main()
