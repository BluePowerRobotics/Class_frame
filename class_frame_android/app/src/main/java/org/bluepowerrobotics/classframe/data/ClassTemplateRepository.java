package org.bluepowerrobotics.classframe.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** classes_frame/*.json 班级模板。 */
public final class ClassTemplateRepository {

    private static final String PREF = "class_frame";
    /** 上次由本程序复制进去的模板内容摘要，用来判断文件有没有被用户改过。 */
    private static final String KEY_SEEDED_HASHES = "classes_seeded_hashes";

    private ClassTemplateRepository() {
    }

    /**
     * 用 assets 里的模板刷新私有目录副本。
     *
     * 以前只在"目录不存在"时复制一次，于是升级 APK 后仍然用着旧模板——
     * 表现就是"换班级配置怎么都不生效"（旧模板里没有位置表）。
     * 也曾经用"版本标记"判断，结果标记与实际内容错位，同样不可靠。
     * 现在直接按内容比对：内容不同就覆盖，代价只是启动时读几十 KB。
     *
     * 用户改过的文件不会被覆盖：与上次复制时记录的摘要不符即视为用户改动，跳过。
     */
    public static void syncAssets(Context context) {
        try {
            ConfigRepository.ensureInitialized(context);
            File dir = ConfigRepository.classDir(context);
            SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String[] assetNames;
            try {
                assetNames = context.getAssets().list(ConfigRepository.CLASS_DIR);
            } catch (Exception e) {
                assetNames = null;
            }
            if (assetNames == null) return;

            StringBuilder hashes = new StringBuilder();
            int refreshed = 0;
            for (String assetName : assetNames) {
                if (!assetName.toLowerCase().endsWith(".json")) continue;
                File target = new File(dir, assetName);
                byte[] asset = readAsset(context, ConfigRepository.CLASS_DIR + "/" + assetName);
                if (asset == null) continue;
                try {
                    byte[] current = target.exists() ? readFile(target) : null;
                    String lastSeeded = hashOf(sp.getString(KEY_SEEDED_HASHES, ""), assetName);
                    boolean userEdited = current != null && lastSeeded != null
                            && !lastSeeded.equals(sha1(current));
                    if (!userEdited && !java.util.Arrays.equals(asset, current)) {
                        ConfigRepository.copyAsset(context,
                                ConfigRepository.CLASS_DIR + "/" + assetName, target);
                        refreshed++;
                        Logs.i("ClassTemplate", "已刷新班级模板 " + assetName);
                    }
                    hashes.append(assetName).append('=').append(sha1(asset)).append(';');
                } catch (Exception e) {
                    Logs.e("ClassTemplate", "刷新 " + assetName + " 失败", e);
                }
            }
            sp.edit().putString(KEY_SEEDED_HASHES, hashes.toString()).apply();
            Logs.i("ClassTemplate", "班级模板同步完成，更新 " + refreshed + " 个");
        } catch (Exception e) {
            Logs.e("ClassTemplate", "同步班级模板失败", e);
        }
    }

    private static String hashOf(String hashes, String name) {
        if (hashes == null || hashes.isEmpty()) return null;
        String prefix = name + "=";
        for (String part : hashes.split(";")) {
            if (part.startsWith(prefix)) return part.substring(prefix.length());
        }
        return null;
    }

    private static byte[] readAsset(Context context, String path) {
        try {
            java.io.InputStream in = context.getAssets().open(path);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readFile(File file) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return out.toByteArray();
    }

    private static String sha1(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] result = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : result) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    public static List<String> listNames(Context context) {
        ConfigRepository.ensureInitialized(context);
        List<String> names = new ArrayList<>();
        File[] files = ConfigRepository.classDir(context).listFiles();
        if (files == null) return names;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.toLowerCase().endsWith(".json")) {
                names.add(name.substring(0, name.length() - 5));
            }
        }
        Collections.sort(names);
        return names;
    }

    public static File fileOf(Context context, String name) {
        return new File(ConfigRepository.classDir(context), name + ".json");
    }

    public static JSONObject read(Context context, String name) throws Exception {
        File file = fileOf(context, name);
        return new JSONObject(ConfigRepository.readUtf8(file));
    }
}
