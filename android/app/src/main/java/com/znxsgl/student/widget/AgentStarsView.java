package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 星星思考动画（参考 BoardUI AgentThinking 的 stars 变体）：
 * 五颗四点闪光交错地放大、摇摆、熄灭，色调偏灰，读起来安静，不抢正文。
 * 仅作为思考状态的装饰指示器，不参与交互。
 */
public class AgentStarsView extends View {

    /** 每颗星星的闪烁周期 */
    private static final long PERIOD_MS = 1400L;

    /** 五颗星星的位置（宽高百分比）与相对大小，主星在中间，四颗小星环绕 */
    private static final float[][] STARS = {
            {50f, 46f, 1f},
            {18f, 22f, 0.55f},
            {82f, 26f, 0.45f},
            {78f, 76f, 0.55f},
            {22f, 78f, 0.4f},
    };

    /** 单颗星星的标准尺寸（dp） */
    private static final float STAR_SIZE_DP = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private long startAt;
    private boolean animating;

    public AgentStarsView(Context context) {
        this(context, null);
    }

    public AgentStarsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // 与左下角灰色状态文字同色系，保持“微妙色调”
        paint.setColor(0xFF9FA9BC);
        paint.setStyle(Paint.Style.FILL);
        buildStarPath();
    }

    /** BoardUI 的 24×24 四点闪光路径 */
    private void buildStarPath() {
        starPath.moveTo(12f, 0f);
        starPath.cubicTo(13f, 7f, 17f, 11f, 24f, 12f);
        starPath.cubicTo(17f, 13f, 13f, 17f, 12f, 24f);
        starPath.cubicTo(11f, 17f, 7f, 13f, 0f, 12f);
        starPath.cubicTo(7f, 11f, 11f, 7f, 12f, 0f);
        starPath.close();
    }

    public void start() {
        if (animating) return;
        animating = true;
        startAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    public void stop() {
        animating = false;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShown()) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!animating) return;
        long now = SystemClock.uptimeMillis();
        float w = getWidth();
        float h = getHeight();
        float baseSize = dp(STAR_SIZE_DP);
        for (int i = 0; i < STARS.length; i++) {
            float phase = phaseOf(now, i);
            float alpha;
            float scale;
            float rotate;
            if (phase < 0.35f) {
                // 淡入：从很小、轻微左倾放大到满亮
                float t = smooth(phase / 0.35f);
                alpha = t;
                scale = 0.25f + 0.75f * t;
                rotate = -18f + 24f * t;
            } else if (phase < 0.62f) {
                // 保持：亮度接近满值，轻微回缩，形成“闪烁”的顿感
                float t = (phase - 0.35f) / 0.27f;
                alpha = 1f - 0.08f * t;
                scale = 1f - 0.12f * t;
                rotate = 6f - 10f * t;
            } else {
                // 淡出：缩小并向另一侧摇摆
                float t = (phase - 0.62f) / 0.38f;
                alpha = 0.92f * (1f - t);
                scale = 0.88f - 0.63f * t;
                rotate = -4f + 22f * t;
            }
            if (alpha <= 0.01f) continue;
            float cx = STARS[i][0] / 100f * w;
            float cy = STARS[i][1] / 100f * h;
            float size = baseSize * STARS[i][2] * scale;
            paint.setAlpha((int) (alpha * 255));
            int save = canvas.save();
            canvas.rotate(rotate, cx, cy);
            canvas.translate(cx - size / 2f, cy - size / 2f);
            canvas.scale(size / 24f, size / 24f);
            canvas.drawPath(starPath, paint);
            canvas.restoreToCount(save);
        }
        postInvalidateOnAnimation();
    }

    /** 每颗星星按索引错开 70% 周期 / 5，形成错落的闪烁节奏 */
    private float phaseOf(long now, int index) {
        float delay = index * PERIOD_MS * 0.7f / STARS.length;
        float elapsed = (now - startAt - delay) % PERIOD_MS;
        if (elapsed < 0) elapsed += PERIOD_MS;
        return elapsed / PERIOD_MS;
    }

    /** 平滑缓动，避免线性插值显得机械 */
    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private float dp(float value) {
        return getResources().getDisplayMetrics().density * value;
    }
}
