package org.bluepowerrobotics.classframe.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.bluepowerrobotics.classframe.data.Logs;

/**
 * 定时唤醒：到点后重新评估"显示 / 隐藏"并重排下一次唤醒。
 *
 * 这里也是判断"希沃是否允许厂商之外的闹钟穿透"的观察点：如果闹钟该响却只留下
 * "app 启动"而没有本类写入的行，就说明唤醒在 Android 侧被拦掉了。
 */
public class ScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_WAKE = "org.bluepowerrobotics.classframe.action.WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "(null)" : String.valueOf(intent.getAction());
        Logs.i("ScheduleReceiver", "闹钟触发 action=" + action);
        try {
            OverlayService.start(context);
        } catch (Exception error) {
            Logs.e("ScheduleReceiver", "拉起服务失败", error);
            // 闹钟触发阶段任何异常都不应让系统弹崩溃
        }
    }
}
