package org.bluepowerrobotics.classframe.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.bluepowerrobotics.classframe.data.Prefs;
import org.bluepowerrobotics.classframe.overlay.OverlayService;

/** 开机自启：仅在"启用开机自启 + 启用悬浮窗 + 已授予悬浮权限"三者同时满足时拉起服务。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            if (!Prefs.bootAutostart(context)) return;
            if (!Prefs.overlayEnabled(context)) return;
            if (!Settings.canDrawOverlays(context)) return;
            OverlayService.start(context);
        } catch (Exception ignored) {
            // 开机阶段任何异常都不应导致系统弹崩溃
        }
    }
}
