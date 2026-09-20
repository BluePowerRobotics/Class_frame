package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import org.json.JSONArray;
import java.io.File;

/** data.json：按日期覆盖课表，格式为 [[日期, tokens], ...]。 */
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

    public static void save(Context context, JSONArray records) throws Exception {
        ConfigRepository.writeUtf8Atomic(ConfigRepository.dataFile(context), records.toString(2));
    }

    public static void upsert(Context context, String date, JSONArray tokens) throws Exception {
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
        result.put(record);
        save(context, result);
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
