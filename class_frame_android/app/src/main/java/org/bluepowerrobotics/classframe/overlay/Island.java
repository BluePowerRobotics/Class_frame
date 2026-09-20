package org.bluepowerrobotics.classframe.overlay;

import android.view.View;
import android.view.WindowManager;

/** 一个悬浮窗口的记录，对应 Python movements.isls 里的一项。 */
public final class Island {

    public final View view;
    public final WindowManager.LayoutParams params;

    public String group = "upper";
    public boolean flush;
    public boolean deleting;
    public boolean dragging;

    public final float[] goal = new float[4];
    public final float[] now = new float[4];
    public final float[] prev = new float[4];

    public int appliedW = -1;
    public int appliedH = -1;
    public int appliedX = Integer.MIN_VALUE;
    public int appliedY = Integer.MIN_VALUE;

    /**
     * 实际窗口尺寸。动画期间取"新旧内容的较大值"，避免逐帧改窗口大小（会掉帧闪烁）；
     * 可见区域由 View 内部按动画尺寸绘制，静止后再收回窗口尺寸。
     */
    public int windowW = -1;
    public int windowH = -1;

    Island(View view, WindowManager.LayoutParams params) {
        this.view = view;
        this.params = params;
    }
}
