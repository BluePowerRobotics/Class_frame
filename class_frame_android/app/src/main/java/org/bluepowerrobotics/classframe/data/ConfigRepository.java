package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 应用私有目录中的数据读写，首次启动时从 assets 释放默认配置。 */
public final class ConfigRepository {

    public static final String CONFIG_NAME = "config.json";
    public static final String DATA_NAME = "data.json";
    public static final String CLASS_DIR = "classes_frame";

    private ConfigRepository() {
    }

    public static File configFile(Context context) {
        return new File(context.getFilesDir(), CONFIG_NAME);
    }

    public static File dataFile(Context context) {
        return new File(context.getFilesDir(), DATA_NAME);
    }

    public static File classDir(Context context) {
        return new File(context.getFilesDir(), CLASS_DIR);
    }

    public static void ensureInitialized(Context context) {
        File filesDir = context.getFilesDir();
        if (!filesDir.exists()) filesDir.mkdirs();

        File cfg = configFile(context);
        if (!cfg.exists()) {
            copyAsset(context, CONFIG_NAME, cfg);
        }

        File classDir = classDir(context);
        if (!classDir.exists()) {
            classDir.mkdirs();
            try {
                String[] names = context.getAssets().list(CLASS_DIR);
                if (names != null) {
                    for (String name : names) {
                        if (name.endsWith(".json")) {
                            copyAsset(context, CLASS_DIR + "/" + name, new File(classDir, name));
                        }
                    }
                }
            } catch (IOException ignored) {
                // assets 缺失时保持空目录
            }
        }
    }

    public static Config load(Context context) throws Exception {
        ensureInitialized(context);
        String text = readUtf8(configFile(context));
        return Config.parse(new JSONObject(text));
    }

    /** 原始 config.json（未迁移、未解释），用于只改单个字段后原样写回。 */
    public static JSONObject loadRaw(Context context) throws Exception {
        ensureInitialized(context);
        return new JSONObject(readUtf8(configFile(context)));
    }

    public static void save(Context context, JSONObject config) throws Exception {
        writeUtf8Atomic(configFile(context), config.toString(2));
    }

    public static void copyAsset(Context context, String assetPath, File dest) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException ignored) {
            // 资源缺失时保持原状
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    public static String readUtf8(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) file.length()));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(in);
        }
    }

    public static void writeUtf8Atomic(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            closeQuietly(out);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("无法覆盖 " + file.getName());
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("无法写入 " + file.getName());
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
