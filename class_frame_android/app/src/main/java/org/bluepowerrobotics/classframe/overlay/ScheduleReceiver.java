package org.bluepowerrobotics.classframe.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 定时唤醒：到点后重新评估"显示 / 隐藏"并重排下一次唤醒。 */
public class ScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_WAKE = "org.bluepowerrobotics.classframe.action.WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            OverlayService.start(context);
        } catch (Exception ignored) {
            // 闹钟触发阶段任何异常都不应让系统弹崩溃
        }
    }
}
