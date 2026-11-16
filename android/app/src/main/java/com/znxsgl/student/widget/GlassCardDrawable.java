package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 学习页课程卡片的玻璃镜片背景。
 *
 * 卡片主色保持不变，但在纯色里叠一层同色系的低频“液体”纹理，再用 drawBitmapMesh
 * 做中间微放大、边缘向中心弯折的透镜形变，让纹理在卡片边缘产生折射错位。
 * 表面再叠加左上方阳光反射、顶部厚度高光、冷暖内缘色散和右下透光，
 * 得到一块有厚度、有光感的彩色玻璃镜片。
 *
 * 文字、底部信息条和按钮都是普通子 View，不会被形变或高光盖住，保持清晰。
 * 纹理只按颜色缓存一次，静态绘制，不额外消耗帧率。
 */
public class GlassCardDrawable extends Drawable {

    private static final int TEX_WIDTH = 128;
    private static final int TEX_HEIGHT = 160;
    private static final int MESH_COLS = 14;
    private static final int MESH_ROWS = 18;
    /** 透镜边缘最大弯折强度（dp） */
    private static final float LENS_STRENGTH_DP = 5.0f;
    private static final float LENS_CENTER_SCALE = 1.012f;

    /** 同一颜色的液体纹理只生成一次，卡片复用时直接取缓存 */
    private static final SparseArray<Bitmap> TEXTURE_CACHE = new SparseArray<>();

    private final int baseColor;
    private final float cornerRadius;
    private final float density;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint causticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private float[] meshVerts;
    private Bitmap texture;

    public GlassCardDrawable(@NonNull Context context, int baseColor, float cornerRadiusPx) {
        this.baseColor = baseColor;
        this.cornerRadius = cornerRadiusPx;
        this.density = context.getResources().getDisplayMetrics().density;

        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(baseColor);

        topEdgePaint.setStyle(Paint.Style.STROKE);
        topEdgePaint.setStrokeWidth(dp(1.2f));

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1f));
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        rect.set(bounds);
        texture = textureFor(baseColor);
        buildMesh();
        updateShaders(bounds.width(), bounds.height());

        clipPath.reset();
        clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (rect.isEmpty()) return;

        int save = canvas.save();
        canvas.clipPath(clipPath);

        // 主色底 + 被透镜弯折的同色液体纹理
        canvas.drawRect(rect, basePaint);
        if (texture != null && meshVerts != null) {
            canvas.drawBitmapMesh(
                    texture, MESH_COLS, MESH_ROWS, meshVerts, 0, null, 0, texturePaint);
        }

        // 左上阳光反射、冷白薄雾、135° 斜向玻璃高光、右下透光
        canvas.drawRect(rect, frostPaint);
        canvas.drawRect(rect, reflectionPaint);
        canvas.drawRect(rect, sheenPaint);
        canvas.drawRect(rect, causticPaint);

        // 顶部受光亮边：只亮上沿，像阳光擦过玻璃厚度
        int topSave = canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.55f);
        canvas.drawPath(clipPath, topEdgePaint);
        canvas.restoreToCount(topSave);

        // 1dp 内缘：暖白 → 冷蓝，轻微色散
        canvas.drawPath(clipPath, rimPaint);

        canvas.restoreToCount(save);
    }

    /** 生成透镜网格：中心微放大，越靠边越向中心弯折 */
    private void buildMesh() {
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        int vc = MESH_COLS + 1;
        int vr = MESH_ROWS + 1;
        if (meshVerts == null || meshVerts.length != vc * vr * 2) {
            meshVerts = new float[vc * vr * 2];
        }
        float lens = dp(LENS_STRENGTH_DP);
        // 网格向四边外扩：边缘顶点会被透镜拉向中心，先用外扩量补偿这段位移，
        // 否则卡片四角会露出没被纹理覆盖的浅色缺口，交界处形成斜向硬边。
        float over = (0.12f + (float) Math.pow(1.5f, 2.2f) * 1.1f) * lens;
        float left = rect.left - over;
        float top = rect.top - over;
        float right = rect.right + over;
        float bottom = rect.bottom + over;
        float bw = right - left;
        float bh = bottom - top;
        float cx = rect.centerX();
        float cy = rect.centerY();
        float hw = Math.max(1f, bw / 2f);
        float hh = Math.max(1f, bh / 2f);

        int idx = 0;
        for (int row = 0; row < vr; row++) {
            float y = top + bh * (row / (float) MESH_ROWS);
            float ny = (y - cy) / hh;
            for (int col = 0; col < vc; col++) {
                float x = left + bw * (col / (float) MESH_COLS);
                float nx = (x - cx) / hw;
                float ql = (float) Math.min(Math.hypot(nx, ny), 1.6f);
                float bend = 0.12f + (float) Math.pow(ql, 2.2f) * 1.1f;
                meshVerts[idx++] = cx + (x - cx) * LENS_CENTER_SCALE - nx * bend * lens;
                meshVerts[idx++] = cy + (y - cy) * LENS_CENTER_SCALE - ny * bend * lens;
            }
        }
    }

    private void updateShaders(float w, float h) {
        float l = rect.left;
        float t = rect.top;

        // 左上角暖色阳光反射
        reflectionPaint.setShader(new RadialGradient(
                l + w * 0.22f, t + h * 0.08f, Math.max(w, h) * 0.65f,
                new int[]{0x24FFF5E6, 0x0FFFF5E6, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));

        // 冷白薄雾：和底部导航同一套玻璃质感，越往右下越明显
        frostPaint.setShader(new LinearGradient(
                l, t, l + w, t + h,
                new int[]{0x0DF7FAFF, 0x1AF4F8FF, 0x2EF7FAFF},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        // 135° 斜向玻璃高光，参考底部导航的 sheen
        sheenPaint.setShader(new LinearGradient(
                l, t, l + w, t + h,
                new int[]{0x4DFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x24FFFFFF},
                new float[]{0f, .42f, .64f, 1f},
                Shader.TileMode.CLAMP));

        // 右下角光线穿过镜片后的暖色焦点
        causticPaint.setShader(new RadialGradient(
                l + w * 0.78f, t + h * 0.54f, h * 0.42f,
                new int[]{0x1AFFE9C9, 0x00FFE9C9},
                null,
                Shader.TileMode.CLAMP));

        // 顶部边缘受光亮线
        topEdgePaint.setShader(new LinearGradient(
                l, t, l + w, t,
                new int[]{0x00FFF3DC, 0x80FFF3DC, 0x26FFF3DC, 0x00FFFFFF},
                new float[]{0f, 0.24f, 0.6f, 1f},
                Shader.TileMode.CLAMP));

        // 玻璃边缘白色渐变高光，和底部导航一致
        rimPaint.setStrokeWidth(dp(1.2f));
        rimPaint.setShader(new LinearGradient(
                l, t, l + w, t + h,
                new int[]{0xB3FFFFFF, 0x1AFFFFFF, 0x14FFFFFF, 0x6BFFFFFF},
                new float[]{0f, 0.45f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
    }

    /** 同色系液体纹理：低频、低对比，既有流动感又不影响白色标题阅读 */
    private static Bitmap textureFor(int color) {
        Bitmap cached = TEXTURE_CACHE.get(color);
        if (cached != null) return cached;

        int w = TEX_WIDTH;
        int h = TEX_HEIGHT;
        int[] pixels = new int[w * h];
        int r0 = (color >> 16) & 0xFF;
        int g0 = (color >> 8) & 0xFF;
        int b0 = color & 0xFF;

        for (int y = 0; y < h; y++) {
            float v = y / (float) (h - 1);
            for (int x = 0; x < w; x++) {
                float u = x / (float) (w - 1);
                // 三组不同频率的正弦叠加，形成缓慢流动的液体纹理
                float a = (float) Math.sin(u * 5.4f + Math.sin(v * 4.1f + 0.8f) * 1.3f + 0.6f);
                float b = (float) Math.sin(v * 6.3f + Math.cos(u * 3.5f + 1.2f) * 1.1f + 2.0f);
                float c = (float) Math.sin((u + v) * 7.2f + Math.sin(u * 2.0f) * 1.4f);
                // 斜向拉丝，让透镜弯折在边缘更容易被看出来
                float streak = (float) Math.sin((u * 3.0f - v * 5.0f) * 5.0f + a * 1.4f);
                float t = a * 0.46f + b * 0.28f + c * 0.18f + streak * 0.13f;

                int r;
                int g;
                int bl;
                if (t >= 0f) {
                    float k = Math.min(0.12f, t * 0.12f);
                    r = (int) (r0 + (255 - r0) * k);
                    g = (int) (g0 + (255 - g0) * k);
                    bl = (int) (b0 + (255 - b0) * k);
                } else {
                    float k = Math.min(0.13f, -t * 0.13f);
                    r = (int) (r0 * (1f - k));
                    g = (int) (g0 * (1f - k));
                    bl = (int) (b0 * (1f - k));
                }
                pixels[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        TEXTURE_CACHE.put(color, bitmap);
        return bitmap;
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    public void setAlpha(int alpha) {
        // 卡片底色由主色决定，不随外部 alpha 变化
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
