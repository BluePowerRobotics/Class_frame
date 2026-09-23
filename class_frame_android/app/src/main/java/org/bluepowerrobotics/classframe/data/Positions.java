package org.bluepowerrobotics.classframe.data;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 单课默认位置的三层体系：①全局 → ②每日 → ③每课。
 *
 * 值域：8 个具体形态 + 一个"跟随上级"。
 *
 * | 界面 | 内部值 | dock | secondStyle |
 * | --- | --- | --- | --- |
 * | 左表 | left_table | left | false |
 * | 左条 | left_bar | left | true |
 * | 上表 | upper_table | upper | false |
 * | 上条 | upper_bar | upper | true |
 * | 右表 | right_table | right | false |
 * | 右条 | right_bar | right | true |
 * | 时钟 | time | center | false（居中·时钟） |
 * | 编辑 | edit | center | true（居中·编辑器） |
 * | 默认 | default | — | 回退上一级 |
 *
 * 兼容：读取时把 null、空字符串、缺项、无法识别的值都当成 {@link #DEFAULT}。
 */
public final class Positions {

    /** "跟随上级"。只有②每日与③每课允许出现，①全局与 data.json 不写它。 */
    public static final String DEFAULT = "default";

    public static final String LEFT_TABLE = "left_table";
    public static final String LEFT_BAR = "left_bar";
    public static final String UPPER_TABLE = "upper_table";
    public static final String UPPER_BAR = "upper_bar";
    public static final String RIGHT_TABLE = "right_table";
    public static final String RIGHT_BAR = "right_bar";
    public static final String TIME = "time";
    public static final String EDIT = "edit";

    /** ①全局可选的 8 项（无"默认"）。 */
    public static final String[] STYLES = {
            LEFT_TABLE, LEFT_BAR, UPPER_TABLE, UPPER_BAR,
            RIGHT_TABLE, RIGHT_BAR, TIME, EDIT,
    };

    /** 界面上①②③统一显示的中文标签，顺序与 {@link #STYLES} 一致。 */
    public static final String[] STYLE_LABELS = {
            "左表", "左条", "上表", "上条", "右表", "右条", "时钟", "编辑",
    };

    /** ②③允许额外选择的一项。 */
    public static final String[] STYLES_WITH_DEFAULT =
            {LEFT_TABLE, LEFT_BAR, UPPER_TABLE, UPPER_BAR,
                    RIGHT_TABLE, RIGHT_BAR, TIME, EDIT, DEFAULT};

    public static final String[] STYLE_LABELS_WITH_DEFAULT = {
            "左表", "左条", "上表", "上条", "右表", "右条", "时钟", "编辑", "默认",
    };

    private Positions() {
    }

    /** 归一化：不可识别、空、null 一律返回 {@link #DEFAULT}。 */
    public static String normalize(Object value) {
        Object first = Json.first(value);
        if (first == null || first == JSONObject.NULL) return DEFAULT;
        String text = String.valueOf(first).trim();
        if (text.isEmpty()) return DEFAULT;
        for (String style : STYLES) {
            if (style.equalsIgnoreCase(text)) return style;
        }
        // 兼容中文标签直接写进 JSON 的情况
        for (int i = 0; i < STYLE_LABELS.length; i++) {
            if (STYLE_LABELS[i].equals(text)) return STYLES[i];
        }
        if ("默认".equals(text) || DEFAULT.equalsIgnoreCase(text)) return DEFAULT;
        return DEFAULT;
    }

    public static boolean isConcrete(String value) {
        return value != null && !DEFAULT.equals(value);
    }

    /**
     * 拆成实际的 dock / secondStyle。
     * 传入值必须是具体形态（{@link #DEFAULT} 请在解析链里解决掉）。
     */
    public static String dockOf(String value) {
        if (LEFT_TABLE.equals(value) || LEFT_BAR.equals(value)) return "left";
        if (RIGHT_TABLE.equals(value) || RIGHT_BAR.equals(value)) return "right";
        if (TIME.equals(value) || EDIT.equals(value)) return "center";
        return "upper";
    }

    public static boolean secondStyleOf(String value) {
        return LEFT_BAR.equals(value) || UPPER_BAR.equals(value)
                || RIGHT_BAR.equals(value) || EDIT.equals(value);
    }

    /** 由 dock + secondStyle 反推形态值。 */
    public static String of(String dock, boolean secondStyle) {
        if ("center".equals(dock)) return secondStyle ? EDIT : TIME;
        if ("left".equals(dock)) return secondStyle ? LEFT_BAR : LEFT_TABLE;
        if ("right".equals(dock)) return secondStyle ? RIGHT_BAR : RIGHT_TABLE;
        return secondStyle ? UPPER_BAR : UPPER_TABLE;
    }

    /** 中文标签 → 内部值，无法识别返回 null。 */
    public static String fromLabel(String label) {
        if (label == null) return null;
        String text = label.trim();
        for (int i = 0; i < STYLE_LABELS_WITH_DEFAULT.length; i++) {
            if (STYLE_LABELS_WITH_DEFAULT[i].equals(text)) return STYLES_WITH_DEFAULT[i];
        }
        return null;
    }

    /** 内部值 → 中文标签。 */
    public static String labelOf(String value) {
        for (int i = 0; i < STYLES_WITH_DEFAULT.length; i++) {
            if (STYLES_WITH_DEFAULT[i].equals(value)) return STYLE_LABELS_WITH_DEFAULT[i];
        }
        return "默认";
    }

    /** 旧键（上课默认位置 + 使用secondStyle）转成形态值，用于老配置迁移到①全局。 */
    public static String fromLegacy(String dock, boolean secondStyle) {
        return of(dock == null || dock.isEmpty() ? "upper" : dock, secondStyle);
    }

    // -------------------------------------------------------- 表读写

    /**
     * 读一行（某天或某课的 N 个槽位）。
     * 来源可以是 JSONArray，也可以是 {"1":"upper_bar","2":"default"} 这种对象。
     */
    public static String[] readRow(Object source, int lessons) {
        String[] row = new String[Math.max(0, lessons)];
        for (int i = 0; i < row.length; i++) row[i] = DEFAULT;
        if (source == null || source == JSONObject.NULL) return row;

        if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < row.length && i < array.length(); i++) {
                row[i] = normalize(array.opt(i));
            }
            return row;
        }
        if (source instanceof JSONObject) {
            JSONObject object = (JSONObject) source;
            for (int i = 0; i < row.length; i++) {
                if (object.has(String.valueOf(i + 1))) {
                    row[i] = normalize(object.opt(String.valueOf(i + 1)));
                } else if (object.has(String.valueOf(i))) {
                    row[i] = normalize(object.opt(String.valueOf(i)));
                }
            }
            return row;
        }
        if (source instanceof String) {
            // 整个表被写成一个字符串（手改配置时容易发生）：按逗号拆
            String[] parts = String.valueOf(source).split("[,，]");
            for (int i = 0; i < row.length && i < parts.length; i++) {
                row[i] = normalize(parts[i]);
            }
        }
        return row;
    }

    /** 写成 JSONArray；长度固定为 lessons。 */
    public static JSONArray writeRow(String[] row, int lessons) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < lessons; i++) {
            array.put(row != null && i < row.length && isConcrete(row[i]) ? row[i] : DEFAULT);
        }
        return array;
    }

    /** 课节数变化时把旧行调整为新长度。 */
    public static String[] fit(String[] row, int lessons) {
        String[] result = new String[Math.max(0, lessons)];
        for (int i = 0; i < result.length; i++) {
            result[i] = row != null && i < row.length ? row[i] : DEFAULT;
        }
        return result;
    }

    /** 读"周几 → 行"的表。 */
    public static String[][] readTable(Object source, int lessons) {
        String[][] table = new String[7][];
        JSONObject object = source instanceof JSONObject ? (JSONObject) source : null;
        for (int day = 1; day <= 7; day++) {
            Object row = null;
            if (object != null) {
                row = object.opt(String.valueOf(day));
                if (row == null) row = object.opt(String.valueOf(day - 1));
            }
            table[day - 1] = readRow(row, lessons);
        }
        return table;
    }

    /** 写"周几 → 行"的表，永远写满 7 天。 */
    public static JSONObject writeTable(String[][] table, int lessons) {
        JSONObject object = new JSONObject();
        try {
            for (int day = 1; day <= 7; day++) {
                String[] row = table != null && day - 1 < table.length ? table[day - 1] : null;
                object.put(String.valueOf(day), writeRow(row, lessons));
            }
        } catch (Exception ignored) {
        }
        return object;
    }

    /**
     * 解析链：data 第3项 → ③每课 → ②每日 → ①全局。
     *
     * @param dataRow  该日期 data 记录里的位置行，可为 null（旧数据没有这一项）
     * @param lesson   0 起的课节下标
     * @param day       1–7 的 ISO 周几
     * @param afterClass 当前是课间/放学（true）还是上课中（false）
     * @param globalOn / globalOff ①全局的上课、下课形态
     */
    public static String resolve(String[] dataRow, String[][] perLesson, String[][] daily,
                                 int lesson, int day, boolean afterClass,
                                 String globalOn, String globalOff) {
        if (dataRow != null && lesson >= 0 && lesson < dataRow.length
                && isConcrete(dataRow[lesson])) {
            return dataRow[lesson];
        }
        String fromPerLesson = lookup(perLesson, day, lesson);
        if (isConcrete(fromPerLesson)) return fromPerLesson;
        String fromDaily = lookup(daily, day, lesson);
        if (isConcrete(fromDaily)) return fromDaily;
        return afterClass ? globalOff : globalOn;
    }

    private static String lookup(String[][] table, int day, int lesson) {
        if (table == null || day < 1 || day > table.length) return DEFAULT;
        String[] row = table[day - 1];
        if (row == null || lesson < 0 || lesson >= row.length) return DEFAULT;
        return row[lesson];
    }
}
