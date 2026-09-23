package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 三层位置（①全局 / ②每日 / ③每课）的落盘规则。
 *
 * 核心判据（与需求逐条对齐）：
 * 1. 拖动/编辑后的形态与操作前的生效值相同 → 什么都不写，无论当天有没有调课记录。
 * 2. 确实变了，而且这一格的内容（课节种类）能由 config 解释：
 *    - 当天没有调课记录 → 写 ③每课；
 *    - 当天有调课记录，但该格内容与 config 一致（记录在这格没有引入差异）
 *      → 仍然写 ③每课；
 *    - 当天有调课记录、该格内容与 config 不同（记录覆盖了这节课）
 *      → 不写 config，只更新 data.json 第 3 项。
 * 3. 写入时只动被触碰的那一格，其余保持 "default"。data.json 的位置行只写解析后的
 *    真实形态（不写 "default"），未触碰的槽位保持空。
 */
public final class PositionStore {

    private PositionStore() {
    }

    // -------------------------------------------------------- 读取

    public static String[] readDailyRow(JSONObject config, int day, int lessons) {
        JSONObject table = config == null ? null : config.optJSONObject("每日日程");
        return Positions.readRow(table == null ? null : table.opt(String.valueOf(day)), lessons);
    }

    public static String[] readPerLessonRow(JSONObject config, int day, int lessons) {
        JSONObject table = config == null ? null : config.optJSONObject("单课日程");
        return Positions.readRow(table == null ? null : table.opt(String.valueOf(day)), lessons);
    }

    public static String[] row(Context context, String tableKey, int day, int lessons) {
        try {
            JSONObject config = ConfigRepository.loadRaw(context);
            JSONObject table = config.optJSONObject(tableKey);
            if (table == null) return new String[lessons];
            String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessons);
            for (int i = 0; i < row.length; i++) {
                if (row[i] == null) row[i] = Positions.DEFAULT;
            }
            return row;
        } catch (Exception e) {
            String[] row = new String[lessons];
            for (int i = 0; i < lessons; i++) row[i] = Positions.DEFAULT;
            return row;
        }
    }

    // -------------------------------------------------------- 写入

    /** 写 ③每课 的某一格。 */
    public static void writePerLesson(Context context, int day, int lesson, String style) {
        writeCell(context, "单课日程", day, lesson, style);
    }

    private static void writeCell(Context context, String tableKey, int day, int lesson,
                                  String style) {
        try {
            JSONObject config = ConfigRepository.loadRaw(context);
            int lessons = lessonsOf(config);
            if (lesson < 0 || lesson >= lessons) return;
            JSONObject table = config.optJSONObject(tableKey);
            if (table == null) {
                table = new JSONObject();
                config.put(tableKey, table);
            }
            String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessons);
            row[lesson] = Positions.isConcrete(style) ? style : Positions.DEFAULT;
            table.put(String.valueOf(day), Positions.writeRow(row, lessons));
            ConfigRepository.save(context, config);
            Logs.i("PositionStore", tableKey + " 第" + day + "天第" + (lesson + 1) + "节 → " + style);
        } catch (Exception e) {
            Logs.e("PositionStore", "写 " + tableKey + " 失败", e);
        }
    }

    /**
     * 按上面的判据决定写哪里。
     *
     * @param dataHasOverride 当天是否存在调课记录且该格内容与 config 不同
     */
    public static void apply(Context context, String date, int day, int lesson,
                             String style, boolean dataHasOverride, int lessons) {
        if (!Positions.isConcrete(style)) return;
        try {
            if (!dataHasOverride) {
                writePerLesson(context, day, lesson, style);
                return;
            }
            // 记录覆盖了这节课：位置只能记在记录上，且写真实形态
            String[] row = DayChangeRepository.positionsForDate(context, date, lessons);
            if (row == null) row = new String[lessons];
            for (int i = 0; i < row.length; i++) {
                if (row[i] == null) row[i] = Positions.DEFAULT;
            }
            row[lesson] = style;
            JSONArray array = new JSONArray();
            for (String cell : row) {
                // data.json 里不写 "default"：空槽位用空字符串表示"没有覆盖"
                array.put(Positions.isConcrete(cell) ? cell : "");
            }
            DayChangeRepository.upsertPositions(context, date, array);
            Logs.i("PositionStore", "data.json " + date + " 第" + (lesson + 1) + "节位置 → " + style);
        } catch (Exception e) {
            Logs.e("PositionStore", "写位置失败", e);
        }
    }

    private static int lessonsOf(JSONObject config) {
        JSONArray starts = config == null ? null : config.optJSONArray("开始时间");
        return starts == null ? 0 : starts.length();
    }
}
