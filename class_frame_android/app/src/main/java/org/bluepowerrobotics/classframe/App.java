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
        org.bluepowerrobotics.classframe.data.Logs.i("ClassFrame",
                "===== app 启动 " + org.bluepowerrobotics.classframe.data.Logs.environment()
                        + " 日志=" + org.bluepowerrobotics.classframe.data.Logs.location(this));
    }

    public static Context ctx() {
        return sAppContext;
    }
}
