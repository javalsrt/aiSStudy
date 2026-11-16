package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 底部导航的半透明玻璃镜片背景。
 *
 * 只做一层通透的玻璃叠层：轻微白雾 + 左上阳光反射 + 底部厚度阴影 + 发丝描边，
 * 页面颜色自然透过来，因此任何页面、任何切换瞬间外观都一致。
 *
 * 早期版本会截取导航正后方的页面内容做折射，切页淡入淡出时会截到旧页面/半透明页面，
 * 出现「上半截白、下半截透明」的硬边分层，这里改为纯绘制，不再依赖截屏时序。
 */
public class GlassNavBackgroundView extends View {

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF shapeRect = new RectF();
    private final RectF insetRect = new RectF();
    private final RectF innerRect = new RectF();

    private Path outerPath = new Path();
    private float cornerRadius;

    public GlassNavBackgroundView(Context context) {
        this(context, null);
    }

    public GlassNavBackgroundView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassNavBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        // 冷白薄雾：只在底部略加重，压住文字背后的花色，上方保持通透
        tintPaint.setColor(0x14F7FAFF);

        edgePaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cornerRadius = dp(36);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cornerRadius = dp(36);
        shapeRect.set(0, 0, w, h);
        float inset = dp(0.5f);
        insetRect.set(inset, inset, w - inset, h - inset);

        // 冷白薄雾：上疏下密，保持上方通透，同时压住底部文字背后的花色
        tintPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0x0AF7FAFF, 0x14F7FAFF, 0x2EF7FAFF},
                new float[]{0f, .45f, 1f},
                Shader.TileMode.CLAMP));

        // 135° 斜向玻璃高光
        sheenPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{0x4DFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF},
                new float[]{0f, .42f, .64f, 1f},
                Shader.TileMode.CLAMP));

        // 边缘只保留斜向高光：左上亮、两侧和底部几乎透明
        edgePaint.setStrokeWidth(dp(0.8f));
        edgePaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x14FFFFFF, 0x33FFFFFF},
                new float[]{0f, .35f, .65f, 1f},
                Shader.TileMode.CLAMP));

        // 内圈厚度高光
        innerPaint.setStrokeWidth(dp(1f));
        innerPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{0x1AFFFFFF, 0x08FFFFFF, 0x06FFFFFF, 0x14FFFFFF},
                new float[]{0f, .45f, .6f, 1f},
                Shader.TileMode.CLAMP));

        // 左上角的小团柔白反射
        reflectionPaint.setShader(new RadialGradient(
                w * 0.18f, h * 0.02f, w * 0.34f,
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, .5f, 1f},
                Shader.TileMode.CLAMP));

        // 底部内阴影：让玻璃有厚度
        bottomShadePaint.setShader(new LinearGradient(
                0, h * 0.55f, 0, h,
                new int[]{0x00000000, 0x12000000},
                null, Shader.TileMode.CLAMP));

        // 右下方的透光焦点
        focusPaint.setShader(new RadialGradient(
                w * 0.84f, h * 0.58f, Math.max(w, h) * 0.30f,
                new int[]{0x14FFE6C2, 0x00FFE6C2},
                null, Shader.TileMode.CLAMP));

        // 底部内反射：下缘一道向上的冷色柔光
        bottomGlowPaint.setShader(new LinearGradient(
                0, h, 0, h * 0.45f,
                new int[]{0x2EEDF7FF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float r = Math.min(cornerRadius, Math.min(w, h) / 2f);
        outerPath = SquirclePath.create(shapeRect, r);

        int save = canvas.save();
        canvas.clipPath(outerPath);

        // 冷白薄雾 → 左上阳光反射 → 右下透光 → 底部内阴影 → 底部内反射 → 斜向高光
        canvas.drawRect(shapeRect, tintPaint);
        canvas.drawRect(shapeRect, reflectionPaint);
        canvas.drawRect(shapeRect, focusPaint);
        canvas.drawRect(shapeRect, bottomShadePaint);
        canvas.drawRect(shapeRect, bottomGlowPaint);
        canvas.drawRect(shapeRect, sheenPaint);

        canvas.restoreToCount(save);

        // 0.8dp 发丝外缘
        canvas.drawPath(SquirclePath.create(insetRect, Math.max(0, r - dp(0.5f))), edgePaint);

        // 1dp 内圈厚度高光
        float innerInset = dp(2);
        innerRect.set(innerInset, innerInset, w - innerInset, h - innerInset);
        canvas.drawPath(SquirclePath.create(innerRect, Math.max(0, r - innerInset)), innerPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
