package com.znxsgl.student.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 长按提交按钮（iOS 扁平胶囊样式）。
 * 视觉参考 CSS 扫过动画：按住时一个斜切色块从左向右扫过填充胶囊，
 * 文字随进度从主蓝渐变为白色；中途松手色块退回，按满触发提交。
 */
public class HoldSubmitButton extends AppCompatTextView {

    public interface OnHoldSubmitListener {
        void onHoldSubmit();
    }

    private static final long HOLD_DURATION_MS = 1500L;

    // 配色（浅蓝底 + 主蓝扫过 + 白字）
    private static final int COLOR_BG = 0xFFA9D2FF;        // 浅蓝底
    private static final int COLOR_SWEEP = 0xFF0A84FF;     // 扫过色块（主蓝）
    private static final int COLOR_TEXT_IDLE = 0xFF0A84FF; // 静止文字（主蓝）
    private static final int COLOR_TEXT_ACTIVE = 0xFFFFFFFF; // 按满文字（白）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private final Path sweepPath = new Path();

    private ValueAnimator holdAnimator;
    private ValueAnimator releaseAnimator;
    private float holdProgress = 0f;
    private boolean submitted = false;
    private boolean holdCancelled = false;
    private OnHoldSubmitListener listener;

    public HoldSubmitButton(Context context) {
        super(context);
        init();
    }

    public HoldSubmitButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HoldSubmitButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setTextColor(COLOR_TEXT_IDLE);
        setTextSize(16f);
        setPadding(0, 0, 0, 0);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setOnHoldSubmitListener(OnHoldSubmitListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }
        float radius = h / 2f; // 胶囊全圆角（与底部导航一致）
        float progress = Math.min(1f, Math.max(0f, holdProgress));

        // 1. 基底：浅蓝胶囊
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 2. 斜切色块从左向右扫过（模拟 CSS ::before translate3d(100%,0,0)）
        if (progress > 0f) {
            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);

            float blockWidth = w * 1.2f;
            float skew = h * 0.577f; // tan(30°) 斜切角
            float left = (-1.25f * w - skew) + progress * (2.25f * w + 2 * skew);
            sweepPath.reset();
            sweepPath.moveTo(left - skew / 2f, 0);
            sweepPath.lineTo(left + blockWidth - skew / 2f, 0);
            sweepPath.lineTo(left + blockWidth + skew / 2f, h);
            sweepPath.lineTo(left + skew / 2f, h);
            sweepPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_SWEEP);
            canvas.drawPath(sweepPath, paint);
            canvas.restore();
        }

        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startHold();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getX() < -dp(24) || event.getX() > getWidth() + dp(24)
                        || event.getY() < -dp(24) || event.getY() > getHeight() + dp(24)) {
                    cancelHold();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!submitted) {
                    cancelHold();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void startHold() {
        submitted = false;
        holdCancelled = false;
        cancelAnimator();
        if (holdProgress >= 1f) {
            holdProgress = 0f;
        }
        long duration = Math.max(80L, (long) (HOLD_DURATION_MS * (1f - holdProgress)));
        holdAnimator = ValueAnimator.ofFloat(holdProgress, 1f);
        holdAnimator.setDuration(duration);
        holdAnimator.addUpdateListener(a -> {
            holdProgress = (float) a.getAnimatedValue();
            float scale = 1f - 0.04f * holdProgress;
            setScaleX(scale);
            setScaleY(scale);
            setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_ACTIVE, holdProgress));
            invalidate();
        });
        holdAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                holdCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!holdCancelled && holdProgress >= 0.999f) {
                    submitted = true;
                    if (listener != null) {
                        listener.onHoldSubmit();
                    }
                    collapseAfterSubmit();
                }
            }
        });
        holdAnimator.start();
    }

    private void collapseAfterSubmit() {
        cancelAnimator();
        releaseAnimator = ValueAnimator.ofFloat(holdProgress, 0f);
        releaseAnimator.setDuration(320L);
        releaseAnimator.addUpdateListener(a -> {
            holdProgress = (float) a.getAnimatedValue();
            float scale = 1f - 0.04f * holdProgress;
            setScaleX(scale);
            setScaleY(scale);
            setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_ACTIVE, holdProgress));
            invalidate();
        });
        releaseAnimator.start();
    }

    private void cancelHold() {
        if (submitted) {
            return;
        }
        cancelAnimator();
        releaseAnimator = ValueAnimator.ofFloat(holdProgress, 0f);
        releaseAnimator.setDuration(220L);
        releaseAnimator.addUpdateListener(a -> {
            holdProgress = (float) a.getAnimatedValue();
            float scale = 1f - 0.04f * holdProgress;
            setScaleX(scale);
            setScaleY(scale);
            setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_ACTIVE, holdProgress));
            invalidate();
        });
        releaseAnimator.start();
    }

    private int blendColor(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped);
        return Color.argb(a, r, g, b);
    }

    private void cancelAnimator() {
        if (holdAnimator != null) {
            holdAnimator.cancel();
            holdAnimator = null;
        }
        if (releaseAnimator != null) {
            releaseAnimator.cancel();
            releaseAnimator = null;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }
}
