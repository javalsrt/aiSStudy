package com.znxsgl.student.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Random;

/**
 * 底部导航中间「AI 星星」的点击爆发动效（ReactBits Sound Lab 式声波反馈）。
 *
 * 一次点击按时间轴叠加 5 层，全部由单个 ValueAnimator 驱动，重绘范围仅限图标周边：
 * 1. 中心柔光   0 → 380ms   蓝色径向光晕由小放大并淡出
 * 2. 声波涟漪   0/90/190ms  3 圈空心圆由内向外扩散，描边同步变细
 * 3. 星芒射线   0 → 360ms   8 道短线向外弹出后迅速收没
 * 4. 四角星光晕 0 → 420ms   星星轮廓放大 1.9 倍后隐去（bloom）
 * 5. 粒子迸发   0 → 700ms   12 颗小光点带阻尼向外飞散
 *
 * 该 View 铺满容器但完全不参与触摸与无障碍，只负责在指定坐标处绘制，不影响下层导航点击。
 */
public class AiTabBurstView extends View {

    /** 整条动画时长 */
    private static final float TOTAL_MS = 820f;

    /**
     * 相对于锚点视图中心的垂直视觉补偿。
     *
     * 锚点用的是导航页签容器（不受图标缩放影响），而星星图标在容器里被
     * {@code styleAiTab()} 放大了 1.95 倍并整体上移，图形视觉重心比容器中心
     * 高约 8.7dp。不补偿的话圆环会明显压在星星下方。
     * 若调整了 MainActivity 里图标的垂直位移量，这里要同步改。
     */
    private static final float OPTICAL_OFFSET_Y_DP = -8.7f;

    private static final int COLOR_CORE = 0xFF0A84FF;   // 主色蓝
    private static final int COLOR_GLOW = 0xFF5AB0FF;   // 提亮蓝（内圈）
    private static final int COLOR_GHOST = 0xFF9CCBFF;  // 浅蓝（小颗粒）

    // 涟漪：起始延迟 / 持续时长
    private static final float[] RING_DELAY = {0f, 90f, 190f};
    private static final float[] RING_DUR = {420f, 470f, 520f};

    // 粒子：角度与距离抖动（固定种子，保证每次形态一致）
    private static final int PARTICLE_COUNT = 12;
    private final float[] particleAngle = new float[PARTICLE_COUNT];
    private final float[] particleDist = new float[PARTICLE_COUNT];
    private final float[] particleRadius = new float[PARTICLE_COUNT];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final DecelerateInterpolator decelerate = new DecelerateInterpolator(1.8f);
    private final LinearInterpolator linear = new LinearInterpolator();

    private ValueAnimator animator;
    /** 当前动画进度 0..1；< 0 表示空闲、不绘制 */
    private float progress = -1f;
    private float centerX = 0f;
    private float centerY = 0f;
    /** 上一帧的中心：中心跟随时用它补重绘旧区域，避免残留 */
    private float lastCenterX = 0f;
    private float lastCenterY = 0f;

    /**
     * 爆发点锚定的视图（AI 星星图标）。
     * 必须用引用而不是一次性坐标：选中态重排与垂直居中校正会在点击后
     * 再把图标挪动几个 dp，只取一次坐标就会让圆环和星星错开。
     */
    private WeakReference<View> anchorRef;
    private final int[] anchorLoc = new int[2];
    private final int[] selfLoc = new int[2];

    public AiTabBurstView(Context context) {
        super(context);
        init();
    }

    public AiTabBurstView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AiTabBurstView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 纯装饰层：不拦触摸、不参与无障碍、不占焦点
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.FILL);

        Random random = new Random(20261107L);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            // 均匀铺满 360°，再叠加少量抖动，避免出现机械的对称感
            float base = 360f * i / PARTICLE_COUNT;
            particleAngle[i] = (float) Math.toRadians(base - 90f + (random.nextFloat() - 0.5f) * 26f);
            particleDist[i] = 0.72f + random.nextFloat() * 0.36f;
            particleRadius[i] = 0.62f + random.nextFloat() * 0.55f;
        }
    }

    /**
     * 以某个视图为中心触发一次爆发。
     * 动画期间持续跟随该视图的实际位置，保证圆环与图标始终同心。
     *
     * @param anchor 爆发点锚定视图（AI 页签容器，不用图标本身：图标被放缩后视觉重心不在 layout 中心）
     */
    public void play(View anchor) {
        if (anchor == null) return;
        anchorRef = new WeakReference<>(anchor);
        if (!syncCenter(false)) return;
        if (animator != null && animator.isRunning()) animator.cancel();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration((long) TOTAL_MS);
        animator.setInterpolator(linear);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            syncCenter(true);
            invalidateArea();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                progress = -1f;
                invalidateArea();
            }
        });
        // 清掉上一轮可能残留的图形（位置可能不同），再开新的一轮
        progress = 0f;
        lastCenterX = centerX;
        lastCenterY = centerY;
        invalidate();
        animator.start();
    }

    /**
     * 把中心同步到锚定视图的当前中心。
     *
     * @param smooth true 时按比例插值跟随（避免图标被校正位时圆环生硬跳动）
     * @return 是否拿到了有效坐标
     */
    private boolean syncCenter(boolean smooth) {
        WeakReference<View> ref = anchorRef;
        View anchor = ref == null ? null : ref.get();
        if (anchor == null || !anchor.isAttachedToWindow() || getWidth() == 0) return false;
        anchor.getLocationInWindow(anchorLoc);
        getLocationInWindow(selfLoc);
        float tx = anchorLoc[0] + anchor.getWidth() / 2f - selfLoc[0];
        float ty = anchorLoc[1] + anchor.getHeight() / 2f - selfLoc[1] + dp(OPTICAL_OFFSET_Y_DP);
        if (smooth) {
            centerX += (tx - centerX) * 0.4f;
            centerY += (ty - centerY) * 0.4f;
        } else {
            centerX = tx;
            centerY = ty;
        }
        return true;
    }

    /** 只重绘图标周边一块，避免整屏 invalidate；中心移动时把旧位置一并覆盖 */
    private void invalidateArea() {
        int r = (int) Math.ceil(dp(66f));
        if (lastCenterX != centerX || lastCenterY != centerY) {
            invalidate((int) (lastCenterX - r), (int) (lastCenterY - r),
                    (int) (lastCenterX + r), (int) (lastCenterY + r));
            lastCenterX = centerX;
            lastCenterY = centerY;
        }
        invalidate((int) (centerX - r), (int) (centerY - r),
                (int) (centerX + r), (int) (centerY + r));
    }

    /** 提前结束（页面销毁时调用，避免回调打到已分离的 View） */
    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        progress = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (progress < 0f) return;
        float elapsed = progress * TOTAL_MS;

        drawCoreGlow(canvas, elapsed);
        drawStarBloom(canvas, elapsed);
        drawRings(canvas, elapsed);
        drawSparks(canvas, elapsed);
        drawParticles(canvas, elapsed);
    }

    /** 1. 中心柔光：从图标中心晕开，快速淡出，提供「按下即有光」的第一反馈 */
    private void drawCoreGlow(Canvas canvas, float elapsed) {
        float u = sub(elapsed, 0f, 380f);
        if (u < 0f || u >= 1f) return;
        float r = dp(9f) + dp(25f) * decelerate.getInterpolation(u);
        int alpha = (int) (0x4D * Math.pow(1f - u, 2.0));
        if (alpha <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(centerX, centerY, r,
                new int[]{withAlpha(COLOR_GLOW, alpha), withAlpha(COLOR_CORE, alpha / 3), Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, r, paint);
        paint.setShader(null);
    }

    /** 2. 四角星光晕：图标本身的放大残影，让星星有「炸开」的重量感 */
    private void drawStarBloom(Canvas canvas, float elapsed) {
        float u = sub(elapsed, 0f, 420f);
        if (u < 0f || u >= 1f) return;
        float scale = 1f + 1.05f * decelerate.getInterpolation(u);
        int alpha = (int) (0x52 * Math.pow(1f - u, 1.6));
        if (alpha <= 0) return;

        buildStar(dp(11f) * scale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f) * (1f - u * 0.6f));
        paint.setColor(withAlpha(COLOR_GLOW, alpha));
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.drawPath(starPath, paint);
        canvas.restore();
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    /** 3. 声波涟漪：三圈错峰扩散，描边随半径增大同步变细（声波消散感） */
    private void drawRings(Canvas canvas, float elapsed) {
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < RING_DELAY.length; i++) {
            float u = sub(elapsed, RING_DELAY[i], RING_DUR[i]);
            if (u < 0f || u >= 1f) continue;
            float eased = decelerate.getInterpolation(u);
            // 半径收到 46dp：足以溢出药丸边界形成声波感，又不压到相邻页签的图标与文字
            float r = dp(13f) + dp(33f) * eased;
            int alpha = (int) (0x62 * Math.pow(1f - u, 1.5));
            if (alpha <= 0) continue;

            paint.setStrokeWidth(Math.max(dp(0.35f), dp(2.4f) * (1f - u * 0.82f)));
            paint.setColor(withAlpha(i == 2 ? COLOR_GLOW : COLOR_CORE, alpha));
            canvas.drawCircle(centerX, centerY, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** 4. 星芒射线：8 道长短交错的短线，弹出后立即收没 */
    private void drawSparks(Canvas canvas, float elapsed) {
        float u = sub(elapsed, 0f, 360f);
        if (u < 0f || u >= 1f) return;
        float eased = decelerate.getInterpolation(u);
        int alpha = (int) (0xCC * Math.pow(1f - u, 1.3));
        if (alpha <= 0) return;

        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            boolean longRay = (i % 2 == 0);
            double angle = Math.toRadians(-90 + i * 45);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            float inner = dp(15f) + dp(15f) * eased;
            float len = (longRay ? dp(11f) : dp(6.5f)) * (1f - eased);
            if (len <= 0.4f) continue;

            paint.setStrokeWidth(longRay ? dp(1.7f) : dp(1.2f));
            paint.setColor(withAlpha(longRay ? COLOR_CORE : COLOR_GLOW, alpha));
            canvas.drawLine(centerX + cos * inner, centerY + sin * inner,
                    centerX + cos * (inner + len), centerY + sin * (inner + len), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 5. 粒子迸发：带阻尼的飞出轨迹 + 半径收缩，尾段拖出一点余韵 */
    private void drawParticles(Canvas canvas, float elapsed) {
        float u = sub(elapsed, 20f, 700f);
        if (u < 0f || u >= 1f) return;
        float eased = decelerate.getInterpolation(u);
        int alpha = (int) (0xE6 * Math.pow(1f - u, 1.7));
        if (alpha <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float dist = (dp(12f) + dp(26f) * eased) * particleDist[i];
            float r = dp(2.1f) * particleRadius[i] * (1f - u * 0.55f);
            if (r <= 0.3f) continue;

            float px = centerX + (float) Math.cos(particleAngle[i]) * dist;
            float py = centerY + (float) Math.sin(particleAngle[i]) * dist;

            // 大颗粒用主色，小颗粒用提亮蓝，形成前后景层次
            paint.setColor(withAlpha(i % 3 == 0 ? COLOR_GHOST : COLOR_CORE, alpha));
            canvas.drawCircle(px, py, r, paint);
        }
    }

    /** 生成以原点为中心的四角星轮廓（外尖内凹） */
    private void buildStar(float outer) {
        starPath.reset();
        float inner = outer * 0.34f;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(-90 + i * 45);
            float r = (i % 2 == 0) ? outer : inner;
            float x = (float) (Math.cos(a) * r);
            float y = (float) (Math.sin(a) * r);
            if (i == 0) starPath.moveTo(x, y);
            else starPath.lineTo(x, y);
        }
        starPath.close();
    }

    /**
     * 取子区间进度：元素在 delay 处启动，dur 后结束。
     *
     * @return 0..1；不在时间窗内返回 -1
     */
    private float sub(float elapsed, float delay, float dur) {
        float t = (elapsed - delay) / dur;
        if (t < 0f || t >= 1f) return t < 0f ? -1f : 1f;
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.min(255, Math.max(0, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
}
