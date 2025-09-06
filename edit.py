import tkinter as tk
from tkinter import ttk, Scrollbar, messagebox, simpledialog, Menu
import json
import datetime

#row+1为实际行数

def save_settings(entries):
    """保存参数设置到字典"""
    global settings_data
    
    for key, entry in entries.items():
        try:
            text=int(entry.get())
        except ValueError:
            try:
                text=float(entry.get())
            except ValueError:
                text=entry.get()
        config[key] = [text]
    #写入文件
    file = open("config.json", "w", encoding='utf-8')
    json.dump(config,file,indent=4,ensure_ascii=False)
    file.close()
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
                # 获取下拉框的值
                value = comboboxes[row_idx+1][i+1].get()
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
    

def create_schedule_tab(parent, text):
    global comboboxes, periods, days, scrollable_frame, canvas
    # 创建表格内容
    num=1
    periods=[]
    days = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    for i in config["日程表"]["1"]:
        if i!='|':
            periods.append(str(num))
            num+=1
        else:
            periods.append("|")
    options = config["更换选项"]
    comboboxes = [[None for _ in range(len(days)+1)] for _ in range(len(periods)+1)]

    # 创建外层Frame
    frame = ttk.Frame(parent)
    
    # 创建Canvas和滚动条
    canvas = tk.Canvas(frame)
    scrollbar = ttk.Scrollbar(frame, orient="vertical", command=canvas.yview)
    scrollable_frame = ttk.Frame(canvas)
    
    # 配置Canvas
    canvas.configure(yscrollcommand=scrollbar.set)
    canvas.create_window((0, 0), window=scrollable_frame, anchor="nw")
    
    # 创建表格标题（横向）
    label = ttk.Label(scrollable_frame, text=" ", width=10, relief="solid", padding=5)
    label.grid(row=0, column=0, sticky="nsew")
    comboboxes[0][0]=label
    for col, day in enumerate(days):
        label = ttk.Label(scrollable_frame, text=day, width=10, relief="solid", padding=5)
        label.grid(row=0, column=col+1, sticky="nsew")
        comboboxes[0][col+1]=label
    
    #创建表格内容
    for row, period in enumerate(periods):
        if period!="|":
            # 行标题（纵向）
            period_label = ttk.Label(scrollable_frame, text=period, relief="solid", padding=5)
            period_label.grid(row=row+1, column=0, sticky="nsew")
            period_label.bind("<Button-1>", lambda e, row=row: show_period_menu(e, row))
            comboboxes[row+1][0] = period_label
            
            # 创建下拉框
            for col in range(7): #每行创建7个
                combobox = ttk.Combobox(
                    scrollable_frame, 
                    values=options, 
                    state="readonly", #设置为只读
                    width=8
                )
                combobox.grid(row=row+1, column=col+1, padx=1, pady=1, sticky="nsew")
                combobox.set(config["日程表"][str(col+1)][row])
                comboboxes[row+1][col+1] = combobox
        else:
            for col in range(8):
                sepLine = ttk.Separator(scrollable_frame, orient='horizontal')
                sepLine.grid(row=row+1, column=col, columnspan=1, sticky='ew', pady=(10,10))
                comboboxes[row+1][col] = sepLine

    
    # 配置网格权重
    for i in range(8):
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
    save_button = ttk.Button(frame, text="保存", command=save_schedule,width=15)
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


def add_separator(row_index):
    """在指定行下方添加分割线"""
    global comboboxes,scrollable_frame,periods,days

    new_period=comboboxes[row_index+1][0]["text"]
    seps=[]
    for col in range(8):
        sepLine = ttk.Separator(scrollable_frame, orient='horizontal')
        sepLine.grid(row=row_index+2, column=col, columnspan=1, sticky='ew', pady=(10,10))
        seps.append(sepLine)
    comboboxes.insert(row_index+2,seps)
    periods.insert(row_index+1,"|")

    # 调整下方所有行的位置
    for rowi in range(row_index+3, len(periods)+1):
        for i in range(8):
            comboboxes[rowi][i].grid(row=rowi)
    
    
    # 更新滚动区域
    canvas.configure(scrollregion=canvas.bbox("all"))
    messagebox.showinfo("添加成功", "已在第"+new_period+"节下方添加新分割线")


def del_separator(row_index):
    """在指定行下方删除分割线"""
    global comboboxes,scrollable_frame,periods,days

    new_period=comboboxes[row_index+1][0]["text"]
    for col in range(8):
        sepLine = comboboxes[row_index+2][col]
        sepLine.destroy()
    del(comboboxes[row_index+2])
    del(periods[row_index+1])
    
    
    # 更新滚动区域
    canvas.configure(scrollregion=canvas.bbox("all"))
    messagebox.showinfo("添加成功", "已在第"+new_period+"节下方删除分割线")


def add_course(row_index):
    """在指定行下方添加新课程行"""
    global periods, comboboxes
    
    new_period=comboboxes[row_index+1][0]["text"]
    for i in range(row_index+1,len(periods)):
        if periods[i]!="|":
            periods[i]=str(int(periods[i])+1)
            comboboxes[i+1][0]["text"]=periods[i]
            comboboxes[i+1][0].unbind('<Button-1>')
            comboboxes[i+1][0].bind("<Button-1>", lambda e, row=i+1: show_period_menu(e, row))
    periods.insert(row_index+1,str(int(new_period)+1))
    
    # 在二维列表中添加新行
    new_combobox_row = []
    
    # 创建新行的行标题（带右键菜单）
    period_label = ttk.Label(
        scrollable_frame, 
        text=str(int(new_period)+1), 
        relief="solid", 
        padding=5,
    )
    period_label.grid(row=row_index+2, column=0, sticky="nsew")
    period_label.bind("<Button-1>", lambda e, row=row_index+2: show_period_menu(e, row))
    new_combobox_row.append(period_label)
    
    # 创建新行的下拉框
    for col in range(len(days)):
        combobox = ttk.Combobox(
            scrollable_frame, 
            values=config["更换选项"], 
            state="readonly",
            width=8
        )
        combobox.set('无')
        combobox.grid(row=row_index+2, column=col+1, padx=1, pady=1, sticky="nsew")
        new_combobox_row.append(combobox)
    comboboxes.insert(row_index+2, new_combobox_row)
        
    # 调整下方所有行的位置
    for rowi in range(row_index+3, len(periods)+1):
        for i in range(8):
            comboboxes[rowi][i].grid(row=rowi)
    
    # 更新滚动区域
    canvas.configure(scrollregion=canvas.bbox("all"))
    messagebox.showinfo("添加成功", "已在第"+new_period+"节下方添加新课程行")


def del_course(row_index):
    """在指定行下方删除课程行"""
    global comboboxes,scrollable_frame,periods,days

    new_period=comboboxes[row_index+1][0]["text"]
    for i in range(row_index+1,len(periods)):
        if periods[i]!="|":
            periods[i]=str(int(periods[i])-1)
            comboboxes[i+1][0]["text"]=periods[i]
            comboboxes[i+1][0].unbind('<Button-1>')
            comboboxes[i+1][0].bind("<Button-1>", lambda e, row=i-1: show_period_menu(e, row))

    # 删除指定行的下拉框和行标题
    for col in range(8):
        combobox = comboboxes[row_index+1][col]
        combobox.destroy()
    del(comboboxes[row_index+1])
    del(periods[row_index])
    
    # 更新滚动区域
    canvas.configure(scrollregion=canvas.bbox("all"))
    messagebox.showinfo("添加成功", "已删除第"+new_period+"节课程行")


def create_time_settings(parent):
    """创建时间设置页面"""
    frame = ttk.Frame(parent, padding=20)
    
    # 创建标签
    ttk.Label(frame, text="上课时间设置", font=("Arial", 10, "bold")).grid(row=0, column=0, padx=5, pady=5)
    ttk.Label(frame, text="下课时间设置", font=("Arial", 10, "bold")).grid(row=0, column=2, padx=5, pady=5)
    
    # 创建上课时间Listbox和滚动条
    class_start_frame = ttk.Frame(frame)
    class_start_frame.grid(row=1, column=0, padx=10, pady=10, sticky="nsew")
    
    class_start_scroll = ttk.Scrollbar(class_start_frame, orient="vertical")
    class_start_listbox = tk.Listbox(
        class_start_frame, 
        width=15, 
        height=15, 
        yscrollcommand=class_start_scroll.set,
        selectmode=tk.SINGLE
    )
    class_start_scroll.config(command=class_start_listbox.yview)
    
    # 添加上课数据
    for time in config["开始时间"]:
        class_start_listbox.insert(tk.END, str(time[0])+":"+str(time[1]))
    
    class_start_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
    class_start_scroll.pack(side=tk.RIGHT, fill=tk.Y)
    
    # 创建上课时间按钮框架
    start_buttons_frame = ttk.Frame(frame)
    start_buttons_frame.grid(row=1, column=1, padx=5, sticky="ns")
    
    def add_start_time():
        """添加上课时间"""
        time = simpledialog.askstring("添加", "请输入上课时间:")
        if time:
            index = class_start_listbox.curselection()
            if index:
                class_start_listbox.insert(index[0] + 1, time)
            else:
                class_start_listbox.insert(tk.END, time)
    
    def edit_start_time():
        """编辑上课时间"""
        index = class_start_listbox.curselection()
        if index:
            current_time = class_start_listbox.get(index)
            new_time = simpledialog.askstring("编辑", "修改上课时间:", initialvalue=current_time)
            if new_time:
                class_start_listbox.delete(index)
                class_start_listbox.insert(index, new_time)
        else:
            messagebox.showwarning("未选择", "请先选择一个上课时间进行编辑")
    
    def delete_start_time():
        """删除上课时间"""
        index = class_start_listbox.curselection()
        if index:
            class_start_listbox.delete(index)
        else:
            messagebox.showwarning("未选择", "请先选择一个上课时间进行删除")
    
    # 添加上课时间按钮
    ttk.Button(start_buttons_frame, text="添加", command=add_start_time, width=8).pack(pady=5)
    ttk.Button(start_buttons_frame, text="编辑", command=edit_start_time, width=8).pack(pady=5)
    ttk.Button(start_buttons_frame, text="删除", command=delete_start_time, width=8).pack(pady=5)
    ttk.Button(
        start_buttons_frame, 
        text="保存", 
        command=lambda: save_start_times(class_start_listbox),
        width=8
    ).pack(pady=15)  # 增加间距以区分
    
    # 创建下课时间Listbox和滚动条
    class_end_frame = ttk.Frame(frame)
    class_end_frame.grid(row=1, column=2, padx=10, pady=10, sticky="nsew")
    
    class_end_scroll = ttk.Scrollbar(class_end_frame, orient="vertical")
    class_end_listbox = tk.Listbox(
        class_end_frame, 
        width=15, 
        height=15, 
        yscrollcommand=class_end_scroll.set,
        selectmode=tk.SINGLE
    )
    class_end_scroll.config(command=class_end_listbox.yview)
    
    # 添加下课数据
    for time in config["结束时间"]:
        class_end_listbox.insert(tk.END, str(time[0])+":"+str(time[1]))
    
    class_end_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
    class_end_scroll.pack(side=tk.RIGHT, fill=tk.Y)
    
    # 创建下课时间按钮框架
    end_buttons_frame = ttk.Frame(frame)
    end_buttons_frame.grid(row=1, column=3, padx=5, sticky="ns")
    
    def add_end_time():
        """添加下课时间"""
        time = simpledialog.askstring("添加", "请输入下课时间:")
        if time:
            index = class_end_listbox.curselection()
            if index:
                class_end_listbox.insert(index[0] + 1, time)
            else:
                class_end_listbox.insert(tk.END, time)
    
    def edit_end_time():
        """编辑下课时间"""
        index = class_end_listbox.curselection()
        if index:
            current_time = class_end_listbox.get(index)
            new_time = simpledialog.askstring("编辑", "修改下课时间:", initialvalue=current_time)
            if new_time:
                class_end_listbox.delete(index)
                class_end_listbox.insert(index, new_time)
        else:
            messagebox.showwarning("未选择", "请先选择一个下课时间进行编辑")
    
    def delete_end_time():
        """删除下课时间"""
        index = class_end_listbox.curselection()
        if index:
            class_end_listbox.delete(index)
        else:
            messagebox.showwarning("未选择", "请先选择一个下课时间进行删除")
    
    def save_start_times(listbox):
        """保存上课时间到列表"""
        global class_start_times
        class_start_times = [listbox.get(i).split(":") for i in range(listbox.size())]
        config["开始时间"]=class_start_times
        file = open("config.json", "w", encoding='utf-8')
        json.dump(config,file,indent=4,ensure_ascii=False)
        file.close()
        messagebox.showinfo("保存成功", "已保存上课时间")

    def save_end_times(listbox):
        """保存下课时间到列表"""
        global class_end_times
        class_end_times = [listbox.get(i).split(":") for i in range(listbox.size())]
        config["结束时间"]=class_end_times
        file = open("config.json", "w", encoding='utf-8')
        json.dump(config,file,indent=4,ensure_ascii=False)
        file.close()
        messagebox.showinfo("保存成功","已保存下课时间")
    
    # 添加下课时间按钮
    ttk.Button(end_buttons_frame, text="添加", command=add_end_time, width=8).pack(pady=5)
    ttk.Button(end_buttons_frame, text="编辑", command=edit_end_time, width=8).pack(pady=5)
    ttk.Button(end_buttons_frame, text="删除", command=delete_end_time, width=8).pack(pady=5)
    ttk.Button(
        end_buttons_frame, 
        text="保存", 
        command=lambda: save_end_times(class_end_listbox),
        width=8
    ).pack(pady=15)
    
    # 配置网格权重
    frame.columnconfigure(0, weight=1)
    frame.columnconfigure(2, weight=1)
    frame.rowconfigure(1, weight=1)
    
    return frame


def create_parameter_settings(parent):
    """创建参数设置页面"""
    global settings_data
    
    frame = ttk.Frame(parent, padding=20)
    
    # 创建参数设置项
    settings_items = [
        "下课显示倒计时", 
        "下课置顶", 
        "上课置顶", 
        "上课显示倒计条", 
        "文字大小", 
        "上课放大倍率", 
        "上课默认位置", 
        "下课默认位置",
        "竖直显示的文字大小",
        "开始提示",
        "结束提示",
        "结尾提示"
    ]
    
    # 存储所有输入框的字典
    entries = {}
    
    # 使用网格布局创建标签和输入框
    for i, item in enumerate(settings_items):
        row = i // 2  # 每行两个设置项
        col = (i % 2) * 2  # 第0列:标签, 第1列:输入框
        
        # 创建标签
        label = ttk.Label(frame, text=item + ":", anchor="e")
        label.grid(row=row, column=col, padx=5, pady=5, sticky="e")
        
        # 创建输入框
        entry = ttk.Entry(frame, width=20)
        entry.grid(row=row, column=col+1, padx=5, pady=5, sticky="w")
        entry.insert(0,str(config[item][0]))
        # 存储输入框引用
        entries[item] = entry
    
    # 添加固定在右下角的保存按钮
    save_button = ttk.Button(
        frame, 
        text="保存参数", 
        command=lambda: save_settings(entries),
        width=15
    )
    save_button.grid(
        row=len(settings_items)//2 + 1, 
        column=3, 
        padx=10, 
        pady=20, 
        sticky="se"
    )
    
    # 配置网格权重
    frame.columnconfigure(0, weight=1)
    frame.columnconfigure(1, weight=1)
    frame.columnconfigure(2, weight=1)
    frame.columnconfigure(3, weight=1)
    
    for i in range(len(settings_items)//2 + 2):
        frame.rowconfigure(i, weight=1)
    
    return frame


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
        if left_selected == "all" and right_selected == "all":
            # 对调整天的课程
            left_schedule, right_schedule = right_schedule, left_schedule
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
        class_change.append([left_date, left_schedule])
        class_change.append([right_date, right_schedule])
        
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
with open('config.json', 'r', encoding='utf-8') as file:
    config = json.load(file)
class_start_times=[]
class_end_times=[]
settings_data = {
    "下课显示倒计时": "",
    "下课置顶": "",
    "上课置顶": "",
    "上课显示倒计条": "",
    "文字大小": "",
    "上课放大倍率": "",
    "上课默认位置": "",
    "下课默认位置": "",
    "竖直显示的文字大小": "",
    "开始提示": "",
    "结束提示": "",
    "结尾提示": ""
}
# 创建主窗口
root = tk.Tk()
root.title("课程表编辑")
root.geometry("800x500")

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