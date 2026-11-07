package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.znxsgl.student.R;

/**
 * 顶部弥散渐变（Aurora）背景。
 *
 * <p>由多个低透明度的径向渐变光斑叠加而成，边缘柔和晕开，
 * 给页面顶部一层品牌氛围但不会抢内容的注意力。
 * 底部叠加一层渐隐到 canvas 色的遮罩，让光斑自然融入页面底色，避免出现硬边。
 *
 * <p>光斑位置与半径均为相对值（相对控件宽高），因此换到不同尺寸的页面可直接复用。
 */
public class AuroraHeaderView extends View {

    /** 光斑：圆心 x / 圆心 y / 半径，均按控件尺寸的比例计算 */
    private static final float[][] BLOBS = {
            {0.10f, 0.02f, 0.72f}, // 左上
            {0.84f, 0.10f, 0.62f}, // 右上
            {0.46f, 0.56f, 0.55f}, // 中下
            {0.00f, 0.84f, 0.48f}, // 左下
    };

    /** 各光斑颜色：低 alpha，靠叠加形成弥散层次 */
    private static final int[] BLOB_COLORS = {
            0x597BB8FF, // 淡蓝
            0x4D40D5A0, // 浅绿
            0x338FA8FF, // 浅蓝
            0x2669A8FF, // 品牌蓝
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader[] blobShaders = new Shader[BLOBS.length];
    private Shader fadeShader;
    private final int canvasColor;

    public AuroraHeaderView(Context context) {
        this(context, null);
    }

    public AuroraHeaderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AuroraHeaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        canvasColor = context.getResources().getColor(R.color.canvas, context.getTheme());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        float base = Math.max(w, h);
        for (int i = 0; i < BLOBS.length; i++) {
            float radius = BLOBS[i][2] * base;
            if (radius <= 0f) {
                blobShaders[i] = null;
                continue;
            }
            int color = BLOB_COLORS[i];
            blobShaders[i] = new RadialGradient(
                    BLOBS[i][0] * w, BLOBS[i][1] * h, radius,
                    new int[]{color, color & 0x00FFFFFF},  // 中心色 → 同色全透明
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        }

        // 下半部分渐隐到底色，避免与滚动内容之间出现生硬的分界线
        fadeShader = new LinearGradient(0, h * 0.45f, 0, h,
                Color.TRANSPARENT, canvasColor, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float base = Math.max(w, h);
        for (int i = 0; i < blobShaders.length; i++) {
            Shader shader = blobShaders[i];
            if (shader == null) continue;
            paint.setShader(shader);
            canvas.drawCircle(BLOBS[i][0] * w, BLOBS[i][1] * h, BLOBS[i][2] * base, paint);
        }

        if (fadeShader != null) {
            paint.setShader(fadeShader);
            canvas.drawRect(0f, h * 0.45f, w, h, paint);
        }
        paint.setShader(null);
    }
}
