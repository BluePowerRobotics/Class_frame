package org.bluepowerrobotics.classframe.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 页面里重复用到的简单控件构造工具。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.BLACK);
        view.setPadding(0, dp(context, 14), 0, dp(context, 4));
        return view;
    }

    public static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 6), 0, dp(context, 6));
        return row;
    }

    public static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.BLACK);
        view.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return view;
    }

    public static Button button(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    public static LinearLayout.LayoutParams wrap(Context context) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 竖直布局里直接放置的行/标签要用这个：宽度占满，否则 Ui.label 的 weight 会让它宽度为 0。 */
    public static LinearLayout.LayoutParams block(Context context) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static void gap(Context context, LinearLayout parent, int heightDp) {
        View view = new View(context);
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
    }

    /** Spinner 适配器：收起状态用 simple_spinner_item，下拉列表用 dropdown 布局。 */
    public static <T> android.widget.ArrayAdapter<T> spinnerAdapter(
            Context context, java.util.List<T> items) {
        android.widget.ArrayAdapter<T> adapter = new android.widget.ArrayAdapter<T>(context,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.BLACK);
                    ((TextView) view).setTextSize(13);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.BLACK);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /** 可用作下拉的文本控件：点击后弹出选项列表（比 Spinner 更可靠、也更好点）。 */
    public static TextView chooser(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(13);
        view.setTextColor(Color.BLACK);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 2), dp(context, 10), dp(context, 2), dp(context, 10));
        view.setBackgroundColor(0xFFFFFFFF);
        view.setClickable(true);
        return view;
    }

    public static void showChoices(final Context context, final TextView target, String title,
                                   final java.util.List<String> items) {
        new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(items.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                target.setText(items.get(which));
                            }
                        })
                .show();
    }
}
