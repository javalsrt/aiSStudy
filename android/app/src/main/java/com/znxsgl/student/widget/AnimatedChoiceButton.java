package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 判断题选项按钮。
 * 1:1 还原 uiverse red-stingray-4 的填充机制：
 * 蓝色先铺满胶囊，再用一个「中心透明圆孔」从大到小收缩（对应原版
 * 20em 大圆 + inset box-shadow 10em 的虹膜式向内填充），
 * 视觉上蓝色从四周边缘向中心合拢；文字随进度渐变（原版 0.3s + 0.1s 延迟）。
 */
public class AnimatedChoiceButton extends AppCompatTextView {

    // 统一配色（与 AnimatedOptionButton 一致）
    private static final int COLOR_BG_IDLE = 0xFFF0F0F4;      // 浅灰底
    private static final int COLOR_FILL = 0xFFA9D2FF;         // 浅蓝填充
    private static final int COLOR_TEXT_IDLE = 0xFF1D1D1F;    // 墨色文字
    private static final int COLOR_TEXT_SELECTED = 0xFF0A84FF;// 选中主蓝

    private static final long FILL_DURATION_MS = 500L;        // 原版 transition: 0.5s ease-out

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float fillProgress = 0f;
    private boolean choiceSelected = false;
    private ValueAnimator animator;

    public AnimatedChoiceButton(Context context) {
        super(context);
        init();
    }

    public AnimatedChoiceButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedChoiceButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setTextColor(COLOR_TEXT_IDLE);
        setBackgroundColor(Color.TRANSPARENT);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        boolean changed = choiceSelected != selected;
        choiceSelected = selected;
        cancelAnimator();
        if (changed) {
            // 点击选中/取消时正常播放一次填充动画
            animateFillTo(selected ? 1f : 0f);
        } else {
            fillProgress = selected ? 1f : 0f;
            updateTextColor();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }
        float radius = h / 2f; // 胶囊全圆角
        rect.set(0, 0, w, h);

        // 1. 基底：浅灰胶囊
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG_IDLE);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 2. 虹膜式填充：离屏层画蓝色胶囊，再用收缩的透明圆孔挖掉中心
        //    progress 0 → 孔最大（全灰）；progress 1 → 孔消失（全蓝）
        if (fillProgress > 0f) {
            int saveCount = canvas.saveLayer(rect, null);
            paint.setColor(COLOR_FILL);
            canvas.drawRoundRect(rect, radius, radius, paint);
            float holeRadius = (float) Math.hypot(w, h) / 2f * (1f - fillProgress);
            if (holeRadius > 0f) {
                canvas.drawCircle(w / 2f, h / 2f, holeRadius, clearPaint);
            }
            canvas.restoreToCount(saveCount);
        }

        super.onDraw(canvas);
    }

    private void animateFillTo(float target) {
        cancelAnimator();
        animator = ValueAnimator.ofFloat(fillProgress, target);
        animator.setDuration(FILL_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1f)); // ease-out
        animator.addUpdateListener(a -> {
            fillProgress = (float) a.getAnimatedValue();
            updateTextColor();
            invalidate();
        });
        animator.start();
    }

    private void updateTextColor() {
        // 原版文字 0.3s 完成变色（比填充快），映射为进度前 60% 完成渐变
        float t = Math.min(1f, fillProgress / 0.6f);
        setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_SELECTED, t));
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
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }
}
