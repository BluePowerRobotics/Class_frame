package org.bluepowerrobotics.classframe.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.bluepowerrobotics.classframe.data.Config;
import org.bluepowerrobotics.classframe.data.ConfigRepository;
import org.bluepowerrobotics.classframe.data.DayChangeRepository;
import org.bluepowerrobotics.classframe.data.Logs;
import org.bluepowerrobotics.classframe.data.Prefs;
import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 悬浮层总控：状态 → 内容 → 窗口几何 → 交互。
 * 对应 Python calendar 的 apply_layout / drag_* / editor_* 部分。
 */
public final class OverlayController implements OverlayView.Listener {

    private static final String TAG = "ClassFrame";
    /** 调试用：打印拖动过程中的分区与样式判定。 */
    private static final boolean DEBUG_DRAG = false;
    /** 拖拽时屏幕最外侧多少像素内视为"贴边"，强制该方向的 secondStyle。与 Python 一致。 */
    private static final float EDGE_TRIGGER_PX = 5f;

    private static OverlayController sInstance;

    private final Context context;
    private final WindowManager windowManager;
    private final MovementController movement;

    private OverlayView mainView;
    private OverlayView countView;
    private TouchProxyView touchView;
    private WindowManager.LayoutParams touchParams;

    private Config config;
    private OverlayState state;
    private List<String> todayTokens = new ArrayList<>();

    private String dock = "upper";
    private boolean secondStyle;
    private Boolean lastAfterClass;

    private boolean shown;
    private boolean dirty = true;
    /** 当前时段被「上课/下课隐藏悬浮层」关掉了（与"未授予权限"等失败区分开）。 */
    private boolean hiddenBySetting;

    // 拖动状态
    private boolean dragging;
    private boolean dragClick;
    private boolean styleApplied;
    private String dragZone = "upper";
    private float pressX;
    private float pressY;
    private float dragRefX;
    private float dragRefY;
    private float dragMoved;
    private OverlayView.Kind lastKind;

    // 编辑器状态
    private Calendar editorDate = Calendar.getInstance();
    private List<String> editorTokens = new ArrayList<>();

    private long lastInteractionAt;
    private int interactionHoldMs = 500;
    private String lastLoggedState;
    private String lastShowError;

    public interface FrameRequester {
        void requestFrames();
    }

    private FrameRequester frameRequester;
    private String appliedFontName;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable settleShrink = new Runnable() {
        @Override
        public void run() {
            if (mainView != null) movement.shrinkToContent(mainView);
            if (countView != null) movement.shrinkToContent(countView);
        }
    };

    public void setFrameRequester(FrameRequester requester) {
        this.frameRequester = requester;
    }

    private void requestFrames() {
        if (frameRequester != null) frameRequester.requestFrames();
    }

    private OverlayController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.movement = new MovementController(windowManager,
                this.context.getResources().getDisplayMetrics().density,
                this.context.getResources());
    }

    public static synchronized OverlayController get(Context context) {
        if (sInstance == null) {
            sInstance = new OverlayController(context);
        }
        return sInstance;
    }

    public boolean canDrawOverlay() {
        return Settings.canDrawOverlays(context);
    }

    public boolean isShown() {
        return shown && mainView != null && mainView.isAttachedToWindow();
    }

    /** 当前时刻按课表本应显示（状态机说了算），与"窗口是否真的挂上去"无关。 */
    public boolean shouldBeVisible() {
        return state != null && !shouldHideBySetting(state);
    }

    /**
     * 「上课隐藏悬浮层」「下课隐藏悬浮层」：这两个开关直接决定整层是否挂上。
     *
     * 「下课」覆盖课间与放学（以及放学后的 10 分钟倒计时窗口）——因为它们同属 afterClass，
     * 拆成"课间隐藏、放学后又不隐藏"会很反直觉。
     */
    private boolean shouldHideBySetting(OverlayState current) {
        if (current == null || config == null) return false;
        return current.afterClass ? config.hideAfterClass : config.hideOnClass;
    }

    public MovementController movement() {
        return movement;
    }

    public void markDirty() {
        dirty = true;
    }

    /**
     * 返回 true 表示调用方不应再挂窗口（已被本方法收掉）。
     * 与 {@link #hide()} 的区别只是不改变状态、并抑制一次"被拦截"的日志噪音。
     */
    private boolean hideBySetting() {
        hiddenBySetting = true;
        hideWindows();
        return true;
    }

    /** 只收窗口，不改变 shown 语义之外的任何状态。 */
    private void hideWindows() {
        shown = false;
        if (countView != null) {
            try {
                windowManager.removeViewImmediate(countView);
            } catch (Exception ignored) {
            }
            countView = null;
        }
        if (mainView != null) {
            try {
                windowManager.removeViewImmediate(mainView);
            } catch (Exception ignored) {
            }
            mainView = null;
        }
        if (touchView != null) {
            try {
                windowManager.removeViewImmediate(touchView);
            } catch (Exception ignored) {
            }
            touchView = null;
            touchParams = null;
        }
        movement.islands().clear();
    }

    /** 是否仍需要按最高帧率刷新（拖动中、刚交互完、或动画未收敛）。 */
    public boolean needsHighFrameRate() {
        if (dragging) return true;
        return System.currentTimeMillis() - lastInteractionAt < interactionHoldMs;
    }

    public void setInteractionHoldMs(int value) {
        interactionHoldMs = Math.max(0, value);
    }

    private void touchInteraction() {
        lastInteractionAt = System.currentTimeMillis();
    }

    /** show() 失败的原因，供界面提示用。 */
    public String lastShowError() {
        return lastShowError;
    }

    /**
     * 显示悬浮层。
     *
     * 这里必须把「启用悬浮窗」当成硬闸门：以前这个开关只在服务里判断了一次，
     * 于是定时唤醒、系统重启后的 ACTION_START、以及界面上的按钮都能绕过去，
     * 表现就是"我明明关掉了，它自己又显示出来"。
     */
    public boolean show() {
        if (!Prefs.overlayEnabled(context)) {
            Logs.w(TAG, "overlay show blocked: 「启用悬浮窗」开关已关闭");
            lastShowError = "「启用悬浮窗」开关已关闭";
            hideWindows();
            return false;
        }
        if (!canDrawOverlay()) {
            Logs.w(TAG, "overlay show skipped: 未授予「显示在其他应用上方」");
            lastShowError = "未授予「显示在其他应用上方」";
            return false;
        }
        // 当前时段被「上课/下课隐藏悬浮层」关掉时，show() 不算失败，只是没什么可显示
        if (hiddenBySetting) return false;
        hiddenBySetting = false;
        try {
            if (mainView == null) {
                Logs.i(TAG, "overlay show: 开始创建绘制窗口");
                mainView = new OverlayView(context);
                mainView.setListener(this);
                WindowManager.LayoutParams params = buildParams();
                windowManager.addView(mainView, params);
                Logs.i(TAG, "overlay show: 绘制窗口已添加 type=" + params.type
                        + " sdk=" + Build.VERSION.SDK_INT);
                mainView.setWindowParams(params);
                touchParams = buildTouchParams();
                touchView = new TouchProxyView(context, mainView);
                windowManager.addView(touchView, touchParams);
                Logs.i(TAG, "overlay show: 触摸代理窗口已添加 type=" + touchParams.type);
            }
            shown = true;
            lastShowError = null;
            dirty = true;
            refresh();
            Logs.i(TAG, "overlay show ok");
            return true;
        } catch (Exception e) {
            Logs.e(TAG, "overlay show failed (无法添加悬浮窗)", e);
            lastShowError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            shown = false;
            return false;
        }
    }

    public void hide() {
        shown = false;
        if (countView != null) {
            try {
                windowManager.removeViewImmediate(countView);
            } catch (Exception ignored) {
            }
            countView = null;
        }
        if (mainView != null) {
            try {
                windowManager.removeViewImmediate(mainView);
            } catch (Exception ignored) {
            }
            mainView = null;
        }
        if (touchView != null) {
            try {
                windowManager.removeViewImmediate(touchView);
            } catch (Exception ignored) {
            }
            touchView = null;
            touchParams = null;
        }
        movement.islands().clear();
        lastAfterClass = null;
        lastKind = null;
    }

    public void reloadConfig() {
        config = null;
        dirty = true;
    }

    /** 重新计算状态并渲染。由服务在 1Hz 心跳或交互后调用。 */
    public void refresh() {
        if (!shown || mainView == null) return;
        if (config == null) {
            try {
                config = ConfigRepository.load(context);
            } catch (Exception e) {
                Log.w(TAG, "load config failed: " + e);
                return;
            }
        }
        if (config == null) return;

        Calendar now = now();
        int iso = isoWeekday(now);
        todayTokens = config.tokensForDay(iso);
        JSONArray override = DayChangeRepository.tokensForDate(
                context, dateString(now), config.starts.size() + 2);
        if (override != null && override.length() > 0) {
            todayTokens = toStringList(override);
        }

        state = StateMachine.compute(config, now, todayTokens);
        // 仅在状态发生变化时输出一行，便于排查又不刷屏
        String stateKey = state.hidden + "|" + state.closing + "|" + state.afterClass + "|"
                + state.stateIndex + "|" + state.stateNext + "|" + dock + "|" + secondStyle + "|"
                + todayTokens.size();
        if (!stateKey.equals(lastLoggedState)) {
            lastLoggedState = stateKey;
            Log.i(TAG, "state " + stateKey + " left=" + Math.round(state.leftSec)
                    + " screen=" + movement.screenW + "x" + movement.screenH);
        }

        if (state.hidden) {
            Log.i(TAG, "hidden -> GONE");
            mainView.setVisibility(View.GONE);
            if (countView != null) countView.setVisibility(View.GONE);
            return;
        }

        // 「上课隐藏悬浮层」「下课隐藏悬浮层」：整层收掉。
        // 注意必须真的把窗口从 WindowManager 移除，不能只设 GONE——
        // 窗口是 FLAG_NOT_TOUCHABLE 的绘制窗 + 贴合可见矩形的触摸代理窗，
        // 窗口留着就意味着那块区域仍在吃触摸，这跟"隐藏"的预期不符。
        if (shouldHideBySetting(state)) {
            if (mainView != null) {
                Logs.i(TAG, "按设置隐藏整层（" + (state.afterClass ? "下课" : "上课") + "）");
            }
            hideBySetting();
            // 动画状态一并清掉，避免下次显示时从旧位置滑入
            movement.resetApplied();
            dragging = false;
            dragClick = false;
            return;
        }
        hiddenBySetting = false;
        mainView.setVisibility(View.VISIBLE);

        applyStateDefaults();

        // 进入编辑器时把日期重置为今天（与 Python build_editor_widgets 一致）
        OverlayView.Kind kind = kindFor(dock, secondStyle);
        if (kind == OverlayView.Kind.CENTER_EDITOR && lastKind != OverlayView.Kind.CENTER_EDITOR) {
            editorDate = now();
            editorTokens = new ArrayList<>(todayTokens);
        }
        lastKind = kind;
        if (kind == OverlayView.Kind.CENTER_EDITOR && editorTokens.isEmpty()) {
            editorTokens = new ArrayList<>(todayTokens);
        }

        movement.updateScreenSize();
        applyFontIfNeeded();
        int availW = movement.screenW - movement.screenGap() * 2;
        int availH = movement.screenH - movement.screenGap() * 2;

        // ---- 主窗口 ----
        String promptName = StateMachine.promptLessonName(config, state, todayTokens);
        OverlayContent content = new OverlayContent();
        content.config = config;
        content.dock = dock;
        content.afterClass = state.afterClass;
        content.state = state;
        if (promptName != null && !"center".equals(dock)) {
            content.kind = OverlayView.Kind.PROMPT;
            content.tokens = todayTokens;
            content.promptName = promptName;
        } else {
            content.kind = kind;
            if (kind == OverlayView.Kind.CENTER_EDITOR) {
                content.tokens = editorTokens;
                content.dateText = dateString(editorDate);
                content.candidates = config.options;
                content.highlightToken = StateMachine.tokenIndexOf(state.highlightLesson, editorTokens);
            } else {
                content.tokens = todayTokens;
                content.highlightToken = state.highlightToken;
            }
        }
        mainView.setContent(content, availW, availH);
        boolean flush = content.kind == OverlayView.Kind.EDGE_BAR;
        movement.attach(mainView, mainView.getWindowParams(),
                mainView.contentWidth(), mainView.contentHeight(),
                dock, flush, movement.find(mainView) == null);

        // ---- 倒计时窗口 ----
        boolean wantCountdown;
        if (content.kind == OverlayView.Kind.EDGE_BAR) {
            wantCountdown = state.offPromptLeft > 0 && !state.closing;
        } else if (content.kind == OverlayView.Kind.PROMPT) {
            wantCountdown = false;
        } else if ("center".equals(dock)) {
            wantCountdown = false;
        } else {
            wantCountdown = (state.afterClass || state.closing) && !dragging;
        }

        if (wantCountdown) {
            if (countView == null) {
                countView = new OverlayView(context);
                countView.setListener(this);
                try {
                    WindowManager.LayoutParams params = buildParams();
                    windowManager.addView(countView, params);
                    countView.setWindowParams(params);
                } catch (Exception e) {
                    countView = null;
                    dirty = false;
                    return;
                }
            }
            countView.setVisibility(View.VISIBLE);
            OverlayContent cc = new OverlayContent();
            cc.kind = OverlayView.Kind.COUNTDOWN;
            cc.config = config;
            cc.dock = dock;
            cc.afterClass = state.afterClass;
            cc.state = state;
            cc.tokens = todayTokens;
            cc.clampWidthPx = mainView.contentWidth();
            cc.clampHeightPx = mainView.contentHeight();
            cc.forcedScale = mainView.appliedScale();
            countView.setContent(cc, availW, availH);
            movement.attach(countView, countView.getWindowParams(),
                    countView.contentWidth(), countView.contentHeight(),
                    dock, false, movement.find(countView) == null);
        } else if (countView != null) {
            movement.detach(countView);
            countView = null;
        }

        movement.resetApplied();
        boolean animating = movement.tick();
        syncAnimatedSize();
        dirty = false;
        if (animating) requestFrames();
        else scheduleShrink();
    }

    /** 只有内容或几何真的变化时才重排，配合"无变动不渲染"。 */
    public void refreshIfDirty() {
        if (dirty) refresh();
    }

    public boolean tickAnimation() {
        if (movement.islands().isEmpty()) return false;
        boolean animating = movement.tick();
        syncAnimatedSize();
        if (!animating) {
            movement.resetApplied();
        }
        return animating;
    }

    private void syncAnimatedSize() {
        // 必须遍历所有 island（包括正在删除的）：删除动画同样要把"当前尺寸"喂给视图，
        // 否则画面会冻结在最后一帧、最后被直接移除（表现为"大小不变地瞬间消失"）。
        for (Island island : movement.islands()) {
            if (!(island.view instanceof OverlayView)) continue;
            OverlayView view = (OverlayView) island.view;
            float[] size = movement.currentSize(view);
            float[] pos = movement.currentPos(view);
            int[] win = movement.appliedPos(view);
            view.setAnimatedRect(Math.round(pos[0]) - win[0], Math.round(pos[1]) - win[1],
                    Math.round(size[0]), Math.round(size[1]));
            if (view == mainView && touchView != null && touchParams != null) {
                // 触摸窗口严格贴合可见矩形；它是全透明的，逐帧 resize 不会造成可见闪烁
                touchParams.x = win[0] + Math.round(pos[0]) - win[0];
                touchParams.y = win[1] + Math.round(pos[1]) - win[1];
                touchParams.x = Math.round(pos[0]);
                touchParams.y = Math.round(pos[1]);
                touchParams.width = Math.max(1, Math.round(size[0]));
                touchParams.height = Math.max(1, Math.round(size[1]));
                try {
                    windowManager.updateViewLayout(touchView, touchParams);
                } catch (Exception ignored) {
                }
            }
            if (DEBUG_DRAG && view == mainView) {
                float[] goal = movement.islandSize(view);
                Log.i(TAG, "anim now=" + Math.round(size[0]) + "x" + Math.round(size[1])
                        + " pos=" + Math.round(pos[0]) + "," + Math.round(pos[1])
                        + " goal=" + Math.round(goal[0]) + "x" + Math.round(goal[1]));
            }
            if (DEBUG_DRAG && view != mainView) {
                Log.i(TAG, "anim countdown now=" + Math.round(size[0]) + "x" + Math.round(size[1])
                        + " deleting=" + island.deleting);
            }
        }
    }

    private void scheduleShrink() {
        handler.removeCallbacks(settleShrink);
        // 动画期间窗口比可见区大（为了避免逐帧 resize 闪烁），但那块透明区域会吃掉点击，
        // 所以动画一停就尽快收回窗口尺寸，把"挡住点击"的时间压到最短。
        handler.postDelayed(settleShrink, 200);
    }

    /** 字体设置变化时重新解析并应用（内置 / 系统字体 / 导入字体）。 */
    private void applyFontIfNeeded() {
        String name = Prefs.fontFamily(context);
        int weight = Prefs.fontWeight(context);
        float lineHeight = Prefs.lineHeightScale(context);
        String key = name + "#" + weight + "#" + lineHeight;
        if (key.equals(appliedFontName)) return;
        appliedFontName = name;
        android.graphics.Typeface typeface =
                org.bluepowerrobotics.classframe.data.FontRepository.resolve(context, name, weight);
        if (mainView != null) mainView.setTypefaces(typeface, null);
        if (countView != null) countView.setTypefaces(typeface, null);
        if (mainView != null) mainView.setLineHeightScale(lineHeight);
        if (countView != null) countView.setLineHeightScale(lineHeight);
    }

    private void applyStateDefaults() {
        if (config == null || state == null) return;
        if (lastAfterClass == null || state.afterClass != lastAfterClass) {
            lastAfterClass = state.afterClass;
            if (!dragging) {
                dock = state.afterClass ? config.offDefaultDock : config.onDefaultDock;
                secondStyle = state.afterClass ? config.offDefaultSecondStyle : config.onDefaultSecondStyle;
            }
        }
    }

    private static OverlayView.Kind kindFor(String dock, boolean secondStyle) {
        if ("left".equals(dock) || "upper".equals(dock) || "right".equals(dock)) {
            return secondStyle ? OverlayView.Kind.EDGE_BAR : OverlayView.Kind.EDGE_TEXT;
        }
        return secondStyle ? OverlayView.Kind.CENTER_EDITOR : OverlayView.Kind.CENTER_SIMPLE;
    }

    private Calendar now() {
        Calendar calendar = Calendar.getInstance();
        if (config != null) {
            calendar.add(Calendar.SECOND, config.timeOffsetSeconds);
        }
        return calendar;
    }

    // ------------------------------------------------------------ 交互回调

    @Override
    public void onTouchDown() {
        touchInteraction();
        requestFrames();
        dragging = true;
        dragClick = true;
        styleApplied = false;
        dragMoved = 0f;
        dragZone = dock;
        dirty = true;
        movement.dragNow(mainView);
    }

    @Override
    public void onTouchMove(float rawX, float rawY) {
        touchInteraction();
        requestFrames();
        if (!dragging) return;
        if (pressX == 0f && pressY == 0f) {
            pressX = rawX;
            pressY = rawY;
            dragRefX = rawX;
            dragRefY = rawY;
        }
        float dxTotal = rawX - pressX;
        float dyTotal = rawY - pressY;
        dragMoved = Math.max(dragMoved, dxTotal * dxTotal + dyTotal * dyTotal);

        // 与新版 Python zone_of_drag 一致：最外侧 EDGE_TRIGGER_PX 像素内强制贴边样式
        String zone;
        Boolean forcedStyle = null;
        if (rawY < EDGE_TRIGGER_PX) {
            zone = "upper";
            forcedStyle = Boolean.TRUE;
        } else if (rawX < EDGE_TRIGGER_PX) {
            zone = "left";
            forcedStyle = Boolean.TRUE;
        } else if (rawX > movement.screenW - EDGE_TRIGGER_PX) {
            zone = "right";
            forcedStyle = Boolean.TRUE;
        } else {
            zone = zoneOf(rawX, rawY);
        }
        boolean desiredStyle = forcedStyle != null
                ? forcedStyle
                : (config != null && Boolean.TRUE.equals(config.dragDefaults.get(zone)));
        if (DEBUG_DRAG) {
            Log.i(TAG, "drag raw=" + Math.round(rawX) + "," + Math.round(rawY)
                    + " zone=" + zone + " forced=" + forcedStyle + " desired=" + desiredStyle
                    + " second=" + secondStyle);
        }

        if (!zone.equals(dragZone)) {
            dragZone = zone;
            dock = zone;
            secondStyle = desiredStyle;
            styleApplied = true;
            dirty = true;
            // 必须先按新 dock/样式重建内容，拿到"目标区域尺寸"，
            // 再用它算目标区域的中点作为弹性起始点（对应 Python 的 preview_size）。
            // 用切换前的旧尺寸会让起始点落到窗口当前中点附近，dx≈0，弹性消失。
            refresh();
            float[] size = movement.islandSize(mainView);
            float[] base = baseCenter(zone, size[0], size[1]);
            dragRefX = base[0];
            dragRefY = base[1];
            recomputeDragGoal(rawX, rawY);
        }
        if (Math.abs(dxTotal) > 3 || Math.abs(dyTotal) > 3) {
            dragClick = false;
        }
        if (!dragClick) {
            // 同一 dock 内移入/移出贴边带时也要切换样式
            if (desiredStyle != secondStyle) {
                secondStyle = desiredStyle;
                dirty = true;
                refresh();
            }
            styleApplied = true;
        }
        recomputeDragGoal(rawX, rawY);
    }

    @Override
    public void onTouchUp(boolean clicked) {
        touchInteraction();
        requestFrames();
        boolean wasClick = clicked && dragMoved <= 9f;
        String zone = dragZone;
        dragging = false;
        if (mainView != null) movement.dragEnd(mainView);
        pressX = 0f;
        pressY = 0f;

        if (wasClick) {
            secondStyle = !secondStyle;
        } else if (!styleApplied && config != null) {
            secondStyle = Boolean.TRUE.equals(config.dragDefaults.get(zone));
        }
        dirty = true;
        refresh();
    }

    @Override
    public void onToggleStyle() {
        secondStyle = !secondStyle;
        dirty = true;
    }

    @Override
    public void onEditorDateShift(int delta) {
        touchInteraction();
        requestFrames();
        editorDate.add(Calendar.DAY_OF_MONTH, delta);
        loadEditorTokens();
        dirty = true;
        refresh();
    }

    @Override
    public void onEditorReplace(int tokenIndex, String course) {
        touchInteraction();
        requestFrames();
        if (tokenIndex < 0 || tokenIndex >= editorTokens.size()) return;
        if (Config.isSpecialToken(editorTokens.get(tokenIndex))) return;
        editorTokens.set(tokenIndex, course);
        try {
            JSONArray array = new JSONArray();
            for (String token : editorTokens) array.put(token);
            DayChangeRepository.upsert(context, dateString(editorDate), array);
        } catch (Exception e) {
            Toast.makeText(context, "保存调课失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        dirty = true;
        refresh();
    }

    private void loadEditorTokens() {
        if (config == null) return;
        int expected = config.starts.size() + 2;
        JSONArray override = DayChangeRepository.tokensForDate(context, dateString(editorDate), expected);
        if (override != null && override.length() > 0) {
            editorTokens = toStringList(override);
        } else {
            editorTokens = config.tokensForDay(isoWeekday(editorDate));
        }
    }

    private String zoneOf(float x, float y) {
        if (x < movement.screenW / 4f) return "left";
        if (x > movement.screenW * 3f / 4f) return "right";
        if (y < movement.screenH / 4f) return "upper";
        return "center";
    }

    private float[] baseCenter(String zone, float w, float h) {
        boolean flush = secondStyle && !"center".equals(zone);
        int gap = movement.screenGap();
        switch (zone) {
            case "left":
                return new float[]{(flush ? 0 : gap) + w / 2f, movement.screenH / 2f};
            case "right":
                return new float[]{movement.screenW - (flush ? 0 : gap) - w / 2f, movement.screenH / 2f};
            case "upper":
                return new float[]{movement.screenW / 2f, (flush ? 0 : gap) + h / 2f};
            default:
                return new float[]{movement.screenW / 2f, movement.screenH / 2f};
        }
    }

    private void recomputeDragGoal(float rawX, float rawY) {
        if (mainView == null) return;
        float[] size = movement.islandSize(mainView);
        float w = size[0];
        float h = size[1];
        float[] base = baseCenter(dock, w, h);
        float dx = rawX - dragRefX;
        float dy = rawY - dragRefY;
        float fx = 0.1f / (Math.abs(dx) / Math.max(movement.screenW, 1) + 0.1f);
        float fy = 0.1f / (Math.abs(dy) / Math.max(movement.screenH, 1) + 0.1f);
        float cx = base[0] + dx * fx;
        float cy = base[1] + dy * fy;
        cx = Math.min(Math.max(cx, w / 2f), Math.max(w / 2f, movement.screenW - w / 2f));
        cy = Math.min(Math.max(cy, h / 2f), Math.max(h / 2f, movement.screenH - h / 2f));
        movement.setDragGoal(mainView, cx - w / 2f, cy - h / 2f, w, h);
        if (DEBUG_DRAG) {
            Log.i(TAG, "dragGoal dock=" + dock + " w=" + w + " h=" + h
                    + " base=" + Math.round(base[0]) + "," + Math.round(base[1])
                    + " ref=" + Math.round(dragRefX) + "," + Math.round(dragRefY)
                    + " goal=" + Math.round(cx - w / 2f) + "," + Math.round(cy - h / 2f));
        }
    }

    // ------------------------------------------------------------ 工具

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // 绘制窗口完全不接触摸：触摸交给同位置的 TouchProxyView
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = 0.92f;
        params.setTitle("class_frame_overlay");
        return params;
    }

    /** 触摸代理窗口：透明、可触摸、位置与尺寸等于可见矩形。 */
    private WindowManager.LayoutParams buildTouchParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("class_frame_touch");
        return params;
    }

    private static int isoWeekday(Calendar calendar) {
        return ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
    }

    private static String dateString(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private static List<String> toStringList(JSONArray array) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            list.add(value == null ? "" : String.valueOf(value));
        }
        return list;
    }
}
