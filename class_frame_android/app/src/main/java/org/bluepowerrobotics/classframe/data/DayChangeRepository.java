package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/**
 * data.json：按日期覆盖课表与位置。
 *
 * 记录格式：
 * <pre>
 * ["2026-09-23", ["周","三","|","英", ...], ["upper_bar","upper_bar","left_table", ""]]
 * </pre>
 * 第 3 项是可选的，与第 2 项的课节一一对应；空字符串表示该槽位没有覆盖，
 * 解析时回退到 config 的③每课 → ②每日 → ①全局。
 *
 * 旧数据只有前两项，读取时按"第 3 项整体不存在"处理，不会进入③每课。
 */
public final class DayChangeRepository {

    private DayChangeRepository() {
    }

    public static JSONArray load(Context context) {
        File file = ConfigRepository.dataFile(context);
        if (!file.exists()) return new JSONArray();
        try {
            JSONArray array = new JSONArray(ConfigRepository.readUtf8(file));
            return array;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /**
     * 与 Python 一致：统计 tokens 中"非 | 的项"，数量不等于"课节数 + 2"的旧记录直接忽略。
     * （+2 指 "周" 与星期两个 token）
     */
    public static JSONArray loadValid(Context context, int expectedNonSeparatorCount) {
        JSONArray source = load(context);
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONArray record = source.optJSONArray(i);
            if (record == null || record.length() < 2) continue;
            JSONArray tokens = record.optJSONArray(1);
            if (tokens == null || countNonSeparators(tokens) != expectedNonSeparatorCount) continue;
            result.put(record);
        }
        return result;
    }

    private static int countNonSeparators(JSONArray tokens) {
        int count = 0;
        for (int i = 0; i < tokens.length(); i++) {
            Object value = tokens.opt(i);
            if (value != null && !"|".equals(String.valueOf(value))) count++;
        }
        return count;
    }

    public static JSONArray tokensForDate(Context context, String date, int expectedTokenCount) {
        JSONArray records = loadValid(context, expectedTokenCount);
        for (int i = 0; i < records.length(); i++) {
            JSONArray record = records.optJSONArray(i);
            if (record == null) continue;
            Object day = record.opt(0);
            if (day != null && date.equals(String.valueOf(day))) {
                JSONArray tokens = record.optJSONArray(1);
                if (tokens != null) return tokens;
            }
        }
        return null;
    }

    /** 该日期记录里的位置行；没有记录或没有第 3 项时返回 null。 */
    public static String[] positionsForDate(Context context, String date, int lessons) {
        JSONArray records = load(context);
        for (int i = 0; i < records.length(); i++) {
            JSONArray record = records.optJSONArray(i);
            if (record == null || record.length() < 3) continue;
            Object day = record.opt(0);
            if (day == null || !date.equals(String.valueOf(day))) continue;
            String[] row = new String[Math.max(0, lessons)];
            JSONArray array = record.optJSONArray(2);
            for (int k = 0; k < row.length; k++) {
                row[k] = array != null ? normalizeCell(array.opt(k)) : Positions.DEFAULT;
            }
            return row;
        }
        return null;
    }

    /** data.json 的位置槽位：空字符串 / null / "default" 都表示"没有覆盖"。 */
    private static String normalizeCell(Object value) {
        if (value == null || value == JSONObject.NULL) return Positions.DEFAULT;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return Positions.DEFAULT;
        return Positions.normalize(text);
    }

    /** 读取记录的第 3 项原始值（可能是 null），供"原样保留"使用。 */
    public static Object rawPositionsForDate(Context context, String date) {
        JSONArray records = load(context);
        for (int i = 0; i < records.length(); i++) {
            JSONArray record = records.optJSONArray(i);
            if (record == null || record.length() < 3) continue;
            Object day = record.opt(0);
            if (day != null && date.equals(String.valueOf(day))) return record.opt(2);
        }
        return null;
    }

    public static void save(Context context, JSONArray records) throws Exception {
        ConfigRepository.writeUtf8Atomic(ConfigRepository.dataFile(context), records.toString(2));
    }

    /** 写入/覆盖某日记录，同时带入位置行（positions 为 null 时只写前两项）。 */
    public static void upsert(Context context, String date, JSONArray tokens, JSONArray positions)
            throws Exception {
        JSONArray source = load(context);
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONArray record = source.optJSONArray(i);
            if (record == null || record.length() < 1) continue;
            Object day = record.opt(0);
            if (day != null && date.equals(String.valueOf(day))) continue;
            result.put(record);
        }
        JSONArray record = new JSONArray();
        record.put(date);
        record.put(tokens);
        if (positions != null) record.put(positions);
        result.put(record);
        save(context, result);
    }

    /** 只更新某日的位置行，保留课表 tokens。 */
    public static void upsertPositions(Context context, String date, JSONArray positions)
            throws Exception {
        String[] empty = new String[0];
        JSONArray source = load(context);
        JSONArray result = new JSONArray();
        JSONArray tokens = null;
        for (int i = 0; i < source.length(); i++) {
            JSONArray record = source.optJSONArray(i);
            if (record == null || record.length() < 1) continue;
            Object day = record.opt(0);
            if (day != null && date.equals(String.valueOf(day))) {
                tokens = record.optJSONArray(1);
                continue;
            }
            result.put(record);
        }
        if (tokens == null) {
            // 该日还没有覆盖记录，但用户只改了位置：此时位置本来就该写进 config，
            // 走到这里说明调用方判定失误，直接放弃以免写出半条记录。
            throw new IllegalStateException("该日期没有调课记录，位置应写入 config 而非 data.json");
        }
        JSONArray record = new JSONArray();
        record.put(date);
        record.put(tokens);
        record.put(positions);
        result.put(record);
        save(context, result);
    }

    /** 兼容旧调用：不带位置行。 */
    public static void upsert(Context context, String date, JSONArray tokens) throws Exception {
        upsert(context, date, tokens, null);
    }

    public static void remove(Context context, String date) throws Exception {
        JSONArray source = load(context);
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONArray record = source.optJSONArray(i);
            if (record == null || record.length() < 1) continue;
            Object day = record.opt(0);
            if (day != null && date.equals(String.valueOf(day))) continue;
            result.put(record);
        }
        save(context, result);
    }

}
