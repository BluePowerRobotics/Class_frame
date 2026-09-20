package org.bluepowerrobotics.classframe.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** 与 Python 版 _num / _flag / _text / minutes_of / fmt_mmss 对应的取值工具。 */
public final class Json {

    private Json() {
    }

    /** 兼容"标量或单元素数组"两种写法，取数组首元素。 */
    public static Object first(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            return array.length() > 0 ? array.opt(0) : null;
        }
        return value;
    }

    public static int num(Object value, int def) {
        Object v = first(value);
        if (v == null || v == JSONObject.NULL) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static double numD(Object value, double def) {
        Object v = first(value);
        if (v == null || v == JSONObject.NULL) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1d : 0d;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean flag(JSONObject cfg, String key, boolean def) {
        if (cfg == null || !cfg.has(key)) return def;
        Object v = first(cfg.opt(key));
        if (v == null || v == JSONObject.NULL) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return num(v, def ? 1 : 0) != 0;
    }

    public static String text(JSONObject cfg, String key, String def) {
        if (cfg == null || !cfg.has(key)) return def;
        Object v = first(cfg.opt(key));
        if (v == null || v == JSONObject.NULL) return def;
        return String.valueOf(v);
    }

    public static int minutesOf(Object value) {
        // 注意：这里不能先用 first() —— [21, 30] 这种时间对本身就是数组
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            if (a.length() >= 2) {
                return num(a.opt(0), 0) * 60 + num(a.opt(1), 0);
            }
            return num(a.opt(0), 0);
        }
        if (value instanceof String && ((String) value).contains(":")) {
            String[] parts = ((String) value).split(":", 2);
            try {
                return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                return 0;
            }
        }
        return num(value, 0);
    }

    public static String fmtMmSs(double seconds) {
        int s = (int) Math.max(0, seconds);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
