package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 课表骨架屏：按真实课表结构画「表头 + 时间轴 + 7 个星期列 + 行分隔线」，
 * 再用几块灰色圆角块模拟课程格子，比通用列表骨架更贴近实际版面。
 */
public class ScheduleSkeletonView extends View {

    /** 表头行数固定 1 行，内容 6 行（与常见作息节次数接近） */
    private static final int ROWS = 6;
    private static final int DAYS = 7;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    /** 呼吸动画进度：只作用于线条与色块，背景保持不透明，避免透出底层真实课表 */
    private float pulse = 1f;
    private ValueAnimator pulseAnim;

    /** {行, 列, 跨行数}：固定图案，避免每次绘制抖动 */
    private static final int[][] BLOCKS = {
            {1, 1, 2},
            {1, 3, 1},
            {2, 5, 2},
            {3, 2, 1},
            {3, 6, 2},
            {4, 4, 1},
            {5, 0, 1},
    };

    public ScheduleSkeletonView(Context context) {
        this(context, null);
    }

    public ScheduleSkeletonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScheduleSkeletonView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        linePaint.setColor(0xFFE5E5EA);
        linePaint.setStrokeWidth(dp(1));
        headerPaint.setColor(0xFFF0F0F4);
        blockPaint.setColor(0xFFE8E8EE);
        blockLightPaint.setColor(0xFFEDEDF2);
    }

    private float dp(float v) {
        return v * density + 0.5f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pulseAnim == null) {
            pulseAnim = ValueAnimator.ofFloat(1f, 0.45f);
            pulseAnim.setDuration(750);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setInterpolator(new LinearInterpolator());
            pulseAnim.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!pulseAnim.isRunning()) pulseAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulseAnim != null && pulseAnim.isRunning()) pulseAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        int alpha = (int) (255 * pulse);
        linePaint.setAlpha((int) (alpha * 0.85f));
        headerPaint.setAlpha(alpha);
        blockPaint.setAlpha(alpha);
        blockLightPaint.setAlpha(alpha);

        float headerH = dp(36);
        float timeW = dp(44);
        float rowH = (h - headerH) / ROWS;
        float colW = (w - timeW) / DAYS;

        // 表头底色
        canvas.drawRect(0, 0, w, headerH, headerPaint);

        // 纵向列线（时间轴右侧 + 7 列分隔）
        for (int i = 0; i <= DAYS; i++) {
            float x = timeW + i * colW;
            canvas.drawLine(x, 0, x, h, linePaint);
        }
        // 时间轴左边线
        canvas.drawLine(0, 0, 0, h, linePaint);

        // 横向行线
        for (int r = 0; r <= ROWS; r++) {
            float y = headerH + r * rowH;
            canvas.drawLine(0, y, w, y, linePaint);
        }
        // 表头下边线加粗一点，突出表头
        canvas.drawRect(0, headerH - dp(1), w, headerH, headerPaint);

        // 表头占位条：节次列 + 7 个星期列，模拟「节次 / 周一…周日」文字
        float barH = dp(9);
        float barY = (headerH - barH) / 2f;
        rect.set(dp(10), barY, timeW - dp(10), barY + barH);
        canvas.drawRoundRect(rect, dp(4), dp(4), blockPaint);
        for (int i = 0; i < DAYS; i++) {
            float cx = timeW + i * colW + colW / 2f;
            float bw = Math.min(colW * 0.52f, dp(26));
            rect.set(cx - bw / 2f, barY, cx + bw / 2f, barY + barH);
            canvas.drawRoundRect(rect, dp(4), dp(4), blockPaint);
        }

        // 课程块
        float pad = dp(3);
        for (int i = 0; i < BLOCKS.length; i++) {
            int row = BLOCKS[i][0];
            int col = BLOCKS[i][1];
            int span = BLOCKS[i][2];
            float left = timeW + col * colW + pad;
            float top = headerH + row * rowH + pad;
            float right = timeW + (col + 1) * colW - pad;
            float bottom = headerH + (row + span) * rowH - pad;
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, dp(6), dp(6), (i % 2 == 0) ? blockPaint : blockLightPaint);
        }
    }
}
