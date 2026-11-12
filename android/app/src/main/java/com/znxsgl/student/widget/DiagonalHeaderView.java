package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 课表左上角斜线表头：右上角「日期」，左下角「节次」。
 */
public class DiagonalHeaderView extends View {

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public DiagonalHeaderView(Context context) {
        this(context, null);
    }

    public DiagonalHeaderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DiagonalHeaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 9f);
        textPaint.setColor(0xFF8E8E93);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1));
        linePaint.setColor(0xFFD1D1D6);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        canvas.drawLine(0, 0, width, height, linePaint);

        float pad = dp(3);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("日期", width - pad, pad - textPaint.ascent(), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("节次", pad, height - pad - textPaint.descent(), textPaint);
    }

    private float dp(float value) {
        return value * density;
    }
}
