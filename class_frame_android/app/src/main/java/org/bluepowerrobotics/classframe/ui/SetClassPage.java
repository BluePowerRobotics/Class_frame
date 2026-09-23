package org.bluepowerrobotics.classframe.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.bluepowerrobotics.classframe.data.ClassTemplateRepository;
import org.bluepowerrobotics.classframe.data.ConfigRepository;
import org.bluepowerrobotics.classframe.data.Logs;
import org.bluepowerrobotics.classframe.data.Positions;
import org.bluepowerrobotics.classframe.overlay.OverlayService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** setclass 页：班级模板列表、预览、应用配置，以及下拉里的"从文件导入"。 */
public class SetClassPage implements MainActivity.Page {

    private static final int REQ_IMPORT_CLASS = 201;
    private static final String IMPORT_ENTRY = "＋ 从文件导入…";

    private final MainActivity activity;
    private ScrollView rootView;
    private TextView spinner;
    private TextView preview;
    private final List<String> names = new ArrayList<>();
    private boolean suppressCallback;
    private JSONObject currentTemplate;

    public SetClassPage(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public View getView() {
        if (rootView != null) return rootView;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F7F7);
        int pad = Ui.dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(Ui.title(activity, "选择班级配置"));

        spinner = Ui.chooser(activity);
        spinner.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        spinner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<String> entries = new ArrayList<>(names);
                entries.add(IMPORT_ENTRY);
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("选择班级")
                        .setItems(entries.toArray(new String[0]),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        if (which >= names.size()) {
                                            importClassFile();
                                            return;
                                        }
                                        String name = names.get(which);
                                        spinner.setText(name);
                                        loadTemplate(name);
                                    }
                                })
                        .show();
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = Ui.row(activity);
        actions.addView(Ui.button(activity, "刷新列表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshList();
            }
        }));
        actions.addView(Ui.button(activity, "应用配置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyConfig();
            }
        }));
        root.addView(actions);

        root.addView(Ui.title(activity, "配置预览"));
        preview = new TextView(activity);
        preview.setTextSize(13);
        preview.setTextColor(Color.DKGRAY);
        root.addView(preview);

        rootView = scroll;
        refreshList();
        return rootView;
    }

    @Override
    public void onShow() {
        refreshList();
    }

    private void refreshList() {
        names.clear();
        names.addAll(ClassTemplateRepository.listNames(activity));
        suppressCallback = true;
        spinner.setText(names.isEmpty() ? "（无班级）" : names.get(0));
        suppressCallback = false;
        if (!names.isEmpty()) {
            loadTemplate(names.get(0));
        } else {
            currentTemplate = null;
            preview.setText("classes_frame 目录下没有班级 json，可从下拉最后一项导入。");
        }
    }

    private void loadTemplate(String name) {
        try {
            currentTemplate = ClassTemplateRepository.read(activity, name);
            preview.setText(buildPreview(name, currentTemplate));
        } catch (Exception e) {
            currentTemplate = null;
            preview.setText("读取失败：" + e.getMessage());
        }
    }

    private String buildPreview(String name, JSONObject template) {
        StringBuilder sb = new StringBuilder();
        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        JSONObject schedule = template.optJSONObject("日程表");
        sb.append("=== ").append(name).append(" ===\n");
        for (int day = 1; day <= 7; day++) {
            JSONArray row = schedule == null ? null : schedule.optJSONArray(String.valueOf(day));
            sb.append(week[day - 1]).append(": ").append(row == null ? "(缺失)" : join(row)).append('\n');
        }
        JSONArray options = template.optJSONArray("更换选项");
        if (options != null) {
            sb.append("\n更换选项: ").append(join(options)).append('\n');
        }
        JSONArray starts = template.optJSONArray("开始时间");
        JSONArray ends = template.optJSONArray("结束时间");
        if (starts != null && ends != null) {
            sb.append("\n时间:\n");
            int n = Math.min(starts.length(), ends.length());
            for (int i = 0; i < n; i++) {
                sb.append("  ").append(fmt(starts.optJSONArray(i)))
                        .append(" - ").append(fmt(ends.optJSONArray(i))).append('\n');
            }
        } else {
            sb.append("\n(该班级文件未包含时间，将沿用现有时间并自动按课节数增删)\n");
        }
        return sb.toString();
    }

    /** 对应 setClass.py 的 apply_config：以课表为准校验课节数并同步时间。 */
    private void applyConfig() {
        if (currentTemplate == null) {
            toast("请先选择班级");
            return;
        }
        try {
            JSONObject config = new JSONObject(
                    ConfigRepository.readUtf8(ConfigRepository.configFile(activity)));
            JSONObject templateSchedule = currentTemplate.optJSONObject("日程表");
            if (templateSchedule == null) throw new IllegalStateException("班级文件缺少 日程表");
            int target = validateSchedule(templateSchedule);

            JSONArray starts = currentTemplate.optJSONArray("开始时间");
            JSONArray ends = currentTemplate.optJSONArray("结束时间");
            if (starts == null || ends == null) {
                starts = config.optJSONArray("开始时间");
                ends = config.optJSONArray("结束时间");
            }
            int oldCount = Math.min(starts == null ? 0 : starts.length(),
                    ends == null ? 0 : ends.length());
            JSONArray[] adjusted = adjustTimes(starts, ends, target);

            config.put("日程表", templateSchedule);
            JSONArray options = currentTemplate.optJSONArray("更换选项");
            if (options != null) config.put("更换选项", options);
            config.put("开始时间", adjusted[0]);
            config.put("结束时间", adjusted[1]);
            applyPositionTables(config, target);
            ConfigRepository.save(activity, config);
            OverlayService.refresh(activity);
            toast(oldCount == target
                    ? "已应用班级配置"
                    : "已应用：课节 " + oldCount + " → " + target + "，时间已自动增删");
        } catch (Exception e) {
            toast("应用失败：" + e.getMessage());
        }
    }

    /**
     * 把班级模板里的三层位置表并进配置。
     *
     * 班级文件缺这三张表时沿用现有配置；行长度按新的课节数对齐
     * （截断或补 "default"），避免出现"表比课节数长/短"的错位。
     */
    private void applyPositionTables(JSONObject config, int lessons) throws Exception {
        Object daily = currentTemplate.opt("每日日程");
        if (daily != null) {
            config.put("每日日程",
                    Positions.writeDaily(Positions.readDaily(daily, lessons), lessons));
        }
        JSONObject perLesson = currentTemplate.optJSONObject("单课日程");
        if (perLesson != null) {
            JSONObject target = new JSONObject();
            for (int day = 1; day <= 7; day++) {
                String[] row = Positions.readRow(perLesson.opt(String.valueOf(day)), lessons);
                try {
                    target.put(String.valueOf(day), Positions.writeRow(row, lessons));
                } catch (Exception ignored) {
                }
            }
            config.put("单课日程", target);
        }
        JSONObject global = currentTemplate.optJSONObject("全局日程");
        if (global != null) {
            String on = Positions.normalize(global.opt("上课"));
            String off = Positions.normalize(global.opt("下课"));
            JSONObject target = new JSONObject();
            try {
                target.put("上课", Positions.isConcrete(on) ? on : Positions.UPPER_TABLE);
                target.put("下课", Positions.isConcrete(off) ? off : Positions.UPPER_TABLE);
            } catch (Exception ignored) {
            }
            config.put("全局日程", target);
            // 与旧键保持同步，旧版 Python 与旧数据仍能读懂
            config.put("上课默认位置", new JSONArray().put(Positions.dockOf(on)));
            config.put("下课默认位置", new JSONArray().put(Positions.dockOf(off)));
            config.put("上课默认使用secondStyle", Positions.secondStyleOf(on));
            config.put("下课默认使用secondStyle", Positions.secondStyleOf(off));
        }
        Logs.i("SetClass", "已应用班级位置表，课节数 " + lessons);
    }

    private int validateSchedule(JSONObject schedule) throws Exception {
        Integer count = null;
        for (int day = 1; day <= 7; day++) {
            JSONArray row = schedule.optJSONArray(String.valueOf(day));
            if (row == null) throw new IllegalStateException("缺少第 " + day + " 天的课表");
            int n = 0;
            for (int i = 0; i < row.length(); i++) {
                Object token = row.opt(i);
                if (token != null && !"|".equals(String.valueOf(token))) n++;
            }
            if (count == null) count = n;
            else if (count != n) throw new IllegalStateException("各天实际课节数不一致");
        }
        return count == null ? 0 : count;
    }

    /** 时间对多了删末尾，少了按"休息 10 分钟 + 上课 40 分钟"补齐。 */
    private JSONArray[] adjustTimes(JSONArray startsIn, JSONArray endsIn, int target) throws Exception {
        List<int[]> s = toPairs(startsIn);
        List<int[]> e = toPairs(endsIn);
        while (s.size() > target && !s.isEmpty()) {
            s.remove(s.size() - 1);
            if (!e.isEmpty()) e.remove(e.size() - 1);
        }
        if (s.isEmpty() && target > 0) {
            s.add(new int[]{7, 10});
            e.add(new int[]{7, 50});
        }
        while (s.size() < target) {
            int lastEnd = e.isEmpty() ? 7 * 60 + 50 : e.get(e.size() - 1)[0] * 60 + e.get(e.size() - 1)[1];
            int start = lastEnd + 10;
            s.add(new int[]{start / 60, start % 60});
            int end = start + 40;
            e.add(new int[]{end / 60, end % 60});
        }
        return new JSONArray[]{toArray(s), toArray(e)};
    }

    private List<int[]> toPairs(JSONArray array) {
        List<int[]> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONArray pair = array.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            list.add(new int[]{pair.optInt(0), pair.optInt(1)});
        }
        return list;
    }

    private JSONArray toArray(List<int[]> pairs) {
        JSONArray array = new JSONArray();
        for (int[] pair : pairs) {
            JSONArray item = new JSONArray();
            item.put(pair[0]);
            item.put(pair[1]);
            array.put(item);
        }
        return array;
    }

    private void importClassFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQ_IMPORT_CLASS);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT_CLASS || resultCode != Activity.RESULT_OK || data == null) {
            return false;
        }
        Uri uri = data.getData();
        if (uri == null) return false;
        try {
            String name = displayName(uri);
            if (!name.toLowerCase().endsWith(".json")) name = name + ".json";
            File target = new File(ConfigRepository.classDir(activity), name);
            InputStream in = activity.getContentResolver().openInputStream(uri);
            FileOutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            out.close();
            in.close();
            new JSONObject(ConfigRepository.readUtf8(target));   // 校验 JSON 合法性
            refreshList();
            toast("已导入班级文件：" + name);
        } catch (Exception e) {
            toast("导入失败（需要合法的 JSON）：" + e.getMessage());
        }
        return true;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = activity.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "class.json";
        }
        return name;
    }

    private String fmt(JSONArray pair) {
        if (pair == null || pair.length() < 2) return "--:--";
        return String.format(java.util.Locale.US, "%02d:%02d", pair.optInt(0), pair.optInt(1));
    }

    private String join(JSONArray array) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(array.opt(i));
        }
        return sb.toString();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
