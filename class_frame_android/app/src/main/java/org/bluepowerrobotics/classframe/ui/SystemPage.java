package org.bluepowerrobotics.classframe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.bluepowerrobotics.classframe.data.ClassTemplateRepository;
import org.bluepowerrobotics.classframe.data.Config;
import org.bluepowerrobotics.classframe.data.ConfigRepository;
import org.bluepowerrobotics.classframe.data.FontRepository;
import org.bluepowerrobotics.classframe.data.Prefs;
import org.bluepowerrobotics.classframe.overlay.OverlayController;
import org.bluepowerrobotics.classframe.overlay.OverlayService;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** system 页：权限、开关、字体、帧率、导入导出与自检。 */
public class SystemPage implements MainActivity.Page {

    private static final int REQ_IMPORT_CONFIG = 101;
    private static final int REQ_EXPORT_CONFIG = 102;
    private static final int REQ_IMPORT_FONT = 103;

    private final MainActivity activity;
    private ScrollView rootView;
    private TextView overlayStatus;
    private TextView batteryStatus;
    private TextView alarmStatus;
    private TextView fontValue;
    private TextView weightLabel;
    private TextView fontPreview;
    private android.widget.SeekBar weightSeek;
    private TextView fpsValue;
    private TextView holdValue;
    private TextView logicValue;
    private TextView selfCheck;
    private Switch bootSwitch;
    private Switch overlaySwitch;

    public SystemPage(MainActivity activity) {
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

        root.addView(Ui.title(activity, "权限"));

        LinearLayout overlayRow = Ui.row(activity);
        overlayStatus = Ui.label(activity, "");
        overlayRow.addView(overlayStatus);
        overlayRow.addView(Ui.button(activity, "去授予", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        }));
        root.addView(overlayRow);

        LinearLayout batteryRow = Ui.row(activity);
        batteryStatus = Ui.label(activity, "");
        batteryRow.addView(batteryStatus);
        batteryRow.addView(Ui.button(activity, "去授予", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        }));
        root.addView(batteryRow);

        LinearLayout alarmRow = Ui.row(activity);
        alarmStatus = Ui.label(activity, "");
        alarmRow.addView(alarmStatus);
        alarmRow.addView(Ui.button(activity, "去授予", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestExactAlarm();
            }
        }));
        root.addView(alarmRow);

        root.addView(Ui.title(activity, "悬浮层"));

        overlaySwitch = new Switch(activity);
        overlaySwitch.setText("启用悬浮窗");
        overlaySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setOverlayEnabled(activity, isChecked);
                if (isChecked) OverlayService.start(activity);
                else OverlayService.stop(activity);
                refresh();
            }
        });
        root.addView(overlaySwitch);

        bootSwitch = new Switch(activity);
        bootSwitch.setText("启用开机自启");
        bootSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setBootAutostart(activity, isChecked);
                refresh();
            }
        });
        root.addView(bootSwitch);

        LinearLayout actions = Ui.row(activity);
        actions.addView(Ui.button(activity, "显示悬浮层", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(activity)) {
                    toast("请先授予「显示在其他应用上方」");
                    openOverlaySettings();
                    return;
                }
                OverlayService.start(activity);
            }
        }));
        actions.addView(Ui.button(activity, "移除悬浮层", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService.hideOverlay(activity);
            }
        }));
        actions.addView(Ui.button(activity, "刷新课表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService.refresh(activity);
                refresh();
            }
        }));
        root.addView(actions);

        root.addView(Ui.title(activity, "显示"));

        LinearLayout fontRow = Ui.row(activity);
        fontValue = Ui.label(activity, "");
        fontRow.addView(fontValue);
        fontRow.addView(Ui.button(activity, "选择字体", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseFont();
            }
        }));
        root.addView(fontRow, Ui.block(activity));
        root.addView(Ui.button(activity, "导入字体文件（ttf/otf）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFont();
            }
        }), Ui.block(activity));

        // 字重：可变字体走 wght 轴（连续），静态字体退化为常规/加粗
        weightLabel = new TextView(activity);
        weightLabel.setTextSize(14);
        weightLabel.setTextColor(Color.BLACK);
        weightLabel.setText("字重：400（100–900，可变字体连续生效）");
        root.addView(weightLabel, Ui.block(activity));
        weightSeek = new android.widget.SeekBar(activity);
        weightSeek.setMax(16);   // 100..900，步进 50
        weightSeek.setProgress((Prefs.fontWeight(activity) - 100) / 50);
        weightSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                updateFontPreview(100 + progress * 50);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                Prefs.setFontWeight(activity, 100 + seekBar.getProgress() * 50);
                OverlayService.refresh(activity);
                refresh();
            }
        });
        root.addView(weightSeek, Ui.block(activity));
        fontPreview = new TextView(activity);
        fontPreview.setText("周一|语数英 化学限时");
        fontPreview.setTextSize(20);
        fontPreview.setTextColor(Color.BLACK);
        fontPreview.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
        root.addView(fontPreview, Ui.block(activity));

        // 行高倍数：字体自身的行高在不同字体/平台上差异很大，这里给一个统一控制
        final TextView lineHeightLabel = new TextView(activity);
        lineHeightLabel.setTextSize(14);
        lineHeightLabel.setTextColor(Color.BLACK);
        lineHeightLabel.setText(String.format(java.util.Locale.US,
                "行高：%.1f× 字体自身行高（0.6–2.2）", Prefs.lineHeightScale(activity)));
        root.addView(lineHeightLabel, Ui.block(activity));
        android.widget.SeekBar lineHeightSeek = new android.widget.SeekBar(activity);
        lineHeightSeek.setMax(16);   // 0.6 .. 2.2，步进 0.1
        lineHeightSeek.setProgress(Math.round((Prefs.lineHeightScale(activity) - 0.6f) * 10f));
        lineHeightSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                lineHeightLabel.setText(String.format(java.util.Locale.US,
                        "行高：%.1f× 字体自身行高（0.6–2.2）", 0.6f + progress * 0.1f));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                Prefs.setLineHeightScale(activity, 0.6f + seekBar.getProgress() * 0.1f);
                OverlayService.refresh(activity);
            }
        });
        root.addView(lineHeightSeek, Ui.block(activity));

        LinearLayout fpsRow = Ui.row(activity);
        fpsValue = Ui.label(activity, "");
        fpsRow.addView(fpsValue);
        fpsRow.addView(Ui.button(activity, "选择", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseMaxFps();
            }
        }));
        root.addView(fpsRow);

        LinearLayout holdRow = Ui.row(activity);
        holdValue = Ui.label(activity, "");
        holdRow.addView(holdValue);
        holdRow.addView(Ui.button(activity, "设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputNumber("交互后保持高帧率（毫秒）", Prefs.interactHoldMs(activity), 0, 5000,
                        new NumberSink() {
                            @Override
                            public void accept(int value) {
                                Prefs.setInteractHoldMs(activity, value);
                                OverlayService.refresh(activity);
                                refresh();
                            }
                        });
            }
        }));
        root.addView(holdRow);

        LinearLayout logicRow = Ui.row(activity);
        logicValue = Ui.label(activity, "");
        logicRow.addView(logicValue);
        logicRow.addView(Ui.button(activity, "设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputNumber("逻辑帧率（动画期间，1-60）", Prefs.logicFps(activity), 1, 60,
                        new NumberSink() {
                            @Override
                            public void accept(int value) {
                                Prefs.setLogicFps(activity, value);
                                refresh();
                            }
                        });
            }
        }));
        root.addView(logicRow);

        root.addView(Ui.title(activity, "配置"));
        root.addView(Ui.button(activity, "导入 config.json", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importConfig();
            }
        }));
        root.addView(Ui.button(activity, "导出 config.json", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportConfig();
            }
        }));

        root.addView(Ui.title(activity, "自检"));
        selfCheck = new TextView(activity);
        selfCheck.setTextSize(12);
        selfCheck.setTextColor(Color.DKGRAY);
        root.addView(selfCheck);

        rootView = scroll;
        refresh();
        return rootView;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        if (overlayStatus == null) return;
        boolean canDraw = Settings.canDrawOverlays(activity);
        overlayStatus.setText(canDraw ? "显示在其他应用上方：已授予" : "显示在其他应用上方：未授予");
        boolean ignoring = isIgnoringBatteryOptimizations();
        batteryStatus.setText(ignoring ? "电池优化豁免：已授予" : "电池优化豁免：未授予");
        boolean exactOk = canScheduleExactAlarms();
        alarmStatus.setText(exactOk ? "闹钟与提醒（精确闹钟）：已授予" : "闹钟与提醒（精确闹钟）：未授予");
        overlaySwitch.setChecked(Prefs.overlayEnabled(activity));
        bootSwitch.setChecked(Prefs.bootAutostart(activity));
        fontValue.setText("使用字体：" + Prefs.fontFamily(activity));
        if (weightLabel != null) weightLabel.setText("字重：" + Prefs.fontWeight(activity)
                + "（100–900，可变字体连续生效）");
        updateFontPreview(Prefs.fontWeight(activity));
        fpsValue.setText("最高帧率（交互）：" + Prefs.maxFps(activity) + " fps");
        holdValue.setText("交互后保持高帧率：" + Prefs.interactHoldMs(activity) + " ms");
        logicValue.setText("逻辑帧率（动画期间）：" + Prefs.logicFps(activity) + " fps");
        selfCheck.setText(buildSelfCheck(canDraw, ignoring));
    }

    private String buildSelfCheck(boolean canDraw, boolean ignoring) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据目录: ").append(activity.getFilesDir().getAbsolutePath()).append('\n');
        sb.append("悬浮层: ").append(OverlayController.get(activity).isShown() ? "显示中" : "未显示").append('\n');
        sb.append("悬浮权限: ").append(canDraw ? "已授予" : "未授予").append('\n');
        sb.append("电池优化: ").append(ignoring ? "已豁免" : "未豁免").append('\n');
        sb.append("开机自启: ").append(Prefs.bootAutostart(activity) ? "开" : "关").append('\n');
        try {
            Config config = ConfigRepository.load(activity);
            Calendar now = Calendar.getInstance();
            now.add(Calendar.SECOND, config.timeOffsetSeconds);
            int iso = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
            List<String> tokens = config.tokensForDay(iso);
            sb.append('\n').append("—— config.json ——").append('\n');
            sb.append("课节数: ").append(config.starts.size())
                    .append("  文字大小: ").append(config.textSize)
                    .append("  进度条宽: ").append(config.progressWidth).append('\n');
            sb.append("上课默认: ").append(config.onDefaultDock)
                    .append(config.onDefaultSecondStyle ? "(第二样式)" : "(文字样式)")
                    .append("  下课默认: ").append(config.offDefaultDock)
                    .append(config.offDefaultSecondStyle ? "(第二样式)" : "(文字样式)").append('\n');
            sb.append("时间偏移: ").append(config.timeOffsetSeconds).append(" 秒\n");
            sb.append("今日 token(").append(tokens.size()).append("): ").append(join(tokens)).append('\n');
        } catch (Exception e) {
            sb.append("\nconfig.json 解析失败: ").append(e.getMessage()).append('\n');
        }
        List<String> names = ClassTemplateRepository.listNames(activity);
        sb.append("\n班级模板: ").append(names.size()).append(' ').append(names);
        return sb.toString();
    }

    // -------------------------------------------------------- 权限

    private void openOverlaySettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            toast("无法打开悬浮权限设置");
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(activity.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            toast("无法打开电池优化设置");
        }
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try {
            android.app.AlarmManager manager =
                    (android.app.AlarmManager) activity.getSystemService(Activity.ALARM_SERVICE);
            return manager != null && manager.canScheduleExactAlarms();
        } catch (Exception e) {
            return true;
        }
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("当前系统版本无需授予");
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            toast("无法打开闹钟与提醒设置");
        }
    }

    // -------------------------------------------------------- 字体与帧率

    private void chooseFont() {
        final List<String> fonts = FontRepository.listFonts(activity);
        new AlertDialog.Builder(activity)
                .setTitle("使用字体")
                .setItems(fonts.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Prefs.setFontFamily(activity, fonts.get(which));
                        OverlayService.refresh(activity);
                        refresh();
                    }
                })
                .show();
    }

    private void importFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQ_IMPORT_FONT);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    private void chooseMaxFps() {
        final int[] values = {30, 60};
        String[] labels = {"30 fps", "60 fps"};
        new AlertDialog.Builder(activity)
                .setTitle("最高帧率（交互期间）")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Prefs.setMaxFps(activity, values[which]);
                        refresh();
                    }
                })
                .show();
    }

    private interface NumberSink {
        void accept(int value);
    }

    private void inputNumber(String title, int current, int min, int max, final NumberSink sink) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            int value = Integer.parseInt(input.getText().toString().trim());
                            sink.accept(Math.max(min, Math.min(max, value)));
                        } catch (Exception e) {
                            toast("请输入数字");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // -------------------------------------------------------- 导入导出

    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQ_IMPORT_CONFIG);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "config.json");
        try {
            activity.startActivityForResult(intent, REQ_EXPORT_CONFIG);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    /** 由 MainActivity 转发；返回 true 表示本页已处理。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return false;
        Uri uri = data.getData();
        if (uri == null) return false;
        if (requestCode == REQ_IMPORT_CONFIG) {
            try {
                String text = readText(uri);
                ConfigRepository.writeUtf8Atomic(ConfigRepository.configFile(activity), text);
                // 让悬浮层丢弃内存里的旧配置并立刻重建，否则要重启应用才生效
                OverlayService.refresh(activity);
                OverlayService.start(activity);
                refresh();
                toast("已导入 config.json");
            } catch (Exception e) {
                toast("导入失败：" + e.getMessage());
            }
            return true;
        }
        if (requestCode == REQ_EXPORT_CONFIG) {
            try {
                String text = ConfigRepository.readUtf8(ConfigRepository.configFile(activity));
                writeText(uri, text);
                toast("已导出 config.json");
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
            }
            return true;
        }
        if (requestCode == REQ_IMPORT_FONT) {
            try {
                String name = displayName(uri);
                InputStream in = activity.getContentResolver().openInputStream(uri);
                String fontName = FontRepository.importFont(activity, in, name);
                if (fontName == null) {
                    toast("只支持 ttf / otf 字体文件");
                } else {
                    Prefs.setFontFamily(activity, fontName);
                    OverlayService.refresh(activity);
                    refresh();
                    toast("已导入字体：" + fontName);
                }
            } catch (Exception e) {
                toast("导入字体失败：" + e.getMessage());
            }
            return true;
        }
        return false;
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
            if (name == null) name = "font.ttf";
        }
        return name;
    }

    private String readText(Uri uri) throws Exception {
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法读取文件");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeText(Uri uri, String text) throws Exception {
        OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new IllegalStateException("无法写入文件");
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /** 用当前字体与字重渲染一行预览，便于直接看效果。 */
    private void updateFontPreview(int weight) {
        if (fontPreview == null) return;
        android.graphics.Typeface typeface = FontRepository.resolve(
                activity, Prefs.fontFamily(activity), weight);
        fontPreview.setTypeface(typeface == null
                ? android.graphics.Typeface.SANS_SERIF : typeface);
        if (weightLabel != null) {
            weightLabel.setText("字重：" + weight + "（100–900，可变字体连续生效）");
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
