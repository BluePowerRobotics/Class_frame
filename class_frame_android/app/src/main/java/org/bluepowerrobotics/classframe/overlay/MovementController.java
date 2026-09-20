package org.bluepowerrobotics.classframe.overlay;

import android.graphics.Point;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.util.DisplayMetrics;

import org.bluepowerrobotics.classframe.data.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * movements 的移植：管理所有悬浮窗口的位置、尺寸与弹性动画。
 *
 * 与原版一致：目标位置由 dock 决定，动画为二阶递推，收敛后吸附到目标并停止刷新。
 */
public final class MovementController {

    /** 与 Python 的 SCREEN_GAP / ISLAND_GAP 对应（dp）。 */
    public static final int SCREEN_GAP_DP = 10;
    public static final int ISLAND_GAP_DP = 30;

    private final WindowManager windowManager;
    private final android.content.res.Resources resources;
    private final float density;
    private final List<Island> islands = new ArrayList<>();

    public int screenW = 1;
    public int screenH = 1;
    /** 状态栏/导航栏高度：悬浮层使用全屏坐标，需要显式避让，否则顶部会被状态栏吃掉触摸。 */
    public int insetTop;
    public int insetBottom;

    public MovementController(WindowManager windowManager, float density,
                              android.content.res.Resources resources) {
        this.windowManager = windowManager;
        this.density = density;
        this.resources = resources;
        updateScreenSize();
    }

    public void updateScreenSize() {
        Display display = windowManager.getDefaultDisplay();
        // 实测：WindowManager 给 APPLICATION_OVERLAY 窗口的坐标系已经排除了状态栏，
        // 因此直接用应用可用区域（getMetrics）即可，不能再叠加系统栏高度。
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        screenW = Math.max(1, metrics.widthPixels);
        screenH = Math.max(1, metrics.heightPixels);
        insetTop = 0;
        insetBottom = 0;
    }

    public int screenGap() {
        return Math.round(SCREEN_GAP_DP * density);
    }

    public int islandGap() {
        return Math.round(ISLAND_GAP_DP * density);
    }

    public List<Island> islands() {
        return islands;
    }

    public Island find(View view) {
        for (Island island : islands) {
            if (island.view == view) return island;
        }
        return null;
    }

    public Island attach(View view, WindowManager.LayoutParams params, int w, int h,
                         String group, boolean flush, boolean snap) {
        Island island = find(view);
        if (island == null) {
            island = new Island(view, params);
            islands.add(island);
            island.goal[0] = w;
            island.goal[1] = h;
            island.now[0] = w;
            island.now[1] = h;
            island.prev[0] = w;
            island.prev[1] = h;
        }
        island.group = Config.normalizeDock(group);
        island.flush = flush;
        island.deleting = false;
        island.goal[0] = Math.max(1, w);
        island.goal[1] = Math.max(1, h);
        // 窗口只增不减：动画期间窗口保持"新旧较大值"，避免逐帧 resize 造成闪烁
        island.windowW = Math.max(island.windowW, w);
        island.windowH = Math.max(island.windowH, h);
        if (snap || island.appliedW < 0) {
            System.arraycopy(island.goal, 0, island.now, 0, 4);
            System.arraycopy(island.goal, 0, island.prev, 0, 4);
        }
        calPos();
        return island;
    }

    public void detach(View view) {
        Island island = find(view);
        if (island == null) return;
        island.deleting = true;
        island.goal[0] = 1;
        island.goal[1] = 1;
        calPos();
    }

    public void removeNow(View view) {
        Island island = find(view);
        if (island == null) return;
        try {
            windowManager.removeViewImmediate(view);
        } catch (Exception ignored) {
        }
        islands.remove(island);
    }

    public void setDragGoal(View view, float x, float y, Float w, Float h) {
        Island island = find(view);
        if (island == null) return;
        if (w != null) island.goal[0] = Math.max(1, w);
        if (h != null) island.goal[1] = Math.max(1, h);
        island.goal[2] = x;
        island.goal[3] = y;
    }

    public void dragNow(View view) {
        Island island = find(view);
        if (island != null) island.dragging = true;
    }

    public void dragEnd(View view) {
        Island island = find(view);
        if (island != null) island.dragging = false;
        calPos();
    }

    public float[] islandSize(View view) {
        Island island = find(view);
        if (island == null) return new float[]{1, 1};
        return new float[]{island.goal[0], island.goal[1]};
    }

    public float[] currentSize(View view) {
        Island island = find(view);
        if (island == null) return new float[]{1, 1};
        return new float[]{island.now[0], island.now[1]};
    }

    public float[] currentPos(View view) {
        Island island = find(view);
        if (island == null) return new float[]{0, 0};
        return new float[]{island.now[2], island.now[3]};
    }

    /** 窗口在屏幕上的实际位置（已钳制在屏内）。 */
    public int[] appliedPos(View view) {
        Island island = find(view);
        if (island == null) return new int[]{0, 0};
        return new int[]{Math.max(0, island.appliedX), Math.max(0, island.appliedY)};
    }

    /** 按 dock 把同组窗口排列到屏幕对应位置，对应 Python 的 cal_pos()。 */
    public void calPos() {
        int gap = islandGap();
        int edgeGap = screenGap();
        for (String group : Config.DOCKS) {
            List<Island> members = new ArrayList<>();
            for (Island island : islands) {
                if (island.group.equals(group) && !island.deleting && !island.dragging) {
                    members.add(island);
                }
            }
            if (members.isEmpty()) continue;

            if ("center".equals(group)) {
                float usableH = screenH - insetTop - insetBottom;
                for (Island island : members) {
                    island.goal[2] = (screenW - island.goal[0]) / 2f;
                    island.goal[3] = insetTop + (usableH - island.goal[1]) / 2f;
                }
                continue;
            }

            if ("left".equals(group) || "right".equals(group)) {
                float total = -gap;
                for (Island island : members) total += island.goal[1] + gap;
                float usableH = screenH - insetTop - insetBottom;
                float y = insetTop + (usableH - total) / 2f;
                for (Island island : members) {
                    float edge = island.flush ? 0 : edgeGap;
                    island.goal[2] = "left".equals(group) ? edge : screenW - island.goal[0] - edge;
                    island.goal[3] = y;
                    y += island.goal[1] + gap;
                }
            } else {
                float total = -gap;
                for (Island island : members) total += island.goal[0] + gap;
                float x = (screenW - total) / 2f;
                for (Island island : members) {
                    float edge = island.flush ? 0 : edgeGap;
                    island.goal[2] = x;
                    island.goal[3] = insetTop + edge;
                    x += island.goal[0] + gap;
                }
            }
        }
    }

    /**
     * 推进一帧动画。
     *
     * @return true 表示仍有窗口在移动，需要继续请求下一帧。
     */
    public boolean tick() {
        boolean animating = false;
        List<Island> finished = new ArrayList<>();
        for (Island island : islands) {
            float[] goal = island.goal;
            float[] now = island.now;
            float[] prev = island.prev;

            float maxError = 0f;
            for (int k = 0; k < 4; k++) {
                maxError = Math.max(maxError, Math.abs(goal[k] - now[k]));
            }

            float[] next = new float[4];
            float maxStep = 0f;
            for (int k = 0; k < 4; k++) {
                float speed = (now[k] - prev[k]) * 0.85f;
                next[k] = 0.035f * goal[k] + 0.965f * now[k] + 0.55f * speed;
                maxStep = Math.max(maxStep, Math.abs(next[k] - now[k]));
            }
            System.arraycopy(now, 0, prev, 0, 4);
            System.arraycopy(next, 0, now, 0, 4);

            // 收敛判定：位置误差与每帧位移同时足够小 → 吸附到目标并停止刷新
            if (maxError < 0.5f && maxStep < 0.05f) {
                System.arraycopy(goal, 0, now, 0, 4);
                System.arraycopy(goal, 0, prev, 0, 4);
            } else {
                animating = true;
            }

            applyGeometry(island);

            if (island.deleting && maxError < 1.5f) {
                island.view.setVisibility(View.GONE);
                try {
                    windowManager.removeViewImmediate(island.view);
                } catch (Exception ignored) {
                }
                finished.add(island);
            }
        }
        islands.removeAll(finished);
        return animating;
    }

    /** 只有取整后的几何真正变化时才调用 updateViewLayout，避免无意义重排。 */
    private void applyGeometry(Island island) {
        int w = Math.max(1, island.windowW);
        int h = Math.max(1, island.windowH);
        // 窗口不能超出屏幕，否则 WindowManager 会自行钳制，并把"锚在窗口左上角"的内容拉偏。
        // 这里主动钳制，可见矩形相对窗口的偏移由 View 补偿。
        int maxX = Math.max(0, screenW - w);
        int maxY = Math.max(0, screenH - h);
        int x = Math.min(Math.max(Math.round(island.now[2]), 0), maxX);
        int y = Math.min(Math.max(Math.round(island.now[3]), 0), maxY);
        if (w == island.appliedW && h == island.appliedH
                && x == island.appliedX && y == island.appliedY) {
            return;
        }
        island.appliedW = w;
        island.appliedH = h;
        island.appliedX = x;
        island.appliedY = y;
        island.params.width = w;
        island.params.height = h;
        island.params.x = x;
        island.params.y = y;
        try {
            windowManager.updateViewLayout(island.view, island.params);
        } catch (Exception ignored) {
        }
    }

    public void resetApplied() {
        for (Island island : islands) {
            island.appliedW = -1;
            island.appliedH = -1;
            island.appliedX = Integer.MIN_VALUE;
            island.appliedY = Integer.MIN_VALUE;
        }
    }

    /** 动画停止且静止一段时间后，把窗口尺寸收回内容尺寸（视觉上无变化，只释放多余表面）。 */
    public void shrinkToContent(View view) {
        Island island = find(view);
        if (island == null) return;
        int w = Math.max(1, Math.round(island.goal[0]));
        int h = Math.max(1, Math.round(island.goal[1]));
        if (w == island.windowW && h == island.windowH) return;
        island.windowW = w;
        island.windowH = h;
        island.appliedW = -1;
        island.appliedH = -1;
        applyGeometry(island);
    }
}
