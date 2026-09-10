package com.znxsgl.student.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.View;
import android.view.Window;

/**
 * 窗口背景模糊工具。
 */
public class BlurUtil {

    private static final float SCALE = 0.18f;
    private static final float BLUR_RADIUS = 16f;

    /**
     * 截取当前 Activity 内容并模糊，返回可设为 Dialog 背景的 Drawable。
     */
    public static Drawable blurActivityBackground(Activity activity) {
        if (activity == null || activity.isFinishing()) return null;
        Window window = activity.getWindow();
        if (window == null) return null;

        View decor = window.getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) return null;

        Bitmap bitmap = Bitmap.createBitmap((int) (width * SCALE), (int) (height * SCALE), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(SCALE, SCALE);
        decor.draw(canvas);

        Bitmap blurred = blurBitmap(activity, bitmap);
        bitmap.recycle();
        return new BitmapDrawable(activity.getResources(), blurred);
    }

    private static Bitmap blurBitmap(Activity activity, Bitmap bitmap) {
        Bitmap out = Bitmap.createBitmap(bitmap);
        RenderScript rs = RenderScript.create(activity);
        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        Allocation in = Allocation.createFromBitmap(rs, bitmap);
        Allocation outAlloc = Allocation.createFromBitmap(rs, out);
        script.setRadius(BLUR_RADIUS);
        script.setInput(in);
        script.forEach(outAlloc);
        outAlloc.copyTo(out);

        in.destroy();
        outAlloc.destroy();
        script.destroy();
        rs.destroy();
        return out;
    }

    /**
     * Android 12+ 可直接设置窗口背景模糊半径。
     */
    public static void applyWindowBlur(Window window, int radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
            try {
                java.lang.reflect.Method m = Window.class.getMethod("setBackgroundBlurBehindRadius", int.class);
                m.invoke(window, radius);
            } catch (Exception ignored) {}
        }
    }
}
