package org.bluepowerrobotics.classframe.data;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字体来源：内置（默认）+ 用户导入 + 系统字体。
 *
 * 系统字体枚举：API 29+ 用公开 API SystemFonts.getAvailableFonts()；
 * 更低版本没有公开 API，先用精选 family 白名单兜底（系统字体目录遍历与
 * name 表解析是后续可选的加强项）。
 */
public final class FontRepository {

    /** 默认项：内置字体（当前等价于系统默认，后续替换为捆绑的资源圆体）。 */
    public static final String DEFAULT = "内置字体";

    private static final String[] CURATED = {
            "sans-serif", "sans-serif-light", "sans-serif-medium", "sans-serif-condensed",
            "serif", "monospace", "casual", "cursive", "sans-serif-smallcaps",
    };

    /** 系统字体文件枚举较慢（部分 ROM 上会阻塞数秒），改为后台预加载并缓存，避免在 UI 线程调用。 */
    private static volatile List<File> sSystemFontFiles;
    private static volatile boolean sLoading;

    public static void preloadSystemFonts() {
        if (sSystemFontFiles != null || sLoading) return;
        sLoading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<File> files = enumerateSystemFontFiles();
                sSystemFontFiles = files;
                sLoading = false;
            }
        }, "font-preload").start();
    }

    private FontRepository() {
    }

    public static File fontDir(Context context) {
        File dir = new File(context.getFilesDir(), "fonts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static List<String> listFonts(Context context) {
        Set<String> names = new LinkedHashSet<>();
        names.add(DEFAULT);
        Collections.addAll(names, importedNames(context));
        // 公共 family 名（可用 Typeface.create 直接取用）
        Collections.addAll(names, CURATED);
        // 系统字体文件（SystemFonts 只公开 getFile()，没有 getFamilyName()，
        // 因此按文件名列出并用 createFromFile 加载）
        for (File file : systemFontFiles()) {
            names.add(fileNameWithoutExt(file));
        }
        List<String> list = new ArrayList<>(names);
        Collections.sort(list);
        list.remove(DEFAULT);
        list.add(0, DEFAULT);
        return list;
    }

    public static String[] importedNames(Context context) {
        File[] files = fontDir(context).listFiles();
        if (files == null) return new String[0];
        List<String> names = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            names.add(name);
        }
        return names.toArray(new String[0]);
    }

    /** 系统字体文件：API 29+ 用 SystemFonts.getAvailableFonts()，否则遍历 /system/fonts。 */
    public static List<File> systemFontFiles() {
        if (sSystemFontFiles != null) return sSystemFontFiles;
        preloadSystemFonts();
        return new ArrayList<>();
    }

    private static List<File> enumerateSystemFontFiles() {
        List<File> files = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                for (android.graphics.fonts.Font font
                        : android.graphics.fonts.SystemFonts.getAvailableFonts()) {
                    try {
                        File file = font.getFile();
                        if (file != null && file.exists()) files.add(file);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (files.isEmpty()) {
            File[] system = new File("/system/fonts").listFiles();
            if (system != null) {
                for (File file : system) {
                    String lower = file.getName().toLowerCase();
                    if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")) {
                        files.add(file);
                    }
                }
            }
        }
        return files;
    }

    private static String fileNameWithoutExt(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 把字体名解析成 Typeface；找不到时返回 null（由调用方回退默认字体）。 */
    public static Typeface resolve(Context context, String name) {
        return resolve(context, name, 400);
    }

    /**
     * 带字重解析：可变字体用 Typeface.Builder 的字体变体设置（API 26+）连续生效；
     * 静态字体或低版本退化为常规/加粗。
     */
    public static Typeface resolve(Context context, String name, int weight) {
        if (name == null || name.isEmpty() || DEFAULT.equals(name)) return null;
        File file = null;
        File imported = new File(fontDir(context), name + ".ttf");
        if (imported.exists()) {
            file = imported;
        }
        if (file == null) {
            File importedOtf = new File(fontDir(context), name + ".otf");
            if (importedOtf.exists()) file = importedOtf;
        }
        if (file == null) {
            for (File system : systemFontFiles()) {
                if (fileNameWithoutExt(system).equals(name)) {
                    file = system;
                    break;
                }
            }
        }
        if (file != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && weight != 400) {
                try {
                    return new Typeface.Builder(file)
                            .setFontVariationSettings("'wght' " + weight)
                            .build();
                } catch (Exception ignored) {
                }
            }
            try {
                return Typeface.createFromFile(file);
            } catch (Exception ignored) {
            }
        }
        try {
            Typeface typeface = Typeface.create(name,
                    weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            if (typeface != null) return typeface;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 从系统文件窗口导入字体文件，返回可用的字体名（失败返回 null）。 */
    public static String importFont(Context context, InputStream in, String displayName) {
        if (in == null || displayName == null) return null;
        String lower = displayName.toLowerCase();
        String ext = lower.endsWith(".otf") ? ".otf" : lower.endsWith(".ttf") ? ".ttf" : null;
        if (ext == null) return null;
        String name = displayName.substring(0, displayName.length() - 4);
        File target = new File(fontDir(context), name + ext);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            return name;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
