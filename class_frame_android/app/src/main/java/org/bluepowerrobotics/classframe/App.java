package org.bluepowerrobotics.classframe;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    private static Context sAppContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
    }

    public static Context ctx() {
        return sAppContext;
    }
}
