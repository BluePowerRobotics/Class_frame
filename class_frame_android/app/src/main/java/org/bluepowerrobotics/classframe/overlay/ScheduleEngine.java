package org.bluepowerrobotics.classframe.overlay;

import org.bluepowerrobotics.classframe.data.Config;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 上课周期调度：判断当前是否应显示悬浮层，以及下一次切换的时刻。
 *
 * 规则（按需求确认）：
 *  - 当天最后一节下课 10 分钟后隐藏悬浮层；
 *  - 次日首节课开始前 1 小时重新显示；
 *  - 不特殊处理"全天无课"的日子，直接按次日第一节开始时间计算。
 *
 * 时间偏移（config 的"时间偏移（秒）"）用于校正设备时钟：
 * 判断在"校正后的时间"里做，返回的触发时刻再换算回真实时钟。
 */
public final class ScheduleEngine {

    public static final class Plan {
        /** 当前是否应该隐藏悬浮层。 */
        public boolean hidden;
        /** 下一次切换的真实时钟时刻（毫秒），0 表示无需排程。 */
        public long triggerAtMillis;
        /** true = 到点显示，false = 到点隐藏。 */
        public boolean triggerShows;

        public String describe() {
            if (triggerAtMillis <= 0) return (hidden ? "hidden" : "shown") + " (no trigger)";
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            return (hidden ? "hidden" : "shown")
                    + " -> " + (triggerShows ? "show" : "hide")
                    + " @ " + fmt.format(new java.util.Date(triggerAtMillis));
        }
    }

    private ScheduleEngine() {
    }

    public static Plan compute(Config config, Calendar appNow, int offsetSeconds) {
        Plan plan = new Plan();
        List<Integer> starts = config.starts;
        List<Integer> ends = config.ends;
        int lessons = Math.min(starts.size(), ends.size());
        if (lessons == 0) {
            plan.hidden = false;
            return plan;
        }

        Calendar showToday = (Calendar) appNow.clone();
        setMinutesOfDay(showToday, starts.get(0));
        showToday.add(Calendar.HOUR_OF_DAY, -1);

        Calendar hideToday = (Calendar) appNow.clone();
        setMinutesOfDay(hideToday, ends.get(lessons - 1));
        hideToday.add(Calendar.MINUTE, 10);

        if (appNow.before(showToday)) {
            // 凌晨：仍处在前一天的隐藏期，等到今天课前 1 小时
            plan.hidden = true;
            plan.triggerShows = true;
            plan.triggerAtMillis = showToday.getTimeInMillis() - offsetSeconds * 1000L;
        } else if (appNow.before(hideToday)) {
            // 白天：显示中，等到当天放学 10 分钟后隐藏
            plan.hidden = false;
            plan.triggerShows = false;
            plan.triggerAtMillis = hideToday.getTimeInMillis() - offsetSeconds * 1000L;
        } else {
            // 放学后：隐藏，等到次日课前 1 小时
            Calendar showTomorrow = (Calendar) appNow.clone();
            showTomorrow.add(Calendar.DAY_OF_MONTH, 1);
            setMinutesOfDay(showTomorrow, starts.get(0));
            showTomorrow.add(Calendar.HOUR_OF_DAY, -1);
            plan.hidden = true;
            plan.triggerShows = true;
            plan.triggerAtMillis = showTomorrow.getTimeInMillis() - offsetSeconds * 1000L;
        }
        return plan;
    }

    private static void setMinutesOfDay(Calendar calendar, int minutesOfDay) {
        calendar.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        calendar.set(Calendar.MINUTE, minutesOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
