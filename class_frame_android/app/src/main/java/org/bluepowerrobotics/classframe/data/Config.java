package org.bluepowerrobotics.classframe.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config.json 的内存模型。字段含义与 Python 版 class_frame.py 的 calendar.load_class 保持一致，
 * migrate() 对应 Python 的 migrate_config()。
 */
public final class Config {

    public static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    public static final String[] DOCKS = {"left", "upper", "right", "center"};

    public JSONObject raw;

    public final Map<String, List<String>> schedule = new LinkedHashMap<>();
    public final List<String> options = new ArrayList<>();
    public final List<Integer> starts = new ArrayList<>();
    public final List<Integer> ends = new ArrayList<>();

    public String startPrompt = "准备上课";
    public String endPrompt = "下课时间";
    public boolean showCountdownAfterClass = true;
    public boolean showProgressOnClass = true;
    public int textSize = 40;
    public int verticalTextSize = 28;
    public int progressWidth = 8;
    public String onDefaultDock = "upper";
    public String offDefaultDock = "upper";
    public boolean onDefaultSecondStyle = false;
    public boolean offDefaultSecondStyle = false;
    public int onPromptDuration = 6;
    public int offPromptDuration = 6;
    public int timeOffsetSeconds = 0;

    public final Map<String, Boolean> dragDefaults = new HashMap<>();
    public final Map<String, Float> scale = new HashMap<>();

    public static Config parse(JSONObject source) throws JSONException {
        if (source == null) throw new IllegalArgumentException("config.json 内容为空");
        JSONObject cfg = migrate(source);

        Config c = new Config();
        c.raw = cfg;

        JSONObject sch = cfg.optJSONObject("日程表");
        if (sch == null) throw new IllegalArgumentException("配置缺少：日程表");
        for (int i = 1; i <= 7; i++) {
            c.schedule.put(String.valueOf(i), stringList(sch.optJSONArray(String.valueOf(i))));
        }

        c.options.addAll(stringList(cfg.optJSONArray("更换选项")));

        JSONArray starts = cfg.optJSONArray("开始时间");
        if (starts != null) {
            for (int i = 0; i < starts.length(); i++) c.starts.add(Json.minutesOf(starts.opt(i)));
        }
        JSONArray ends = cfg.optJSONArray("结束时间");
        if (ends != null) {
            for (int i = 0; i < ends.length(); i++) c.ends.add(Json.minutesOf(ends.opt(i)));
        }

        c.startPrompt = Json.text(cfg, "开始提示", "准备上课");
        c.endPrompt = Json.text(cfg, "结束提示", "下课时间");
        c.showCountdownAfterClass = Json.flag(cfg, "下课显示倒计时", true);
        c.showProgressOnClass = Json.flag(cfg, "上课显示倒计条", true);
        c.textSize = Json.num(cfg.opt("文字大小"), 40);
        c.verticalTextSize = Json.num(cfg.opt("竖直显示的文字大小"), 28);
        c.progressWidth = Math.max(1, Json.num(cfg.opt("进度条宽度"), 8));
        c.onDefaultDock = normalizeDock(Json.text(cfg, "上课默认位置", "upper"));
        c.offDefaultDock = normalizeDock(Json.text(cfg, "下课默认位置", "upper"));
        c.onDefaultSecondStyle = Json.flag(cfg, "上课默认使用secondStyle", false);
        c.offDefaultSecondStyle = Json.flag(cfg, "下课默认使用secondStyle", false);
        c.onPromptDuration = Math.max(1, Json.num(cfg.opt("上课提示时长"), 6));
        c.offPromptDuration = Math.max(1, Json.num(cfg.opt("下课提示时长"), 6));
        c.timeOffsetSeconds = Json.num(cfg.opt("时间偏移（秒）"), 0);

        JSONObject dragDefaults = cfg.optJSONObject("拖动默认样式");
        for (String dock : DOCKS) {
            c.dragDefaults.put(dock, dragDefaults != null && dragDefaults.optBoolean(dock, false));
        }

        for (String key : new String[]{
                "left上课缩放", "left下课缩放", "upper上课缩放", "upper下课缩放", "center缩放"}) {
            c.scale.put(key, (float) Json.numD(cfg.opt(key), 1.0));
        }
        return c;
    }

    /** 对应 Python 的 migrate_config()：兼容旧位置写法、全局 secondStyle 与旧缩放字段。 */
    public static JSONObject migrate(JSONObject cfg) throws JSONException {
        boolean legacyUpper = false;
        for (String key : new String[]{"上课默认位置", "下课默认位置"}) {
            if (cfg.has(key)) {
                String old = Json.text(cfg, key, "upper");
                if ("u".equals(old)) legacyUpper = true;
                cfg.put(key, new JSONArray().put(positionMap(old)));
            }
        }

        boolean oldGlobalStyle;
        Object oldStyle = cfg.opt("secondStyle");
        if (oldStyle == null) {
            oldGlobalStyle = legacyUpper;
        } else if (oldStyle instanceof Boolean) {
            oldGlobalStyle = (Boolean) oldStyle;
        } else {
            Object first = Json.first(oldStyle);
            oldGlobalStyle = first != null && first != JSONObject.NULL
                    && (first instanceof Boolean ? (Boolean) first : Json.num(first, 0) != 0);
        }
        cfg.remove("secondStyle");
        if (!cfg.has("上课默认使用secondStyle")) cfg.put("上课默认使用secondStyle", oldGlobalStyle);
        if (!cfg.has("下课默认使用secondStyle")) cfg.put("下课默认使用secondStyle", oldGlobalStyle);

        if (!cfg.has("上课提示时长")) cfg.put("上课提示时长", new JSONArray().put(6));
        if (!cfg.has("下课提示时长")) cfg.put("下课提示时长", new JSONArray().put(6));
        if (!cfg.has("时间偏移（秒）")) cfg.put("时间偏移（秒）", new JSONArray().put(0));

        JSONObject styleDefaults = cfg.optJSONObject("拖动默认样式");
        if (styleDefaults == null) {
            styleDefaults = new JSONObject();
            cfg.put("拖动默认样式", styleDefaults);
        }
        for (String dock : DOCKS) {
            if (!styleDefaults.has(dock)) {
                styleDefaults.put(dock, "upper".equals(dock) && legacyUpper);
            }
        }

        double legacyScale = 1.0;
        if (cfg.has("上课放大倍率")) {
            legacyScale = Json.numD(cfg.opt("上课放大倍率"), 1.0);
        }
        if (!cfg.has("left上课缩放")) cfg.put("left上课缩放", new JSONArray().put(legacyScale));
        if (!cfg.has("left下课缩放")) cfg.put("left下课缩放", new JSONArray().put(1.0));
        if (!cfg.has("upper上课缩放")) cfg.put("upper上课缩放", new JSONArray().put(legacyScale));
        if (!cfg.has("upper下课缩放")) cfg.put("upper下课缩放", new JSONArray().put(1.0));
        if (!cfg.has("center缩放")) cfg.put("center缩放", new JSONArray().put(1.0));
        if (!cfg.has("进度条宽度")) cfg.put("进度条宽度", new JSONArray().put(8));
        return cfg;
    }

    public static String positionMap(String old) {
        if (old == null) return "upper";
        switch (old) {
            case "a":
                return "left";
            case "b":
                return "upper";
            case "c":
                return "right";
            case "f":
                return "center";
            case "u":
                return "upper";
            default:
                return normalizeDock(old);
        }
    }

    public static String normalizeDock(String dock) {
        for (String d : DOCKS) {
            if (d.equals(dock)) return d;
        }
        return "upper";
    }

    public static boolean isSpecialToken(String token) {
        if (token == null) return true;
        if ("周".equals(token) || "|".equals(token)) return true;
        for (String w : WEEK_NAMES) {
            if (w.equals(token)) return true;
        }
        return false;
    }

    /** 当天完整 token：["周", 星期, "|"] + 当天课表。 */
    public List<String> tokensForDay(int isoWeekday) {
        List<String> day = schedule.get(String.valueOf(isoWeekday));
        List<String> tokens = new ArrayList<>();
        tokens.add("周");
        tokens.add(WEEK_NAMES[Math.max(0, Math.min(6, isoWeekday - 1))]);
        tokens.add("|");
        if (day != null) tokens.addAll(day);
        return tokens;
    }

    /**
     * 第几个课节 -> tokens 下标；特殊 token 不计数。
     * 返回数组长度即课节总数。
     */
    public static int[] lessonPositionMap(List<String> tokens) {
        int[] map = new int[tokens.size()];
        int count = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (isSpecialToken(tokens.get(i))) continue;
            map[count++] = i;
        }
        int[] result = new int[count];
        System.arraycopy(map, 0, result, 0, count);
        return result;
    }

    private static List<String> stringList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            Object v = array.opt(i);
            list.add(v == null || v == JSONObject.NULL ? "" : String.valueOf(v));
        }
        return list;
    }
}
