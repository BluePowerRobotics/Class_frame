package org.bluepowerrobotics.classframe.data;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志：同时写 logcat 与一个"外部可读"的文本文件。
 *
 * 课堂机不方便接 adb，而且一旦 app 出错（点不开 system 页）就没法用 app 内的导出按钮，
 * 所以日志必须落在不需要打开 app 也能拿到的地方：
 *
 * - Android 10+（含教室那台 12）：走 MediaStore 写到公共「下载」目录下的 ClassFrame 文件夹。
 *   这是 app 自己的文件，不需要任何运行时权限，任何文件管理器、U 盘、MTP 都能看到。
 * - Android 9 及以下：先要 WRITE_EXTERNAL_STORAGE 才能写公共目录，所以退化写到
 *   /sdcard/Android/data/<包名>/files/log，该目录在 Android 9 上是公开可见的。
 * - 兜底：始终再写一份到 app 私有目录，任何情况下都有现场。
 */
public final class Logs {

    private static final String TAG = "ClassFrame";
    private static final String FILE_NAME = "class_frame.log";
    private static final String PREV_NAME = "class_frame.prev.log";
    /** 公共「下载」目录下的子文件夹，避免和别人的文件混在一起。 */
    private static final String PUBLIC_DIR = "ClassFrame";
    private static final String MEDIA_RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR + "/";
    private static final long MAX_BYTES = 512 * 1024;

    private static Context sContext;
    /** 兜底：app 私有目录，绝对可写。 */
    private static File sFile;
    /** 对外公开的那一份（Android 9- 用外部私有目录）。 */
    private static File sPublicFile;
    /** Android 10+ 走 MediaStore，避免申请存储权限。 */
    private static Uri sMediaUri;
    /**
     * MediaStore 写入比普通文件贵得多（每次都要走 provider），所以保持一个长连接：
     * 打开后一直 append，累计 MAX_BYTES/8 关闭重开一次（相当于 flush），避免拖慢主线程。
     */
    private static OutputStream sMediaOut;
    private static long sMediaSinceFlush;
    /** 给人看的位置说明，显示在 system 页。 */
    private static String sLocation = "(未初始化)";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Logs() {
    }

    public static synchronized void init(Context context) {
        sContext = context.getApplicationContext();
        if (sFile == null) prepare(context);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler parent =
                    Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                append("FATAL thread=" + thread.getName() + "\n" + describe(throwable)
                        + "\n" + environment());
                // 进程马上要被系统干掉，先把公共目录那份刷进磁盘
                closeMedia();
                // 闪退时用户通常不在 app 里，无法点"导出运行日志"；这里给一条通知，点开就是 app，
                // 再走 system 页导出即可把崩溃现场取出。
                try {
                    notifyCrash(throwable);
                } catch (Throwable ignored) {
                }
                if (parent != null) parent.uncaughtException(thread, throwable);
            }
        });
    }

    /** 决定日志落点，并把上一次运行的日志留存下来。 */
    private static void prepare(Context context) {
        File dir = new File(context.getFilesDir(), "log");
        if (!dir.exists()) dir.mkdirs();
        sFile = new File(dir, FILE_NAME);
        archive(dir, sFile, new File(dir, PREV_NAME));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sMediaUri = openMediaDocument(context, FILE_NAME);
            sLocation = sMediaUri != null
                    ? "/存储/下载/" + PUBLIC_DIR + "/" + FILE_NAME + "（文件管理器可见）"
                    : "未能写入公共下载目录，已退回 " + sFile.getAbsolutePath();
            return;
        }

        // Android 9 及以下：外部私有目录对文件管理器与 MTP 是可见的
        try {
            File external = context.getExternalFilesDir("log");
            if (external != null) {
                if (!external.exists()) external.mkdirs();
                sPublicFile = new File(external, FILE_NAME);
                archive(external, sPublicFile, new File(external, PREV_NAME));
                sLocation = sPublicFile.getAbsolutePath() + "（文件管理器可见）";
            }
        } catch (Exception ignored) {
        }
        if (sPublicFile == null) {
            sLocation = sFile.getAbsolutePath() + "（私有目录，需 app 内导出）";
        }
    }

    private static void archive(File dir, File current, File prev) {
        try {
            if (!current.exists()) return;
            if (prev.exists()) prev.delete();
            if (!current.renameTo(prev)) {
                // rename 失败（跨挂载点等）时退化为复制
                copy(current, prev);
                current.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
        out.close();
        in.close();
    }

    /**
     * 在公共「下载」目录下拿到/创建日志文件。
     * 已经存在就复用（返回已有条目的 URI），否则整档重来会一次次堆副本。
     */
    private static Uri openMediaDocument(Context context, String name) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE};
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] args = {name, MEDIA_RELATIVE_PATH};
            long id = -1;
            long size = 0;
            Cursor cursor = null;
            try {
                cursor = resolver.query(collection, projection, selection, args, null);
                if (cursor != null && cursor.moveToFirst()) {
                    id = cursor.getLong(0);
                    size = cursor.getLong(1);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }

            // 超过上限就换一个新名字，避免日志无限增长（旧文件留在原处供取走）
            if (id >= 0 && size > MAX_BYTES) {
                String rotated = "class_frame-" + new SimpleDateFormat("MMdd-HHmm", Locale.US)
                        .format(new Date()) + ".log";
                id = -1;
                name = rotated;
            }

            if (id >= 0) {
                return android.content.ContentUris.withAppendedId(collection, id);
            }

            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_RELATIVE_PATH);
            return resolver.insert(collection, values);
        } catch (Throwable error) {
            Log.w(TAG, "openMediaDocument failed", error);
            return null;
        }
    }

    /** 日志落点说明，system 页与导出文件里都会带上。 */
    public static String location(Context context) {
        init(context);
        return sLocation;
    }

    /**
     * Android 9 及以下写公共目录需要 WRITE_EXTERNAL_STORAGE；若现在只能用私有目录，
     * 说明还没授权（或者用户拒绝了），界面据此提示/申请。
     */
    public static boolean needsStoragePermission(Context context) {
        init(context);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && sPublicFile == null;
    }

    /** 授权结果变化后重新决定落点。 */
    public static synchronized void refreshLocation(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        if (sPublicFile != null && sPublicFile.getParentFile().canWrite()) return;
        closeMedia();
        sPublicFile = null;
        prepare(context.getApplicationContext());
    }

    /** 本次运行日志 + 上次运行日志，一起导出，避免丢失崩溃现场。 */
    public static String readAll(Context context) {
        init(context);
        closeMedia();
        StringBuilder sb = new StringBuilder();
        sb.append("日志位置: ").append(sLocation).append('\n');
        try {
            if (sMediaUri != null) {
                sb.append("\n===== 公共下载目录日志 =====\n");
                sb.append(readUri(context, sMediaUri));
                sb.append('\n');
            }
            File dir = context.getFilesDir();
            File prev = new File(new File(dir, "log"), PREV_NAME);
            if (prev.exists()) {
                sb.append("===== 上次运行日志（崩溃现场通常在这里）=====\n");
                sb.append(readFile(prev));
                sb.append('\n');
            }
            File current = new File(new File(dir, "log"), FILE_NAME);
            sb.append("===== 本次运行日志 =====\n");
            sb.append(current.exists() ? readFile(current) : "(暂无)\n");
            sb.append('\n').append(environment()).append('\n');
        } catch (Exception e) {
            sb.append("\n日志读取失败: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private static String readUri(Context context, Uri uri) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return "(无法打开)";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "(读取失败: " + e + ")";
        }
    }

    /** 设备与版本环境：排查"某台机器上才有"的问题时必要信息。 */
    public static String environment() {
        return "env sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE
                + " brand=" + Build.BRAND
                + " model=" + Build.MODEL
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "(no throwable)";
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void notifyCrash(Throwable throwable) {
        Context context = sContext;
        if (context == null) return;
        android.app.NotificationManager manager = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String channelId = "crash";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    channelId, "崩溃信息", android.app.NotificationManager.IMPORTANCE_HIGH));
        }
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(),
                "org.bluepowerrobotics.classframe.ui.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        android.app.PendingIntent pending =
                android.app.PendingIntent.getActivity(context, 9001, intent, flags);
        String message = throwable == null ? "未知异常"
                : throwable.getClass().getSimpleName()
                        + ": " + String.valueOf(throwable.getMessage());
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(context, channelId)
                : new android.app.Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("灵动课表刚刚出错，日志位置见下")
                .setContentText(message)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(
                        message + "\n日志: " + sLocation))
                .setAutoCancel(true)
                .setContentIntent(pending);
        manager.notify(9001, builder.build());
    }

    public static Context context() {
        return sContext;
    }

    public static File file(Context context) {
        init(context);
        return sFile;
    }

    private static String readFile(File file) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        append("I/" + tag + " " + message);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        append("W/" + tag + " " + message);
    }

    public static void e(String tag, String message, Throwable error) {
        Log.e(tag, message, error);
        StringWriter writer = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(writer));
        append("E/" + tag + " " + message + (error == null ? "" : "\n" + writer));
    }

    private static synchronized void append(String line) {
        if (sFile == null) return;
        String text = FMT.format(new Date()) + " " + line + "\n";
        try {
            if (sFile.exists() && sFile.length() > MAX_BYTES) {
                File old = new File(sFile.getParentFile(), FILE_NAME + ".old");
                if (old.exists()) old.delete();
                sFile.renameTo(old);
            }
            write(new FileOutputStream(sFile, true), text);
        } catch (Exception ignored) {
        }
        if (sPublicFile != null) {
            try {
                appendPublicFile(text);
            } catch (Exception ignored) {
            }
        }
        if (sMediaUri != null) {
            try {
                appendMedia(text);
            } catch (Exception ignored) {
            }
        }
    }

    private static void write(OutputStream out, String text) throws Exception {
        out.write(text.getBytes("UTF-8"));
        out.flush();
        out.close();
    }

    private static void appendPublicFile(String text) throws Exception {
        if (sPublicFile.exists() && sPublicFile.length() > MAX_BYTES) {
            File old = new File(sPublicFile.getParentFile(), FILE_NAME + ".old");
            if (old.exists()) old.delete();
            sPublicFile.renameTo(old);
        }
        write(new FileOutputStream(sPublicFile, true), text);
    }

    /**
     * 长连接写法见字段注释：每行只付一次 write，避免 MediaStore 的开销拖慢主线程。
     */
    private static void appendMedia(String text) throws Exception {
        if (sMediaOut == null) {
            // 文件可能被系统（例如"清理"）挪走，重试几次并允许放弃，绝不把主线程拖死
            for (int attempt = 0; attempt < 3 && sMediaOut == null; attempt++) {
                try {
                    sMediaOut = sContext.getContentResolver().openOutputStream(sMediaUri, "wa");
                } catch (Exception error) {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (sMediaOut == null) {
            return;
        }
        byte[] bytes = text.getBytes("UTF-8");
        sMediaOut.write(bytes);
        sMediaOut.flush();
        sMediaSinceFlush += bytes.length;
        if (sMediaSinceFlush > MAX_BYTES / 8) {
            closeMedia();
        }
    }

    private static void closeMedia() {
        try {
            if (sMediaOut != null) sMediaOut.close();
        } catch (Exception ignored) {
        }
        sMediaOut = null;
        sMediaSinceFlush = 0;
    }
}
