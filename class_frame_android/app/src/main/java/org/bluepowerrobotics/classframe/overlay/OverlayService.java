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
    /** 心跳里每隔多少秒重新核对一次调度（兜底，正常由定时唤醒精确触发）。 */
    private static final int SCHEDULE_RECHECK_TICKS = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickerScheduled;
    private boolean frameScheduled;
    private boolean screenOn = true;
    private int tickCount;
    private Boolean lastVisible;

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
            }
            scheduleTicker();
        }
    };

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
        try {
            startForegroundCompat();
            Logs.i(TAG, "startForeground ok");
        } catch (Exception e) {
            // 前台服务起不来时（例如 FGS 类型不被该系统接受）必须留证据，否则表现为"什么都没发生"
            Logs.e(TAG, "startForeground failed", e);
        }

        String action = intent == null ? ACTION_START : intent.getAction();
        OverlayController controller = OverlayController.get(this);
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
            stopTicker();
            controller.hide();
            updateNotification(false);
            return START_STICKY;
        }

        if (!Prefs.overlayEnabled(this)) {
            Logs.w(TAG, "overlayEnabled=false，悬浮层不启动");
            stopTicker();
            cancelWake();
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
            plan = ScheduleEngine.compute(config, appNow, config.timeOffsetSeconds);
        } catch (Exception e) {
            Logs.e(TAG, "schedule: compute failed", e);
            return;
        }

        OverlayController controller = OverlayController.get(this);
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
        if (plan.triggerAtMillis <= 0) return;

        long delay = plan.triggerAtMillis - System.currentTimeMillis();
        if (delay > 0) {
            // 进程存活时用 Handler 精确触发（开销最低）
            handler.postDelayed(wakeRunnable, delay + 50);
        } else {
            // 已经过点：下一次心跳会重新评估
            handler.post(wakeRunnable);
        }

        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = wakePendingIntent();
        try {
            manager.cancel(pending);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !manager.canScheduleExactAlarms()) {
                // 未授予"闹钟与提醒"权限时退化为非精确闹钟，仍能在 Doze 中唤醒
                manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pending);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pending);
            }
        } catch (Exception e) {
            Logs.w(TAG, "schedule: alarm failed: " + e);
        }
    }

    private void cancelWake() {
        handler.removeCallbacks(wakeRunnable);
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            try {
                manager.cancel(wakePendingIntent());
            } catch (Exception ignored) {
            }
        }
    }

    private PendingIntent wakePendingIntent() {
        Intent intent = new Intent(this, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION_WAKE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, ALARM_REQUEST_CODE, intent, flags);
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
