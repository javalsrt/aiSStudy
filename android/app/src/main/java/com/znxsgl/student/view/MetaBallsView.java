package com.znxsgl.student.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * MetaBalls 风格加载动画。
 * 参考 reactbits.dev/animations/meta-balls：多个带高斯模糊的圆形按不同轨迹运动，
 * 通过 SCREEN/ADD 混合产生液态融合感。
 */
public class MetaBallsView extends View {

    private static final int BALL_COUNT = 4;
    private static final float RADIUS_DP = 14f;
    private static final float BLUR_RADIUS_DP = 18f;
    private static final long ANIMATION_DURATION = 3200L;

    private final Paint[] paints = new Paint[BALL_COUNT];
    private final float[] phases = new float[]{0f, 1.5f, 3f, 4.5f};
    private final float[] speed = new float[]{1f, 0.8f, 1.2f, 0.9f};
    private final float[] radiusRatios = new float[]{1f, 0.85f, 0.7f, 0.9f};

    private Bitmap offscreenBitmap;
    private Canvas offscreenCanvas;
    private ValueAnimator animator;

    private float centerX;
    private float centerY;
    private float orbitX;
    private float orbitY;
    private float ballRadius;
    private float blurRadius;
    private float animatedValue = 0f;

    public MetaBallsView(Context context) {
        super(context);
        init(context);
    }

    public MetaBallsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MetaBallsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        ballRadius = RADIUS_DP * density;
        blurRadius = BLUR_RADIUS_DP * density;

        int primary = 0xFF5E6AD2;
        int secondary = 0xFF8B95E8;
        int[] colors = new int[]{primary, secondary, primary, secondary};

        for (int i = 0; i < BALL_COUNT; i++) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(colors[i]);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL));
            paints[i] = paint;
        }

        startAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        orbitX = w * 0.28f;
        orbitY = h * 0.28f;

        if (offscreenBitmap != null) {
            offscreenBitmap.recycle();
        }
        offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        offscreenCanvas = new Canvas(offscreenBitmap);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (offscreenCanvas == null) {
            return;
        }

        // 清空离屏缓冲
        offscreenCanvas.drawColor(0, PorterDuff.Mode.CLEAR);

        // 使用 ADD 模式绘制每个球，使重叠区域高亮融合
        Paint blendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blendPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        for (int i = 0; i < BALL_COUNT; i++) {
            float t = animatedValue * speed[i] + phases[i];
            float x = centerX + (float) Math.cos(t) * orbitX * (0.6f + 0.4f * (i % 2));
            float y = centerY + (float) Math.sin(t * 1.3f) * orbitY * (0.6f + 0.4f * (i % 2 == 0 ? 1 : 0));

            offscreenCanvas.save();
            offscreenCanvas.drawCircle(x, y, ballRadius * radiusRatios[i], paints[i]);
            // 叠加一层小高光球，增强 meta-balls 视觉中心
            blendPaint.setColor(paints[i].getColor());
            blendPaint.setAlpha(120);
            blendPaint.setMaskFilter(null);
            offscreenCanvas.drawCircle(x, y, ballRadius * radiusRatios[i] * 0.45f, blendPaint);
            blendPaint.setAlpha(255);
            offscreenCanvas.restore();
        }

        canvas.drawBitmap(offscreenBitmap, 0, 0, null);
    }

    private void startAnimation() {
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(ANIMATION_DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            animatedValue = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
        if (offscreenBitmap != null) {
            offscreenBitmap.recycle();
            offscreenBitmap = null;
        }
    }
}
