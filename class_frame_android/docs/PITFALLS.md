# 已踩过的坑（真机复现并修复）

这份文档记录"看代码看不出、只能上真机才发现"的问题，避免重复踩。

## 1. 时间校准对话框一点就闪退 — 两个输入框互相回写导致 StackOverflowError

**现象**：system 页点「时间校准」，对话框还没出现就闪退（体感像"卡死"）。

**日志**（logcat）：

```
E AndroidRuntime: 	at android.widget.TextView.setText(TextView.java:5545)
E AndroidRuntime: 	at SystemPage$28.afterTextChanged(SystemPage.java:754)
E AndroidRuntime: 	at SystemPage.refreshTimes(SystemPage.java:875)
E AndroidRuntime: 	at SystemPage.showTimeDialog(SystemPage.java:778)
```

**原因**：偏移输入框与时间输入框各挂了一个 `TextWatcher`，互相 `setText`。
原来靠 `hasFocus()` 判断重入，但对话框刚构造时两边都没有焦点，于是无限递归。

**修法**：用一个 `syncing[0]` 标志位包住所有程序化写入，并在 `setText` 前比较文本，
值相同就不写。初次填充也整段处于"正在同步"状态。

**教训**：互相联动的输入框不能用 `hasFocus()` 做重入保护，必须用显式标志位。

## 2. 选择班级后"②每日"不生效 — app 私有目录里是旧模板

**现象**：setclass 里选 2701/2704 并应用配置，课表变了，但位置（早读=上表、答疑前一节=时钟）
没有跟着变。

**排查**：

```bash
adb shell "run-as org.bluepowerrobotics.classframe ls -l files/classes_frame/"
# 结果：2701.json 还是 9/20 的旧文件，文件里没有 全局日程/每日日程/单课日程
```

**原因**：`ConfigRepository.ensureInitialized` 只在"目录不存在"时把 assets 里的模板复制一次；
APK 升级后私有目录里的旧副本永远不会刷新。应用班级时读的是这份旧文件，自然没有新表。

**修法**：`ClassTemplateRepository.syncAssets` 在每次启动时**按内容比对** assets 与私有目录副本，
不一致就覆盖；用户改过的文件（与上次复制时记录的 SHA-1 不符）跳过不覆盖。

**注意**：这条路径踩过两次坑——先是用"目录存在就不再复制"，后用"版本标记"判断，
标记与实际内容错位后同样失效。**能用内容比对就不要用标记。**

**怎么验证**：

```bash
adb logcat | grep ClassTemplate     # 应看到"已刷新班级模板 2701.json"
adb shell run-as org.bluepowerrobotics.classframe cat files/classes_frame/2701.json
```

## 3. 悬浮窗关闭后自己又打开

见 `docs/LOGGING.md` 与提交 `6a924f6`：`OverlayController.show()` 里必须把
「启用悬浮窗」当硬闸门，否则唤醒、服务重启、界面按钮都能绕过去。

## 4. 调试期通用经验

- 设备上的 app 日志：`adb shell run-as org.bluepowerrobotics.classframe cat files/log/class_frame.log`
  （公开位置还有一份：`/sdcard/Android/data/<包名>/files/log/class_frame.log`）
- 改完代码务必 `adb install -r` 后再看日志，并在日志里确认进程真的重启了
  （看 `===== app 启动` 那一行的时间戳）。
- 判断"新代码有没有装进去"，最可靠的办法是拉回 APK 比对 SHA-256，或看日志里的新特征行。
