"""单课默认位置的三层体系：①全局 → ②每日 → ③每课。

值域：8 个具体形态 + 一个"跟随上级"。

| 界面 | 内部值 | dock | secondStyle |
| --- | --- | --- | --- |
| 左表 | left_table | left | False |
| 左条 | left_bar | left | True |
| 上表 | upper_table | upper | False |
| 上条 | upper_bar | upper | True |
| 右表 | right_table | right | False |
| 右条 | right_bar | right | True |
| 时钟 | time | center | False（居中·时钟） |
| 编辑 | edit | center | True（居中·编辑器） |
| 默认 | default | — | 回退上一级 |

兼容：读取时把 None、空字符串、缺项、无法识别的值都当成 "default"。
与 Android 版 data/Positions.java 一一对应，改动需同步。
"""

DEFAULT = "default"

LEFT_TABLE = "left_table"
LEFT_BAR = "left_bar"
UPPER_TABLE = "upper_table"
UPPER_BAR = "upper_bar"
RIGHT_TABLE = "right_table"
RIGHT_BAR = "right_bar"
TIME = "time"
EDIT = "edit"

# ①全局可选的 8 项（无"默认"）
STYLES = [
    LEFT_TABLE, LEFT_BAR, UPPER_TABLE, UPPER_BAR,
    RIGHT_TABLE, RIGHT_BAR, TIME, EDIT,
]
STYLE_LABELS = ["左表", "左条", "上表", "上条", "右表", "右条", "时钟", "编辑"]

# ②每日 / ③每课 允许额外选择"默认"
STYLES_WITH_DEFAULT = STYLES + [DEFAULT]
STYLE_LABELS_WITH_DEFAULT = STYLE_LABELS + ["默认"]

WEEK_NAMES = ["一", "二", "三", "四", "五", "六", "日"]


def _first(value):
    """兼容"标量或数组"两种写法，取数组首元素。"""
    if isinstance(value, (list, tuple)):
        return value[0] if value else None
    return value


def normalize(value):
    """归一化：不可识别、空、None 一律返回 DEFAULT。"""
    value = _first(value)
    if value is None:
        return DEFAULT
    text = str(value).strip()
    if not text:
        return DEFAULT
    lowered = text.lower()
    for style in STYLES:
        if style.lower() == lowered:
            return style
    # 兼容中文标签直接写进 JSON 的情况
    for i, label in enumerate(STYLE_LABELS):
        if label == text:
            return STYLES[i]
    if text == "默认" or lowered == DEFAULT:
        return DEFAULT
    return DEFAULT


def is_concrete(value):
    return value is not None and value != DEFAULT


def dock_of(style):
    if style in (LEFT_TABLE, LEFT_BAR):
        return "left"
    if style in (RIGHT_TABLE, RIGHT_BAR):
        return "right"
    if style in (TIME, EDIT):
        return "center"
    return "upper"


def second_style_of(style):
    return style in (LEFT_BAR, UPPER_BAR, RIGHT_BAR, EDIT)


def of(dock, second_style):
    """由 dock + secondStyle 反推形态值。"""
    if dock == "center":
        return EDIT if second_style else TIME
    if dock == "left":
        return LEFT_BAR if second_style else LEFT_TABLE
    if dock == "right":
        return RIGHT_BAR if second_style else RIGHT_TABLE
    return UPPER_BAR if second_style else UPPER_TABLE


def from_legacy(dock, second_style):
    """旧键（默认位置 + 使用secondStyle）转成形态值。"""
    return of(dock or "upper", bool(second_style))


def label_of(style):
    for i, value in enumerate(STYLES_WITH_DEFAULT):
        if value == style:
            return STYLE_LABELS_WITH_DEFAULT[i]
    return "默认"


def from_label(label):
    text = str(label or "").strip()
    for i, value in enumerate(STYLE_LABELS_WITH_DEFAULT):
        if value == text:
            return STYLES_WITH_DEFAULT[i]
    return None


# ---------------------------------------------------------------- 表

def read_row(source, lessons):
    """读一行（某天或某课的 N 个槽位）。

    source 可以是 list，也可以是 {"1": "upper_bar", "2": "default"} 这种 dict。
    """
    lessons = max(0, int(lessons))
    row = [DEFAULT] * lessons
    if source is None:
        return row
    if isinstance(source, dict):
        for i in range(lessons):
            if str(i + 1) in source:
                row[i] = normalize(source[str(i + 1)])
            elif str(i) in source:
                row[i] = normalize(source[str(i)])
        return row
    if isinstance(source, str):
        source = source.replace("，", ",").split(",")
    if isinstance(source, (list, tuple)):
        for i in range(min(lessons, len(source))):
            row[i] = normalize(source[i])
    return row


def write_row(row, lessons):
    """写成 list，长度为 lessons。"""
    lessons = max(0, int(lessons))
    return [
        row[i] if row and i < len(row) and is_concrete(row[i]) else DEFAULT
        for i in range(lessons)
    ]


def read_table(source, lessons):
    """读"周几 → 行"的表，返回 7 行（索引 0 = 周一）。"""
    table = []
    for day in range(1, 8):
        item = None
        if isinstance(source, dict):
            item = source.get(str(day), source.get(str(day - 1)))
        table.append(read_row(item, lessons))
    return table


def write_table(table, lessons):
    """写"周几 → 行"的表，永远写满 7 天。"""
    result = {}
    for day in range(1, 8):
        row = table[day - 1] if table and day - 1 < len(table) else None
        result[str(day)] = write_row(row, lessons)
    return result


def read_daily(source, lessons):
    """读②每日：它只有一行（各星期共用），长度为课节数。

    兼容早期的 7×N 写法：七行内容一致就压成一行；不一致时取周一，
    差异内容应挪到③每课。
    """
    if isinstance(source, dict):
        first = read_row(source.get("1"), lessons)
        for day in range(2, 8):
            other = read_row(source.get(str(day)), lessons)
            if first != other:
                return first
        return first
    return read_row(source, lessons)


def write_daily(row, lessons):
    """写②每日：一行，长度为课节数。"""
    return write_row(row, lessons)


def lookup(table, day, lesson):
    if not table or day < 1 or day > len(table):
        return DEFAULT
    row = table[day - 1]
    if not row or lesson < 0 or lesson >= len(row):
        return DEFAULT
    return row[lesson]


def resolve(data_row, per_lesson, daily, lesson, day, after_class, global_on, global_off):
    """解析链：data 第3项 → ③每课 → ②每日 → ①全局。"""
    if data_row and 0 <= lesson < len(data_row) and is_concrete(data_row[lesson]):
        return data_row[lesson]
    from_per_lesson = lookup(per_lesson, day, lesson)
    if is_concrete(from_per_lesson):
        return from_per_lesson
    from_daily = daily[lesson] if daily and 0 <= lesson < len(daily) else DEFAULT
    if is_concrete(from_daily):
        return from_daily
    return global_off if after_class else global_on


# ---------------------------------------------------------------- 迁移

def migrate(cfg, docks=("left", "upper", "right", "center")):
    """为老配置补上三层位置表，并清掉已废弃的"拖入区域默认样式"。

    ①全局由旧的「上课/下课默认位置 + 使用secondStyle」迁移；
    ②每日/③每课缺失时补成"全默认"，读旧配置的行为与升级前一致。
    返回是否改动了配置（调用方可据此决定要不要写回文件）。
    """
    changed = False

    def _dock_of(key):
        value = cfg.get(key, "upper")
        if isinstance(value, (list, tuple)):
            value = value[0] if value else "upper"
        mapping = {"a": "left", "b": "upper", "c": "right", "f": "center", "u": "upper"}
        return mapping.get(str(value), str(value))

    def _flag_of(key):
        value = cfg.get(key, False)
        if isinstance(value, (list, tuple)):
            value = value[0] if value else False
        return bool(value)

    if "全局日程" not in cfg:
        cfg["全局日程"] = {
            "上课": from_legacy(_dock_of("上课默认位置"),
                              _flag_of("上课默认使用secondStyle")),
            "下课": from_legacy(_dock_of("下课默认位置"),
                              _flag_of("下课默认使用secondStyle")),
        }
        changed = True

    if "拖动默认样式" in cfg:
        cfg.pop("拖动默认样式", None)
        changed = True
    for dock in docks:
        key = "拖入%s时使用secondStyle" % dock
        if key in cfg:
            cfg.pop(key, None)
            changed = True

    lessons = len(cfg.get("开始时间") or [])
    # ②每日：各星期共用的一行（早期 7×N 写法在这里被压缩）
    if "每日日程" not in cfg:
        cfg["每日日程"] = [DEFAULT] * lessons
        changed = True
    else:
        fitted = write_daily(read_daily(cfg["每日日程"], lessons), lessons)
        if fitted != cfg["每日日程"]:
            cfg["每日日程"] = fitted
            changed = True
    # ③每课：7 天 × 课节数
    if "单课日程" not in cfg:
        cfg["单课日程"] = write_table([[DEFAULT] * lessons for _ in range(7)], lessons)
        changed = True
    else:
        fitted = write_table(read_table(cfg["单课日程"], lessons), lessons)
        if fitted != cfg["单课日程"]:
            cfg["单课日程"] = fitted
            changed = True
    return changed
