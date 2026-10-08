package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

/**
 * iOS 风格开关。
 * 参考 CSS switch 动画：灰→绿轨道、白色圆钮带投影、
 * 圆钮内叉/勾图标随进度缩放、cubic-bezier 回弹插值（用 OvershootInterpolator 近似）。
 */
public class IOSSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    // 配色
    private static final int COLOR_TRACK_OFF = 0xFF838383;  // 关：灰
    private static final int COLOR_TRACK_ON = 0xFF34C759;   // 开：iOS 绿
    private static final int COLOR_KNOB = 0xFFFFFFFF;       // 圆钮白
    private static final int COLOR_KNOB_SHADOW = 0x73929292;
    private static final int COLOR_CROSS = 0xFF838383;      // 圆钮内叉
    private static final int COLOR_CHECK = 0xFF34C759;      // 圆钮内勾

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();

    private boolean checked = true;
    private float progress = 1f;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    public IOSSwitch(Context context) {
        super(context);
        init();
    }

    public IOSSwitch(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public IOSSwitch(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        // 圆钮投影需要软件层
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        knobPaint.setColor(COLOR_KNOB);
        knobPaint.setShadowLayer(dp(1.5f), 0, dp(1f), COLOR_KNOB_SHADOW);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(1.6f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
        this.progress = checked ? 1f : 0f;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    /** 外部触发行点击时调用 */
    public void toggle() {
        performClick();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize((int) dp(46), widthMeasureSpec);
        int h = resolveSize((int) dp(24), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = h / 2f;
        float t = clamp(progress);

        // 1. 轨道：灰 → 绿
        trackPaint.setColor(blend(COLOR_TRACK_OFF, COLOR_TRACK_ON, t));
        canvas.drawRoundRect(0, 0, w, h, radius, radius, trackPaint);

        // 2. 白色圆钮（带投影），左右各留 3dp
        float d = h - dp(6);
        float offset = dp(3);
        float x = offset + (w - d - 2 * offset) * progress; // 允许轻微回弹越过端点
        float knobCx = x + d / 2f;
        float cy = h / 2f;
        canvas.drawCircle(knobCx, cy, d / 2f, knobPaint);

        // 3. 圆钮内图标：叉随关闭进度出现，勾随开启进度出现
        float crossS = dp(3f);
        iconPaint.setColor(COLOR_CROSS);
        drawCross(canvas, knobCx, cy, crossS, 1f - t);
        float checkS = dp(4.5f);
        iconPaint.setColor(COLOR_CHECK);
        drawCheck(canvas, knobCx, cy, checkS, t);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float s, float scale) {
        if (scale <= 0.02f) return;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(clamp(scale), clamp(scale));
        iconPath.reset();
        iconPath.moveTo(-s, -s);
        iconPath.lineTo(s, s);
        iconPath.moveTo(-s, s);
        iconPath.lineTo(s, -s);
        canvas.drawPath(iconPath, iconPaint);
        canvas.restore();
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float s, float scale) {
        if (scale <= 0.02f) return;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(clamp(scale), clamp(scale));
        iconPath.reset();
        iconPath.moveTo(-s * 0.9f, 0);
        iconPath.lineTo(-s * 0.1f, s * 0.75f);
        iconPath.lineTo(s * 0.9f, -s * 0.75f);
        canvas.drawPath(iconPath, iconPaint);
        canvas.restore();
    }

    @Override
    public boolean performClick() {
        boolean target = !checked;
        checked = target;
        animateTo(target ? 1f : 0f);
        if (listener != null) listener.onCheckedChanged(checked);
        return super.performClick();
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(220L);
        // 近似 cubic-bezier(0.27, 0.2, 0.25, 1.51) 的回弹手感
        animator.setInterpolator(new OvershootInterpolator(1.1f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int blend(int from, int to, float t) {
        float c = clamp(t);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * c);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * c);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * c);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * c);
        return Color.argb(a, r, g, b);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
