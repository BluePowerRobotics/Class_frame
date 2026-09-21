package org.bluepowerrobotics.classframe.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.bluepowerrobotics.classframe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：底部导航（edit / setclass / system）+ 三个页面。
 * 底部栏为自绘实现，避免引入 Material 依赖。
 */
public class MainActivity extends Activity {

    public interface Page {
        View getView();

        void onShow();
    }

    private static final int BAR_HEIGHT_DP = 58;

    private final List<Page> pages = new ArrayList<>();
    private final List<LinearLayout> tabItems = new ArrayList<>();
    private final List<ImageView> tabIcons = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private FrameLayout container;
    private int currentIndex = -1;
    private int colorSelected;
    private int colorNormal;
    private static final int REQ_STORAGE = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        org.bluepowerrobotics.classframe.data.Logs.i("Ui",
                "MainActivity.onCreate " + org.bluepowerrobotics.classframe.data.Logs.environment());
        org.bluepowerrobotics.classframe.data.Logs.i("Ui", "运行日志位置: "
                + org.bluepowerrobotics.classframe.data.Logs.location(this));
        requestStorageIfNeeded();
        colorSelected = 0xFF1565C0;
        colorNormal = 0xFF888888;

        try {
            pages.add(new EditPage(this));
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "EditPage ctor failed", error);
            pages.add(new BrokenPage(describe(error)));
        }
        try {
            pages.add(new SetClassPage(this));
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "SetClassPage ctor failed", error);
            pages.add(new BrokenPage(describe(error)));
        }
        try {
            pages.add(new SystemPage(this));
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "SystemPage ctor failed", error);
            pages.add(new BrokenPage(describe(error)));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 程序化构建的界面必须显式给背景色：否则窗口背景（本机型为黑）会透出，
        // 深色文字与 Spinner 收起态的文字都会看不见。
        root.setBackgroundColor(0xFFF7F7F7);

        container = new FrameLayout(this);
        container.setBackgroundColor(0xFFF7F7F7);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        bar.setElevation(dp(8));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP)));

        addTab(bar, R.drawable.ic_tab_edit, "edit", 0);
        addTab(bar, R.drawable.ic_tab_class, "setclass", 1);
        addTab(bar, R.drawable.ic_tab_system, "system", 2);

        setContentView(root);
        select(0);
    }

    private void addTab(LinearLayout bar, int iconRes, String label, final int index) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setPadding(0, dp(6), 0, dp(4));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(colorNormal);
        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(colorNormal);
        item.addView(text);

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(index);
            }
        });

        bar.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabItems.add(item);
        tabIcons.add(icon);
        tabLabels.add(text);
    }

    /** 切换页面；页面视图按需创建并缓存。 */
    public void select(int index) {
        org.bluepowerrobotics.classframe.data.Logs.i("Ui", "select tab " + index);
        if (index == currentIndex) {
            pages.get(index).onShow();
            return;
        }
        currentIndex = index;
        container.removeAllViews();
        View view;
        try {
            view = pages.get(index).getView();
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "page " + index + " build failed", error);
            view = brokenView(describe(error));
        }
        container.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabIcons.size(); i++) {
            boolean on = i == index;
            tabIcons.get(i).setColorFilter(on ? colorSelected : colorNormal);
            tabLabels.get(i).setTextColor(on ? colorSelected : colorNormal);
        }
        try {
            pages.get(index).onShow();
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "page " + index + " onShow failed", error);
        }
    }

    private static String describe(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** 出错页面：把异常直接画在屏幕上，方便用户截图反馈，比闪退/黑屏可诊断得多。 */
    private View brokenView(String detail) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        TextView text = new TextView(this);
        text.setTextSize(12);
        text.setTextColor(0xFFB00020);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        text.setTextIsSelectable(true);
        text.setText("此页面载入失败（已写入运行日志，可到其它页导出）\n\n" + detail);
        scroll.addView(text);
        return scroll;
    }

    private final class BrokenPage implements Page {
        private final String detail;

        BrokenPage(String detail) {
            this.detail = detail;
        }

        @Override
        public View getView() {
            return brokenView(detail);
        }

        @Override
        public void onShow() {
        }
    }

    /** 当前是否停留在 system 页（部分状态刷新用）。 */
    public boolean isSystemPage() {
        return currentIndex == 2;
    }

    @Override
    protected void onResume() {
        super.onResume();
        org.bluepowerrobotics.classframe.data.Logs.i("Ui", "MainActivity.onResume, overlayEnabled="
                + org.bluepowerrobotics.classframe.data.Prefs.overlayEnabled(this)
                + " canDrawOverlays=" + android.provider.Settings.canDrawOverlays(this));
        // 打开应用时确保悬浮层服务在运行（之前 M1 有这段，M4 重写界面时漏了；
        // 现在之所以"打开 system 页才出现悬浮层"，是因为那页开关的 setChecked 顺带启动了服务）
        if (org.bluepowerrobotics.classframe.data.Prefs.overlayEnabled(this)
                && android.provider.Settings.canDrawOverlays(this)) {
            try {
                org.bluepowerrobotics.classframe.overlay.OverlayService.start(this);
            } catch (Throwable error) {
                org.bluepowerrobotics.classframe.data.Logs.e("Ui", "OverlayService.start failed", error);
            }
        } else {
            org.bluepowerrobotics.classframe.data.Logs.w("Ui",
                    "overlay not started: enabled="
                            + org.bluepowerrobotics.classframe.data.Prefs.overlayEnabled(this)
                            + " canDrawOverlays=" + android.provider.Settings.canDrawOverlays(this));
        }
        if (currentIndex >= 0) {
            try {
                pages.get(currentIndex).onShow();
            } catch (Throwable error) {
                org.bluepowerrobotics.classframe.data.Logs.e("Ui", "onResume onShow failed", error);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        org.bluepowerrobotics.classframe.data.Logs.i("Ui",
                "onActivityResult req=" + requestCode + " result=" + resultCode);
        for (Page page : pages) {
            if (page instanceof SystemPage
                    && ((SystemPage) page).onActivityResult(requestCode, resultCode, data)) return;
            if (page instanceof SetClassPage
                    && ((SetClassPage) page).onActivityResult(requestCode, resultCode, data)) return;
            if (page instanceof EditPage
                    && ((EditPage) page).onActivityResult(requestCode, resultCode, data)) return;
        }
    }

    /**
     * Android 9 及以下写公共目录要存储权限。只在这里申请一次：日志是排障刚需，
     * 拒绝也不影响 app 运行，只是退回私有目录。
     */
    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;
        }
        if (!org.bluepowerrobotics.classframe.data.Logs.needsStoragePermission(this)) return;
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        } catch (Throwable error) {
            org.bluepowerrobotics.classframe.data.Logs.e("Ui", "request storage failed", error);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_STORAGE) return;
        org.bluepowerrobotics.classframe.data.Logs.refreshLocation(this);
        org.bluepowerrobotics.classframe.data.Logs.i("Ui", "存储权限结果="
                + (results.length > 0 ? results[0] : -1) + "，日志位置: "
                + org.bluepowerrobotics.classframe.data.Logs.location(this));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
