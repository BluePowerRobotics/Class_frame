# 运行日志与课堂机排障

课堂机不方便接 adb（也不想为此把个人电脑带进教室），所以 app 自带一套"能把现场带出来"的日志。

## 日志写在哪里

| 位置 | 路径 | 说明 |
| --- | --- | --- |
| logcat | tag `ClassFrame` | 有 adb 时最快 |
| app 私有目录 | `/data/data/org.bluepowerrobotics.classframe/files/log/class_frame.log` | 本次运行 |
| 上一次运行 | 同目录 `class_frame.prev.log` | 启动时把上一份改名留存，避免"重启一次现场就没了" |

单文件超过 512 KB 时滚动为 `.old`。

## 三种取日志的方式

1. **app 内直接看**：system 页 →「查看运行日志」。文本可选中复制，也可以直接截图。
2. **导出成文件**：system 页 →「导出运行日志」→ 系统文件窗口选 U 盘 → 得到一个 txt。
3. **文件管理器**：Android 11+ 可能看不到 `Android/data`，那就走前两种。

导出内容是"上次运行日志 + 本次运行日志 + 设备环境（sdk / release / brand / model）"。

## 崩溃时的行为

- 未捕获异常会整栈写入日志文件，并在通知栏留一条「灵动课表刚刚出错」的通知；点通知回到 app，
  再从 system 页导出即可。
- 后台线程崩溃同样会被记录（`Thread.setDefaultUncaughtExceptionHandler` 对全部线程生效）。
- 如果**进程直接被系统杀掉**（例如前台服务被拒、被 ROM 冻结），日志会停在最后一条记录上，
  这本身就说明死在哪一步。

## 关键排查点

打开 app 后日志里应当能依次看到：

```
app onCreate
env sdk=.. release=.. brand=.. model=..
Ui MainActivity.onCreate env ...
Ui MainActivity.onResume, overlayEnabled=true canDrawOverlays=true
service onCreate
service onStartCommand action=...START
startForeground ok
schedule: visible -> show
overlay show: 开始创建绘制窗口
overlay show: 绘制窗口已添加 type=2038 sdk=..
overlay show: 触摸代理窗口已添加 type=2038
overlay show ok
```

缺哪一行就等于定位到哪一步失败：

| 缺失/出现的行 | 含义 | 处理 |
| --- | --- | --- |
| 没有 `MainActivity.onResume` | Activity 没起来或更早就崩 | 看 `FATAL` 段 |
| `overlayEnabled=false` | 悬浮窗开关被关掉 | system 页打开开关 |
| `overlay show skipped: 未授予...` | 悬浮权限没给 | system 页「去授予」→ 打开对应设置 |
| 有 `startForeground failed` | 前台服务被系统拒绝 | 按日志里的异常类型处理（FGS 类型 / 通知权限） |
| 有 `overlay show failed` | `WindowManager.addView` 抛异常 | 异常栈里通常是 `BadTokenException` / 权限 |
| 只有 `app onCreate` 之后什么都没有 | 进程在 Application 阶段被杀或崩溃 | 通知栏找崩溃通知，或看 `FATAL` |

页面级异常不会闪退：三个 tab 各自构造/显示都被 try/catch 包住，出错时把异常栈直接画在该页上，
同时写日志，便于截图反馈。

## 设备环境

日志会记录 `sdk`、`release`、`brand`、`model`、`abi`。希沃各代机型差异较大，
这一行能直接确认"这台到底是 Android 7 还是 11/12"，避免靠猜。
