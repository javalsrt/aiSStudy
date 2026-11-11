package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import com.znxsgl.student.R;

/**
 * 底部导航的静态毛玻璃背景。
 *
 * 实现思路：把导航条正后方的页面内容缩小采样后再平滑放大，得到一层柔和的
 * 磨砂底图（缩小比例越大，放大后的模糊越强），再叠加白色薄雾、顶部高光渐变
 * 与内外描边，模拟玻璃板的通透质感。只在滚动 / 布局变化 / 切换页签时刷新，
 * 不做逐帧捕获，避免持续占用 GPU 与 CPU。
 */
public class GlassNavBackgroundView extends View {

    /** 背景内容采样比例：越小越模糊，0.12 约等于 8 倍平滑放大 */
    private static final float CAPTURE_SCALE = 0.12f;
    /** 滚动时两次捕获的最小间隔：跟屏幕刷新同频，磨砂底图和页面同步移动，不会一跳一跳 */
    private static final long CAPTURE_INTERVAL = 16L;

    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shapeRect = new RectF();
    private final RectF insetRect = new RectF();
    private final RectF innerRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View contentView;
    private Bitmap snapshot;
    /** 复用同一个 Shader，滚动时只更新矩阵，避免每帧新建对象 */
    private BitmapShader backdropShader;
    private float cornerRadius;
    private long lastCaptureAt;
    private boolean captureQueued;

    private final Runnable captureTask = new Runnable() {
        @Override
        public void run() {
            captureQueued = false;
            if (!isAttachedToWindow() || getVisibility() != VISIBLE) return;
            lastCaptureAt = SystemClock.uptimeMillis();
            captureBackdrop();
        }
    };

    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener =
            this::scheduleCapture;

    private final View.OnLayoutChangeListener layoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> scheduleCapture();

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
        backdropPaint.setFilterBitmap(true);
        // 模糊后颜色会被白色薄雾冲淡，先提高饱和度并轻微压暗，让背后的色块、网格线仍能从玻璃里透出来
        ColorMatrix backdropMatrix = new ColorMatrix();
        backdropMatrix.setSaturation(1.45f);
        backdropMatrix.postConcat(new ColorMatrix(new float[]{
                1f, 0, 0, 0, -8f,
                0, 1f, 0, 0, -8f,
                0, 0, 1f, 0, -8f,
                0, 0, 0, 1, 0
        }));
        backdropPaint.setColorFilter(new ColorMatrixColorFilter(backdropMatrix));
        fallbackPaint.setColor(0xFAFFFFFF);
        edgePaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStyle(Paint.Style.STROKE);
        tintPaint.setColor(0xACF4F8FF); // 68% 冷白薄雾：降低透视感，同时保留背后的明暗层次
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cornerRadius = dp(36);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });

        if (getParent() instanceof ViewGroup) {
            contentView = ((ViewGroup) getParent()).findViewById(R.id.fragment_container);
        }
        if (contentView != null) {
            contentView.addOnLayoutChangeListener(layoutListener);
            contentView.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        }
        scheduleCapture(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(captureTask);
        captureQueued = false;
        backdropPaint.setShader(null);
        backdropShader = null;
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
        if (contentView != null) {
            contentView.removeOnLayoutChangeListener(layoutListener);
            if (contentView.getViewTreeObserver().isAlive()) {
                contentView.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            }
            contentView = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cornerRadius = dp(36);
        shapeRect.set(0, 0, w, h);
        float inset = dp(0.5f);
        insetRect.set(inset, inset, w - inset, h - inset);
        // 自上而下的玻璃厚度高光：顶部亮、底部收暗，避免大面积死白
        sheenPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0x70FFFFFF, 0x2AFFFFFF, 0x0DFFFFFF},
                new float[]{0f, .55f, 1f},
                Shader.TileMode.CLAMP));
        edgePaint.setStrokeWidth(dp(1));
        edgePaint.setColor(0xCCFFFFFF);
        // 内圈折射高光：白 → 冷蓝 → 白 → 淡绿，模拟液态玻璃边缘的色散反光
        innerPaint.setStrokeWidth(dp(2.5f));
        innerPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0xD9FFFFFF, 0x8AE8F4FF, 0xB3FFFFFF, 0x66E9FFF3},
                new float[]{0f, .35f, .65f, 1f},
                Shader.TileMode.CLAMP));
        // 左上角玻璃反光斑：参照液态玻璃球的高光位置
        reflectionPaint.setShader(new RadialGradient(
                w * 0.26f, h * 0.18f, Math.max(w, h) * 0.6f,
                new int[]{0x80FFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[]{0f, .45f, 1f},
                Shader.TileMode.CLAMP));
        // 底部内反射：下缘一道向上的柔和反光
        bottomGlowPaint.setShader(new LinearGradient(
                0, h, 0, h * 0.45f,
                new int[]{0x47FFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
        backdropShader = null;
        backdropPaint.setShader(null);
        scheduleCapture(true);
    }

    /** 切换页签等场景下主动刷新（隐藏再显示时调用，保证底图不是旧页面） */
    public void refresh() {
        scheduleCapture(true);
    }

    private void scheduleCapture() {
        scheduleCapture(false);
    }

    private void scheduleCapture(boolean force) {
        if (!isAttachedToWindow()) return;
        if (captureQueued && !force) return;
        long now = SystemClock.uptimeMillis();
        // 未到下次刷新时间就顺延到该时刻，而不是丢弃本次滚动事件：
        // 否则快速滚动时事件被节流丢掉，底图会停顿、一跳一跳地更新
        long delay = force ? 0L : Math.max(0L, CAPTURE_INTERVAL - (now - lastCaptureAt));
        handler.removeCallbacks(captureTask);
        captureQueued = true;
        handler.postDelayed(captureTask, delay);
    }

    private void captureBackdrop() {
        View content = contentView;
        if (content == null || getWidth() <= 0 || getHeight() <= 0) return;
        int bw = Math.max(1, Math.round(getWidth() * CAPTURE_SCALE));
        int bh = Math.max(1, Math.round(getHeight() * CAPTURE_SCALE));
        if (snapshot == null || snapshot.getWidth() != bw || snapshot.getHeight() != bh) {
            if (snapshot != null) snapshot.recycle();
            snapshot = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            backdropShader = new BitmapShader(snapshot,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backdropPaint.setShader(backdropShader);
        } else {
            snapshot.eraseColor(Color.TRANSPARENT);
        }

        Canvas canvas = new Canvas(snapshot);
        int[] selfLocation = new int[2];
        int[] contentLocation = new int[2];
        getLocationOnScreen(selfLocation);
        content.getLocationOnScreen(contentLocation);
        canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE);
        // 把导航条区域换算到内容视图的局部坐标系，只截取正后方的画面
        canvas.translate(contentLocation[0] - selfLocation[0],
                contentLocation[1] - selfLocation[1]);
        // 只光栅化胶囊背后这一小块区域：滚动时其余内容全部被裁剪掉，
        // 单次捕获开销大幅下降，才能跟屏幕刷新同频更新底图
        canvas.clipRect(0f, 0f, getWidth(), getHeight());
        content.draw(canvas);

        // 只更新采样矩阵：位图分辨率不变时不必重建 Shader，减少每帧对象分配
        shaderMatrix.setScale(getWidth() / (float) bw, getHeight() / (float) bh);
        if (backdropShader != null) backdropShader.setLocalMatrix(shaderMatrix);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = Math.min(cornerRadius, Math.min(getWidth(), getHeight()) / 2f);
        // G2 连续曲率：外层、发丝外框、内倒角各用一条超椭圆路径
        android.graphics.Path outerPath = SquirclePath.create(shapeRect, r);

        // 磨砂底图：缩小采样 + 双线性放大，天然形成柔和模糊
        if (backdropPaint.getShader() != null) {
            canvas.drawPath(outerPath, backdropPaint);
        } else {
            canvas.drawPath(outerPath, fallbackPaint);
        }

        // 玻璃质感层：白雾 → 左上反光斑 → 底部内反射 → 顶部厚度高光
        int glassSave = canvas.save();
        canvas.clipPath(outerPath);
        canvas.drawRect(shapeRect, tintPaint);
        canvas.drawRect(shapeRect, reflectionPaint);
        canvas.drawRect(shapeRect, bottomGlowPaint);
        canvas.drawRect(shapeRect, sheenPaint);
        canvas.restoreToCount(glassSave);

        // 1dp 亮白外缘 + 2.5dp 内圈折射高光，模拟液态玻璃的边缘反光
        canvas.drawPath(
                SquirclePath.create(insetRect, Math.max(0, r - dp(0.5f))),
                edgePaint);
        float innerInset = dp(2);
        innerRect.set(innerInset, innerInset, getWidth() - innerInset, getHeight() - innerInset);
        canvas.drawPath(
                SquirclePath.create(innerRect, Math.max(0, r - innerInset)),
                innerPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
