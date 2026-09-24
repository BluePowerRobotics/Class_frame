package org.bluepowerrobotics.classframe.overlay;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import org.bluepowerrobotics.classframe.R;
import org.bluepowerrobotics.classframe.data.Config;
import org.bluepowerrobotics.classframe.data.ConfigRepository;
import org.bluepowerrobotics.classframe.data.Logs;
import org.bluepowerrobotics.classframe.data.Prefs;
import org.bluepowerrobotics.classframe.data.TimeSync;
import org.bluepowerrobotics.classframe.ui.MainActivity;

import java.util.Calendar;

/**
 * 承载悬浮层的前台服务。
 *
 * 渲染按需进行：无变化不渲染，只有交互/动画期间使用最高帧率，
 * 其余时间靠 1Hz 秒边界心跳与定时唤醒驱动。
 */
public class OverlayService extends Service {

    private static final String TAG = "ClassFrame";

    public static final String ACTION_START = "org.bluepowerrobotics.classframe.action.START";
    public static final String ACTION_STOP = "org.bluepowerrobotics.classframe.action.STOP";
    public static final String ACTION_REFRESH = "org.bluepowerrobotics.classframe.action.REFRESH";
    public static final String ACTION_HIDE_OVERLAY = "org.bluepowerrobotics.classframe.action.HIDE_OVERLAY";

    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ALARM_REQUEST_CODE = 2001;
    /** 课前自检闹钟用单独的 requestCode，避免和主触发互相覆盖。 */
    private static final int ALARM_REQUEST_CODE_WATCHDOG = 2002;
    /** 心跳里每隔多少秒重新核对一次调度（兜底，正常由定时唤醒精确触发）。 */
    private static final int SCHEDULE_RECHECK_TICKS = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickerScheduled;
    private boolean frameScheduled;
    private boolean screenOn = true;
    private int tickCount;
    private Boolean lastVisible;
    private int lastArmedCode;
    private int lastLoggedOffset = Integer.MIN_VALUE;
    /**
     * 悬浮权限的授予有时不会立刻对已运行的进程可见（设置页返回、ContentProvider 传播有延迟），
     * 也可能出现"权限给了但服务早就放弃过"。这里记住最近一次失败，之后自愈重试。
     */
    private long lastShowAttemptAt;
    private static final long SHOW_RETRY_INTERVAL_MS = 5000;

    private final Runnable wakeRunnable = new Runnable() {
        @Override
        public void run() {
            applySchedule();
        }
    };

    /** 1Hz 秒边界心跳：只在有内容随时间变化时重排。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickerScheduled = false;
            OverlayController controller = OverlayController.get(OverlayService.this);
            controller.markDirty();
            controller.refreshIfDirty();
            tickCount++;
            if (tickCount % SCHEDULE_RECHECK_TICKS == 0) {
                applySchedule();
            } else {
                ensureOverlayAlive();
            }
            scheduleTicker();
        }
    };

    /**
     * 只要"开关开着 + 权限给了 + 当前应当显示 + 窗口其实没挂上"，就重试挂载。
     * 这样权限晚一点生效、或某次 addView 失败，都不会留下"永远不出现"的状态。
     */
    private void ensureOverlayAlive() {
        if (!Prefs.overlayEnabled(this)) return;
        OverlayController controller = OverlayController.get(this);
        if (!controller.shouldBeVisible() || controller.isShown()) return;
        long now = System.currentTimeMillis();
        if (now - lastShowAttemptAt < SHOW_RETRY_INTERVAL_MS) return;
        lastShowAttemptAt = now;
        Logs.i(TAG, "自愈重试: 应当显示但窗口未挂载，重新 show()");
        applySchedule();
    }

    /** 帧循环只在动画未收敛或处于交互期时运行。 */
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            OverlayController controller = OverlayController.get(OverlayService.this);
            controller.refreshIfDirty();
            boolean animating = controller.tickAnimation();
            if (animating || controller.needsHighFrameRate()) {
                scheduleFrame();
            }
        }
    };

    /** 息屏时停掉心跳，亮屏时重新评估。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                stopTicker();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                applySchedule();
            }
        }
    };

    /** 系统时间/时区变化后重算并重排。 */
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applySchedule();
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, OverlayService.class));
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void hideOverlay(Context context) {
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_HIDE_OVERLAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.i(TAG, "service onCreate");
        // 开机自启的机器可能一直没人打开界面，这里也尝试一次自动对时
        try {
            org.bluepowerrobotics.classframe.data.TimeSync.syncAutoQuietly(this);
        } catch (Throwable error) {
            Logs.e(TAG, "自动对时启动失败", error);
        }
        createChannel();
        registerReceiver(screenReceiver, new IntentFilter() {{
            addAction(Intent.ACTION_SCREEN_ON);
            addAction(Intent.ACTION_SCREEN_OFF);
        }});
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logs.i(TAG, "service onStartCommand action="
                + (intent == null ? "(null)" : intent.getAction()) + " startId=" + startId);

        String action = intent == null ? ACTION_START : intent.getAction();
        OverlayController controller = OverlayController.get(this);

        /*
         * startForeground 必须在任何 return 之前调用。
         *
         * 用 startForegroundService() 拉起来的服务，系统要求 5 秒内调用 startForeground()，
         * 否则直接抛 RemoteServiceException 把进程打死；而返回 START_STICKY 又会让系统重启它，
         * 于是变成"一直闪退"的死循环（真机日志里连着一串 FATAL 就是这个）。
         * 所以先无条件进前台，再按开关决定要不要真的显示悬浮层。
         */
        try {
            startForegroundCompat();
            Logs.i(TAG, "startForeground ok");
        } catch (Exception e) {
            // 前台服务起不来时（例如 FGS 类型不被该系统接受）必须留证据，否则表现为"什么都没发生"
            Logs.e(TAG, "startForeground failed", e);
        }

        // 开关关着时立刻收掉前台状态与悬浮层，避免通知栏一直挂着"运行中"
        if (!Prefs.overlayEnabled(this) && !ACTION_STOP.equals(action)) {
            Logs.w(TAG, "overlayEnabled=false，悬浮层不启动（action=" + action + "）");
            stopTicker();
            cancelWake();
            controller.hide();
            updateNotification(false);
            stopForegroundCompat();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        controller.setFrameRequester(new OverlayController.FrameRequester() {
            @Override
            public void requestFrames() {
                scheduleFrame();
            }
        });

        if (ACTION_STOP.equals(action)) {
            stopTicker();
            cancelWake();
            controller.hide();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_REFRESH.equals(action)) {
            controller.reloadConfig();
        }

        if (ACTION_HIDE_OVERLAY.equals(action)) {
            // "移除悬浮层"要真正落实成"关掉开关"，否则下一次唤醒又会把它显示回来
            Prefs.setOverlayEnabled(this, false);
            stopTicker();
            controller.hide();
            updateNotification(false);
            return START_STICKY;
        }

        controller.setInteractionHoldMs(Prefs.interactHoldMs(this));
        applySchedule();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTicker();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(timeReceiver);
        } catch (Exception ignored) {
        }
        OverlayController.get(this).hide();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            OverlayController.get(this).movement().updateScreenSize();
            OverlayController.get(this).markDirty();
            OverlayController.get(this).refresh();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 调度

    /** 按当前时间评估"显示 / 隐藏"，并把下一次切换排进定时器与闹钟。 */
    private void applySchedule() {
        Config config;
        try {
            config = ConfigRepository.load(this);
        } catch (Exception e) {
            Logs.e(TAG, "schedule: load config failed", e);
            return;
        }

        Calendar appNow = Calendar.getInstance();
        appNow.add(Calendar.SECOND, config.timeOffsetSeconds);
        ScheduleEngine.Plan plan;
        try {
            plan = ScheduleEngine.compute(config, appNow, config.timeOffsetSeconds,
                    Prefs.preClassWakeMinutes(this));
        } catch (Exception e) {
            Logs.e(TAG, "schedule: compute failed", e);
            return;
        }

        OverlayController controller = OverlayController.get(this);
        // 唤醒链路是"闹钟 → 接收器 → 服务"，用户能看到的历史全在日志里，
        // 所以每次时间参数变化都留一行，便于判断闹钟到底有没有穿透。
        if (config.timeOffsetSeconds != lastLoggedOffset) {
            lastLoggedOffset = config.timeOffsetSeconds;
            Logs.i(TAG, "时间参数变化: 设备 " + TimeSync.format(System.currentTimeMillis(),
                    TimeSync.zone(this)) + " → 校正后 " + TimeSync.format(
                    System.currentTimeMillis() + config.timeOffsetSeconds * 1000L,
                    TimeSync.zone(this)) + "（偏移 " + config.timeOffsetSeconds + " 秒）");
        }
        if (plan.hidden) {
            Logs.i(TAG, "schedule: hidden -> hide");
            stopTicker();
            controller.hide();
            lastVisible = false;
            updateNotification(false);
        } else {
            Logs.i(TAG, "schedule: visible -> show");
            boolean ok = controller.show();
            lastVisible = ok;
            if (ok && screenOn) scheduleTicker();
            updateNotification(ok);
        }
        scheduleWake(plan);
        Logs.i(TAG, "schedule: " + plan.describe() + " screenOn=" + screenOn);
    }

    private void scheduleWake(ScheduleEngine.Plan plan) {
        handler.removeCallbacks(wakeRunnable);

        if (plan.triggerAtMillis > 0) {
            long delay = plan.triggerAtMillis - System.currentTimeMillis();
            if (delay > 0) {
                // 进程存活时用 Handler 精确触发（开销最低）
                handler.postDelayed(wakeRunnable, delay + 50);
            } else {
                // 已经过点：下一次心跳会重新评估
                handler.post(wakeRunnable);
            }
        }

        // 主触发（到点显示 / 到点隐藏）
        boolean armed = armAlarm(plan.triggerAtMillis, ALARM_REQUEST_CODE, false);

        // 课前自检：只在主触发会走"显示"这条路时才需要
        if (plan.triggerShows && plan.watchdogAtMillis > 0
                && (plan.triggerAtMillis <= 0 || plan.watchdogAtMillis < plan.triggerAtMillis)) {
            armed |= armAlarm(plan.watchdogAtMillis, ALARM_REQUEST_CODE_WATCHDOG, true);
        }
        if (!armed) cancelWake();
    }

    /** 返回是否真的排上了闹钟。 */
    private boolean armAlarm(long atMillis, int requestCode, boolean watchdog) {
        if (atMillis <= 0) return false;
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return false;
        PendingIntent pending = wakePendingIntent(requestCode);
        try {
            manager.cancel(pending);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !manager.canScheduleExactAlarms()) {
                // 未授予"闹钟与提醒"权限时退化为非精确闹钟，仍能在 Doze 中唤醒
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, atMillis, pending);
            }
            lastArmedCode = requestCode;
            Logs.i(TAG, "闹钟已排" + (watchdog ? "（课前自检）" : "") + ": "
                    + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(atMillis))
                    + " 距现在 " + Math.round((atMillis - System.currentTimeMillis()) / 1000.0)
                    + " 秒");
            return true;
        } catch (Exception e) {
            Logs.w(TAG, "schedule: alarm failed: " + e);
            return false;
        }
    }

    private void cancelWake() {
        handler.removeCallbacks(wakeRunnable);
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            for (int code : new int[]{ALARM_REQUEST_CODE, ALARM_REQUEST_CODE_WATCHDOG}) {
                try {
                    manager.cancel(wakePendingIntent(code));
                } catch (Exception ignored) {
                }
            }
        }
        lastArmedCode = 0;
    }

    private PendingIntent wakePendingIntent(int requestCode) {
        Intent intent = new Intent(this, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION_WAKE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, requestCode, intent, flags);
    }

    // ------------------------------------------------------------ 心跳与帧

    private void scheduleTicker() {
        if (tickerScheduled || !screenOn) return;
        tickerScheduled = true;
        long now = SystemClock.elapsedRealtime();
        long delay = 1000 - (now % 1000) + 8;   // 对齐秒边界并略滞后，避免重复同一秒
        handler.postDelayed(ticker, delay);
    }

    private void stopTicker() {
        tickerScheduled = false;
        handler.removeCallbacks(ticker);
        frameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    // ------------------------------------------------------------ 通知

    private void startForegroundCompat() {
        Notification notification = buildNotification(lastVisible == null || lastVisible);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(boolean visible) {
        if (lastVisible != null && lastVisible == visible) return;
        lastVisible = visible;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(visible));
        } catch (Exception ignored) {
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private Notification buildNotification(boolean visible) {
        Intent content = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, content, pendingFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(visible ? R.string.notif_text : R.string.notif_text_hidden))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        manager.createNotificationChannel(channel);
    }
}
