package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 护眼的白黄「点场」静态背景：暖白渐变底 + 均匀点阵，不播放任何动画。
 */
public class DotFieldView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public DotFieldView(Context context) {
        this(context, null);
    }

    public DotFieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dotPaint.setColor(0xFFE8DBBC);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 暖白到浅米黄的纵向渐变，接近纸张的柔和底色
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                0xFFFFFDF5, 0xFFF7EFD8, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        float step = 22 * density;
        float radius = 1.15f * density;
        float offset = step / 2f;
        for (float y = offset; y < getHeight(); y += step) {
            for (float x = offset; x < getWidth(); x += step) {
                canvas.drawCircle(x, y, radius, dotPaint);
            }
        }
    }
}
