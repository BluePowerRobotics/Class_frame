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
        /**
         * 看门狗：在"课前自检点"之后一次自检，用来兜底"闹钟被 ROM 拦掉/唤醒失败"。
         * 它不决定显示或隐藏，只负责把状态重新算一遍。
         */
        public long watchdogAtMillis;

        public String describe() {
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder(hidden ? "hidden" : "shown");
            if (triggerAtMillis > 0) {
                sb.append(" -> ").append(triggerShows ? "show" : "hide")
                        .append(" @ ").append(fmt.format(new java.util.Date(triggerAtMillis)));
            } else {
                sb.append(" (no trigger)");
            }
            if (watchdogAtMillis > 0) {
                sb.append(" | 自检 @ ").append(fmt.format(new java.util.Date(watchdogAtMillis)));
            }
            return sb.toString();
        }
    }

    private ScheduleEngine() {
    }

    /**
     * @param preClassWakeMinutes 首节课开始前多少分钟做一次自检；<=0 表示不排自检
     */
    public static Plan compute(Config config, Calendar appNow, int offsetSeconds,
                               int preClassWakeMinutes) {
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

        // 课前自检点（当天首节课）
        Calendar checkToday = (Calendar) appNow.clone();
        setMinutesOfDay(checkToday, starts.get(0));
        checkToday.add(Calendar.MINUTE, -Math.max(0, preClassWakeMinutes));

        Calendar hideToday = (Calendar) appNow.clone();
        setMinutesOfDay(hideToday, ends.get(lessons - 1));
        hideToday.add(Calendar.MINUTE, 10);

        long nowReal = appNow.getTimeInMillis() - offsetSeconds * 1000L;

        // 自检点：必须晚于"显示"时刻，否则会被当成又一次显示触发。
        Calendar nextCheck = appNow.before(checkToday) ? checkToday : nextDay(checkToday, appNow);
        if (nextCheck.before(showToday)) nextCheck = showToday;
        long checkReal = nextCheck.getTimeInMillis() - offsetSeconds * 1000L;
        if (checkReal < nowReal) checkReal = nowReal + 60_000L;
        if (preClassWakeMinutes > 0) plan.watchdogAtMillis = checkReal + 60_000L;

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

    /** 把时刻推到"今天或明天的同一时刻"，用于已经过点的情况。 */
    private static Calendar nextDay(Calendar template, Calendar appNow) {
        Calendar result = (Calendar) template.clone();
        while (!result.after(appNow)) {
            result.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    private static void setMinutesOfDay(Calendar calendar, int minutesOfDay) {
        calendar.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        calendar.set(Calendar.MINUTE, minutesOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
