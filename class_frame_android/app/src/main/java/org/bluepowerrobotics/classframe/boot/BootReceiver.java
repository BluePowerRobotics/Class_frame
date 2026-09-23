package org.bluepowerrobotics.classframe.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.bluepowerrobotics.classframe.data.Prefs;
import org.bluepowerrobotics.classframe.overlay.OverlayService;

/**
 * 开机/恢复/解锁自启。
 *
 * 希沃这类双通道设备的 Android 侧可能被冻结或不给第三方自启动机会，
 * 所以除了 BOOT_COMPLETED，还要在"覆盖安装完成""解锁"这些确定性的时刻补一次，
 * 尽量缩短"该显示却没显示"的窗口。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 解锁/亮屏类事件很密集，做个去抖，避免反复拉服务。 */
    private static long sLastStartAt;
    private static final long DEBOUNCE_MS = 30_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = String.valueOf(intent.getAction());
        try {
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
            boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
            boolean unlocked = Intent.ACTION_USER_PRESENT.equals(action);
            if (!boot && !replaced && !unlocked) return;
            org.bluepowerrobotics.classframe.data.Logs.i("BootReceiver",
                    "收到 " + action);
            // 开机与覆盖安装必须显式开启自启；解锁是兜底，只看悬浮窗开关
            if ((boot || replaced) && !Prefs.bootAutostart(context)) return;
            if (!Prefs.overlayEnabled(context)) return;
            if (!Settings.canDrawOverlays(context)) return;
            long now = System.currentTimeMillis();
            if (unlocked && now - sLastStartAt < DEBOUNCE_MS) return;
            sLastStartAt = now;
            OverlayService.start(context);
        } catch (Exception error) {
            org.bluepowerrobotics.classframe.data.Logs.e("BootReceiver", "自启失败 " + action, error);
            // 开机阶段任何异常都不应导致系统弹崩溃
        }
    }
}
