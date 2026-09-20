# class_frame Android 版

把本仓库的 Python/Tk 课表悬浮层（`class_frame.py` / `edit.py` / `setClass.py`）重写为 Android 原生应用。

本目录当前只有规划文档，尚未开始编写代码。桌面版 Python 代码、`config.json`、`data.json`、`classes_frame/` 的字段含义与语义保持不变。

> 代码基线：`class_frame.py` @ `3d08bff`（含"拖拽至屏幕边缘带强制 secondStyle"）。远端更新时
> 请先备份本目录、再合并远端，最后把本目录放回。

## 1. 项目目标

| 编号 | 目标 | 验收方式 |
| --- | --- | --- |
| G1 | 悬浮层显示内容与现有 Python 版一致 | 同一份 config.json 下，四种停靠位置 × 两种样式 × 上课/课间/放学各状态的显示与 Python 版逐项比对 |
| G2 | 开机自启，开机后自动显示悬浮层，且悬浮在其他应用上方 | 重启设备后未手动打开 App，悬浮层自动出现并压在其他应用之上 |
| G3 | 图标启动进入 Bottom Navigation：edit（默认）/ setclass / system | 三页均可用，edit 与 setclass 与对应 Python 功能对齐 |
| G4 | 与桌面版数据双向兼容 | 桌面版导出的 config.json / data.json / 班级模板可直接导入使用，Android 导出的文件桌面版可读 |
| G5 | 常驻低功耗 | 无窗口大小/位置/内容变化时不渲染；仅交互与动画期间使用高帧率 |
| G6 | 覆盖 4 代机及以上 | minSdk 24（Android 7.0），在 4/5/6/7 代希沃一体机的安卓通道上可运行 |

## 2. 非目标

- 不做国内厂商 ROM 专项适配（用户已确认：希沃安卓通道接近 AOSP，定制主要在 Windows 侧边栏）。
- 不支持 API 23 及以下，即 3 代及更早的 Android 4.4 机型。
- 不做云同步、账号体系、多设备协同。
- 不改动桌面版 Python 代码，也不改变现有 JSON 字段的含义。

## 3. 技术选型

| 项 | 选择 | 理由 |
| --- | --- | --- |
| 应用包名 | `org.bluepowerrobotics.classframe` | 已确认 |
| 显示名 | 灵动课表 | 已确认 |
| 语言 | Java | 用户偏好；悬浮层用自定义 View + Canvas，不需要 Kotlin 特性 |
| 最低版本 | minSdk 24（Android 7.0） | 与 4 代机系统版本对齐 |
| 编译/目标版本 | compileSdk / targetSdk 取当年最新 | 跟随系统行为与工具链要求 |
| 悬浮层渲染 | `WindowManager` + 自定义 `View`/`Canvas` | 需要精确控制文字测量与像素布局，第三方渲染库不合适 |
| 悬浮层调度 | 事件驱动 + 按需 `Choreographer` | 满足"无变动不渲染"与低功耗 |
| 主界面 | `BottomNavigationView` + Fragment + TabLayout | Java 生态成熟，贴近原 Notebook 结构 |
| 常驻 | 前台 Service（Android 14+ 声明 `specialUse`） | 开机后长期存在，支撑悬浮层与次日重现 |
| 文件交互 | SAF（`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`） | 系统文件管理窗口，无需存储权限 |
| 字体 | 内置开源圆体 + `SystemFonts`(29+) + 目录遍历回退 | 见 `docs/DESIGN.md` 字体管线 |

## 4. 里程碑

| 里程碑 | 内容 | 人天 |
| --- | --- | --- |
| M1 骨架 | Gradle 工程、数据层、JSON 兼容、前台服务与悬浮窗最小可用（开机显示静态课表） | 6–9 |
| M2 渲染 | 四种停靠 × 两种样式、提示、倒计时、进度条、拖动与动画、center 内置编辑器 | 12–18 |
| M3 调度 | 事件驱动状态机、10 分钟隐藏、次日课前 1 小时重现、权限与开机自启 | 6–9 |
| M4 界面 | edit / setclass / system 三页、SAF 导入导出、字体管线 | 12–17 |
| M5 适配 | 4–7 代真机验证、功耗与帧率实测、与 Python 版回归比对 | 5–8 |
| M6 交付 | 签名 APK、使用说明 | 1–2 |

## 5. 工作量估算

| 模块 | 内容 | 人天 |
| --- | --- | --- |
| 工程与数据层 | Gradle、JSON 读写、`migrate_config`、data.json、默认配置 | 2–3 |
| 悬浮层渲染核心 | 4 dock × 2 style × 5 种内容形态、文字测量、换行与缩放 | 8–12 |
| 渲染调度层 | idle / 低频 / 交互三档、脏标记、收敛判定、跳帧节流 | 2–3 |
| 悬浮窗与拖拽 | 多窗口、间距、弹性动画、分区吸附、点按切样式 | 3–4 |
| 事件驱动状态机 | 状态边界排程、提示到期、10min 隐藏、次日 1h 重现、1Hz 时钟 | 5–7 |
| 后台与开机 | 前台服务、BOOT_COMPLETED、悬浮权限、电池优化、通知、看门狗 | 4–6 |
| 主界面 + edit | 课表编辑、节次增删/分割线、时间 CRUD、参数表单、对调页 | 6–9 |
| setclass | 列表、预览、应用配置、下拉导入行 | 2–3 |
| system | 权限三态、开关、字体选择、导入导出、帧率设置 | 4–5.5 |
| SAF | config.json 与班级 json 导入导出 | 1–2 |
| 字体 | 内置圆体、`SystemFonts`、目录遍历与字体名解析 | 2–3 |
| 测试适配 | 4–7 代真机、功耗与帧率、后台存活、功能回归 | 5–8 |
| 打包交付 | 签名、文档 | 1–2 |
| **合计** | 单人全职 | **45–65 人天** |
| **MVP** | 先跑通开机悬浮 + 三页主流程 | **22–28 人天** |

规模对比：Python 侧约 2,900 行（`class_frame.py` 1,757 行为主），Android 侧预计 5,000–7,000 行 Java/XML。

## 6. 已确认决策

| 项 | 结论 |
| --- | --- |
| 应用包名 | `org.bluepowerrobotics.classframe` |
| 显示名 | 灵动课表 |
| 最高帧率档位 | 只提供到 60，不提供 90/120 |
| config.json 导出 | 只需桌面版能兼容读取，不要求逐字节一致（缩进、键顺序可不同） |
| 次日重现 | 不特殊处理"全天无课"的日子，直接按次日第一节开始时间计算 |
| 低性能模式 | 不做，性能由使用者通过帧率设置自主调节 |
| 默认字体 | 内置开源圆体（资源圆体，OFL 授权）替代微软幼圆，并保留"导入字体文件"入口 |
| 分发方式 | 侧载 APK，不上架应用商店 |
| 关闭"启用悬浮窗" | 立即移除悬浮层，停止前台服务并取消次日重现排程；开机自启为 true 时开机广播也不拉起悬浮服务 |

暂无待确认项。

## 7. 目录规划

```
class_frame_android/
├── README.md                     # 本文件：目标、范围、里程碑、工作量
├── docs/
│   ├── FEATURES.md               # 功能规划：全量需求清单
│   ├── DESIGN.md                 # 技术方案：架构与关键实现决策
│   └── ROADMAP.md                # 实施路线图：M1–M6 逐步步骤与验收
├── settings.gradle
├── build.gradle
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/               # 默认 config.json、班级模板
        ├── java/...              # 应用代码
        └── res/                  # 布局、图标、字体资源
```

## 8. 相关文档

- 功能规划：[`docs/FEATURES.md`](docs/FEATURES.md)
- 技术方案：[`docs/DESIGN.md`](docs/DESIGN.md)
- 实施路线图：[`docs/ROADMAP.md`](docs/ROADMAP.md)
- M1 测试指南：[`docs/M1-TESTING.md`](docs/M1-TESTING.md)
- M2 测试指南：[`docs/M2-TESTING.md`](docs/M2-TESTING.md)
- M3 测试指南：[`docs/M3-TESTING.md`](docs/M3-TESTING.md)

## 9. 当前进度

| 里程碑 | 状态 |
| --- | --- |
| M1 骨架与最小悬浮窗 | 已完成，真机验收通过（见 M1 测试指南） |
| M2 渲染与交互 | 已完成，六种形态与交互在真机逐项验收（见 M2 测试指南） |
| M3 调度与生命周期 | 已完成，真机验证通过（见 M3 测试指南） |
| M4 三页界面与字体 | 未开始 |
| M5 真机适配与回归 | 未开始 |
| M6 打包交付 | 未开始 |
