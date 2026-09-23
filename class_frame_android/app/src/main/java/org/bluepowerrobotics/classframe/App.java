package org.bluepowerrobotics.classframe;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    private static Context sAppContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        org.bluepowerrobotics.classframe.data.Logs.init(this);
        // 时区必须最先定好：后面所有 Calendar.getInstance() 都会用到
        org.bluepowerrobotics.classframe.data.TimeSync.applyDefaultZone(this);
        org.bluepowerrobotics.classframe.data.Logs.i("ClassFrame",
                "===== app 启动 " + org.bluepowerrobotics.classframe.data.Logs.environment()
                        + " 日志=" + org.bluepowerrobotics.classframe.data.Logs.location(this));
        org.bluepowerrobotics.classframe.data.Logs.i("ClassFrame",
                "本机时间=" + org.bluepowerrobotics.classframe.data.TimeSync.format(
                        System.currentTimeMillis(),
                        org.bluepowerrobotics.classframe.data.TimeSync.zone(this))
                        + " 时区=" + org.bluepowerrobotics.classframe.data.Prefs.timeZoneId(this)
                        + " 偏移=" + org.bluepowerrobotics.classframe.data.TimeSync
                        .currentOffsetSeconds(this) + " 秒");
        // 打开"自动更新时间"时，启动就尝试同步一次
        org.bluepowerrobotics.classframe.data.TimeSync.syncAutoQuietly(this);
        // APK 升级后用 assets 里的班级模板刷新私有目录副本（旧副本会让"应用班级"看起来没生效）
        org.bluepowerrobotics.classframe.data.ClassTemplateRepository.syncAssets(this);
    }

    public static Context ctx() {
        return sAppContext;
    }
}
