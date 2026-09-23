package org.bluepowerrobotics.classframe.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.bluepowerrobotics.classframe.data.ConfigRepository;
import org.bluepowerrobotics.classframe.data.DayChangeRepository;
import org.bluepowerrobotics.classframe.data.Positions;
import org.bluepowerrobotics.classframe.overlay.OverlayService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** edit 页：课表 / 时间 / 参数 / 对调 四个子页，对应 Python 的 edit.py。 */
public class EditPage implements MainActivity.Page {

    private static final String[] WEEK = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[][] SECTIONS = {
            {"显示与置顶", "上课显示倒计条:bool:1", "下课显示倒计时:bool:1",
                    "上课隐藏悬浮层:bool:1", "下课隐藏悬浮层:bool:1",
                    "上课提示时长:number:1", "下课提示时长:number:1"},
            {"字号与尺寸", "文字大小:number:1", "竖直显示的文字大小:number:1",
                    "进度条宽度:number:1", "left上课缩放:number:0", "left下课缩放:number:0",
                    "upper上课缩放:number:0", "upper下课缩放:number:0", "center缩放:number:0"},
            // ①全局：替代原来的"上课/下课默认位置 + 使用secondStyle"，
            // 也取代"拖入区域时默认使用的样式"（新三层不再依赖它）
            {"① 全局（未设置②每日/③每课时使用）",
                    "全局日程·上课:style:0", "全局日程·下课:style:0"},
            {"提示文字", "开始提示:text:0", "结束提示:text:0", "结尾提示:text:0"},
            {"时间校正", "时间偏移（秒）:number:0"},
    };
    private static final String[] DOCK_LABELS = {"左侧", "上方", "右侧", "居中"};
    private static final String[] DOCK_KEYS = {"left", "upper", "right", "center"};
    private static final String[] DOCK_KEYS_LEGACY = {"a", "b", "c", "f", "u"};

    private final MainActivity activity;
    private LinearLayout rootView;
    private LinearLayout content;
    private final List<TextView> tabLabels = new ArrayList<>();
    private int currentTab = 0;
    private JSONObject raw;

    // 课表子页
    private static final class Row {
        boolean separator;
        String period;
        final List<TextView> cells = new ArrayList<>();
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<String> options = new ArrayList<>();

    // 时间子页
    private final List<int[]> starts = new ArrayList<>();
    private final List<int[]> ends = new ArrayList<>();
    private int selectedTimeIndex = -1;

    // 参数子页
    private final List<Object> paramWidgets = new ArrayList<>();
    private final List<String> paramKeys = new ArrayList<>();

    // ②每日那一列：默认收起，点列首的 × / 每日 切换
    private boolean dailyColumnOpen;
    private int dailyDay = 1;

    public EditPage(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public View getView() {
        if (rootView != null) return rootView;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F7F7);

        LinearLayout tabBar = new LinearLayout(activity);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFFEDEDED);
        String[] titles = {"课表", "时间", "参数", "对调"};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TextView tab = new TextView(activity);
            tab.setText(titles[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            tabBar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabLabels.add(tab);
        }
        root.addView(tabBar);

        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootView = root;
        selectTab(0);
        return rootView;
    }

    @Override
    public void onShow() {
        // 每次都从磁盘重读：导入 config.json / 应用班级模板之后，编辑页必须反映新数据
        loadConfig();
        selectTab(currentTab);
    }

    private void loadConfig() {
        try {
            raw = new JSONObject(ConfigRepository.readUtf8(ConfigRepository.configFile(activity)));
        } catch (Exception e) {
            raw = new JSONObject();
        }
    }

    private void selectTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabLabels.size(); i++) {
            boolean on = i == index;
            tabLabels.get(i).setTextColor(on ? 0xFF1565C0 : 0xFF666666);
            tabLabels.get(i).setBackgroundColor(on ? 0xFFFFFFFF : 0x00000000);
        }
        content.removeAllViews();
        if (raw == null) loadConfig();
        switch (index) {
            case 0:
                buildScheduleTab();
                break;
            case 1:
                buildTimeTab();
                break;
            case 2:
                buildParamTab();
                break;
            default:
                buildSwapTab();
                break;
        }
    }

    // ------------------------------------------------------------ 课表

    private void buildScheduleTab() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        options.clear();
        JSONArray optionArray = raw.optJSONArray("更换选项");
        if (optionArray != null) {
            for (int i = 0; i < optionArray.length(); i++) options.add(optionArray.optString(i));
        }

        rows.clear();
        JSONObject schedule = raw.optJSONObject("日程表");
        JSONArray day1 = schedule == null ? null : schedule.optJSONArray("1");
        int periodNo = 0;
        int count = day1 == null ? 0 : day1.length();
        for (int rowIndex = 0; rowIndex < count; rowIndex++) {
            Row row = new Row();
            String token = day1.optString(rowIndex, "无");
            row.separator = "|".equals(token);
            row.period = row.separator ? "|" : String.valueOf(++periodNo);
            rows.add(row);
        }

        LinearLayout header = Ui.row(activity);
        TextView corner = new TextView(activity);
        corner.setText("节次");
        corner.setWidth(Ui.dp(activity, 56));
        header.addView(corner);
        for (String day : WEEK) {
            TextView label = new TextView(activity);
            label.setText(day);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(12);
            header.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // 周日的右边：②每日那一列的开关（默认收起）
        TextView dailyToggle = new TextView(activity);
        dailyToggle.setGravity(Gravity.CENTER);
        dailyToggle.setTextSize(12);
        dailyToggle.setTextColor(0xFF1565C0);
        dailyToggle.setText(dailyColumnOpen ? "× 周" + WEEK[dailyDay - 1] : "每日");
        dailyToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dailyColumnOpen) {
                    dailyColumnOpen = false;
                    selectTab(0);
                } else {
                    chooseDailyDay();
                }
            }
        });
        header.addView(dailyToggle, new LinearLayout.LayoutParams(
                Ui.dp(activity, dailyColumnOpen ? 84 : 48),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            final Row row = rows.get(rowIndex);
            if (row.separator) {
                View line = new View(activity);
                line.setBackgroundColor(0xFFBBBBBB);
                root.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 1)));
                continue;
            }
            LinearLayout line = Ui.row(activity);
            TextView label = new TextView(activity);
            label.setText(row.period);
            label.setGravity(Gravity.CENTER);
            label.setWidth(Ui.dp(activity, 56));
            label.setTextColor(0xFF1565C0);
            final int periodIndex = rowIndex;
            label.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPeriodMenu(periodIndex);
                }
            });
            line.addView(label);
            for (int day = 1; day <= 7; day++) {
                final TextView cell = Ui.chooser(activity);
                JSONArray dayTokens = schedule == null ? null
                        : schedule.optJSONArray(String.valueOf(day));
                String value = "无";
                if (dayTokens != null && rowIndex < dayTokens.length()) {
                    value = dayTokens.optString(rowIndex, "无");
                }
                cell.setText(value);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Ui.showChoices(activity, cell, "选择课程", options);
                    }
                });
                row.cells.add(cell);
                line.addView(cell, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (dailyColumnOpen) {
                // ②每日：这一格表示"某天某节用什么形态"，默认表示跟随①全局
                final int lessonIndex = courseIndexAtRow(rowIndex);
                TextView dailyCell = Ui.chooser(activity);
                dailyCell.setTextSize(11);
                dailyCell.setText(Positions.labelOf(dailyStyleAt(dailyDay, lessonIndex)));
                dailyCell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        choosePosition(dailyCell, "周" + WEEK[dailyDay - 1]
                                + "·第" + (lessonIndex + 1) + "节（②每日）", true,
                                dailyDay, lessonIndex);
                    }
                });
                line.addView(dailyCell, new LinearLayout.LayoutParams(
                        Ui.dp(activity, 84), ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            root.addView(line);
        }

        root.addView(Ui.button(activity, "保存课表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSchedule();
            }
        }));
        content.addView(scroll);
    }

    private void showPeriodMenu(final int rowIndex) {
        final String[] items = {"添加课程行", "删除课程行", "添加分割线", "删除分割线"};
        new AlertDialog.Builder(activity)
                .setTitle("第 " + rows.get(rowIndex).period + " 节")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                addCourseRow(rowIndex);
                                break;
                            case 1:
                                deleteCourseRow(rowIndex);
                                break;
                            case 2:
                                addSeparator(rowIndex);
                                break;
                            default:
                                deleteSeparator(rowIndex);
                                break;
                        }
                    }
                })
                .show();
    }

    // -------------------------------------------------------- ②每日 / ③每课

    /** 界面行号（含分割线）→ 第几节课（0 起）；不是课程行时返回 -1。 */
    private int courseIndexAtRow(int rowIndex) {
        int lesson = -1;
        for (int i = 0; i <= rowIndex && i < rows.size(); i++) {
            if (!rows.get(i).separator) lesson++;
        }
        return rows.get(rowIndex).separator ? -1 : lesson;
    }

    private int lessonCount() {
        JSONArray starts = raw.optJSONArray("开始时间");
        return starts == null ? 0 : starts.length();
    }

    /** ②每日里某天某节的形态（"default" 表示跟随①全局）。 */
    private String dailyStyleAt(int day, int lesson) {
        if (lesson < 0) return Positions.DEFAULT;
        JSONObject table = raw.optJSONObject("每日日程");
        if (table == null) return Positions.DEFAULT;
        String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessonCount());
        return lesson < row.length ? row[lesson] : Positions.DEFAULT;
    }

    private String perLessonStyleAt(int day, int lesson) {
        if (lesson < 0) return Positions.DEFAULT;
        JSONObject table = raw.optJSONObject("单课日程");
        if (table == null) return Positions.DEFAULT;
        String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessonCount());
        return lesson < row.length ? row[lesson] : Positions.DEFAULT;
    }

    /** 列首点击：选择这一列显示周几。 */
    private void chooseDailyDay() {
        final String[] labels = new String[7];
        for (int i = 0; i < 7; i++) labels[i] = "周" + WEEK[i];
        new AlertDialog.Builder(activity)
                .setTitle("②每日：选择要编辑的星期")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dailyDay = which + 1;
                        dailyColumnOpen = true;
                        selectTab(0);
                    }
                })
                .show();
    }

    /**
     * 选形态。daily=true 写②每日，false 写③每课。
     * ③每课只在这里的文本入口里出现，不做图形编辑。
     */
    private void choosePosition(final TextView target, String title, final boolean daily,
                                final int day, final int lesson) {
        if (lesson < 0) return;
        final String[] values = Positions.STYLES_WITH_DEFAULT;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(Positions.STYLE_LABELS_WITH_DEFAULT,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String style = values[which];
                                setStyleInTable(daily ? "每日日程" : "单课日程",
                                        day, lesson, style);
                                target.setText(Positions.labelOf(style));
                            }
                        })
                .show();
    }

    /** 写入 config 的②每日或③每课，只动一格。 */
    private void setStyleInTable(String tableKey, int day, int lesson, String style) {
        try {
            JSONObject table = raw.optJSONObject(tableKey);
            if (table == null) {
                table = new JSONObject();
                raw.put(tableKey, table);
            }
            int lessons = lessonCount();
            String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessons);
            if (lesson >= row.length) return;
            row[lesson] = style;
            table.put(String.valueOf(day), Positions.writeRow(row, lessons));
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
        } catch (Exception e) {
            toast("保存位置失败：" + e.getMessage());
        }
    }

    /** ③每课：文本框整表编辑（不做图形界面）。 */
    private void showPerLessonEditor() {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setTextSize(11);
        input.setMinLines(8);
        input.setText(perLessonAsText());
        new AlertDialog.Builder(activity)
                .setTitle("③每课（每行一天，用逗号分隔）")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyPerLessonText(input.getText().toString());
                    }
                })
                .setNeutralButton("填入②每日", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (int day = 1; day <= 7; day++) {
                            for (int lesson = 0; lesson < lessonCount(); lesson++) {
                                setStyleInTableQuiet("单课日程", day, lesson,
                                        dailyStyleAt(day, lesson));
                            }
                        }
                        saveRawQuiet();
                        toast("已用②每日填充③每课");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String perLessonAsText() {
        StringBuilder sb = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            sb.append("周").append(WEEK[day - 1]).append(':');
            for (int lesson = 0; lesson < lessonCount(); lesson++) {
                if (lesson > 0) sb.append(',');
                sb.append(perLessonStyleAt(day, lesson));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void applyPerLessonText(String text) {
        try {
            String[] lines = text.split("\n");
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                int colon = value.indexOf(':');
                if (colon < 0) colon = value.indexOf('：');
                if (colon < 0) continue;
                String dayName = value.substring(0, colon).trim().replace("周", "");
                int day = -1;
                for (int i = 0; i < WEEK.length; i++) {
                    if (WEEK[i].equals(dayName)) day = i + 1;
                }
                if (day < 0) continue;
                String[] parts = value.substring(colon + 1).split("[,，]");
                int lessons = lessonCount();
                String[] row = new String[lessons];
                for (int i = 0; i < lessons; i++) {
                    row[i] = i < parts.length ? Positions.normalize(parts[i]) : Positions.DEFAULT;
                }
                JSONObject table = raw.optJSONObject("单课日程");
                if (table == null) {
                    table = new JSONObject();
                    raw.put("单课日程", table);
                }
                table.put(String.valueOf(day), Positions.writeRow(row, lessons));
            }
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
            toast("③每课已保存");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void setStyleInTableQuiet(String tableKey, int day, int lesson, String style) {
        try {
            JSONObject table = raw.optJSONObject(tableKey);
            if (table == null) {
                table = new JSONObject();
                raw.put(tableKey, table);
            }
            int lessons = lessonCount();
            String[] row = Positions.readRow(table.opt(String.valueOf(day)), lessons);
            if (lesson >= row.length) return;
            row[lesson] = style;
            table.put(String.valueOf(day), Positions.writeRow(row, lessons));
        } catch (Exception ignored) {
        }
    }

    private void saveRawQuiet() {
        try {
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
        } catch (Exception ignored) {
        }
    }

    /** 在指定课程行之后插入一行（课程或分割线），并重排节次编号。 */
    private void addCourseRow(int rowIndex) {
        Row row = new Row();
        row.separator = false;
        rows.add(rowIndex + 1, row);
        renumber();
        selectTab(0);
    }

    private void deleteCourseRow(int rowIndex) {
        if (rows.size() <= 1) {
            toast("至少保留一行");
            return;
        }
        rows.remove(rowIndex);
        renumber();
        selectTab(0);
    }

    private void addSeparator(int rowIndex) {
        Row row = new Row();
        row.separator = true;
        rows.add(rowIndex + 1, row);
        selectTab(0);
    }

    private void deleteSeparator(int rowIndex) {
        for (int i = rowIndex + 1; i < rows.size(); i++) {
            if (rows.get(i).separator) {
                rows.remove(i);
                selectTab(0);
                return;
            }
        }
        toast("下方没有分割线");
    }

    private void renumber() {
        int no = 0;
        for (Row row : rows) {
            if (!row.separator) row.period = String.valueOf(++no);
        }
    }

    private void saveSchedule() {
        try {
            JSONObject schedule = new JSONObject();
            for (int day = 1; day <= 7; day++) {
                JSONArray tokens = new JSONArray();
                for (Row row : rows) {
                    if (row.separator) {
                        tokens.put("|");
                    } else {
                        TextView cell = row.cells.size() >= day ? row.cells.get(day - 1) : null;
                        tokens.put(cell == null ? "无" : cell.getText().toString());
                    }
                }
                schedule.put(String.valueOf(day), tokens);
            }
            raw.put("日程表", schedule);
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
            toast("课表已保存");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 时间

    private void buildTimeTab() {
        loadTimes();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        for (int i = 0; i < starts.size(); i++) {
            final int index = i;
            LinearLayout row = Ui.row(activity);
            TextView label = Ui.label(activity, String.format(Locale.US, "%2d.  %s ~ %s",
                    i + 1, fmtPair(starts.get(i)), fmtPair(i < ends.size() ? ends.get(i) : null)));
            label.setTextSize(13);
            if (i == selectedTimeIndex) label.setTextColor(0xFF1565C0);
            row.addView(label);
            row.addView(Ui.button(activity, "选中", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedTimeIndex = index;
                    selectTab(1);
                }
            }));
            root.addView(row);
        }

        LinearLayout actions = Ui.row(activity);
        actions.addView(Ui.button(activity, "编辑上课", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTime(true);
            }
        }));
        actions.addView(Ui.button(activity, "编辑下课", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTime(false);
            }
        }));
        actions.addView(Ui.button(activity, "其后插入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertTime();
            }
        }));
        actions.addView(Ui.button(activity, "删除所选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteTime();
            }
        }));
        root.addView(actions);
        root.addView(Ui.button(activity, "保存时间", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTimes();
            }
        }));

        TextView hint = new TextView(activity);
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setText("插入/删除会成对作用于开始与结束时间，并在七天课表同一位置加入/移除一整行。\n"
                + "\"|\" 只作为分隔符，不计入课节序号。");
        root.addView(hint);
        content.addView(scroll);
    }

    private void loadTimes() {
        starts.clear();
        ends.clear();
        JSONArray s = raw.optJSONArray("开始时间");
        JSONArray e = raw.optJSONArray("结束时间");
        if (s != null) {
            for (int i = 0; i < s.length(); i++) starts.add(pairOf(s.optJSONArray(i)));
        }
        if (e != null) {
            for (int i = 0; i < e.length(); i++) ends.add(pairOf(e.optJSONArray(i)));
        }
        if (selectedTimeIndex >= starts.size()) selectedTimeIndex = -1;
    }

    /** 编辑所选那一节的上课或下课时间；保存后重建列表以便立即看到更新。 */
    private void editTime(final boolean startSide) {
        if (selectedTimeIndex < 0 || selectedTimeIndex >= starts.size()) {
            toast("请先选中一节");
            return;
        }
        final int index = selectedTimeIndex;
        int[] current = startSide
                ? starts.get(index)
                : (index < ends.size() ? ends.get(index) : new int[]{0, 0});
        askTime(startSide ? "编辑上课时间（HH:MM）" : "编辑下课时间（HH:MM）",
                fmtPair(current), new TimeSink() {
            @Override
            public void accept(int hour, int minute) {
                if (startSide) {
                    starts.set(index, new int[]{hour, minute});
                } else {
                    while (ends.size() <= index) ends.add(new int[]{hour, minute});
                    ends.set(index, new int[]{hour, minute});
                }
                saveTimes();
                selectTab(1);
            }
        });
    }

    private void insertTime() {
        if (selectedTimeIndex < 0 || selectedTimeIndex >= starts.size()) {
            toast("请先选中一节，新时间会插到它后面");
            return;
        }
        final int index = selectedTimeIndex;
        askTime("新的上课时间（HH:MM）", "08:00", new TimeSink() {
            @Override
            public void accept(final int hour, final int minute) {
                askTime("新的下课时间（HH:MM）", "08:40", new TimeSink() {
                    @Override
                    public void accept(int endHour, int endMinute) {
                        starts.add(index + 1, new int[]{hour, minute});
                        ends.add(Math.min(index + 1, ends.size()), new int[]{endHour, endMinute});
                        insertScheduleRow(index);
                        saveTimes();
                        selectedTimeIndex = index + 1;
                        selectTab(1);
                        toast("已插入第 " + (index + 1) + " 节之后，并加入一行\"无\"");
                    }
                });
            }
        });
    }

    private void deleteTime() {
        if (selectedTimeIndex < 0 || selectedTimeIndex >= starts.size()) {
            toast("请先选中要删除的一节");
            return;
        }
        final int index = selectedTimeIndex;
        new AlertDialog.Builder(activity)
                .setTitle("确认删除")
                .setMessage("将删除第 " + (index + 1) + " 节（" + fmtPair(starts.get(index)) + " ~ "
                        + fmtPair(index < ends.size() ? ends.get(index) : null) + "）\n\n七天对应课表：\n"
                        + lessonContext(index))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        starts.remove(index);
                        if (index < ends.size()) ends.remove(index);
                        deleteScheduleRow(index);
                        saveTimes();
                        selectedTimeIndex = -1;
                        selectTab(1);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 七天课表在第 index 节课位置插入一行"无"（不动分隔符）。 */
    private void insertScheduleRow(int lessonIndex) {
        try {
            JSONObject schedule = raw.optJSONObject("日程表");
            if (schedule == null) return;
            for (int day = 1; day <= 7; day++) {
                JSONArray tokens = schedule.optJSONArray(String.valueOf(day));
                if (tokens == null) continue;
                int pos = nthLessonPos(tokens, lessonIndex);
                JSONArray next = new JSONArray();
                for (int i = 0; i < tokens.length(); i++) {
                    next.put(tokens.opt(i));
                    if (i == pos) next.put("无");
                }
                schedule.put(String.valueOf(day), next);
            }
            raw.put("日程表", schedule);
        } catch (Exception ignored) {
        }
    }

    private void deleteScheduleRow(int lessonIndex) {
        try {
            JSONObject schedule = raw.optJSONObject("日程表");
            if (schedule == null) return;
            for (int day = 1; day <= 7; day++) {
                JSONArray tokens = schedule.optJSONArray(String.valueOf(day));
                if (tokens == null) continue;
                int pos = nthLessonPos(tokens, lessonIndex);
                if (pos < 0) continue;
                JSONArray next = new JSONArray();
                for (int i = 0; i < tokens.length(); i++) {
                    if (i != pos) next.put(tokens.opt(i));
                }
                schedule.put(String.valueOf(day), next);
            }
            raw.put("日程表", schedule);
        } catch (Exception ignored) {
        }
    }

    private int nthLessonPos(JSONArray tokens, int lessonIndex) {
        int count = 0;
        for (int i = 0; i < tokens.length(); i++) {
            if ("|".equals(tokens.optString(i))) continue;
            if (count == lessonIndex) return i;
            count++;
        }
        return tokens.length() - 1;
    }

    private String lessonContext(int lessonIndex) {
        StringBuilder sb = new StringBuilder();
        JSONObject schedule = raw.optJSONObject("日程表");
        for (int day = 1; day <= 7; day++) {
            JSONArray tokens = schedule == null ? null : schedule.optJSONArray(String.valueOf(day));
            String value = "无";
            if (tokens != null) {
                int pos = nthLessonPos(tokens, lessonIndex);
                if (pos >= 0 && pos < tokens.length()) value = tokens.optString(pos);
            }
            sb.append("周").append(WEEK[day - 1]).append("：").append(value).append('\n');
        }
        return sb.toString();
    }

    private void saveTimes() {
        try {
            JSONArray s = new JSONArray();
            for (int[] pair : starts) s.put(toArray(pair));
            JSONArray e = new JSONArray();
            for (int[] pair : ends) e.put(toArray(pair));
            raw.put("开始时间", s);
            raw.put("结束时间", e);
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
            toast("时间已保存");
        } catch (Exception ex) {
            toast("保存失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 参数

    private void buildParamTab() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        paramWidgets.clear();
        paramKeys.clear();
        for (String[] section : SECTIONS) {
            root.addView(Ui.title(activity, section[0]));
            for (int i = 1; i < section.length; i++) {
                String[] parts = section[i].split(":");
                String key = parts[0];
                String kind = parts[1];
                boolean wrapped = "1".equals(parts[2]);
                LinearLayout row = Ui.row(activity);
                row.addView(Ui.label(activity, key));
                paramKeys.add(key + "|" + kind + "|" + wrapped);
                if ("bool".equals(kind)) {
                    final TextView chooser = Ui.chooser(activity);
                    boolean value = key.startsWith("拖入")
                            ? dragDefault(key)
                            : boolValue(key);
                    chooser.setText(value ? "是" : "否");
                    chooser.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Ui.showChoices(activity, chooser, key,
                                    new ArrayList<>(java.util.Arrays.asList("否", "是")));
                        }
                    });
                    paramWidgets.add(chooser);
                    row.addView(chooser, Ui.wrap(activity));
                } else if ("style".equals(kind)) {
                    // ①全局：读"全局日程"，旧配置没有这一项时由旧的
                    // "上课/下课默认位置 + 使用secondStyle" 迁移过来
                    final boolean onClass = key.endsWith("上课");
                    final TextView chooser = Ui.chooser(activity);
                    chooser.setText(Positions.labelOf(globalStyle(onClass)));
                    chooser.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            new AlertDialog.Builder(activity)
                                    .setTitle("① 全局·" + (onClass ? "上课" : "下课"))
                                    .setItems(Positions.STYLE_LABELS,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int which) {
                                                    setGlobalStyle(onClass,
                                                            Positions.STYLES[which]);
                                                    chooser.setText(
                                                            Positions.STYLE_LABELS[which]);
                                                }
                                            })
                                    .show();
                        }
                    });
                    paramWidgets.add(chooser);
                    row.addView(chooser, Ui.wrap(activity));
                } else if ("dock".equals(kind)) {
                    final TextView chooser = Ui.chooser(activity);
                    chooser.setText(DOCK_LABELS[dockIndex(textValue(key, "upper"))]);
                    chooser.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Ui.showChoices(activity, chooser, key,
                                    new ArrayList<>(java.util.Arrays.asList(DOCK_LABELS)));
                        }
                    });
                    paramWidgets.add(chooser);
                    row.addView(chooser, Ui.wrap(activity));
                } else {
                    EditText input = new EditText(activity);
                    input.setInputType("number".equals(kind)
                            ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                            | InputType.TYPE_NUMBER_FLAG_SIGNED
                            : InputType.TYPE_CLASS_TEXT);
                    input.setText(rawValue(key));
                    paramWidgets.add(input);
                    row.addView(input, Ui.wrap(activity));
                }
                root.addView(row);
            }
        }
        root.addView(Ui.button(activity, "保存参数", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveParams();
            }
        }));
        root.addView(Ui.button(activity, "③每课（文本框整表编辑）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPerLessonEditor();
            }
        }));
        content.addView(scroll);
    }

    private boolean dragDefault(String key) {
        String dock = key.substring(2, key.indexOf("时"));
        JSONObject defaults = raw.optJSONObject("拖动默认样式");
        return defaults != null && defaults.optBoolean(dock, false);
    }

    /** ①全局当前值；没有"全局日程"时由旧的默认位置 + secondStyle 迁移。 */
    private String globalStyle(boolean onClass) {
        JSONObject global = raw.optJSONObject("全局日程");
        if (global != null && global.has(onClass ? "上课" : "下课")) {
            return Positions.normalize(global.opt(onClass ? "上课" : "下课"));
        }
        String dockKey = onClass ? "上课默认位置" : "下课默认位置";
        String styleKey = onClass ? "上课默认使用secondStyle" : "下课默认使用secondStyle";
        String dock = textValue(dockKey, "upper");
        return Positions.fromLegacy(dock, boolValue(styleKey));
    }

    /** 写①全局，同时同步旧键，保证 Python 版与旧版仍能读到同样的语义。 */
    private void setGlobalStyle(boolean onClass, String style) {
        try {
            JSONObject global = raw.optJSONObject("全局日程");
            if (global == null) {
                global = new JSONObject();
                raw.put("全局日程", global);
            }
            global.put(onClass ? "上课" : "下课", style);
            raw.put(onClass ? "上课默认位置" : "下课默认位置",
                    new JSONArray().put(Positions.dockOf(style)));
            raw.put(onClass ? "上课默认使用secondStyle" : "下课默认使用secondStyle",
                    Positions.secondStyleOf(style));
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
        } catch (Exception e) {
            toast("保存全局位置失败：" + e.getMessage());
        }
    }

    private boolean boolValue(String key) {
        Object value = raw.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() == 0) return false;
            Object first = array.opt(0);
            if (first instanceof Boolean) return (Boolean) first;
            return array.optInt(0, 0) != 0;
        }
        return false;
    }

    private String textValue(String key, String def) {
        Object value = raw.opt(key);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            return array.length() > 0 ? array.optString(0, def) : def;
        }
        return value == null ? def : String.valueOf(value);
    }

    private String rawValue(String key) {
        Object value = raw.opt(key);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            return array.length() > 0 ? array.opt(0).toString() : "";
        }
        return value == null ? "" : String.valueOf(value);
    }

    private int dockIndex(String dock) {
        String normalized = dock;
        for (int i = 0; i < DOCK_KEYS_LEGACY.length; i++) {
            if (DOCK_KEYS_LEGACY[i].equals(dock)) {
                normalized = i < 4 ? DOCK_KEYS[i] : "upper";
                break;
            }
        }
        for (int i = 0; i < DOCK_KEYS.length; i++) {
            if (DOCK_KEYS[i].equals(normalized)) return i;
        }
        for (int i = 0; i < DOCK_LABELS.length; i++) {
            if (DOCK_LABELS[i].equals(dock)) return i;
        }
        return 1;
    }

    private void saveParams() {
        try {
            for (int i = 0; i < paramKeys.size(); i++) {
                String[] parts = paramKeys.get(i).split("\\|");
                String key = parts[0];
                String kind = parts[1];
                boolean wrapped = "1".equals(parts[2]);
                Object widget = paramWidgets.get(i);
                if ("style".equals(kind)) {
                    // ①全局在点击时就写盘了（并同步旧键），这里不需要再处理
                    continue;
                }
                if ("bool".equals(kind)) {
                    boolean value = "是".equals(((TextView) widget).getText().toString());
                    if (key.startsWith("拖入")) {
                        String dock = key.substring(2, key.indexOf("时"));
                        JSONObject defaults = raw.optJSONObject("拖动默认样式");
                        if (defaults == null) {
                            defaults = new JSONObject();
                            raw.put("拖动默认样式", defaults);
                        }
                        defaults.put(dock, value);
                    } else if (key.endsWith("使用secondStyle")) {
                        raw.put(key, value);
                    } else {
                        raw.put(key, new JSONArray().put(value ? 1 : 0));
                    }
                } else if ("dock".equals(kind)) {
                    String text = ((TextView) widget).getText().toString();
                    int index = 1;
                    for (int k = 0; k < DOCK_LABELS.length; k++) {
                        if (DOCK_LABELS[k].equals(text)) index = k;
                    }
                    raw.put(key, new JSONArray().put(DOCK_KEYS[index]));
                } else {
                    String text = ((EditText) widget).getText().toString().trim();
                    if ("number".equals(kind)) {
                        double value;
                        try {
                            value = Double.parseDouble(text);
                        } catch (Exception e) {
                            value = 0;
                        }
                        if (value == Math.floor(value)) raw.put(key, new JSONArray().put((int) value));
                        else raw.put(key, new JSONArray().put(value));
                    } else {
                        raw.put(key, new JSONArray().put(text));
                    }
                }
            }
            // 需求确认：拖入区域的默认样式不再参与三层体系，保存时直接删掉
            raw.remove("拖动默认样式");
            for (String dock : new String[]{"left", "upper", "right", "center"}) {
                raw.remove("拖入" + dock + "时使用secondStyle");
            }
            ConfigRepository.save(activity, raw);
            OverlayService.refresh(activity);
            toast("参数已保存");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 对调

    private void buildSwapTab() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final Calendar today = Calendar.getInstance();
        final int[][] dates = {
                {today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)},
                {today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)},
        };
        final RadioGroup[] groups = new RadioGroup[2];
        final String[] selection = new String[2];

        for (int side = 0; side < 2; side++) {
            root.addView(Ui.title(activity, side == 0 ? "左侧日期" : "右侧日期"));
            LinearLayout dateRow = Ui.row(activity);
            final int index = side;
            final RadioGroup group = new RadioGroup(activity);
            group.setOrientation(RadioGroup.VERTICAL);
            groups[side] = group;
            final Runnable reload = new Runnable() {
                @Override
                public void run() {
                    group.removeAllViews();
                    selection[index] = null;
                    fillCourses(group, dates[index], selection, index);
                }
            };
            dateRow.addView(dateChooser(dates[side], 0, today.get(Calendar.YEAR) - 1,
                    today.get(Calendar.YEAR) + 3, reload), Ui.wrap(activity));
            dateRow.addView(dateChooser(dates[side], 1, 1, 12, reload), Ui.wrap(activity));
            dateRow.addView(dateChooser(dates[side], 2, 1, 31, reload), Ui.wrap(activity));
            root.addView(dateRow);
            root.addView(group);
            reload.run();
        }

        root.addView(Ui.button(activity, "对调课程", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swap(dates[0], dates[1], selection[0], selection[1]);
            }
        }));
        content.addView(scroll);
    }

    /** 日期选择：点击弹出可选值，改变后回调刷新课程列表。 */
    private TextView dateChooser(final int[] date, final int field, int from, int to,
                                 final Runnable onChange) {
        final TextView view = Ui.chooser(activity);
        view.setText(String.valueOf(date[field]));
        final List<String> values = new ArrayList<>();
        for (int i = from; i <= to; i++) values.add(String.valueOf(i));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(activity)
                        .setTitle(field == 0 ? "年" : field == 1 ? "月" : "日")
                        .setItems(values.toArray(new String[0]),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        date[field] = Integer.parseInt(values.get(which));
                                        view.setText(values.get(which));
                                        onChange.run();
                                    }
                                })
                        .show();
            }
        });
        return view;
    }

    private void fillCourses(RadioGroup group, int[] date, final String[] selection, final int side) {
        group.removeAllViews();
        List<String> tokens = tokensForDate(date);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (isSpecial(token)) continue;
            RadioButton button = new RadioButton(activity);
            button.setText(token);
            button.setTag(String.valueOf(i));
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selection[side] = String.valueOf(v.getTag());
                }
            });
            group.addView(button);
        }
        RadioButton all = new RadioButton(activity);
        all.setText("全部");
        all.setTag("all");
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selection[side] = "all";
            }
        });
        group.addView(all);
    }

    private List<String> tokensForDate(int[] date) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(date[0], Math.max(0, date[1] - 1), date[2]);
        String dateString = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
        List<String> tokens = new ArrayList<>();
        JSONArray record = DayChangeRepository.tokensForDate(activity, dateString, expectedTokens());
        if (record != null) {
            for (int k = 0; k < record.length(); k++) tokens.add(record.optString(k));
            return tokens;
        }
        int iso = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
        tokens.add("周");
        tokens.add(WEEK[iso - 1]);
        tokens.add("|");
        JSONObject schedule = raw.optJSONObject("日程表");
        JSONArray row = schedule == null ? null : schedule.optJSONArray(String.valueOf(iso));
        if (row != null) {
            for (int k = 0; k < row.length(); k++) tokens.add(row.optString(k));
        }
        return tokens;
    }

    private int expectedTokens() {
        JSONArray startsArray = raw.optJSONArray("开始时间");
        return (startsArray == null ? 0 : startsArray.length()) + 2;
    }

    private boolean isSpecial(String token) {
        if (token == null) return true;
        if ("周".equals(token) || "|".equals(token)) return true;
        for (String week : WEEK) {
            if (week.equals(token)) return true;
        }
        return false;
    }

    private void swap(int[] leftDate, int[] rightDate, String leftSelection, String rightSelection) {
        try {
            if (leftSelection == null || rightSelection == null) {
                toast("请左右两侧都选择课程");
                return;
            }
            boolean leftAll = "all".equals(leftSelection);
            boolean rightAll = "all".equals(rightSelection);
            if (leftAll != rightAll) {
                toast("不能一边选\"全部\"一边选具体课程");
                return;
            }
            List<String> leftTokens = new ArrayList<>(tokensForDate(leftDate));
            List<String> rightTokens = new ArrayList<>(tokensForDate(rightDate));
            if (leftAll) {
                List<String> temp = leftTokens;
                leftTokens = rightTokens;
                rightTokens = temp;
            } else {
                int l = Integer.parseInt(leftSelection);
                int r = Integer.parseInt(rightSelection);
                if (l < 0 || l >= leftTokens.size() || r < 0 || r >= rightTokens.size()) {
                    toast("课程下标越界，请重新选择");
                    return;
                }
                String temp = leftTokens.get(l);
                leftTokens.set(l, rightTokens.get(r));
                rightTokens.set(r, temp);
            }
            saveDay(leftDate, leftTokens);
            saveDay(rightDate, rightTokens);
            OverlayService.refresh(activity);
            toast("已对调");
            selectTab(3);
        } catch (Exception e) {
            toast("对调失败：" + e.getMessage());
        }
    }

    private void saveDay(int[] date, List<String> tokens) throws Exception {
        Calendar calendar = Calendar.getInstance();
        calendar.set(date[0], date[1] - 1, date[2]);
        String dateString = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
        JSONArray array = new JSONArray();
        for (String token : tokens) array.put(token);
        DayChangeRepository.upsert(activity, dateString, array);
    }

    // ------------------------------------------------------------ 工具

    private interface TimeSink {
        void accept(int hour, int minute);
    }

    private void askTime(String title, String initial, final TimeSink sink) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_DATETIME);
        input.setText(initial);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String[] parts = input.getText().toString().trim().split(":");
                        if (parts.length != 2) {
                            toast("时间格式应为 HH:MM");
                            return;
                        }
                        try {
                            sink.accept(Integer.parseInt(parts[0].trim()),
                                    Integer.parseInt(parts[1].trim()));
                        } catch (Exception e) {
                            toast("时间格式应为 HH:MM");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int[] pairOf(JSONArray pair) {
        if (pair == null || pair.length() < 2) return new int[]{0, 0};
        return new int[]{pair.optInt(0), pair.optInt(1)};
    }

    private JSONArray toArray(int[] pair) {
        JSONArray array = new JSONArray();
        array.put(pair[0]);
        array.put(pair[1]);
        return array;
    }

    private String fmtPair(int[] pair) {
        if (pair == null) return "--:--";
        return String.format(Locale.US, "%02d:%02d", pair[0], pair[1]);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /** 目前 edit 页不处理系统文件窗口；保留接口以便后续扩展。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }
}
