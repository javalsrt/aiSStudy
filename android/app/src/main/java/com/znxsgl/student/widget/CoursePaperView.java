package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * 学习页的课程纸卡：暖白纸张 + 课程名 + 教师 + 装饰线。
 * 可在父容器内自由拖动；轻点（未拖动）进入课程。
 */
public class CoursePaperView extends View {

    public interface Listener {
        void onOpen(Object item);
    }

    /** 拖动结束回调：宿主据此把卡片置顶并记住位置 */
    public interface OnMovedListener {
        void onMoved(CoursePaperView card);
    }

    private static final float RADIUS_DP = 15f;
    /** 四角长按拖动改大小的判定区域边长 */
    private static final float CORNER_ZONE_DP = 30f;
    private static final int CORNER_NONE = 0, CORNER_TL = 1, CORNER_TR = 2, CORNER_BL = 3, CORNER_BR = 4;
    private static final int MODE_NONE = 0, MODE_RESIZE = 1;

    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint recentCaptionPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint recentPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final RectF pill = new RectF();
    private final List<String> lines = new ArrayList<>(2);

    private final float density = getResources().getDisplayMetrics().density;

    private Object item;
    private String title = "";
    private String subtitle = "";
    /** 「最近学习」章节文字；空字符串表示不显示 */
    private String recentChapter = "";
    private Listener listener;
    private OnMovedListener movedListener;

    private float downRawX, downRawY, startTx, startTy;
    private boolean dragging;
    private boolean pressed;
    /** 静置时的层级：按下抬起后要还原成这个值，否则卡片会沉到相邻卡片下面 */
    private float restElevation;

    /** 当前手势模式与按下的角 */
    private int mode = MODE_NONE;
    private int pressedCorner = CORNER_NONE;
    /** 长按改大小的基准尺寸与固定对角（父坐标） */
    private float resizeStartW, resizeStartH;
    private float resizeAnchorX, resizeAnchorY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int longPressTimeout;
    private final Runnable resizeLongPress = () -> {
        if (mode != MODE_NONE || pressedCorner == CORNER_NONE || getParent() == null) return;
        mode = MODE_RESIZE;
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
        setElevation(24 * density);
        resizeStartW = getWidth();
        resizeStartH = getHeight();
        float left = getLeft() + getTranslationX();
        float top = getTop() + getTranslationY();
        // 固定对角，拖动哪个角就只改另外两条边
        resizeAnchorX = (pressedCorner == CORNER_TL || pressedCorner == CORNER_BL)
                ? left + resizeStartW : left;
        resizeAnchorY = (pressedCorner == CORNER_TL || pressedCorner == CORNER_TR)
                ? top + resizeStartH : top;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    public CoursePaperView(Context context) {
        super(context);
        longPressTimeout = ViewConfiguration.get(context).getLongPressTimeout();
        setClickable(true);
        setFocusable(true);
        setElevation(8 * density);
        restElevation = 8 * density;
        // 阴影按圆角轮廓绘制，避免四个角露出直角阴影
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), RADIUS_DP * density);
            }
        });
        setClipToOutline(false);

        paperPaint.setColor(0xFFFFFDF8);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
        edgePaint.setColor(0xFFE7DCC3);

        titlePaint.setColor(0xFF3A3325);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        subPaint.setColor(0xFF8C8168);

        linePaint.setColor(0xFFF0E8D6);

        pillPaint.setColor(0xFFF6ECCE);
        pillTextPaint.setColor(0xFF8A6D33);
        pillTextPaint.setTextAlign(Paint.Align.CENTER);
        pillTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        recentPaint.setColor(0xFF6B614C);
        recentCaptionPaint.setColor(0xFFA79B82);
    }

    /** 绑定课程与标题；subtitle 一般是教师名 */
    public void bind(Object item, String title, String subtitle) {
        this.item = item;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        invalidate();
    }

    public void setOnOpenListener(Listener listener) {
        this.listener = listener;
    }

    /** 卡片上显示的「最近学习」章节；传 null 或空字符串则不显示 */
    public void setRecentChapter(String text) {
        String next = text == null ? "" : text;
        if (!next.equals(recentChapter)) {
            recentChapter = next;
            invalidate();
        }
    }

    public void setOnMovedListener(OnMovedListener listener) {
        this.movedListener = listener;
    }

    /** 由宿主设置在纸堆里的静置层级，抬起后还原，保证纸堆顺序不被打乱 */
    public void setRestElevation(float px) {
        restElevation = px;
        setElevation(px);
    }

    public Object getItem() {
        return item;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        titlePaint.setTextSize(w * 0.075f);
        subPaint.setTextSize(w * 0.049f);
        pillTextPaint.setTextSize(w * 0.047f);
        recentPaint.setTextSize(w * 0.052f);
        recentCaptionPaint.setTextSize(w * 0.038f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = RADIUS_DP * density;
        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density);
        canvas.drawRoundRect(rect, r, r, paperPaint);
        canvas.drawRoundRect(rect, r, r, edgePaint);

        float pad = w * 0.075f;
        float maxW = w - pad * 2;
        wrapTitle(maxW);

        float y = pad + titlePaint.getTextSize() * 0.92f;
        for (String line : lines) {
            canvas.drawText(line, pad, y, titlePaint);
            y += titlePaint.getTextSize() * 1.26f;
        }

        // 底部：教师名 + 进入胶囊
        float bottom = h - pad;
        float pillW = w * 0.29f;
        float pillH = w * 0.115f;
        float contentLimit = bottom - pillH - density * 4;

        if (!recentChapter.isEmpty()) {
            // 有最近学习章节：不再画装饰线，改成「小标题 + 章节名」的排版
            float captionY = y + density * 11;
            float nameY = captionY + density * 16;
            if (nameY <= contentLimit) {
                canvas.drawText("最近学习", pad, captionY, recentCaptionPaint);
                String remain = recentChapter;
                for (int line = 0; line < 2 && !remain.isEmpty() && nameY <= contentLimit; line++) {
                    if (line == 1 || recentPaint.measureText(remain) <= maxW) {
                        canvas.drawText(ellipsize(remain, maxW, recentPaint), pad, nameY, recentPaint);
                        break;
                    }
                    int cut = remain.length();
                    while (cut > 1 && recentPaint.measureText(remain.substring(0, cut)) > maxW) cut--;
                    canvas.drawText(remain.substring(0, cut), pad, nameY, recentPaint);
                    remain = remain.substring(cut);
                    nameY += recentPaint.getTextSize() * 1.18f;
                }
            }
        } else {
            // 文档纸张的淡色装饰线：只有没有章节文字时才画
            y += density * 7;
            float lineH = 3 * density;
            float rr = 1.5f * density;
            canvas.drawRoundRect(pad, y, pad + maxW * 0.94f, y + lineH, rr, rr, linePaint);
            y += density * 12;
            canvas.drawRoundRect(pad, y, pad + maxW * 0.72f, y + lineH - density * 0.4f, rr, rr, linePaint);
        }

        pill.set(w - pad - pillW, bottom - pillH, w - pad, bottom);
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, pillPaint);
        canvas.drawText("进入", pill.centerX(),
                pill.centerY() + pillTextPaint.getTextSize() * 0.36f, pillTextPaint);

        if (!subtitle.isEmpty()) {
            String text = subtitle;
            float subMax = pill.left - pad - density * 8;
            while (subPaint.measureText(text) > subMax && text.length() > 1) {
                text = text.substring(0, text.length() - 2) + "…";
            }
            canvas.drawText(text, pad, bottom - pillH * 0.24f, subPaint);
        }
    }

    /** 单行文字超宽时截断并加省略号 */
    private String ellipsize(String text, float maxW, Paint paint) {
        if (paint.measureText(text) <= maxW) return text;
        String cut = text;
        while (cut.length() > 1 && paint.measureText(cut + "…") > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /** 标题最多两行，第二行放不下时截断加省略号 */
    private void wrapTitle(float maxW) {
        lines.clear();
        if (title.isEmpty()) return;
        String remain = title;
        for (int line = 0; line < 2 && !remain.isEmpty(); line++) {
            if (titlePaint.measureText(remain) <= maxW) {
                lines.add(remain);
                return;
            }
            if (line == 1) {
                String cut = remain;
                while (cut.length() > 1 && titlePaint.measureText(cut + "…") > maxW) {
                    cut = cut.substring(0, cut.length() - 1);
                }
                lines.add(cut + "…");
                return;
            }
            int cut = remain.length();
            while (cut > 1 && titlePaint.measureText(remain.substring(0, cut)) > maxW) cut--;
            lines.add(remain.substring(0, cut));
            remain = remain.substring(cut);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startTx = getTranslationX();
                startTy = getTranslationY();
                dragging = false;
                pressed = true;
                mode = MODE_NONE;
                bringToFront();
                setElevation(22 * density);
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).start();
                // 四个角：长按后拖动改卡片大小
                pressedCorner = cornerAt(event.getX(), event.getY());
                if (pressedCorner != CORNER_NONE) {
                    handler.postDelayed(resizeLongPress, longPressTimeout);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (mode == MODE_RESIZE) {
                    applyResize(dx, dy);
                    return true;
                }
                if (!dragging && Math.hypot(dx, dy) > 6 * density) {
                    dragging = true;
                    handler.removeCallbacks(resizeLongPress);
                }
                if (dragging) {
                    float tx = startTx + dx;
                    float ty = startTy + dy;
                    ViewParent p = getParent();
                    if (p instanceof View) {
                        View parent = (View) p;
                        float margin = 6 * density;
                        float minX = margin - getLeft();
                        float maxX = parent.getWidth() - getWidth() - margin - getLeft();
                        float minY = margin - getTop();
                        float maxY = parent.getHeight() - getHeight() - margin - getTop();
                        if (maxX >= minX) tx = Math.max(minX, Math.min(maxX, tx));
                        if (maxY >= minY) ty = Math.max(minY, Math.min(maxY, ty));
                    }
                    setTranslationX(tx);
                    setTranslationY(ty);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(resizeLongPress);
                boolean wasResize = mode == MODE_RESIZE;
                mode = MODE_NONE;
                pressed = false;
                animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                setElevation(restElevation);
                invalidate();
                if (!dragging && !wasResize && event.getActionMasked() == MotionEvent.ACTION_UP
                        && listener != null) {
                    listener.onOpen(item);
                }
                if ((dragging || wasResize) && movedListener != null) movedListener.onMoved(this);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 判断按下的位置落在哪个角（四角各 30dp 见方） */
    private int cornerAt(float x, float y) {
        float zone = CORNER_ZONE_DP * density;
        float w = getWidth(), h = getHeight();
        if (x <= zone && y <= zone) return CORNER_TL;
        if (x >= w - zone && y <= zone) return CORNER_TR;
        if (x <= zone && y >= h - zone) return CORNER_BL;
        if (x >= w - zone && y >= h - zone) return CORNER_BR;
        return CORNER_NONE;
    }

    /** 长按某个角后：拖动改变宽高，对角的卡片位置保持不动 */
    private void applyResize(float dx, float dy) {
        ViewParent p = getParent();
        if (!(p instanceof View)) return;
        View parent = (View) p;
        int parentW = parent.getWidth(), parentH = parent.getHeight();
        int padL = parent.getPaddingLeft(), padT = parent.getPaddingTop();
        int contentRight = parentW - parent.getPaddingRight();
        int contentBottom = parentH - parent.getPaddingBottom();
        int contentW = contentRight - padL;
        int contentH = contentBottom - padT;
        if (contentW <= 0 || contentH <= 0) return;

        float minW = 112 * density, minH = 84 * density;
        float maxW, maxH;
        switch (pressedCorner) {
            case CORNER_TL: maxW = resizeAnchorX - padL; maxH = resizeAnchorY - padT; break;
            case CORNER_TR: maxW = contentRight - resizeAnchorX; maxH = resizeAnchorY - padT; break;
            case CORNER_BL: maxW = resizeAnchorX - padL; maxH = contentBottom - resizeAnchorY; break;
            default: maxW = contentRight - resizeAnchorX; maxH = contentBottom - resizeAnchorY; break;
        }
        float newW, newH;
        switch (pressedCorner) {
            case CORNER_TL: newW = resizeStartW - dx; newH = resizeStartH - dy; break;
            case CORNER_TR: newW = resizeStartW + dx; newH = resizeStartH - dy; break;
            case CORNER_BL: newW = resizeStartW - dx; newH = resizeStartH + dy; break;
            default: newW = resizeStartW + dx; newH = resizeStartH + dy; break;
        }
        newW = Math.max(minW, Math.min(maxW, newW));
        newH = Math.max(minH, Math.min(maxH, newH));
        if (maxW < minW) newW = maxW;
        if (maxH < minH) newH = maxH;

        int w = Math.round(newW), h = Math.round(newH);
        // Gravity.CENTER 下布局位置随尺寸变化，用平移把固定对角钉回原位
        float layoutLeft = padL + (contentW - w) / 2f;
        float layoutTop = padT + (contentH - h) / 2f;
        float targetLeft, targetTop;
        switch (pressedCorner) {
            case CORNER_TL: targetLeft = resizeAnchorX - w; targetTop = resizeAnchorY - h; break;
            case CORNER_TR: targetLeft = resizeAnchorX; targetTop = resizeAnchorY - h; break;
            case CORNER_BL: targetLeft = resizeAnchorX - w; targetTop = resizeAnchorY; break;
            default: targetLeft = resizeAnchorX; targetTop = resizeAnchorY; break;
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w;
            lp.height = h;
            setLayoutParams(lp);
        }
        setTranslationX(targetLeft - layoutLeft);
        setTranslationY(targetTop - layoutTop);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(resizeLongPress);
        super.onDetachedFromWindow();
    }

    /** 供宿主判断是否正在被拖动 */
    public boolean isDragging() {
        return dragging || pressed;
    }
}
