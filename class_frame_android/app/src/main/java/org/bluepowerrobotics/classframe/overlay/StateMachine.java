package org.bluepowerrobotics.classframe.overlay;

import org.bluepowerrobotics.classframe.data.Config;

import java.util.Calendar;
import java.util.List;

/** calendar.update_state 的移植：给定时间与课表，算出当前状态。 */
public final class StateMachine {

    private StateMachine() {
    }

    public static OverlayState compute(Config config, Calendar now, List<String> tokens) {
        OverlayState s = new OverlayState();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        int second = now.get(Calendar.SECOND);
        double nowSec = hour * 3600.0 + minute * 60.0 + second;
        int nowMin = hour * 60 + minute;

        List<Integer> starts = config.starts;
        List<Integer> ends = config.ends;
        int n = Math.min(starts.size(), ends.size());

        if (n > 0) {
            int lastOffSec = ends.get(n - 1) * 60;
            if (nowSec >= lastOffSec && nowSec < lastOffSec + 600) {
                s.closing = true;
                s.afterClass = true;
                s.stateIndex = -1;
                s.stateNext = -1;
                s.highlightLesson = -1;
                s.totalSec = 600;
                s.leftSec = lastOffSec + 600 - nowSec;
                s.promptLeft = 0;
                s.offPromptLeft = 0;
                s.highlightToken = -1;
                return s;
            }
            if (nowSec >= lastOffSec + 600) {
                // Python 版此处退出进程；Android 版改为隐藏，由调度层负责次日重现
                s.hidden = true;
                s.afterClass = true;
                s.stateIndex = -1;
                s.stateNext = -1;
                s.highlightLesson = -1;
                s.highlightToken = -1;
                return s;
            }
        }

        for (int i = 0; i < n; i++) {
            if (starts.get(i) * 60.0 <= nowSec && nowSec < ends.get(i) * 60.0) {
                s.closing = false;
                s.afterClass = false;
                s.stateIndex = i;
                s.stateNext = -1;
                s.highlightLesson = i;
                s.totalSec = (ends.get(i) - starts.get(i)) * 60.0;
                s.leftSec = ends.get(i) * 60.0 - nowSec;
                int elapsed = (int) (nowSec - starts.get(i) * 60.0);
                s.promptLeft = Math.max(0, config.onPromptDuration - elapsed);
                s.offPromptLeft = 0;
                s.highlightToken = tokenIndexOf(s.highlightLesson, tokens);
                return s;
            }
        }

        s.closing = false;
        s.afterClass = true;
        s.stateIndex = -1;
        s.promptLeft = 0;
        s.offPromptLeft = 0;
        s.highlightLesson = -1;
        s.highlightToken = -1;

        int next = -1;
        for (int i = 0; i < n; i++) {
            if (nowMin < starts.get(i)) {
                next = i;
                break;
            }
        }
        if (next >= 0) {
            s.stateNext = next;
            s.highlightLesson = next;
            s.highlightToken = tokenIndexOf(next, tokens);
            double prevEnd = next > 0 ? ends.get(next - 1) * 60.0 : 0.0;
            s.totalSec = starts.get(next) * 60.0 - prevEnd;
            s.leftSec = starts.get(next) * 60.0 - nowSec;
            if (next > 0) {
                int offElapsed = (int) (nowSec - ends.get(next - 1) * 60.0);
                s.offPromptLeft = Math.max(0, config.offPromptDuration - offElapsed);
            }
        } else {
            s.stateNext = -1;
            s.totalSec = 1;
            s.leftSec = 0;
        }
        return s;
    }

    public static int tokenIndexOf(int lessonIndex, List<String> tokens) {
        if (lessonIndex < 0 || tokens == null) return -1;
        int[] map = Config.lessonPositionMap(tokens);
        return lessonIndex < map.length ? map[lessonIndex] : -1;
    }

    /** 上课提示要显示的课名；不需要提示时返回 null。 */
    public static String promptLessonName(Config config, OverlayState state, List<String> tokens) {
        if (state.afterClass || state.closing || state.promptLeft <= 0 || state.stateIndex < 0) return null;
        int index = tokenIndexOf(state.stateIndex, tokens);
        if (index < 0 || index >= tokens.size()) return null;
        String name = tokens.get(index);
        if (Config.isSpecialToken(name) || "无".equals(name)) return null;
        return name;
    }
}
