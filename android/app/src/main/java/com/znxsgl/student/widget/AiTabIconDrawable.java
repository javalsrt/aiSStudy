package com.znxsgl.student.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.znxsgl.student.R;

/**
 * 底部导航 AI 页签图标：
 * 空闲时显示原本的三颗星图标；AI 思考/回答时平滑过渡到动态星星，
 * 输出结束后再过渡回原图标（配合外层的模糊 RenderEffect 形成模糊过渡）。
 */
public class AiTabIconDrawable extends Drawable implements Drawable.Callback {

    private final Context context;
    private final Drawable original;
    private final AgentStarsDrawable stars;

    /** 0 = 原图标，1 = 动态星星 */
    private float progress = 0f;
    private boolean thinking;
    private boolean active;
    private int alpha = 255;
    private ValueAnimator transition;

    public AiTabIconDrawable(Context context) {
        this.context = context.getApplicationContext();
        Drawable icon = AppCompatResources.getDrawable(context, R.drawable.ic_ai);
        this.original = icon != null ? icon.mutate() : null;
        this.stars = new AgentStarsDrawable();
        if (original != null) original.setCallback(this);
        stars.setCallback(this);
        applyActiveTint();
    }

    /** 跟随导航选中态：选中蓝色、未选中灰色，与原图标行为一致 */
    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        applyActiveTint();
        invalidateSelf();
    }

    private void applyActiveTint() {
        if (original == null) return;
        int color = ContextCompat.getColor(context, active ? R.color.primary : R.color.ink_subtle);
        original.setTint(color);
    }

    public void setThinking(boolean thinking) {
        if (this.thinking == thinking) return;
        this.thinking = thinking;
        if (thinking) stars.setAnimating(true);
        animateTo(thinking ? 1f : 0f);
    }

    private void animateTo(float target) {
        if (transition != null) {
            // 取消旧过渡时先移除监听，避免旧的 onAnimationEnd 把新动画的星星停掉
            transition.removeAllListeners();
            transition.removeAllUpdateListeners();
            transition.cancel();
        }
        transition = ValueAnimator.ofFloat(progress, target);
        transition.setDuration(320L);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        transition.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 完全回到原图标后停止星星动画，空闲时不再逐帧刷新
                if (target <= 0f) stars.setAnimating(false);
            }
        });
        transition.start();
    }

    public void stop() {
        if (transition != null) transition.cancel();
        stars.stop();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        if (original != null) original.setBounds(bounds);
        stars.setBounds(bounds);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (progress < 1f && original != null) {
            original.setAlpha((int) (alpha * (1f - progress)));
            original.draw(canvas);
        }
        if (progress > 0f) {
            stars.setAlpha((int) (alpha * progress));
            stars.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (original != null) original.setColorFilter(colorFilter);
        stars.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // 子 Drawable 的重绘/调度转发给外层图标
    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }
}
