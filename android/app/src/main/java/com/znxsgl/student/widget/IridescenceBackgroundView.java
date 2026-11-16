package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * 学习页背景：ReactBits「Iridescence」的 Android 移植版。
 *
 * 参数在官网默认预览基础上放慢：色值 #2f69e1、亮度 1.5、detail 8、warp 1，
 * 动画速度取默认的 0.35 倍。
 * 液态虹彩由一段 AGSL 着色器按屏幕尺寸逐像素计算，图层本身不接收触摸。
 *
 * 动画用 RuntimeShader（API 33+）直接画在 View 上，因此能正常参与页面绘制
 * 和底部导航的截屏取景；低于 33 的设备退化为同色系的静态渐变。
 */
public class IridescenceBackgroundView extends View {

    /** 主色：#2f69e1 */
    private static final float COLOR_R = 0x2F / 255f;
    private static final float COLOR_G = 0x69 / 255f;
    private static final float COLOR_B = 0xE1 / 255f;
    /** 背景纯展示：放慢到官网默认速度的 0.35 倍，避免液态流动分散注意力 */
    private static final float SPEED = 0.35f;

    /**
     * 与 WebGL 版 FRAGMENT 着色器同一套公式，去掉鼠标搅动 / 点击漩涡 / 颗粒，
     * 循环层数固定为 detail=8（AGSL 需要可展开的循环）。
     */
    private static final String SHADER = ""
            + "uniform float2 uResolution;\n"
            + "uniform float uTime;\n"
            + "uniform float3 uColor;\n"
            + "uniform float2 uMouse;\n"
            + "uniform float uAmplitude;\n"
            + "uniform float uScale;\n"
            + "uniform float uRotation;\n"
            + "uniform float uWarp;\n"
            + "uniform float uSaturation;\n"
            + "uniform float uBrightness;\n"
            + "uniform float uContrast;\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 vUv = fragCoord / uResolution;\n"
            + "    float mr = min(uResolution.x, uResolution.y);\n"
            + "    float2 aspect = uResolution / mr;\n"
            + "    float2 uv = (vUv * 2.0 - 1.0) * aspect;\n"
            + "    float c = cos(uRotation);\n"
            + "    float s = sin(uRotation);\n"
            + "    uv = float2(c * uv.x + s * uv.y, -s * uv.x + c * uv.y) / uScale;\n"
            + "    uv += (uMouse - float2(0.5)) * uAmplitude;\n"
            + "\n"
            + "    float d = -uTime * 0.5;\n"
            + "    float a = 0.0;\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "        float fi = float(i);\n"
            + "        a += cos(fi - d - a * uv.x * uWarp);\n"
            + "        d += sin(uv.y * fi + a);\n"
            + "    }\n"
            + "    d += uTime * 0.5;\n"
            + "\n"
            + "    float3 col = float3(cos(uv.x * d) * 0.6 + 0.4,\n"
            + "                        cos(uv.y * a) * 0.6 + 0.4,\n"
            + "                        cos(a + d) * 0.5 + 0.5);\n"
            + "    float3 k = float3(cos(d), cos(a), cos(2.5));\n"
            + "    col = float3(cos(col.x * k.x * 0.5 + 0.5),\n"
            + "                 cos(col.y * k.y * 0.5 + 0.5),\n"
            + "                 cos(col.z * k.z * 0.5 + 0.5)) * uColor;\n"
            + "\n"
            + "    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));\n"
            + "    col = mix(float3(luma), col, uSaturation);\n"
            + "    col = (col - 0.5) * uContrast + 0.5;\n"
            + "    col *= uBrightness;\n"
            + "    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);\n"
            + "}";

    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ShaderHolder holder;
    private long startMs;

    public IridescenceBackgroundView(Context context) {
        this(context, null);
    }

    public IridescenceBackgroundView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IridescenceBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        startMs = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                holder = new ShaderHolder();
            } catch (Throwable ignored) {
                // 着色器编译失败时退回静态渐变，不影响页面可用性
                holder = null;
            }
        }
        fallbackPaint.setColor(0xFF2F69E1);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        // 低版本兜底：同色系的对角渐变，保持整页蓝色的基调
        fallbackPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{0xFF1B3F8C, 0xFF2F69E1, 0xFF6FA8FF, 0xFF9CC4FF},
                new float[]{0f, 0.45f, 0.78f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 软件画布（例如底部导航截取背景）不支持 RuntimeShader，直接画静态渐变
        if (holder != null && canvas.isHardwareAccelerated()) {
            float time = (SystemClock.uptimeMillis() - startMs) / 1000f * SPEED;
            holder.update(w, h, time);
            canvas.drawRect(0, 0, w, h, holder.paint);
            if (isShown()) postInvalidateOnAnimation();
        } else {
            canvas.drawRect(0, 0, w, h, fallbackPaint);
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) postInvalidateOnAnimation();
    }

    /** 只在 API 33+ 加载：持有 RuntimeShader 与画笔 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class ShaderHolder {
        final RuntimeShader shader;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ShaderHolder() {
            shader = new RuntimeShader(SHADER);
            paint.setShader(shader);
        }

        void update(int w, int h, float time) {
            shader.setFloatUniform("uResolution", (float) w, (float) h);
            shader.setFloatUniform("uTime", time);
            shader.setFloatUniform("uColor", COLOR_R, COLOR_G, COLOR_B);
            shader.setFloatUniform("uMouse", 0.5f, 0.5f);
            shader.setFloatUniform("uAmplitude", 0.1f);
            shader.setFloatUniform("uScale", 1f);
            shader.setFloatUniform("uRotation", 0f);
            shader.setFloatUniform("uWarp", 1f);
            shader.setFloatUniform("uSaturation", 1f);
            shader.setFloatUniform("uBrightness", 1.5f);
            shader.setFloatUniform("uContrast", 1f);
        }
    }
}
