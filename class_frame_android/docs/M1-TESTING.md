# M1 测试指南

## 1. 产物

| 构建 | 路径 | 大小 |
| --- | --- | --- |
| debug | `app/build/outputs/apk/debug/app-debug.apk` | 约 41 KB |
| release | `app/build/outputs/apk/release/app-release.apk` | 约 33 KB |

- 包名：`org.bluepowerrobotics.classframe`
- 版本：`0.1.0`（versionCode 1）
- minSdk 24 / targetSdk 35
- 两个 APK 使用同一把密钥签名，证书 SHA-256：
  `78:F9:23:DE:18:6D:93:2E:37:19:7A:89:D9:3C:AF:D3:55:1E:D6:09:D8:E9:1A:FE:90:EB:2F:8A:94:35:6D:53`
- 因此 debug 与 release 可以互相覆盖安装（用 `adb install -r`）

密钥库：`keystore/classframe.jks`（别名 `classframe`，口令 `classframe`）。请妥善备份，丢失后无法原地升级。

## 2. 安装

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB install -r class_frame_android/app/build/outputs/apk/debug/app-debug.apk
```

## 3. 首次使用流程

1. 打开「灵动课表」。
2. 点「去授予」，在系统页面允许「显示在其他应用上方」。
3. 「启用悬浮窗」默认已打开。
4. 可选：授予电池优化豁免、Android 13+ 授予通知权限、打开「启用开机自启」。
5. 回到桌面或其他应用，屏幕上方应出现黑底课表。

## 4. 预期效果（验收项）

**A. 悬浮层外观**

- 位置：屏幕最上方、水平居中，距屏幕顶边约 10dp（利用 `FLAG_LAYOUT_IN_SCREEN`，会覆盖状态栏所在的区域，与 Python 版贴顶一致）
- 外观：纯黑背景、整体 92% 不透明、白字；当前正在上的课节为黄字
- 内容：`周` + 当天星期 + `|` + 当天课表，与 `config.json` 中 `日程表` 对应行一致
- 尺寸：随内容长度自适应；字号取自「文字大小」（M1 按 dp 处理）
- 触摸：只有自身矩形区域接收触摸，其余区域不受影响

**B. 权限与开关**

- 未授予悬浮权限时点「显示悬浮层」会跳到系统设置，不崩溃
- 关闭「启用悬浮窗」→ 悬浮层立即消失、前台服务停止、通知消失
- 重新打开 → 悬浮层恢复
- 「移除悬浮层」只移除悬浮层，前台服务与通知保留
- 「刷新课表」重新读取 config.json 并重绘

**C. 开机自启**

- 打开「启用开机自启」并已授予悬浮权限后重启设备 → 不打开 App 也会自动出现悬浮层
- 关闭该开关后重启 → 不出现

**D. 自检页应显示**

- 数据目录：`/data/user/0/org.bluepowerrobotics.classframe/files`
- 悬浮层状态、悬浮权限、电池优化、开机自启状态
- config.json 解析结果：课节数 12、文字大小 40、竖排字号 20、进度条宽度 16、上课默认 `upper`(文字样式)、下课默认 `upper`(第二样式)、提示文案「准备上课 / 下课时间」
- 今日 token 列表（`周` + 星期 + `|` + 当天课表；按当前 config.json 为 18 项）
- 当前时间状态（上课中第 N 节 / 课间距第 N 节 / 今日课程已结束）
- 班级模板数量 3（2701 / 2704 / 2707）

**E. 通知**

- Android 8+ 出现「悬浮课表」通知渠道
- Android 13+ 首次启动会请求通知权限；拒绝后悬浮层仍可运行，只是看不到常驻通知

## 5. M1 阶段已知差异（属于计划内）

- 只支持「上方居中」一种形态；拖动、四停靠、第二样式、进度条、倒计时、center 编辑器都在 M2
- 字体为系统 sans-serif，内置资源圆体在 M2/M4 加入
- 字号按 dp 处理，多分辨率校准在 M2
- 悬浮层不会随上课/下课自动重绘，M3 接入调度；当前可点「刷新课表」
- Python 版在本机运行时会强制显示系统导航栏，外观细节（尤其贴顶位置）可能与 Android 版不同，比对时以内容与相对位置为准

## 6. 常见问题

| 现象 | 处理 |
| --- | --- |
| 悬浮层不出现 | 检查「显示在其他应用上方」是否已授予（自检页会显示）；部分系统需要重新打开 App |
| 重启后不出现 | 检查「启用开机自启」是否打开；部分系统需在设置里允许自启动 |
| 看不到通知 | Android 13+ 需授予通知权限，不影响悬浮层 |
| 安装失败 | 若之前装过其他签名版本，先 `adb uninstall org.bluepowerrobotics.classframe` |
| 悬浮层遮住状态栏 | 预期行为，与 Python 版贴顶一致；如需避让可在 M2 调整 |

## 8. 小屏设备的自适应（M1 临时行为）

config.json 的 `文字大小` 是按大屏设计的（当前值 40）。在 1920 宽的希沃一体机上整行约 970px，不会超宽；
但在 400px 宽的小屏设备上会超出屏幕。M1 因此加了一条临时规则：**当内容宽度超过屏幕可用宽度时，
整体等比缩小到可放下为止**（只改尺寸，不改内容）。1920 宽的设备不会触发这条规则。

## 9. 实测记录（2026-09-20）

测试设备：一台 Android 9（API 28）测试机（竖屏 400×960 ／ 横屏 960×400）。

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 安装与启动 | 通过 | `adb install -r` 成功，MainActivity 正常显示 |
| 悬浮层显示在其他应用之上 | 通过 | `dumpsys window` 中存在 `class_frame_overlay`（`ty=APPLICATION_OVERLAY`）；在系统桌面截图中可见悬浮层 |
| 内容与 config 一致 | 通过 | 2026-09-20 为周日，显示 `周日\|无\|…`，与 `日程表["7"]` 完全一致 |
| 窗口尺寸 | 通过 | 横屏 806×88（原始字号，居中 x=77）；竖屏等比缩放为 358×40 以适配 400px 宽 |
| 旋转自适应 | 通过 | 横竖屏切换后窗口自动重新居中 |
| 权限状态识别 | 通过 | 自检页显示"悬浮权限：已授予""电池优化：已豁免" |
| 前台服务通知 | 通过 | `dumpsys notification` 中存在 `channel=overlay`、`id=1001` 的通知，图标为 `ic_notification` |
| 配置解析 | 通过 | 课节数 12、文字大小 40、竖排字号 20、进度条宽 16、上/下课默认 `upper`、模板 3 个 |
| 开机自启链路 | 通过（模拟广播） | 发送 `BOOT_COMPLETED` 后进程被拉起且悬浮窗出现 |

### 测试中发现并已修复的问题

1. **小屏内容超宽**：原实现按 config 的 `文字大小` 直接出图，806px 内容在 400px 屏上被裁切。已加入"超宽等比缩放"（见第 8 节）。
2. **测试页文字与背景同为黑色**：根布局未显式设置背景，窗口背景未生效导致文字不可见。已在根 `ScrollView` 上显式设置浅色背景。
3. **自检页悬浮层状态滞后**：前台服务异步启动，首次读取时状态尚未更新。已在 `onResume` 后延迟 1 秒重新刷新。
4. **旋转后窗口不重新居中**：已为 `OverlayService` 增加 `onConfigurationChanged` 处理。

### 需要留意的 Android 机制

- 应用被 `adb shell am force-stop` 或用户在设置里"强行停止"后，系统会将其标记为 stopped，**此后不会再收到 `BOOT_COMPLETED`**，直到用户再次手动打开应用。这是系统行为，不是缺陷，但需要在交付说明中写清楚。
- 悬浮层使用 `FLAG_LAYOUT_IN_SCREEN` 贴屏幕顶端，在有手机式状态栏的设备上会盖住状态栏（截图中时间被遮住一部分）。希沃一体机没有该状态栏，因此与 Python 版观感一致；如需避让可在 M2 加开关。

### 本次测试采用的操作

- 悬浮权限通过 `adb shell appops set org.bluepowerrobotics.classframe SYSTEM_ALERT_WINDOW allow` 授予（等价于在系统设置里授权）。
- "启用开机自启"通过直接写入 `shared_prefs/class_frame.xml` 打开（界面上该开关在滚动区域外）。
- 未执行设备重启；开机自启用 `am broadcast -a android.intent.action.BOOT_COMPLETED -p <包名>` 模拟验证。

## 7. 本机构建命令

本机有两个特殊条件，已确认并记录：

1. `com.android.tools.build:aapt2:8.7.3` 的 mac 版 Jar 未在 Gradle 缓存中，需用 SDK 自带的 aapt2 覆盖；
2. GraalVM 21 的 `jlink` 与 AGP 的 `JdkImageTransform` 不兼容，需使用 Android Studio 自带的 JBR。

```bash
./gradlew assembleDebug assembleRelease
```

离线或受限环境下可能用到的两个绕过手段（路径按自己机器替换）：

- `aapt2` 的宿主平台 Jar 未缓存时，用
  `-Pandroid.aapt2FromMavenOverride=<Android SDK>/build-tools/<版本>/aapt2`
  指向 SDK 自带的 aapt2；
- 若所用 JDK 的 `jlink` 与 AGP 不兼容，把 `JAVA_HOME` 指向 Android Studio 自带的 JBR。
