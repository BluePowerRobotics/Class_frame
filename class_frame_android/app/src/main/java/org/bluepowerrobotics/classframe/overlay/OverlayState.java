package org.bluepowerrobotics.classframe.overlay;

/** 某一时刻的课堂状态，对应 Python calendar.update_state 的计算结果。 */
public final class OverlayState {

    /** 放学 10 分钟后，悬浮层应当隐藏。 */
    public boolean hidden;
    /** 放学后的 10 分钟倒计时窗口内。 */
    public boolean closing;
    /** 当前处于课间/放学（true）还是上课中（false）。 */
    public boolean afterClass = true;
    /** 正在上的课节下标，-1 表示不在上课。 */
    public int stateIndex = -1;
    /** 即将开始的课节下标。 */
    public int stateNext = -1;
    public double totalSec = 1;
    public double leftSec;
    /** 上课提示剩余秒数。 */
    public int promptLeft;
    /** 下课提示剩余秒数。 */
    public int offPromptLeft;
    /** 需要高亮的课节下标。 */
    public int highlightLesson = -1;
    /** 需要高亮的 token 下标。 */
    public int highlightToken = -1;

    public float ratio() {
        if (totalSec <= 0) return 0f;
        return (float) Math.max(0.0, Math.min(1.0, leftSec / totalSec));
    }
}
