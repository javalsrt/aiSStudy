package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.znxsgl.student.R;

/**
 * AI 助手欢迎卡顶部的深色图片区。
 *
 * 图片在顶部左右圆角处与卡片轮廓贴合，向下不是简单的一层模糊，
 * 而是“清晰 → 轻模糊 → 中模糊 → 重模糊”的分层景深。
 * 底部不再用白色渐隐（深色叠白会经过灰），而是让白色内容面板带着
 * 16dp 上圆角向上压住图片约 24dp：图片以深色干净收尾，不会出现灰带。
 */
public class AiWelcomeHeaderView extends FrameLayout {

    private static final float CORNER_DP = 0f;
    /** 白色内容面板压住图片的高度与上圆角 */
    private static final float SHEET_HEIGHT_DP = 24f;
    private static final float SHEET_CORNER_DP = 16f;
    /** 三层模糊的降采样比例与半径 */
    private static final float BLUR_SCALE = 0.28f;
    private static final int BLUR_LIGHT = 3;
    private static final int BLUR_MEDIUM = 9;
    private static final int BLUR_HEAVY = 18;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path clipPath = new Path();
    private final Path sheetPath = new Path();
    private final RectF rect = new RectF();
    private final RectF sheetRect = new RectF();
    private final Rect srcRect = new Rect();

    private Bitmap source;
    private Bitmap composite;
    private Bitmap blurLight;
    private Bitmap blurMedium;
    private Bitmap blurHeavy;
    private float cornerPx;

    public AiWelcomeHeaderView(Context context) {
        this(context, null);
    }

    public AiWelcomeHeaderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AiWelcomeHeaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        cornerPx = dp(CORNER_DP);
        source = BitmapFactory.decodeResource(getResources(), R.drawable.ai_welcome_bg);

        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        // 深色图上压一层很淡的黑，保证白色标题在亮色波纹上仍然清楚
        scrimPaint.setColor(0x24000000);
        // 底部白色面板与卡片内容区同色，向上压住图片形成干净分界
        sheetPaint.setColor(0xFFFFFFFF);
        fallbackPaint.setColor(0xFF0B1220);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        rect.set(0, 0, w, h);
        float[] radii = {
                cornerPx, cornerPx,
                cornerPx, cornerPx,
                0, 0,
                0, 0
        };
        clipPath.reset();
        clipPath.addRoundRect(rect, radii, Path.Direction.CW);

        updateSrcRect(w, h);

        // 下半部分再压一层冷色景深，让模糊层在白色面板上方更有空间感
        depthPaint.setShader(new LinearGradient(
                0, h * 0.38f, 0, h,
                new int[]{0x0008101F, 0x4D08101F},
                null, Shader.TileMode.CLAMP));

        // 白色面板：顶部两角圆角，底部与内容区齐平
        float sheetTop = Math.max(0f, h - dp(SHEET_HEIGHT_DP));
        float r = dp(SHEET_CORNER_DP);
        sheetRect.set(0, sheetTop, w, h);
        sheetPath.reset();
        sheetPath.addRoundRect(sheetRect,
                new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);

        buildBlurs(w, h);
        buildComposite(w, h);
        recycleBlurs();
    }

    private void updateSrcRect(int w, int h) {
        if (source == null || w <= 0 || h <= 0) return;
        float viewAspect = w / (float) h;
        float srcAspect = source.getWidth() / (float) source.getHeight();
        if (srcAspect > viewAspect) {
            int cropW = Math.round(source.getHeight() * viewAspect);
            int left = (source.getWidth() - cropW) / 2;
            srcRect.set(left, 0, left + cropW, source.getHeight());
        } else {
            int cropH = Math.round(source.getWidth() / viewAspect);
            int top = (source.getHeight() - cropH) / 2;
            srcRect.set(0, top, source.getWidth(), top + cropH);
        }
    }

    private void buildBlurs(int w, int h) {
        recycleBlurs();
        blurLight = createBlurBitmap(w, h, BLUR_LIGHT);
        blurMedium = createBlurBitmap(w, h, BLUR_MEDIUM);
        blurHeavy = createBlurBitmap(w, h, BLUR_HEAVY);
    }

    private Bitmap createBlurBitmap(int w, int h, int radius) {
        if (source == null || w <= 0 || h <= 0) return null;
        int bw = Math.max(1, Math.round(w * BLUR_SCALE));
        int bh = Math.max(1, Math.round(h * BLUR_SCALE));
        Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(source, srcRect, new Rect(0, 0, bw, bh), imagePaint);
        boxBlur(bitmap, radius);
        return bitmap;
    }

    /** 预合成整块头图，避免每帧做多次 saveLayer；文字仍由子 View 绘制，保持清晰 */
    private void buildComposite(int w, int h) {
        if (source == null || w <= 0 || h <= 0) return;
        if (composite != null) {
            composite.recycle();
            composite = null;
        }
        composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(composite);

        canvas.drawBitmap(source, srcRect, rect, imagePaint);

        // 分层景深：越往下模糊越重，并且每一层的过渡区间相互重叠
        drawBlurLayer(canvas, blurLight, 0.20f, 0.52f, h);
        drawBlurLayer(canvas, blurMedium, 0.38f, 0.70f, h);
        drawBlurLayer(canvas, blurHeavy, 0.56f, 0.86f, h);

        canvas.drawRect(rect, scrimPaint);
        canvas.drawRect(rect, depthPaint);

        // 白色圆角面板压住图片底部：深色直接收在白边上，不再经过灰
        canvas.drawPath(sheetPath, sheetPaint);
    }

    private void drawBlurLayer(Canvas canvas, Bitmap bitmap, float start, float end, int h) {
        if (bitmap == null) return;
        int layer = canvas.saveLayer(rect, null);
        canvas.drawBitmap(bitmap, null, rect, imagePaint);

        maskPaint.setShader(new LinearGradient(
                0, h * start, 0, h * end,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0xFFFFFFFF},
                new float[]{0f, 0.30f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, maskPaint);
        canvas.restoreToCount(layer);
    }

    private void recycleBlurs() {
        if (blurLight != null) {
            blurLight.recycle();
            blurLight = null;
        }
        if (blurMedium != null) {
            blurMedium.recycle();
            blurMedium = null;
        }
        if (blurHeavy != null) {
            blurHeavy.recycle();
            blurHeavy = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int save = canvas.save();
        canvas.clipPath(clipPath);

        if (composite != null) {
            canvas.drawBitmap(composite, 0, 0, imagePaint);
        } else {
            canvas.drawRect(rect, fallbackPaint);
        }
        canvas.restoreToCount(save);
    }

    /** 轻量盒式模糊，只处理降采样后的小图 */
    private static void boxBlur(Bitmap bitmap, int radius) {
        if (radius <= 0) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] src = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                int from = Math.max(0, x - radius);
                int to = Math.min(w - 1, x + radius);
                for (int k = from; k <= to; k++) {
                    int c = src[row + k];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                tmp[row + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int r = 0, g = 0, b = 0, n = 0;
                int from = Math.max(0, y - radius);
                int to = Math.min(h - 1, y + radius);
                for (int k = from; k <= to; k++) {
                    int c = tmp[k * w + x];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                src[y * w + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        bitmap.setPixels(src, 0, w, 0, 0, w, h);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
