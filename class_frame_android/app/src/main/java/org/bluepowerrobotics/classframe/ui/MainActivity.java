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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        colorSelected = 0xFF1565C0;
        colorNormal = 0xFF888888;

        pages.add(new EditPage(this));
        pages.add(new SetClassPage(this));
        pages.add(new SystemPage(this));

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
        if (index == currentIndex) {
            pages.get(index).onShow();
            return;
        }
        currentIndex = index;
        container.removeAllViews();
        View view = pages.get(index).getView();
        container.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabIcons.size(); i++) {
            boolean on = i == index;
            tabIcons.get(i).setColorFilter(on ? colorSelected : colorNormal);
            tabLabels.get(i).setTextColor(on ? colorSelected : colorNormal);
        }
        pages.get(index).onShow();
    }

    /** 当前是否停留在 system 页（部分状态刷新用）。 */
    public boolean isSystemPage() {
        return currentIndex == 2;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 打开应用时确保悬浮层服务在运行（之前 M1 有这段，M4 重写界面时漏了；
        // 现在之所以"打开 system 页才出现悬浮层"，是因为那页开关的 setChecked 顺带启动了服务）
        if (org.bluepowerrobotics.classframe.data.Prefs.overlayEnabled(this)
                && android.provider.Settings.canDrawOverlays(this)) {
            org.bluepowerrobotics.classframe.overlay.OverlayService.start(this);
        }
        if (currentIndex >= 0) pages.get(currentIndex).onShow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (Page page : pages) {
            if (page instanceof SystemPage
                    && ((SystemPage) page).onActivityResult(requestCode, resultCode, data)) return;
            if (page instanceof SetClassPage
                    && ((SetClassPage) page).onActivityResult(requestCode, resultCode, data)) return;
            if (page instanceof EditPage
                    && ((EditPage) page).onActivityResult(requestCode, resultCode, data)) return;
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
