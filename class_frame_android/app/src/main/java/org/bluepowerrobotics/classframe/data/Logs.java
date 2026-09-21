package org.bluepowerrobotics.classframe.data;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志：同时写 logcat 与 app 私有目录下的文本文件。
 * 用途：课堂电脑不方便接 adb 时，可通过 system 页"导出运行日志"把日志存到 U 盘/文件管理器。
 * 未捕获异常也会记入该文件，闪退后仍有证据。
 */
public final class Logs {

    private static final String TAG = "ClassFrame";
    private static final String FILE_NAME = "class_frame.log";
    private static final String PREV_NAME = "class_frame.prev.log";
    private static final long MAX_BYTES = 512 * 1024;

    private static Context sContext;
    private static File sFile;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Logs() {
    }

    public static synchronized void init(Context context) {
        sContext = context.getApplicationContext();
        boolean firstInit = sFile == null;
        if (firstInit) {
            File dir = new File(context.getFilesDir(), "log");
            if (!dir.exists()) dir.mkdirs();
            sFile = new File(dir, FILE_NAME);
            // 上次运行（很可能是崩溃）的日志留一份，避免"重启一次就把现场冲掉"。
            try {
                File prev = new File(dir, PREV_NAME);
                if (sFile.exists()) {
                    if (prev.exists()) prev.delete();
                    sFile.renameTo(prev);
                }
            } catch (Exception ignored) {
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler parent =
                    Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                append("FATAL thread=" + thread.getName() + "\n" + describe(throwable)
                        + "\n" + environment());
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
                .setContentTitle("灵动课表刚刚出错，请导出运行日志")
                .setContentText(message)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(
                        message + "\n打开 app → system → 导出运行日志"))
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

    /** 本次运行日志 + 上次运行日志，一起导出，避免丢失崩溃现场。 */
    public static String readAll(Context context) {
        init(context);
        StringBuilder sb = new StringBuilder();
        try {
            File dir = context.getFilesDir();
            File prev = new File(new File(dir, "log"), PREV_NAME);
            if (prev.exists()) {
                sb.append("===== 上次运行日志（崩溃现场通常在这里）=====\n");
                sb.append(readFile(prev));
                sb.append('\n');
            }
            File current = new File(new File(dir, "log"), FILE_NAME);
            sb.append("===== 本次运行日志 =====\n");
            if (current.exists()) {
                sb.append(readFile(current));
            } else {
                sb.append("(暂无)\n");
            }
            sb.append('\n').append(environment()).append('\n');
        } catch (Exception e) {
            sb.append("\n日志读取失败: ").append(e).append('\n');
        }
        return sb.toString();
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
        try {
            if (sFile.exists() && sFile.length() > MAX_BYTES) {
                File old = new File(sFile.getParentFile(), FILE_NAME + ".old");
                if (old.exists()) old.delete();
                sFile.renameTo(old);
            }
            FileOutputStream out = new FileOutputStream(sFile, true);
            out.write((FMT.format(new Date()) + " " + line + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Exception ignored) {
        }
    }
}
