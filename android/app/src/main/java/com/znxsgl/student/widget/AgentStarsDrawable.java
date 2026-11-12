package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 底部导航「AI 页签」的星星思考动画（Drawable 版）。
 *
 * 复用 AI 助手思考状态的星星节奏：五颗四点闪光错峰放大、摇摆、熄灭，
 * 作为 BottomNavigationView 的菜单图标由 Material 直接绘制，
 * 保留原有的图标放大、弹性点击与爆发动效。
 */
public class AgentStarsDrawable extends Drawable {

    /** 每颗星星的闪烁周期 */
    private static final long PERIOD_MS = 1400L;

    /** 五颗星星的位置（宽高百分比）与相对大小 */
    private static final float[][] STARS = {
            {50f, 46f, 1f},
            {18f, 22f, 0.55f},
            {82f, 26f, 0.45f},
            {78f, 76f, 0.55f},
            {22f, 78f, 0.4f},
    };

    private static final int COLOR_MAIN = 0xFF0A84FF; // 导航主色蓝
    private static final int COLOR_SOFT = 0xFF5AB0FF; // 小星星浅蓝

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final long startAt = SystemClock.uptimeMillis();
    private ValueAnimator animator;
    /** 是否处于思考/回答状态：false 时只绘制「中间主星最大」的静止帧 */
    private boolean animating = false;
    /** 外层（图标过渡）传入的整体透明度 */
    private int viewAlpha = 255;

    /** 静止帧对应的时间点：主星刚好放大到最大（相位约 0.45） */
    private static final float STATIC_FRAME_MS = PERIOD_MS * 0.45f;

    public AgentStarsDrawable() {
        paint.setStyle(Paint.Style.FILL);
        buildStarPath();
    }

    /** 与 AI 助手思考动画一致的 24×24 四点闪光路径 */
    private void buildStarPath() {
        starPath.moveTo(12f, 0f);
        starPath.cubicTo(13f, 7f, 17f, 11f, 24f, 12f);
        starPath.cubicTo(17f, 13f, 13f, 17f, 12f, 24f);
        starPath.cubicTo(11f, 17f, 7f, 13f, 0f, 12f);
        starPath.cubicTo(7f, 11f, 11f, 7f, 12f, 0f);
        starPath.close();
    }

    /** 只在 AI 思考/回答期间播放动画；其余时间停在主星最大的静止帧 */
    public void setAnimating(boolean animating) {
        if (this.animating == animating) return;
        this.animating = animating;
        if (animating) {
            ensureAnimating();
        } else {
            stop();
        }
        invalidateSelf();
    }

    /** 动画是否在跑：由第一帧绘制时惰性启动，避免菜单创建前空转 */
    private void ensureAnimating() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> {
            if (getCallback() == null) {
                // 图标已从窗口移除，停止动画避免泄漏
                ((ValueAnimator) a).cancel();
                animator = null;
                return;
            }
            invalidateSelf();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (animating) ensureAnimating();
        Rect b = getBounds();
        if (b.isEmpty()) return;

        float w = b.width();
        float h = b.height();
        float base = Math.min(w, h) * 0.62f;
        long elapsed = animating ? (SystemClock.uptimeMillis() - startAt) : (long) STATIC_FRAME_MS;

        for (int i = 0; i < STARS.length; i++) {
            float phase = phaseAt(elapsed, i);
            float alpha;
            float scale;
            float rotate;
            if (phase < 0.35f) {
                float t = smooth(phase / 0.35f);
                alpha = t;
                scale = 0.25f + 0.75f * t;
                rotate = -18f + 24f * t;
            } else if (phase < 0.62f) {
                float t = (phase - 0.35f) / 0.27f;
                alpha = 1f - 0.08f * t;
                scale = 1f - 0.12f * t;
                rotate = 6f - 10f * t;
            } else {
                float t = (phase - 0.62f) / 0.38f;
                alpha = 0.92f * (1f - t);
                scale = 0.88f - 0.63f * t;
                rotate = -4f + 22f * t;
            }
            if (alpha <= 0.01f) continue;

            float cx = b.left + STARS[i][0] / 100f * w;
            float cy = b.top + STARS[i][1] / 100f * h;
            float size = base * STARS[i][2] * scale;

            paint.setColor(i == 0 ? COLOR_MAIN : COLOR_SOFT);
            paint.setAlpha((int) Math.min(255, alpha * 255 * (viewAlpha / 255f)));
            int save = canvas.save();
            canvas.rotate(rotate, cx, cy);
            canvas.translate(cx - size / 2f, cy - size / 2f);
            canvas.scale(size / 24f, size / 24f);
            canvas.drawPath(starPath, paint);
            canvas.restoreToCount(save);
        }
    }

    private float phaseAt(long elapsedMs, int index) {
        float delay = index * PERIOD_MS * 0.7f / STARS.length;
        float elapsed = (elapsedMs - delay) % PERIOD_MS;
        if (elapsed < 0) elapsed += PERIOD_MS;
        return elapsed / PERIOD_MS;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    @Override
    public void setAlpha(int alpha) {
        viewAlpha = alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
