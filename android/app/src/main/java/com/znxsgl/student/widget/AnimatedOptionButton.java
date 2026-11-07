package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 选择题选项按钮（与判断题按钮统一风格）。
 * 未选中为浅灰胶囊，选中后底色与文字颜色平滑淡入为「浅蓝底 + 主蓝字」。
 * 已移除旧的“透明圆孔收缩填充”实现——该动画会在按钮中心挖出白色空洞，观感破碎。
 */
public class AnimatedOptionButton extends AppCompatTextView {

    private static final int COLOR_BG_IDLE = 0xFFF0F0F4;      // 浅灰底
    private static final int COLOR_BG_SELECTED = 0xFFE5F1FF;  // primary_soft 浅蓝
    private static final int COLOR_TEXT_IDLE = 0xFF1D1D1F;    // 墨色文字
    private static final int COLOR_TEXT_SELECTED = 0xFF0A84FF;// 主蓝文字

    private static final long FADE_DURATION_MS = 180L;

    private final GradientDrawable bg = new GradientDrawable();
    private float progress = 0f;
    private boolean optionSelected = false;
    private ValueAnimator animator;

    public AnimatedOptionButton(Context context) {
        super(context);
        init();
    }

    public AnimatedOptionButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedOptionButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        setFocusable(true);
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(COLOR_BG_IDLE);
        setBackground(bg);
        setTextColor(COLOR_TEXT_IDLE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 胶囊全圆角
        bg.setCornerRadius(h / 2f);
    }

    public void setOptionSelected(boolean selected) {
        boolean changed = optionSelected != selected;
        optionSelected = selected;
        cancelAnimator();
        if (changed) {
            animateTo(selected ? 1f : 0f);
        } else {
            // 列表复用回绑时直接落到目标态，避免每次滑动都重新播放动画
            progress = selected ? 1f : 0f;
            applyProgress();
        }
    }

    public boolean isOptionSelected() {
        return optionSelected;
    }

    private void animateTo(float target) {
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(FADE_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            applyProgress();
        });
        animator.start();
    }

    private void applyProgress() {
        bg.setColor(blendColor(COLOR_BG_IDLE, COLOR_BG_SELECTED, progress));
        setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_SELECTED, progress));
    }

    private int blendColor(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * clamped);
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * clamped);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * clamped);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * clamped);
        return android.graphics.Color.argb(a, r, g, b);
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
