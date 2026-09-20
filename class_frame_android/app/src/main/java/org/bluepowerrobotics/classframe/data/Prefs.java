package org.bluepowerrobotics.classframe.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用级开关与设置项，默认值与需求文档一致。 */
public final class Prefs {

    public static final String KEY_BOOT_AUTOSTART = "boot_autostart";
    public static final String KEY_OVERLAY_ENABLED = "overlay_enabled";
    public static final String KEY_FONT_FAMILY = "font_family";
    public static final String KEY_FONT_WEIGHT = "font_weight";
    public static final String KEY_LINE_HEIGHT = "line_height_scale";
    public static final String KEY_MAX_FPS = "max_fps";
    public static final String KEY_INTERACT_HOLD_MS = "interact_hold_ms";
    public static final String KEY_LOGIC_FPS = "logic_fps";

    public static final boolean DEFAULT_BOOT_AUTOSTART = false;
    public static final boolean DEFAULT_OVERLAY_ENABLED = true;
    public static final String DEFAULT_FONT_FAMILY = "__bundled__";
    public static final int DEFAULT_FONT_WEIGHT = 400;
    public static final int DEFAULT_MAX_FPS = 60;
    public static final int DEFAULT_INTERACT_HOLD_MS = 500;
    public static final int DEFAULT_LOGIC_FPS = 60;

    private Prefs() {
    }

    public static SharedPreferences sp(Context context) {
        return context.getSharedPreferences("class_frame", Context.MODE_PRIVATE);
    }

    public static boolean bootAutostart(Context context) {
        return sp(context).getBoolean(KEY_BOOT_AUTOSTART, DEFAULT_BOOT_AUTOSTART);
    }

    public static void setBootAutostart(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_BOOT_AUTOSTART, value).apply();
    }

    public static boolean overlayEnabled(Context context) {
        return sp(context).getBoolean(KEY_OVERLAY_ENABLED, DEFAULT_OVERLAY_ENABLED);
    }

    public static void setOverlayEnabled(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply();
    }

    public static String fontFamily(Context context) {
        return sp(context).getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY);
    }

    public static void setFontFamily(Context context, String value) {
        sp(context).edit().putString(KEY_FONT_FAMILY, value).apply();
    }

    /** 字重（100–900）。可变字体走 wght 轴连续生效，静态字体退化为常规/加粗。 */
    public static int fontWeight(Context context) {
        return sp(context).getInt(KEY_FONT_WEIGHT, DEFAULT_FONT_WEIGHT);
    }

    public static void setFontWeight(Context context, int value) {
        sp(context).edit().putInt(KEY_FONT_WEIGHT, Math.max(100, Math.min(900, value))).apply();
    }

    /** 行高倍数：1.0 为字体自身行高，>1 更疏、<1 更紧。 */
    public static float lineHeightScale(Context context) {
        return sp(context).getFloat(KEY_LINE_HEIGHT, 1.0f);
    }

    public static void setLineHeightScale(Context context, float value) {
        sp(context).edit().putFloat(KEY_LINE_HEIGHT,
                Math.max(0.5f, Math.min(2.5f, value))).apply();
    }

    public static int maxFps(Context context) {
        return sp(context).getInt(KEY_MAX_FPS, DEFAULT_MAX_FPS);
    }

    public static void setMaxFps(Context context, int value) {
        sp(context).edit().putInt(KEY_MAX_FPS, Math.max(1, Math.min(60, value))).apply();
    }

    public static int interactHoldMs(Context context) {
        return sp(context).getInt(KEY_INTERACT_HOLD_MS, DEFAULT_INTERACT_HOLD_MS);
    }

    public static void setInteractHoldMs(Context context, int value) {
        sp(context).edit().putInt(KEY_INTERACT_HOLD_MS, Math.max(0, value)).apply();
    }

    public static int logicFps(Context context) {
        return sp(context).getInt(KEY_LOGIC_FPS, DEFAULT_LOGIC_FPS);
    }

    public static void setLogicFps(Context context, int value) {
        sp(context).edit().putInt(KEY_LOGIC_FPS, Math.max(1, Math.min(60, value))).apply();
    }
}
