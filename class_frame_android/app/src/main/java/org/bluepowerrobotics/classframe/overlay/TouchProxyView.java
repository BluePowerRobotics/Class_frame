package org.bluepowerrobotics.classframe.overlay;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/**
 * 触摸代理窗口：与可见矩形严格同尺寸、完全透明，只负责接收触摸并转交给绘制视图。
 *
 * 背景：绘制窗口在停靠切换动画期间会取"新旧较大矩形"（避免逐帧 resize 造成闪烁），
 * 但窗口默认整个矩形都可触摸，透明部分会吃掉点击。拆成两个窗口后：
 *  - 绘制窗口 FLAG_NOT_TOUCHABLE，绝不接触摸；
 *  - 本代理窗口完全透明，尺寸=可见矩形，透明窗口 resize 不会有可见闪烁。
 */
public class TouchProxyView extends View {

    private final OverlayView target;

    public TouchProxyView(Context context, OverlayView target) {
        super(context);
        this.target = target;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 两个窗口位置一致，事件里的 raw 坐标可直接交给绘制视图的触摸逻辑
        return target.dispatchTouchEvent(event);
    }
}
