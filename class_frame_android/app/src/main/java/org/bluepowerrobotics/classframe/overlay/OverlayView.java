package org.bluepowerrobotics.classframe.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.bluepowerrobotics.classframe.data.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮层渲染器：一个窗口一个 View，负责按形态测量、绘制并处理触摸。
 * 形态与 Python 的 kind_for / ensure_content 一一对应。
 */
public class OverlayView extends View {

    public enum Kind {EDGE_TEXT, EDGE_BAR, CENTER_SIMPLE, CENTER_EDITOR, PROMPT, COUNTDOWN}

    /** 调试用：打印编辑器各元素坐标，便于自动化测试定位。 */
    private static final boolean DEBUG_LOG_EDITOR = false;

    public interface Listener {
        void onTouchDown();

        void onTouchMove(float rawX, float rawY);

        void onTouchUp(boolean clicked);

        void onToggleStyle();

        void onEditorDateShift(int delta);

        void onEditorReplace(int tokenIndex, String course);
    }

    private static final int COLOR_BG = Color.BLACK;
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_HIGHLIGHT = Color.YELLOW;
    private static final int COLOR_BAR_SOFT = 0xFFC0C0C0;
    private static final float GAP_RATE = 0.25f;
    /**
     * Tk 的字号是"点"，而 wraplength 是"像素"，96dpi 下相差 96/72 倍。
     * 原版折行位置因此比"同样像素宽度"更窄，这里换算回来保持一致
     * （4 字 token 折成 2/2 而不是 3/1）。
     */
    private static final float TK_POINT_TO_PX = 96f / 72f;
    /** 每行可容纳的"字符数系数"：横向由它决定折行，竖向也按同一字符数切分以保持观感一致。 */
    private static final float WRAP_CHARS_RATIO = 1.5f / TK_POINT_TO_PX;

    private final Paint bgPaint = new Paint();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint();
    private final float density;

    private Typeface tokenTypeface = Typeface.SANS_SERIF;
    private final Typeface digitTypeface = Typeface.MONOSPACE;

    private OverlayContent content = new OverlayContent();
    private Listener listener;
    private android.view.WindowManager.LayoutParams windowParams;

    private float contentW = 1f;
    private float contentH = 1f;
    private float appliedScale = 1f;
    private float lineHeightScale = 1f;
    private int animatedW = -1;
    private int animatedH = -1;
    private int animatedX;
    private int animatedY;
    private int availableW = 1;
    private int availableH = 1;
    private float gap = 1f;
    private float fpx = 40f;

    private final List<float[]> tokenSizes = new ArrayList<>();   // [width, height, size]
    private final List<List<String>> tokenLines = new ArrayList<>();
    private final List<RectF> tokenRects = new ArrayList<>();
    private final List<float[]> candidateSizes = new ArrayList<>();  // [width, height, size]
    private final List<List<String>> candidateLines = new ArrayList<>();
    private final List<RectF> candidateRects = new ArrayList<>();
    private RectF barRect;
    private RectF leftArrowRect;
    private RectF rightArrowRect;
    private RectF marginLeftRect;
    private RectF marginRightRect;
    private String clockText = "";
    private float clockSize;
    private RectF clockRect;
    private String countdownText = "";
    private float countdownSize;
    private boolean countdownMono;
    private float countdownTextH;
    private float countdownTextW;

    // 交互状态
    private boolean windowDrag;
    private boolean candidateDrag;
    private int pendingDateShift;
    private String draggingCourse;
    private float dragLocalX;
    private float dragLocalY;
    private float downRawX;
    private float downRawY;
    private float maxMoved;

    public OverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        bgPaint.setColor(COLOR_BG);
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(COLOR_TEXT);
        setFocusable(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 行高倍数（1.0 = 字体自身的行高）。 */
    public void setLineHeightScale(float scale) {
        float value = Math.max(0.5f, Math.min(2.5f, scale));
        if (Math.abs(value - lineHeightScale) < 0.001f) return;
        lineHeightScale = value;
        contentW = 1;
        contentH = 1;
        requestLayout();
        invalidate();
    }

    public float lineHeightScale() {
        return lineHeightScale;
    }

    public void setTypefaces(Typeface tokenTypeface, Typeface unusedDigit) {
        // 传 null 表示回到默认字体
        this.tokenTypeface = tokenTypeface != null ? tokenTypeface : Typeface.SANS_SERIF;
        requestLayout();
        invalidate();
    }

    public OverlayContent contentRef() {
        return content;
    }

    /** 创建该视图时使用的窗口参数，供运动控制器读写几何。 */
    public android.view.WindowManager.LayoutParams getWindowParams() {
        return windowParams;
    }

    public void setWindowParams(android.view.WindowManager.LayoutParams params) {
        this.windowParams = params;
    }

    public void setContent(OverlayContent next, int availableW, int availableH) {
        this.content = next == null ? new OverlayContent() : next;
        layout(availableW, availableH);
        requestLayout();
        invalidate();
    }

    // ---------------------------------------------------------------- 测量

    private void layout(int availableW, int availableH) {
        this.availableW = Math.max(1, availableW);
        this.availableH = Math.max(1, availableH);
        if (content.forcedScale > 0f) {
            layoutWith(content.forcedScale);
            return;
        }
        layoutWith(1f);
        if (content.kind == Kind.EDGE_BAR) {
            // 纯进度条没有文字，不做等比缩放，长度直接按可用宽度收敛
            return;
        }
        if (availableW > 0 && availableH > 0
                && (contentW > availableW || contentH > availableH)) {
            float scale = Math.min(availableW / contentW, availableH / contentH) * 0.98f;
            layoutWith(Math.max(0.05f, scale));
        }
    }

    private void layoutWith(float extraScale) {
        appliedScale = extraScale;
        tokenSizes.clear();
        tokenLines.clear();
        tokenRects.clear();
        candidateSizes.clear();
        candidateLines.clear();
        candidateRects.clear();
        barRect = null;
        leftArrowRect = null;
        rightArrowRect = null;
        marginLeftRect = null;
        marginRightRect = null;
        clockRect = null;

        Config config = content.config;
        if (config == null) {
            contentW = 1;
            contentH = 1;
            return;
        }

        switch (content.kind) {
            case EDGE_BAR:
                layoutEdgeBar(config, extraScale);
                break;
            case CENTER_SIMPLE:
                layoutCenterSimple(config, extraScale);
                break;
            case CENTER_EDITOR:
                layoutCenterEditor(config, extraScale);
                break;
            case PROMPT:
                layoutPrompt(config, extraScale);
                break;
            case COUNTDOWN:
                layoutCountdown(config, extraScale);
                break;
            case EDGE_TEXT:
            default:
                layoutEdgeText(config, extraScale);
                break;
        }
    }

    private float fontPx(Config config, String dock, boolean after, float extraScale) {
        int base;
        String key;
        if ("left".equals(dock) || "right".equals(dock)) {
            base = config.verticalTextSize;
            key = after ? "left下课缩放" : "left上课缩放";
        } else if ("upper".equals(dock)) {
            base = config.textSize;
            key = after ? "upper下课缩放" : "upper上课缩放";
        } else {
            base = config.textSize;
            key = "center缩放";
        }
        Float scale = config.scale.get(key);
        float value = base * density * (scale == null ? 1f : scale) * extraScale;
        return Math.max(2f, value);
    }

    private float tokenSize(String text, float baseFpx, boolean vertical) {
        int len = Math.max(1, text.length());
        double divisor = Math.pow(len, vertical ? 0.7 : 0.5);
        return Math.max(1f, (float) (baseFpx / divisor));
    }

    private float textWidth(String text, float size, Typeface tf) {
        paint.setTypeface(tf);
        paint.setTextSize(size);
        return paint.measureText(text);
    }

    private float textHeight(float size, Typeface tf) {
        paint.setTypeface(tf);
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (fm.descent - fm.ascent) * lineHeightScale;
    }

    private void measureTokens(Config config, boolean vertical, float baseFpx) {
        // 折行宽度统一按横向的 1.5 倍（再除以点/像素换算）：
        // 原版竖向用 1.7 是历史遗留，会让长 token 在左右两侧不折行、把列撑宽，
        // 与横向观感不一致（横向 4 字折 2/2，竖向也应如此）。
        float wrapWidth = Math.max(10f, baseFpx * 1.5f / TK_POINT_TO_PX);
        List<String> tokens = content.tokens;
        for (int i = 0; i < tokens.size(); i++) {
            String text = displayToken(tokens.get(i), vertical);
            // 竖向与横向统一：字号一律 len^0.5、折行宽度一律 1.5fpx÷(96/72)。
            // 原版竖向用 len^0.7 + 1.7fpx 属历史遗留，会导致竖向列过窄/折行不一致。
            float size = tokenSize(text, baseFpx, false);
            List<String> lines = wrapLines(text, size, wrapWidth, tokenTypeface);
            float[] block = measureBlock(lines, size, tokenTypeface);
            tokenSizes.add(new float[]{block[0], block[1], size});
            tokenLines.add(lines);
        }
    }

    /** 按字符贪心折行：CJK 没有空格，等价于 Tk 在 word 过长时的字符级折行。 */
    private List<String> wrapLines(String text, float size, float maxWidth, Typeface tf) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        if (maxWidth <= 0) {
            lines.add(text);
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (current.length() > 0
                    && textWidth(current.toString() + ch, size, tf) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    /** 折行后的块尺寸：宽度取最宽行，高度为行数 × 行高。 */
    private float[] measureBlock(List<String> lines, float size, Typeface tf) {
        float width = 0f;
        float lineHeight = textHeight(size, tf);
        for (String line : lines) {
            width = Math.max(width, textWidth(line, size, tf));
        }
        return new float[]{width, lines.size() * lineHeight};
    }

    /** 固定每行字符数切分（竖向用，保证与横向的每行字符数一致）。 */
    private List<String> chunkByCount(String text, int perLine) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        for (int i = 0; i < text.length(); i += perLine) {
            lines.add(text.substring(i, Math.min(text.length(), i + perLine)));
        }
        return lines;
    }

    private void layoutEdgeText(Config config, float extraScale) {
        boolean vertical = "left".equals(content.dock) || "right".equals(content.dock);
        fpx = fontPx(config, content.dock, content.afterClass, extraScale);
        gap = Math.max(1f, fpx * GAP_RATE);
        measureTokens(config, vertical, fpx);

        float total = 0f;
        float side = 0f;
        for (float[] m : tokenSizes) {
            if (vertical) {
                total += m[1];
                side = Math.max(side, m[0]);
            } else {
                total += m[0];
                side = Math.max(side, m[1]);
            }
        }
        contentW = vertical ? side + gap * 2 : total + gap * 2;
        contentH = vertical ? total + gap * 2 : side + gap * 2;

        float x = gap;
        float y = gap;
        for (int i = 0; i < tokenSizes.size(); i++) {
            float[] m = tokenSizes.get(i);
            // 横向：以行中线为基准放置每个 token，行高变化时上下对称涨缩（不再顶对齐往下长）；
            // 竖向：本就是一列，按高度累积即可。
            float top = vertical ? y : gap + (side - m[1]) / 2f;
            tokenRects.add(new RectF(x, top, x + m[0], top + m[1]));
            if (vertical) y += m[1];
            else x += m[0];
        }

        if (!content.afterClass && config.showProgressOnClass && content.state != null) {
            float pw = Math.max(2f, fpx * GAP_RATE);
            float ratio = content.state.ratio();
            if ("upper".equals(content.dock)) {
                float length = contentW * ratio;
                barRect = new RectF((contentW - length) / 2f, contentH - pw,
                        (contentW + length) / 2f, contentH);
            } else {
                float length = contentH * ratio;
                float left = "left".equals(content.dock) ? contentW - pw : 0f;
                barRect = new RectF(left, (contentH - length) / 2f,
                        left + pw, (contentH + length) / 2f);
            }
        }
    }

    private void layoutEdgeBar(Config config, float extraScale) {
        boolean vertical = "left".equals(content.dock) || "right".equals(content.dock);
        fpx = fontPx(config, content.dock, content.afterClass, extraScale);
        gap = Math.max(1f, fpx * GAP_RATE);
        measureTokens(config, vertical, fpx);

        float total = 0f;
        float side = 0f;
        for (float[] m : tokenSizes) {
            if (vertical) {
                total += m[1];
                side = Math.max(side, m[0]);
            } else {
                total += m[0];
                side = Math.max(side, m[1]);
            }
        }
        float thickness = Math.max(1f, config.progressWidth * density * extraScale);
        float maxLen = (vertical ? availableH : availableW) - gap * 2;
        if (maxLen > 0 && total > maxLen) {
            total = maxLen;
        }
        if (vertical) {
            contentW = thickness;
            contentH = total + gap * 2;
        } else {
            contentW = total + gap * 2;
            contentH = thickness;
        }
        float ratio = content.state == null ? 0f : content.state.ratio();
        if (vertical) {
            float length = contentH * ratio;
            barRect = new RectF(0, (contentH - length) / 2f, contentW, (contentH + length) / 2f);
        } else {
            float length = contentW * ratio;
            barRect = new RectF((contentW - length) / 2f, 0,
                    (contentW + length) / 2f, contentH);
        }
    }

    private void layoutCenterSimple(Config config, float extraScale) {
        fpx = fontPx(config, "center", content.afterClass, extraScale);
        gap = Math.max(4f, fpx * GAP_RATE);
        measureTokens(config, false, fpx);

        float rowW = 0f;
        float rowH = 0f;
        for (float[] m : tokenSizes) {
            rowW += m[0];
            rowH = Math.max(rowH, m[1]);
        }
        float scheduleW = rowW + gap * 2;
        float scheduleH = rowH + gap * 2;

        java.util.Calendar now = java.util.Calendar.getInstance();
        if (config.timeOffsetSeconds != 0) {
            now.add(java.util.Calendar.SECOND, config.timeOffsetSeconds);
        }
        clockText = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                now.get(java.util.Calendar.HOUR_OF_DAY),
                now.get(java.util.Calendar.MINUTE),
                now.get(java.util.Calendar.SECOND));
        clockSize = fpx * 2f;
        float clockW = textWidth(clockText, clockSize, digitTypeface);
        float clockH = textHeight(clockSize, digitTypeface);

        contentW = Math.max(scheduleW, clockW + gap * 2);
        float barH = Math.max(2f, fpx / 3f);
        contentH = gap + barH + gap + clockH + gap + scheduleH + gap;

        float ratio = content.state == null ? 0f : content.state.ratio();
        float length = contentW * ratio;
        barRect = new RectF((contentW - length) / 2f, gap, (contentW + length) / 2f, gap + barH);

        clockRect = new RectF((contentW - clockW) / 2f, gap + barH + gap,
                (contentW + clockW) / 2f, gap + barH + gap + clockH);

        float x = gap + Math.max(0f, (contentW - scheduleW) / 2f);
        float y = gap + barH + gap + clockH + gap + gap;
        for (int i = 0; i < tokenSizes.size(); i++) {
            float[] m = tokenSizes.get(i);
            tokenRects.add(new RectF(x, y, x + m[0], y + m[1]));
            x += m[0];
        }
    }

    private void layoutCenterEditor(Config config, float extraScale) {
        fpx = fontPx(config, "center", content.afterClass, extraScale);
        gap = Math.max(4f, fpx * GAP_RATE);
        measureTokens(config, false, fpx);

        float scheduleW = gap;
        float scheduleH = gap;
        for (float[] m : tokenSizes) {
            scheduleW += m[0];
            scheduleH = Math.max(scheduleH, m[1] + gap);
        }
        scheduleW += gap;

        float smallSize = Math.max(4f, fpx * 0.5f);
        float arrowW = textWidth("<", smallSize, digitTypeface);
        float arrowH = textHeight(smallSize, digitTypeface);
        float dateW = textWidth(content.dateText, smallSize, digitTypeface);
        float dateH = textHeight(smallSize, digitTypeface);
        float rowW = gap + arrowW + 10 + dateW + 10 + arrowW + gap;
        float rowH = Math.max(arrowH, dateH) + gap;

        float width = Math.max(scheduleW, rowW + gap * 2);

        float selW = fpx;
        float selH = fpx;
        float candidateWrap = Math.max(10f, fpx * 1.5f / TK_POINT_TO_PX);
        for (String name : content.candidates) {
            float size = tokenSize(name, fpx, false);
            List<String> lines = wrapLines(name, size, candidateWrap, tokenTypeface);
            float[] block = measureBlock(lines, size, tokenTypeface);
            candidateSizes.add(new float[]{block[0], block[1], size});
            candidateLines.add(lines);
            selW = Math.max(selW, block[0]);
            selH = Math.max(selH, block[1]);
        }
        int perRow = 6;
        width = Math.max(width, gap * 2 + perRow * (selW + gap));

        float y = gap + rowH + gap;
        float x = (width - scheduleW) / 2f;
        for (int i = 0; i < tokenSizes.size(); i++) {
            float[] m = tokenSizes.get(i);
            tokenRects.add(new RectF(x, y, x + m[0], y + m[1]));
            x += m[0];
        }
        y += scheduleH + gap;

        float cellW = Math.max(1f, (width - gap * 2) / perRow);
        float bottom = y;
        for (int i = 0; i < content.candidates.size(); i++) {
            float[] block = i < candidateSizes.size()
                    ? candidateSizes.get(i) : new float[]{fpx, fpx, fpx};
            float w = block[0];
            float h = block[1];
            int row = i / perRow;
            int col = i % perRow;
            float rowY = y + row * (selH + gap);
            float cx = gap + col * cellW + (cellW - w) / 2f;
            float cy = rowY + (selH - h) / 2f;
            candidateRects.add(new RectF(cx, cy, cx + w, cy + h));
            bottom = Math.max(bottom, rowY + selH + gap);
        }

        float dateX = (width - rowW) / 2f;
        leftArrowRect = new RectF(dateX, gap, dateX + arrowW + 8, gap + rowH);
        float dateLeft = dateX + arrowW + 10;
        rightArrowRect = new RectF(dateLeft + dateW + 10, gap,
                dateLeft + dateW + 10 + arrowW + 8, gap + rowH);
        float marginW = Math.max(1f, (width - rowW) / 2f);
        marginLeftRect = new RectF(0, gap, marginW, gap + rowH);
        marginRightRect = new RectF(width - marginW, gap, width, gap + rowH);

        contentW = width;
        contentH = bottom;

        if (DEBUG_LOG_EDITOR) {
            StringBuilder sb = new StringBuilder("editor rects tokens=");
            for (RectF r : tokenRects) {
                sb.append(String.format(java.util.Locale.US, "(%.0f,%.0f-%.0f,%.0f)",
                        r.left, r.top, r.right, r.bottom));
            }
            sb.append(" candidates=");
            for (RectF r : candidateRects) {
                sb.append(String.format(java.util.Locale.US, "(%.0f,%.0f-%.0f,%.0f)",
                        r.left, r.top, r.right, r.bottom));
            }
            android.util.Log.i("ClassFrame", sb.toString());
        }
    }

    private void layoutPrompt(Config config, float extraScale) {
        boolean vertical = "left".equals(content.dock) || "right".equals(content.dock);
        fpx = fontPx(config, content.dock, content.afterClass, extraScale);
        gap = Math.max(4f, fpx * GAP_RATE);

        String prompt = config.startPrompt;
        String name = content.promptName == null ? "" : content.promptName;
        float promptSize = tokenSize(prompt, fpx, vertical);
        float nameSize = tokenSize(name, fpx, vertical);
        float pw = textWidth(prompt, promptSize, tokenTypeface);
        float ph = textHeight(promptSize, tokenTypeface);
        float nw = textWidth(name, nameSize, tokenTypeface);
        float nh = textHeight(nameSize, tokenTypeface);

        if (vertical) {
            contentW = pw + nw + gap * 3;
            contentH = Math.max(ph, nh) + gap * 2;
            tokenRects.add(new RectF(gap, (contentH - ph) / 2f, gap + pw, (contentH + ph) / 2f));
            tokenRects.add(new RectF(gap + pw + gap, (contentH - nh) / 2f,
                    gap + pw + gap + nw, (contentH + nh) / 2f));
        } else {
            contentW = Math.max(pw, nw);
            contentH = gap + ph + gap + nh + gap;
            tokenRects.add(new RectF((contentW - pw) / 2f, gap,
                    (contentW + pw) / 2f, gap + ph));
            tokenRects.add(new RectF((contentW - nw) / 2f, gap + ph + gap,
                    (contentW + nw) / 2f, gap + ph + gap + nh));
        }
        tokenSizes.add(new float[]{pw, ph, promptSize});
        tokenSizes.add(new float[]{nw, nh, nameSize});
    }

    private void layoutCountdown(Config config, float extraScale) {
        boolean vertical = "left".equals(content.dock) || "right".equals(content.dock);
        fpx = fontPx(config, content.dock, true, extraScale);
        gap = Math.max(2f, fpx * GAP_RATE);

        OverlayState state = content.state;
        boolean closing = state != null && state.closing;
        boolean showTime = closing
                || (content.afterClass && config.showCountdownAfterClass
                && (state == null || state.offPromptLeft <= 0));

        if (showTime && state != null) {
            String mmss = String.format(java.util.Locale.US, "%02d:%02d",
                    ((int) Math.max(0, state.leftSec)) / 60, ((int) Math.max(0, state.leftSec)) % 60);
            countdownText = vertical ? mmss.substring(0, 2) + "\n\n" + mmss.substring(3) : mmss;
            countdownSize = fpx;
            countdownMono = true;
        } else {
            countdownText = config.endPrompt;
            countdownSize = fpx;
            countdownMono = false;
            if (vertical) {
                // 与 Python 一致：纵向的下课提示按 fpx*1.5 折行
                List<String> wrapped = wrapLines(countdownText, countdownSize,
                        Math.max(10f, fpx * 1.5f / TK_POINT_TO_PX), tokenTypeface);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wrapped.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(wrapped.get(i));
                }
                countdownText = sb.toString();
            }
        }

        Typeface tf = countdownMono ? digitTypeface : tokenTypeface;
        float w = 0f;
        float h = 0f;
        for (String line : countdownText.split("\n", -1)) {
            if (line.isEmpty()) {
                h += countdownSize * 0.6f;
                continue;
            }
            w = Math.max(w, textWidth(line, countdownSize, tf));
            h += textHeight(countdownSize, tf);
        }
        countdownTextW = w;
        countdownTextH = h;

        contentW = w + gap * 2;
        if (vertical && content.clampWidthPx > 0) {
            // 与 Python 一致：竖向时窗口宽度直接等于主课表条宽度，数字更宽则被裁掉
            contentW = Math.max(1, content.clampWidthPx);
        }
        contentH = h + gap * 2;
        if (!vertical && content.clampHeightPx > 0) {
            // 与 Python 一致：上方停靠时窗口高度与主课表条一致
            contentH = Math.max(1, content.clampHeightPx);
        }
    }

    private String displayToken(String token, boolean vertical) {
        if (token == null) return "";
        if (vertical && "|".equals(token)) return "—";
        return token;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 关键：窗口在动画期间尺寸一直在变，View 必须"填满窗口"，
        // 这样黑色背景会跟着窗口一起渐变（与 Python 的 Tk 窗口一致）；
        // 内容仍按新布局锚定在左上角绘制，超出的部分被窗口裁掉。
        int w = contentWidth();
        int h = contentHeight();
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            w = Math.max(1, MeasureSpec.getSize(widthMeasureSpec));
        }
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            h = Math.max(1, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(w, h);
    }

    /** 内容的目标尺寸（未受窗口动画影响），供运动控制器设定窗口目标几何。 */
    public int contentWidth() {
        return Math.max(1, Math.round(contentW));
    }

    public int contentHeight() {
        return Math.max(1, Math.round(contentH));
    }

    /** 当前内容实际使用的缩放系数（供倒计时窗口共用）。 */
    public float appliedScale() {
        return appliedScale;
    }

    /**
     * 动画中的可见尺寸。窗口可能比可见区域大（为避免逐帧 resize），
     * 因此背景与内容都按这个尺寸绘制并裁剪。
     */
    public void setAnimatedRect(int x, int y, int w, int h) {
        if (w == animatedW && h == animatedH && x == animatedX && y == animatedY) return;
        animatedX = x;
        animatedY = y;
        animatedW = w;
        animatedH = h;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left = animatedW > 0 ? Math.max(0, animatedX) : 0;
        int top = animatedW > 0 ? Math.max(0, animatedY) : 0;
        int w = animatedW > 0 ? Math.min(animatedW, getWidth() - left) : getWidth();
        int h = animatedH > 0 ? Math.min(animatedH, getHeight() - top) : getHeight();
        canvas.save();
        canvas.clipRect(left, top, left + w, top + h);
        canvas.drawRect(left, top, left + w, top + h, bgPaint);
        canvas.translate(left, top);
        if (content.config == null) {
            canvas.restore();
            return;
        }

        switch (content.kind) {
            case EDGE_TEXT:
                drawTokens(canvas);
                break;
            case EDGE_BAR:
                drawBar(canvas, COLOR_TEXT);
                break;
            case CENTER_SIMPLE:
                drawBar(canvas, COLOR_TEXT);
                drawClock(canvas);
                drawTokens(canvas);
                break;
            case CENTER_EDITOR:
                drawEditor(canvas);
                break;
            case PROMPT:
                drawPrompt(canvas);
                break;
            case COUNTDOWN:
                drawCountdown(canvas);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void drawBar(Canvas canvas, int color) {
        if (barRect == null) return;
        barPaint.setColor(color);
        canvas.drawRect(barRect, barPaint);
    }

    private void drawTokens(Canvas canvas) {
        for (int i = 0; i < tokenRects.size() && i < tokenSizes.size(); i++) {
            drawTokenBlock(canvas, tokenRects.get(i), tokenSizes.get(i)[2],
                    i < tokenLines.size() ? tokenLines.get(i) : null,
                    i == content.highlightToken ? COLOR_HIGHLIGHT : COLOR_TEXT);
        }
        if (content.kind == Kind.EDGE_TEXT && barRect != null) {
            drawBar(canvas, COLOR_BAR_SOFT);
        }
    }

    /** 绘制一个（可能折行的）文本块；各行在自身宽度内水平居中，与 Tk Label 的 anchor=center 一致。 */
    private void drawTokenBlock(Canvas canvas, RectF rect, float size,
                                List<String> lines, int color) {
        paint.setTypeface(tokenTypeface);
        paint.setTextSize(size);
        paint.setColor(color);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * lineHeightScale;
        float y = rect.top;
        if (lines == null || lines.isEmpty()) return;
        for (String line : lines) {
            float w = paint.measureText(line);
            float x = rect.left + (rect.width() - w) / 2f;
            canvas.drawText(line, x, y - fm.ascent, paint);
            y += lineHeight;
        }
    }

    private void drawClock(Canvas canvas) {
        if (clockRect == null) return;
        paint.setTypeface(digitTypeface);
        paint.setTextSize(clockSize);
        paint.setColor(COLOR_TEXT);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(clockText, clockRect.left, clockRect.top - fm.ascent, paint);
    }

    private void drawPrompt(Canvas canvas) {
        Config config = content.config;
        paint.setTypeface(tokenTypeface);
        String[] texts = {config.startPrompt, content.promptName == null ? "" : content.promptName};
        for (int i = 0; i < tokenRects.size() && i < texts.length && i < tokenSizes.size(); i++) {
            RectF rect = tokenRects.get(i);
            paint.setTextSize(tokenSizes.get(i)[2]);
            paint.setColor(COLOR_HIGHLIGHT);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(texts[i], rect.left, rect.top - fm.ascent, paint);
        }
    }

    private void drawCountdown(Canvas canvas) {
        Typeface tf = countdownMono ? digitTypeface : tokenTypeface;
        paint.setTypeface(tf);
        paint.setTextSize(countdownSize);
        paint.setColor(COLOR_HIGHLIGHT);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * lineHeightScale;
        float y = Math.max(gap, (contentH - countdownTextH) / 2f);
        float centerX = contentW / 2f;
        for (String line : countdownText.split("\n", -1)) {
            if (!line.isEmpty()) {
                float w = paint.measureText(line);
                canvas.drawText(line, centerX - w / 2f, y - fm.ascent, paint);
            }
            y += line.isEmpty() ? countdownSize * 0.6f : lineHeight;
        }
    }

    private void drawEditor(Canvas canvas) {
        Config config = content.config;
        float smallSize = Math.max(4f, fpx * 0.5f);

        paint.setTypeface(tokenTypeface);
        for (int i = 0; i < tokenRects.size() && i < tokenSizes.size(); i++) {
            drawTokenBlock(canvas, tokenRects.get(i), tokenSizes.get(i)[2],
                    i < tokenLines.size() ? tokenLines.get(i) : null,
                    i == content.highlightToken ? COLOR_HIGHLIGHT : COLOR_TEXT);
        }

        for (int i = 0; i < candidateRects.size() && i < content.candidates.size(); i++) {
            float[] block = i < candidateSizes.size()
                    ? candidateSizes.get(i) : new float[]{fpx, fpx, fpx};
            drawTokenBlock(canvas, candidateRects.get(i), block[2],
                    i < candidateLines.size() ? candidateLines.get(i) : null, COLOR_TEXT);
        }

        paint.setTypeface(digitTypeface);
        paint.setTextSize(smallSize);
        paint.setColor(COLOR_TEXT);
        Paint.FontMetrics fm = paint.getFontMetrics();
        if (leftArrowRect != null) {
            canvas.drawText("<", leftArrowRect.left, leftArrowRect.top - fm.ascent, paint);
        }
        if (rightArrowRect != null) {
            float w = paint.measureText(">");
            canvas.drawText(">", rightArrowRect.left, rightArrowRect.top - fm.ascent, paint);
        }
        float dateW = paint.measureText(content.dateText);
        float dateX = (contentW - dateW) / 2f;
        canvas.drawText(content.dateText, dateX, gap - fm.ascent, paint);

        if (candidateDrag && draggingCourse != null) {
            paint.setTypeface(tokenTypeface);
            float size = tokenSize(draggingCourse, fpx, false);
            paint.setTextSize(size);
            paint.setColor(COLOR_HIGHLIGHT);
            Paint.FontMetrics dfm = paint.getFontMetrics();
            canvas.drawText(draggingCourse, dragLocalX, dragLocalY - dfm.ascent, paint);
        }
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (content.config == null) return false;
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        int[] location = new int[2];
        getLocationOnScreen(location);
        float localX = rawX - location[0];
        float localY = rawY - location[1];

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = rawX;
                downRawY = rawY;
                maxMoved = 0f;
                pendingDateShift = 0;
                candidateDrag = false;
                windowDrag = false;
                if (content.kind == Kind.CENTER_EDITOR) {
                    if (leftArrowRect != null && leftArrowRect.contains(localX, localY)) {
                        pendingDateShift = -1;
                        return true;
                    }
                    if (rightArrowRect != null && rightArrowRect.contains(localX, localY)) {
                        pendingDateShift = 1;
                        return true;
                    }
                    for (int i = 0; i < candidateRects.size() && i < content.candidates.size(); i++) {
                        if (candidateRects.get(i).contains(localX, localY)) {
                            candidateDrag = true;
                            draggingCourse = content.candidates.get(i);
                            dragLocalX = localX;
                            dragLocalY = localY;
                            invalidate();
                            return true;
                        }
                    }
                    boolean onMargin = (marginLeftRect != null && marginLeftRect.contains(localX, localY))
                            || (marginRightRect != null && marginRightRect.contains(localX, localY));
                    if (!onMargin) return true;
                }
                windowDrag = true;
                if (listener != null) listener.onTouchDown();
                return true;

            case MotionEvent.ACTION_MOVE:
                maxMoved = Math.max(maxMoved, (float) Math.hypot(rawX - downRawX, rawY - downRawY));
                if (candidateDrag) {
                    dragLocalX = localX;
                    dragLocalY = localY;
                    invalidate();
                } else if (windowDrag && listener != null) {
                    listener.onTouchMove(rawX, rawY);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (pendingDateShift != 0) {
                    if (listener != null) listener.onEditorDateShift(pendingDateShift);
                    pendingDateShift = 0;
                    return true;
                }
                if (candidateDrag) {
                    String course = draggingCourse;
                    candidateDrag = false;
                    draggingCourse = null;
                    int target = nearestToken(localX, localY);
                    invalidate();
                    if (target >= 0 && course != null && listener != null) {
                        listener.onEditorReplace(target, course);
                    }
                    return true;
                }
                if (windowDrag && listener != null) {
                    listener.onTouchUp(maxMoved <= 3f);
                    windowDrag = false;
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                candidateDrag = false;
                draggingCourse = null;
                if (windowDrag && listener != null) listener.onTouchUp(false);
                windowDrag = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private int nearestToken(float localX, float localY) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < tokenRects.size() && i < content.tokens.size(); i++) {
            if (Config.isSpecialToken(content.tokens.get(i))) continue;
            RectF rect = tokenRects.get(i);
            float dx = rect.left - localX;
            float dy = rect.top - localY;
            float dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        float threshold = Math.max(10000f, fpx * fpx * 8f);
        return bestDist < threshold ? best : -1;
    }
}
