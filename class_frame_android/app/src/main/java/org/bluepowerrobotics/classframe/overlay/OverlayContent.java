package org.bluepowerrobotics.classframe.overlay;

import org.bluepowerrobotics.classframe.data.Config;

import java.util.ArrayList;
import java.util.List;

/** 一次渲染所需的全部输入。 */
public final class OverlayContent {

    public OverlayView.Kind kind = OverlayView.Kind.EDGE_TEXT;
    public String dock = "upper";
    public boolean afterClass = true;
    public Config config;
    public List<String> tokens = new ArrayList<>();
    public int highlightToken = -1;
    public OverlayState state;
    /** 上课提示的课名；为空表示不显示提示形态。 */
    public String promptName;
    /** center 编辑器的日期文本。 */
    public String dateText = "";
    /** center 编辑器的候选课程。 */
    public List<String> candidates = new ArrayList<>();
    /** 倒计时窗口宽度上限（对应 Python 里钳制到主课表条宽度）。 */
    public int clampWidthPx;
    /** 倒计时窗口高度对齐值（对应 Python 上方停靠时两窗等高）。 */
    public int clampHeightPx;
    /**
     * 强制缩放系数（>0 时生效）。用于让倒计时窗口与主窗口共用同一个字号缩放，
     * 保证"左右停靠时两窗等宽、上方停靠时两窗等高"的原设计约束。
     */
    public float forcedScale;
}
