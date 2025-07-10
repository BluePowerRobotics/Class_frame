import tkinter as tk
from tkinter import ttk, Scrollbar, messagebox
import json

def save_schedule():
    """保存课表"""
    schedule_data = {'1':[],'2':[],'3':[],'4':[],'5':[],'6':[],'7':[]}
    
    # 遍历所有行
    for row_idx, period in enumerate(periods):
        if period!="|":
            # 遍历所有列
            for i in range(7):
                # 获取下拉框的值
                value = comboboxes[row_idx][i].get()
                # 添加数据
                schedule_data[str(i+1)].append(value)
        else:
            for i in range(7):
                schedule_data[str(i+1)].append("|")
    #写入文件
    config["日程表"]=schedule_data
    file = open("config.json", "w", encoding='utf-8')
    json.dump(config,file)
    file.close()
    # 显示保存成功消息
    messagebox.showinfo("保存成功", "课表数据已保存！")
    



def create_schedule_tab(parent, text):
    global comboboxes, periods, days

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
    days = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    for col, day in enumerate(days):
        label = ttk.Label(scrollable_frame, text=day, width=10, relief="solid", padding=5)
        label.grid(row=0, column=col+1, sticky="nsew")
    
    # 创建表格内容
    num=1
    periods=[]
    for i in config["日程表"]["1"]:
        if i!='|':
            periods.append(str(num))
            num+=1
        else:
            periods.append("|")
    options = config["更换选项"]
    comboboxes = [[None for _ in range(len(days))] for _ in range(len(periods))]
    
    for row, period in enumerate(periods):
        if period!="|":
            # 行标题（纵向）
            period_label = ttk.Label(scrollable_frame, text=period, relief="solid", padding=5)
            period_label.grid(row=row+1, column=0, sticky="nsew")
            
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
                comboboxes[row][col] = combobox
        else:
            for col in range(7):
                sepLine = ttk.Separator(scrollable_frame, orient='horizontal')
                sepLine.grid(row=row+1, column=col+1, columnspan=8, sticky='ew', pady=(3,3))

    
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


def create_settings_tab(parent):
    frame = ttk.Frame(parent)
    label = ttk.Label(frame, text="设置页面内容将在这里显示", font=("Arial", 14))
    label.pack(pady=50)
    return frame


#读取json文件
with open('config.json', 'r', encoding='utf-8') as file:
    config = json.load(file)



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

# 运行主循环
root.mainloop()