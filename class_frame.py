import os
from tkinter import *
import time
import json
import datetime

class movements():
    def __init__(self):
        isl_test=Tk()
        self.screen_gap=10
        self.island_gap=30
        self.screen_width=isl_test.winfo_screenwidth()
        self.screen_height=isl_test.winfo_screenheight()
        isl_test.destroy()
        self.isls=[]
        self.changingpos=0
        self.movingisl=[]
        
    def cal_pos(self):
        mema=-self.island_gap
        memb=-self.island_gap
        memc=-self.island_gap
        for i in self.isls:
            if(i[-1]=='a'):
                mema+=i[1][1]+self.island_gap
            if(i[-1]=='b'):
                memb+=i[1][0]+self.island_gap
            if(i[-1]=='c'):
                memc+=i[1][1]+self.island_gap
        mema=(self.screen_height-mema)/2
        memb=(self.screen_width-memb)/2
        memc=(self.screen_height-memc)/2
        for i in range(len(self.isls)):
            if(self.isls[i][-1]=='a'):
                self.isls[i][1][2]=self.screen_gap
                self.isls[i][1][3]=mema
                mema+=self.isls[i][1][1]+self.island_gap
            elif(self.isls[i][-1]=='b'):
                self.isls[i][1][3]=self.screen_gap
                self.isls[i][1][2]=memb
                memb+=self.isls[i][1][0]+self.island_gap
            elif(self.isls[i][-1]=='c'):
                self.isls[i][1][2]=self.screen_width-self.screen_gap-self.isls[i][1][0]
                self.isls[i][1][3]=memc
                memc+=self.isls[i][1][1]+self.island_gap
            elif(self.isls[i][-1]=='f'):
                self.isls[i][1][2]=self.screen_width/2-self.isls[i][1][0]/2
                self.isls[i][1][3]=self.screen_height/2-self.isls[i][1][1]/2
            else:
                if(self.isls[i][0]!=0):
                    if(self.isls[i][-1]=='da'):
                        self.isls[i][1][3]+=self.isls[i][1][1]/2
                        self.isls[i][1][1]=1
                    elif(self.isls[i][-1]=='dc'):
                        self.isls[i][1][3]+=self.isls[i][1][1]/2
                        self.isls[i][1][1]=1
                    elif(self.isls[i][-1]=='db'):
                        self.isls[i][1][2]+=self.isls[i][1][0]/2
                        self.isls[i][1][0]=1
                    elif(self.isls[i][-1]=='df'):
                        self.isls[i][1]=[1,1,self.screen_width/2,self.screen_height/2]
    
    def refresh_isl(self):
        if(self.movingisl!=[]):
            for i in range(len(self.isls)):
                if(self.isls[i][0]==self.movingisl[0]):
                    self.isls[i][2][2]=self.movingisl[1]+self.mousex
                    self.isls[i][2][3]=self.movingisl[2]+self.mousey
                    break
            if(self.mousex<self.screen_width/4):
                group='a'
                groupnum=0
            elif(self.mousex>self.screen_width*3/4):
                group='c'
                groupnum=2
            else:
                if(self.mousey<self.screen_height/4):
                    group='b'
                    groupnum=1
                else:
                    group='f'
                    groupnum=3
            self.to_isl(self.movingisl[0],self.movingisl[3][groupnum][0],self.movingisl[3][groupnum][1],group)

        for i in range(len(self.isls)):
            if(self.isls[i][0]!=0):
                now=self.isls[i][2]
                goal=self.isls[i][1]
                speed=[int((x-y)*10)/10 for x,y in zip(self.isls[i][2],self.isls[i][3])]
                l=[0.01*x+0.99*y+z*0.8 for x,y,z in zip(goal,now,speed)]
                self.isls[i][3]=list(self.isls[i][2])
                self.isls[i][2]=l
                self.isls[i][0].geometry(str(max(round(now[0]),1))+'x'+str(max(round(now[1]),1))+'+'+str(round(now[2]))+'+'+str(round(now[3])))
                self.isls[i][0].update()
                if('d' in self.isls[i][-1]):
                    delta=[round(x-y) for x,y in zip(self.isls[i][1],self.isls[i][2])]
                    if(delta==[0,0,0,0]):
                        self.isls[i][0].destroy()
                        self.isls[i][0]=0
                             
    def findisl(self,isl):
        num=-1
        for i in range(len(self.isls)):
            if(self.isls[i][0]==isl):
                num=i
                break
        return num
        
    def to_isl(self,isl,l,w,group=-1):
        formernum=self.findisl(isl)
        if(formernum==-1):
            isl.overrideredirect(True)
            isl.attributes('-alpha',0.7)
            isl.config(bg='black')
            self.isls.append([isl,[l,w,0,0],[0,0,0,0],[0,0,0,0],group])
            self.cal_pos()
            if(group=='a'):
                self.isls[-1][2]=[l,1,self.screen_gap,self.isls[-1][1][3]+w/2]
            elif(group=='b'):
                self.isls[-1][2]=[1,w,self.isls[-1][1][2]+l/2,self.screen_gap]
            elif(group=='c'):
                self.isls[-1][2]=[l,1,self.isls[-1][1][2],self.isls[-1][1][3]+w/2]
            elif(group=='f'):
                self.isls[-1][2]=[1,1,self.screen_width/2,self.screen_height/2]
            self.isls[-1][3]=self.isls[-1][2]
        else:
            self.isls[formernum][1][0]=l
            self.isls[formernum][1][1]=w
            self.isls[formernum][-1]=group
            self.cal_pos()
        
        if(formernum==-1):
            return len(self.isls)-1
        else:
            return formernum
    
    def del_isl(self,isl):
        formernum=self.findisl(isl)
        if(formernum!=-1):
            self.isls[formernum][-1]='d'+self.isls[formernum][-1]
            self.cal_pos()

    def draggable(self,isl,frame,hope):
        frame.bind('<B1-Motion>',lambda event:self.changepos(event,isl,hope))
        frame.bind('<ButtonRelease-1>',lambda event:self.buttonrel(event,isl))
    
    def disdraggable(self,frame):
        frame.unbind('<B1-Motion>')
        frame.unbind('<ButtonRelease-1>')
    
    def changepos(self,event,isl,hope):
        self.changingpos+=1
        
        self.mousex=event.x_root
        self.mousey=event.y_root
        if(self.changingpos==1):
            self.movingisl=[isl,isl.winfo_x()-self.mousex,isl.winfo_y()-self.mousey,hope]
            
    def buttonrel(self,event,isl):
        self.changingpos=0
        if(self.movingisl!=[]):
            self.movingisl=[]
        

class calendar:
    def __init__(self):
        self.moving_class=0
        self.labelsize=40
        self.gaprate=0.25
        self.onclass_rate=1
        self.classes=[]
        self.selects=[]
        self.on=[]
        self.off=[]
        self.onw=""
        self.offw=""
        self.nowgroup='b'
        self.l_nowgroup='b'
        self.showt_afterclass=0
        self.showt_onclass=0
        self.ontop_afterclass=0
        self.ontop_onclass=0
        self.ac_size=1

        self.class_change=[]
        self.labels=[]

        self.after_class=True
        self.l_after_class=True
        self.moving=False
        self.l_moving=False
        self.sec_past=False
        self.l_sec_past=False
        self.date_view=datetime.datetime.now()

    def load_class(self):
        today=datetime.datetime.now()
        self.to_week=datetime.date(today.year,today.month,today.day).weekday()
        self.week=["一","二","三","四","五","六","日"]
        
        with open('config.json',encoding="utf-8") as file:
            text=json.load(file)
            self.classes=text["日程表"]
            self.selects=text["更换选项"]
            self.on=text["开始时间"]
            self.off=text["结束时间"]
            for i in range(len(self.on)):
                self.on[i]=int(self.on[i][0])*60+int(self.on[i][1])
            for i in range(len(self.off)):
                self.off[i]=int(self.off[i][0])*60+int(self.off[i][1])


            self.onw=text["开始提示"][0]
            self.offw=text["结束提示"][0]
            self.onclass_default_pos=text["上课默认位置"][0]
            self.offclass_default_pos=text["下课默认位置"][0]
            # 初始位置根据当前状态决定
            self.nowgroup=self.offclass_default_pos  # 默认下课状态
            self.l_nowgroup=self.offclass_default_pos
            self.showt_afterclass=text["下课显示倒计时"][0]
            self.showt_onclass=text["上课显示倒计条"][0]
            self.ontop_afterclass=text["下课置顶"][0]
            self.ontop_onclass=text["上课置顶"][0]
            self.b_size=text["文字大小"][0]
            self.onclass_rate=text["上课放大倍率"][0]
            self.ac_size=text["竖直显示的文字大小"][0]

        with open('data.json', 'r') as file:
            self.class_change= json.load(file)
            
                   
    def start(self):
        self.mainland=Tk()
        self.isl_frame=movements()
        self.load_class()
        self.timer=time.time()
        self.counter=-1

        self.week=["一","二","三","四","五","六","日"]
        self.highlight=[]
        self.canvas=Canvas()
        
    def turn_date(self,t=0):
        self.date_view+=datetime.timedelta(days=t)
        self.t_date_view=self.date_view.strftime("%Y-%m-%d")
        self.todate.config(text=self.t_date_view)
        to_week=datetime.date(self.date_view.year,self.date_view.month,self.date_view.day).weekday()
        self.changing_class=["周"]+[self.week[to_week]]+["|"]+self.classes[str(to_week+1)]
        for i in self.class_change:
            if(i[0]==self.t_date_view):
                self.changing_class=i[1]
        for i in range(len(self.labels)):
            self.labels[i].config(text=self.changing_class[i],font=('幼圆',int(self.labelsize/(len(self.changing_class[i])**0.5))))


    def select(self,event,m,n):
        self.moving_class+=1
        self.mousex=event.x_root
        self.mousey=event.y_root
        if(self.moving_class==1):
            self.x_start=self.mousex
            self.y_start=self.mousey
            self.ml.tkraise()
            self.ml.config(text=n,font=('幼圆',int(self.b_size/(len(n)**0.5))),fg='yellow',bg='black',wraplength=self.b_size*1.5)
            
        self.ml.place(x=m.winfo_x()+self.mousex-self.x_start,y=m.winfo_y()+self.mousey-self.y_start)

    def release(self,event,m,n):
        dy=self.ml.winfo_y()
        dx=self.ml.winfo_x()
        if(dy>=self.b_size*(0.5+self.gaprate*2)-m.winfo_reqheight()/2 and dy<=self.b_size*(0.5+self.gaprate*2)+m.winfo_reqheight()/2):
            df=10000
            kf=0
            for i in range(len(self.labels)):
                if(not self.labels[i].cget('text') in ['周','|']+self.week):
                    if(df>abs(self.labels[i].winfo_x()-dx)):
                        df=abs(self.labels[i].winfo_x()-dx)
                        kf=i
                    else:
                        break
            self.changing_class[kf]=n
            self.class_change.append([self.t_date_view,self.changing_class])
            gx=self.labels[kf].winfo_x()
            gy=self.labels[kf].winfo_y()
            for i in range(1,40):
                self.ml.place(x=self.ml.winfo_x()*0.7+gx*0.3,y=self.ml.winfo_y()*0.7+gy*0.3)
                time.sleep(1/120)
                self.mainland.update()
            self.labels[kf].config(text=n,font=('幼圆',int(self.b_size/(len(n)**0.5))))
        else:
            gx=m.winfo_x()
            gy=m.winfo_y()
            for i in range(1,40):
                self.ml.place(x=self.ml.winfo_x()*0.7+gx*0.3,y=self.ml.winfo_y()*0.7+gy*0.3)
                time.sleep(1/120)
                self.mainland.update()
        self.moving_class=0
        self.ml.place_forget()

    def bind_letters(self,x,y,z):
        x.bind('<B1-Motion>',lambda event:self.select(event,y,z))
        x.bind('<ButtonRelease-1>',lambda event:self.release(event,y,z))
    
    def draw(self):
        if(self.counter==0):
            self.ml=Label(self.mainland)
            self.on_class_label=Label(self.mainland)
            self.todate=Label(self.mainland)
            self.left_shift=Button(self.mainland,command=lambda:self.turn_date(-1))
            self.right_shift=Button(self.mainland,command=lambda:self.turn_date(1))
            self.select_list=[0]*len(self.selects)
            for y in range(len(self.selects)):
                self.select_list[y]=Label(self.mainland,text=self.selects[y],font=('幼圆',int(self.b_size/(len(self.selects[y])**0.5))),fg='white',bg='black',wraplength=self.b_size*1.5)
                self.bind_letters(self.select_list[y],self.select_list[y],self.selects[y])
            self.f_height=self.b_size*((len(self.select_list)-1)//6*0.5+1+0.5+self.gaprate*2)+((len(self.select_list)-1)//6+2)*self.select_list[y].winfo_reqheight()
            self.changing_class=self.today_class
            
            #绑定拖动
            self.typical_size=[]
            for k in [0,1]:
                self.t_labels=[]
                self.t_labelsize=self.b_size
                if(k):
                    self.t_labelsize*=self.onclass_rate
                self.t_height=0
                t_position=self.t_labelsize*self.gaprate
                for x in range(len(self.today_class)):
                    if(x!=self.highlight):
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.t_labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.t_labelsize*1.5)
                    else:
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.t_labelsize/(len(self.today_class[x])**0.5))),fg='yellow',bg='black',wraplength=self.t_labelsize*1.5)
                    class_lab.place(x=t_position,y=self.t_labelsize*self.gaprate)
                    self.t_labels.append(class_lab)
                    t_position+=class_lab.winfo_reqwidth()
                    t_height=class_lab.winfo_reqheight()+self.t_labelsize*self.gaprate*2
                    if t_height>self.t_height:
                        self.t_height=t_height
                self.t_width=t_position+self.t_labelsize*self.gaprate
                for i in self.t_labels:
                    i.destroy()
                self.s_labels=[]
                self.s_labelsize=self.ac_size
                if(k):
                    self.s_labelsize*=self.onclass_rate
                self.s_width=0
                s_position=self.gaprate*self.s_labelsize
                for x in range(len(self.today_class)):
                    if(x!=self.highlight):
                        if(self.today_class[x]!='|'):
                            class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.s_labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.s_labelsize*1.5)
                        else:
                            class_lab=Label(self.mainland,text='—',font=('幼圆',int(self.s_labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.s_labelsize*1.5)
                    else:
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.s_labelsize/(len(self.today_class[x])**0.5))),fg='yellow',bg='black',wraplength=self.s_labelsize*1.5)
                    class_lab.place(x=self.s_labelsize*self.gaprate,y=s_position)
                    self.s_labels.append(class_lab)
                    s_position+=class_lab.winfo_reqheight()
                    s_width=class_lab.winfo_reqwidth()+self.s_labelsize*self.gaprate*2
                    if s_width>self.s_width:
                        self.s_width=s_width
                self.s_height=s_position+self.s_labelsize*self.gaprate
                for i in self.s_labels:
                    i.destroy()
                self.typical_size.append([self.t_width,self.t_height,self.s_width,self.s_height])

            #渲染文字
            self.labels=[]        
            if(self.nowgroup=='b'):
                self.labelsize=self.b_size
                if(not self.after_class):
                    self.labelsize*=self.onclass_rate
                self.height=0
                position=self.labelsize*self.gaprate
                for x in range(len(self.today_class)):
                    if(x!=self.highlight):
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.labelsize*1.5)
                    else:
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.labelsize/(len(self.today_class[x])**0.5))),fg='yellow',bg='black',wraplength=self.labelsize*1.5)
                    class_lab.place(x=position,y=self.labelsize*self.gaprate)
                    self.labels.append(class_lab)
                    position+=class_lab.winfo_reqwidth()
                    height=class_lab.winfo_reqheight()+self.labelsize*self.gaprate*2
                    if height>self.height:
                        self.height=height
                self.width=position+self.labelsize*self.gaprate
            else:
                self.labelsize=self.ac_size
                if(not self.after_class):
                    self.labelsize*=self.onclass_rate
                self.width=0
                position=self.gaprate*self.labelsize
                for x in range(len(self.today_class)):
                    if(x!=self.highlight):
                        if(self.today_class[x]!='|'):
                            class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.labelsize*1.5)
                        else:
                            class_lab=Label(self.mainland,text='—',font=('幼圆',int(self.labelsize/(len(self.today_class[x])**0.5))),fg='white',bg='black',wraplength=self.labelsize*1.5)
                    else:
                        class_lab=Label(self.mainland,text=self.today_class[x],font=('幼圆',int(self.labelsize/(len(self.today_class[x])**0.5))),fg='yellow',bg='black',wraplength=self.labelsize*1.5)
                    class_lab.place(x=self.labelsize*self.gaprate,y=position)
                    self.labels.append(class_lab)
                    position+=class_lab.winfo_reqheight()
                    width=class_lab.winfo_reqwidth()+self.labelsize*self.gaprate*2
                    if width>self.width:
                        self.width=width
                self.height=position+self.labelsize*self.gaprate
            
        if(self.counter==0 or self.after_class!=self.l_after_class or self.nowgroup!=self.l_nowgroup or self.sec_past!=self.l_sec_past):
            for i in range(len(self.labels)):
                if(i==self.highlight):
                    self.labels[i].config(fg='yellow')
                else:
                    if(self.sec_past and self.nowgroup!='f' and not self.after_class):
                        self.labels[i].place_forget()
                        self.labels[i].pack_forget()
                    else:
                        self.labels[i].config(fg='white')
            if(self.sec_past and self.nowgroup!='f' and not self.after_class):
                if(self.nowgroup=='b'):
                    self.labelsize=self.b_size*self.onclass_rate
                    self.labels[self.highlight].config(font=('幼圆',int(self.labelsize/(len(self.labels[self.highlight].cget('text'))**0.5))),wraplength=self.labelsize*1.5)
                    self.labels[self.highlight].pack(padx=0,anchor='n',pady=self.labelsize*self.gaprate)
                    self.on_class_label.config(text=self.onw,font=('幼圆',int(self.labelsize)),fg='yellow',bg='black',anchor='s')
                    self.on_class_label.pack()
                    self.width=self.on_class_label.winfo_reqwidth()+self.labelsize*self.gaprate*2
                    self.height=self.on_class_label.winfo_reqheight()+self.labelsize*self.gaprate*3+self.labels[self.highlight].winfo_reqheight()
                else:
                    self.labelsize=self.ac_size*self.onclass_rate
                    self.labels[self.highlight].config(font=('幼圆',int(self.labelsize/(len(self.labels[self.highlight].cget('text'))**0.5))),wraplength=self.labelsize*1.5)
                    if(self.nowgroup=='a'):
                        self.labels[self.highlight].pack(padx=self.labelsize*self.gaprate,anchor='nw',pady=self.labelsize*self.gaprate)
                        self.on_class_label.config(text=self.onw,font=('幼圆',int(self.labelsize)),fg='yellow',bg='black',anchor='nw')
                        self.on_class_label.place(x=self.labelsize*self.gaprate*2+self.labels[self.highlight].winfo_reqwidth(),y=self.labelsize*self.gaprate)
                    else:
                        self.labels[self.highlight].pack(padx=self.labelsize*self.gaprate,anchor='ne',pady=self.labelsize*self.gaprate)
                        self.on_class_label.config(text=self.onw,font=('幼圆',int(self.labelsize)),fg='yellow',bg='black',anchor='w')
                        self.on_class_label.place(x=self.labelsize*self.gaprate,y=self.labelsize*self.gaprate)
                    
                    self.height=self.on_class_label.winfo_reqheight()+self.labelsize*self.gaprate*2
                    self.width=self.on_class_label.winfo_reqwidth()+self.labelsize*self.gaprate*4+self.labels[self.highlight].winfo_reqwidth()
            elif(self.nowgroup in ['b','f']):
                self.labelsize=self.b_size
                if(not self.after_class and self.nowgroup!='f'):
                    self.labelsize*=self.onclass_rate
                position=self.labelsize*self.gaprate
                for i in self.labels:
                    if(i.cget('text')=='—'):
                        i.config(text='|',font=('幼圆',int(self.labelsize/(len(i.cget('text'))**0.5))),wraplength=self.labelsize*1.5)
                    else:
                        i.config(font=('幼圆',int(self.labelsize/(len(i.cget('text'))**0.5))),wraplength=self.labelsize*1.5,anchor='nw')
                    if(self.nowgroup=='b'):
                        i.place(x=position,y=self.labelsize*self.gaprate)
                    else:
                        i.place(x=position,y=self.labelsize/2+self.labelsize*self.gaprate*2)
                    position+=i.winfo_reqwidth()
                if(self.after_class):
                    self.width=self.typical_size[0][0]
                    self.height=self.typical_size[0][1]
                else:
                    self.width=self.typical_size[1][0]
                    self.height=self.typical_size[1][1]
            else:
                self.labelsize=self.ac_size
                if(not self.after_class):
                    self.labelsize*=self.onclass_rate
                position=self.labelsize*self.gaprate
                for i in self.labels:
                    if(i.cget('text')=='|'):
                        i.config(text='—',font=('幼圆',int(self.labelsize/(len(i.cget('text'))**0.5))),wraplength=self.labelsize*1.5)
                    else:
                        i.config(font=('幼圆',int(self.labelsize/(len(i.cget('text'))**0.5))),wraplength=self.labelsize*1.5,anchor='nw')
                    i.place(x=self.labelsize*self.gaprate,y=position)
                    position+=i.winfo_reqheight()
                if(self.after_class):
                    self.width=self.typical_size[0][2]
                    self.height=self.typical_size[0][3]
                else:
                    self.width=self.typical_size[1][2]
                    self.height=self.typical_size[1][3]

            if(self.after_class):
                if(self.nowgroup=='f'):
                    self.isl_frame.draggable(self.mainland,self.canvas,[self.typical_size[0][2:4],self.typical_size[0][0:2],self.typical_size[0][2:4],[self.typical_size[0][0],self.f_height]])
                else:
                    self.isl_frame.draggable(self.mainland,self.mainland,[self.typical_size[0][2:4],self.typical_size[0][0:2],self.typical_size[0][2:4],[self.typical_size[0][0],self.f_height]])
            else:
                if(self.nowgroup=='f'):
                    self.isl_frame.draggable(self.mainland,self.canvas,[self.typical_size[1][2:4],self.typical_size[1][0:2],self.typical_size[1][2:4],[self.typical_size[0][0],self.f_height]])
                else:
                    self.isl_frame.draggable(self.mainland,self.mainland,[self.typical_size[1][2:4],self.typical_size[1][0:2],self.typical_size[1][2:4],[self.typical_size[0][0],self.f_height]])

            self.main_num=self.isl_frame.to_isl(self.mainland,self.width,self.height,self.nowgroup)

        if(self.moving!=self.l_moving and self.nowgroup=='f' and not self.moving):
            self.isl_frame.disdraggable(self.mainland)
        
        if(self.counter==0 or self.after_class!=self.l_after_class):
            #置顶
            if(self.after_class):
                self.mainland.attributes('-topmost',bool(self.ontop_afterclass))
                self.viceland=Tk()
                self.vicelabel=Label()
            else:
                self.mainland.attributes('-topmost',bool(self.ontop_onclass))

        if(self.counter==0 or self.after_class!=self.l_after_class or self.nowgroup!=self.l_nowgroup or self.sec_past!=self.l_sec_past):
            #渲染进度条
            self.canvas.delete('all')
            if(self.nowgroup=='f'):
                self.canvas.config(bg="black", width=int(self.typical_size[0][0]), height=self.labelsize/2+self.labelsize*self.gaprate*2,highlightthickness=0)
            elif(not self.after_class and self.showt_onclass):
                if(self.nowgroup=='b'):
                    self.canvas.config( bg="black", width=int(self.isl_frame.isls[self.main_num][1][0]), height=self.labelsize*self.gaprate,highlightthickness=0)
                else:
                    self.canvas.config(bg="black", height=int(self.isl_frame.isls[self.main_num][1][1]), width=self.labelsize*self.gaprate,highlightthickness=0)
      
        if(not self.after_class and self.sec_past!=self.l_sec_past and not self.sec_past):
           self.on_class_label.place_forget()
           self.on_class_label.pack_forget()

        #倒计时
        if(self.counter%12==0 or self.nowgroup!=self.l_nowgroup or self.sec_past!=self.l_sec_past):
            if(self.nowgroup=='f'):
                self.canvas.delete('all')
                self.rect=self.canvas.create_rectangle(self.isl_frame.isls[self.main_num][1][0]/4, self.labelsize*self.gaprate*1/4,self.isl_frame.isls[self.main_num][1][0]*3/4, self.labelsize*self.gaprate*3/4, fill='black',width=0)
                self.canvas.place(x=0,y=0)
            if(self.after_class):
                if(self.nowgroup in ['b','f']):
                    self.labelsize=self.b_size
                    self.vicelabel.destroy()
                    if(self.showt_afterclass and not self.sec_past):
                        self.vicelabel=Label(self.viceland,text=self.time_left,font=('黑体',self.labelsize),fg='yellow',bg='black')
                    else:
                        self.vicelabel=Label(self.viceland,text=self.offw,font=('幼圆',self.labelsize),fg='yellow',bg='black')
                else:
                    self.labelsize=self.ac_size
                    self.vicelabel.destroy()
                    if(self.showt_afterclass):
                        ac_text=self.time_left[0]+self.time_left[1]+'\n\n'+self.time_left[3]+self.time_left[4]
                        self.vicelabel=Label(self.viceland,text=ac_text,font=('黑体',self.labelsize),fg='yellow',bg='black',wraplength=self.labelsize*1.5)
                    else:
                        self.vicelabel=Label(self.viceland,text=self.offw,font=('幼圆',self.labelsize),fg='yellow',bg='black',wraplength=self.labelsize*1.5)
                self.vicelabel.place(x=self.labelsize*self.gaprate,y=self.labelsize*self.gaprate)
            else:
                if(self.nowgroup=='b'):
                    self.canvas.delete('all')
                    w=self.isl_frame.isls[self.main_num][1][0]*self.sec_left/60/(self.off[self.off_i]-self.on[self.on_i-1])
                    self.rect=self.canvas.create_rectangle(self.isl_frame.isls[self.main_num][1][0]/2-w/2, self.labelsize*self.gaprate*1/4,self.isl_frame.isls[self.main_num][1][0]/2+w/2, self.labelsize*self.gaprate*3/4, fill='grey',width=0)
                    self.canvas.place(y=int(self.isl_frame.isls[self.main_num][1][1]-self.labelsize*self.gaprate))
                elif(self.nowgroup!='f'):
                    self.canvas.delete('all')
                    h=self.isl_frame.isls[self.main_num][1][1]*self.sec_left/60/(self.off[self.off_i]-self.on[self.on_i-1])
                    self.rect=self.canvas.create_rectangle(self.labelsize*self.gaprate*1/4,self.isl_frame.isls[self.main_num][1][1]/2-h/2, self.labelsize*self.gaprate*3/4,self.isl_frame.isls[self.main_num][1][1]/2+h/2,  fill='grey',width=0)
                    if(self.nowgroup=='a'):
                        self.canvas.place(x=int(self.isl_frame.isls[self.main_num][1][0]-self.labelsize*self.gaprate))
                    elif(self.nowgroup=='c'):
                        self.canvas.place(x=0)

        if(self.after_class!=self.l_after_class or self.counter==0 or self.nowgroup!=self.l_nowgroup or self.sec_past!=self.l_sec_past):
            if(self.after_class):
                self.viceland.attributes('-topmost',bool(self.ontop_afterclass))
                if(self.nowgroup!='f'):
                    self.isl_frame.to_isl(self.viceland,self.vicelabel.winfo_reqwidth()+self.labelsize*self.gaprate*2,self.vicelabel.winfo_reqheight()+self.labelsize*self.gaprate*2,self.nowgroup)
                else:
                    self.isl_frame.to_isl(self.viceland,self.vicelabel.winfo_reqwidth()+self.labelsize*self.gaprate*2,self.vicelabel.winfo_reqheight()+self.labelsize*self.gaprate*2,'b')
            else:
                if(self.after_class!=self.l_after_class and self.counter!=0):
                    self.isl_frame.del_isl(self.viceland)

        if(self.nowgroup!=self.l_nowgroup and self.nowgroup=='f'):
            self.width=self.typical_size[0][0]
            self.height=self.f_height
            self.date_view=datetime.datetime.now()
            self.t_date_view=self.date_view.strftime("%Y-%m-%d")
            self.todate.config(text=self.date_now,font=('黑体',int(self.labelsize/2)),fg='white',bg='black',highlightcolor='yellow')
            self.todate.pack(anchor='n',pady=1/2*self.labelsize*self.gaprate)
            self.left_shift.config(text='<',font=('黑体',int(self.labelsize/2)),fg='white',bg='black',bd=0)
            self.left_shift.place(x=self.width/2-self.todate.winfo_reqwidth()/2-self.left_shift.winfo_reqwidth()-self.labelsize*self.gaprate,y=0)
            self.right_shift.config(text='>',font=('黑体',int(self.labelsize/2)),fg='white',bg='black',bd=0)
            self.right_shift.place(x=self.width/2+self.todate.winfo_reqwidth()/2+self.labelsize*self.gaprate,y=0)

            mem=(self.width-6*self.select_list[0].winfo_reqwidth())/7
            for i in range(len(self.select_list)):
                self.select_list[i].place(x=mem+i%6*(mem+self.select_list[0].winfo_reqwidth()),y=self.labelsize*(1+self.gaprate*2)+self.select_list[0].winfo_reqheight()+(self.labelsize/2+self.select_list[0].winfo_reqheight())*(i//6))
                
        if(self.nowgroup!=self.l_nowgroup and self.nowgroup!='f'):
            self.todate.pack_forget()
            self.left_shift.place_forget()
            self.right_shift.place_forget()
            for i in self.select_list:
                i.place_forget()
            self.class_change.append([self.date_now,self.changing_class])
            templ=[]
            tempr=[]
            self.class_change.reverse()
            format_pattern = '%Y-%m-%d'
            for i in self.class_change:
                difference = (datetime.datetime.strptime(i[0] , format_pattern) - datetime.datetime.strptime(self.date_now, format_pattern))
                if(not i[0] in templ and difference.days>=0):
                    templ.append(i[0])
                    tempr.append(i)
            self.class_change=tempr

            with open('data.json', 'w') as file:
                json.dump(self.class_change, file)


        self.l_nowgroup=self.nowgroup
        self.nowgroup=self.isl_frame.isls[self.main_num][-1]
        self.l_moving=self.moving
        self.moving=self.isl_frame.changingpos
            
    def refresh(self):
        while True:
            if os.path.exists('stop_signal.txt'):
                print("检测到停止信号，程序即将退出。")
                break
            time.sleep(max(1/120-(time.time()-self.timer),0))
            #print(int((time.time()-self.timer)*1000))
            self.timer=time.time()
            self.counter+=1

            
            if(self.counter==0):
                today=datetime.datetime.now()
                to_week=datetime.date(today.year,today.month,today.day).weekday()
                self.date_now=today.strftime("%Y-%m-%d")
                self.today_class=["周"]+[self.week[to_week]]+["|"]+self.classes[str(to_week+1)]
                for i in self.class_change:
                    if(i[0]==self.date_now):
                        self.today_class=i[1]



            if(self.counter%60==0):
                nowt=datetime.datetime.now().hour*60+datetime.datetime.now().minute
                self.on_i=-1
                for i in range(len(self.on)):
                    if(nowt<self.on[i]):
                        self.on_i=i
                        break
                self.off_i=-1
                for i in range(len(self.off)):
                    if(nowt<self.off[i]):
                        self.off_i=i
                        break

                self.after_class=self.on_i==self.off_i
                
                # 自动切换显示位置
                if self.after_class != self.l_after_class:
                    if self.after_class:
                        # 下课状态，切换到下课默认位置
                        self.nowgroup = self.offclass_default_pos
                    else:
                        # 上课状态，切换到上课默认位置
                        self.nowgroup = self.onclass_default_pos
                
                self.highlight=self.off_i
                for i in range(len(self.today_class)):
                    if(not self.today_class[i] in ['周','|']+self.week):
                        self.highlight-=1
                        if(self.highlight<0):
                            self.highlight=i
                            break


                
            if(self.counter%12==0):
                now_sec=datetime.datetime.now().hour*3600+datetime.datetime.now().minute*60+datetime.datetime.now().second
                if(self.after_class):
                    self.sec_left=self.on[self.on_i]*60-now_sec
                else:
                    self.sec_left=self.off[self.off_i]*60-now_sec
                if(len(str(self.sec_left//60))==1):
                    self.time_left='0'+str(self.sec_left//60)+':'
                else:
                    self.time_left=str(self.sec_left//60)+':'
                if(len(str(self.sec_left%60))==1):
                    self.time_left+='0'+str(self.sec_left%60)
                else:
                    self.time_left+=str(self.sec_left%60)
                if(now_sec-self.on[self.on_i-1]*60 in [0,1,2,3,4,5] or now_sec-self.off[self.off_i-1]*60 in [0,1,2,3,4,5]):
                    self.sec_past=True
                else:
                    self.sec_past=False
                #print(self.sec_past)
            
            self.draw()
            
            #self.mainframe.geometry(str(int(self.isl_frame.isls[self.main_num][1][0]))+'x'+str(int(self.isl_frame.isls[self.main_num][1][1]))+'+'+str(int(self.isl_frame.isls[self.main_num][1][2]))+'+'+str(int(self.isl_frame.isls[self.main_num][1][3])))
            #m=time.time()
            
            
            self.isl_frame.refresh_isl()
            #print(int((time.time()-m)*1000))
            #if(self.counter%12==0):
            #    print(self.isl_frame.isls)
            
            self.l_after_class=self.after_class
            self.l_sec_past=self.sec_past
                
            

if os.path.exists('stop_signal.txt'):
    #清理已经使用的停止信号
    os.remove('stop_signal.txt')
classform=calendar()
classform.start()
classform.refresh()
