package com.znxsgl.student.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.znxsgl.student.util.BlurUtil;

/**
 * 全屏显示 uiverse 加载动画，背景为当前 App 界面毛玻璃效果。
 */
public class FullscreenLoaderDialog extends Dialog {

    public enum LoaderType {
        COMPASS,
        RARE_COW
    }

    private static final String COMPASS_HTML =
            "<!DOCTYPE html>"
                    + "<html>"
                    + "<head>"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "<style>"
                    + "*{margin:0;padding:0;box-sizing:border-box;}"
                    + "body{background:transparent;width:100vw;height:100vh;display:flex;align-items:center;justify-content:center;overflow:hidden;}"
                    + ".pl{display:block;width:9.375em;height:9.375em;}"
                    + ".pl__arrows,.pl__ring-rotate,.pl__ring-stroke,.pl__tick{animation-duration:2s;animation-timing-function:linear;animation-iteration-count:infinite;}"
                    + ".pl__arrows{animation-name:arrows42;transform:rotate(45deg);transform-origin:16px 52px;}"
                    + ".pl__ring-rotate,.pl__ring-stroke{transform-origin:80px 80px;}"
                    + ".pl__ring-rotate{animation-name:ringRotate42;}"
                    + ".pl__ring-stroke{animation-name:ringStroke42;transform:rotate(-45deg);}"
                    + ".pl__tick{animation-name:tick42;}"
                    + ".pl__tick:nth-child(2){animation-delay:-1.75s;}"
                    + ".pl__tick:nth-child(3){animation-delay:-1.5s;}"
                    + ".pl__tick:nth-child(4){animation-delay:-1.25s;}"
                    + ".pl__tick:nth-child(5){animation-delay:-1s;}"
                    + ".pl__tick:nth-child(6){animation-delay:-0.75s;}"
                    + ".pl__tick:nth-child(7){animation-delay:-0.5s;}"
                    + ".pl__tick:nth-child(8){animation-delay:-0.25s;}"
                    + "@keyframes arrows42{from{transform:rotate(45deg);}to{transform:rotate(405deg);}}"
                    + "@keyframes ringRotate42{from{transform:rotate(0);}to{transform:rotate(720deg);}}"
                    + "@keyframes ringStroke42{from,to{stroke-dashoffset:452;transform:rotate(-45deg);}50%{stroke-dashoffset:169.5;transform:rotate(-180deg);}}"
                    + "@keyframes tick42{from,3%,47%,to{stroke-dashoffset:-12;}14%,36%{stroke-dashoffset:0;}}"
                    + "</style>"
                    + "</head>"
                    + "<body>"
                    + "<svg class=\"pl\" viewBox=\"0 0 160 160\" width=\"160px\" height=\"160px\" xmlns=\"http://www.w3.org/2000/svg\">"
                    + "<defs>"
                    + "<linearGradient id=\"grad\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0%\" stop-color=\"#000\"></stop><stop offset=\"100%\" stop-color=\"#fff\"></stop></linearGradient>"
                    + "<mask id=\"mask1\"><rect x=\"0\" y=\"0\" width=\"160\" height=\"160\" fill=\"url(#grad)\"></rect></mask>"
                    + "<mask id=\"mask2\"><rect x=\"28\" y=\"28\" width=\"104\" height=\"104\" fill=\"url(#grad)\"></rect></mask>"
                    + "</defs>"
                    + "<g><g class=\"pl__ring-rotate\"><circle class=\"pl__ring-stroke\" cx=\"80\" cy=\"80\" r=\"72\" fill=\"none\" stroke=\"hsl(223,90%,55%)\" stroke-width=\"16\" stroke-dasharray=\"452.39 452.39\" stroke-dashoffset=\"452\" stroke-linecap=\"round\" transform=\"rotate(-45,80,80)\"></circle></g></g>"
                    + "<g mask=\"url(#mask1)\"><g class=\"pl__ring-rotate\"><circle class=\"pl__ring-stroke\" cx=\"80\" cy=\"80\" r=\"72\" fill=\"none\" stroke=\"hsl(193,90%,55%)\" stroke-width=\"16\" stroke-dasharray=\"452.39 452.39\" stroke-dashoffset=\"452\" stroke-linecap=\"round\" transform=\"rotate(-45,80,80)\"></circle></g></g>"
                    + "<g><g stroke-width=\"4\" stroke-dasharray=\"12 12\" stroke-dashoffset=\"12\" stroke-linecap=\"round\" transform=\"translate(80,80)\">"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(-135,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(-90,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(-45,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(0,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(45,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(90,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(135,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,10%,90%)\" points=\"0,2 0,14\" transform=\"rotate(180,0,0) translate(0,40)\"></polyline>"
                    + "</g></g>"
                    + "<g mask=\"url(#mask1)\"><g stroke-width=\"4\" stroke-dasharray=\"12 12\" stroke-dashoffset=\"12\" stroke-linecap=\"round\" transform=\"translate(80,80)\">"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(-135,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(-90,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(-45,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(0,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(45,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(90,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(135,0,0) translate(0,40)\"></polyline>"
                    + "<polyline class=\"pl__tick\" stroke=\"hsl(223,90%,80%)\" points=\"0,2 0,14\" transform=\"rotate(180,0,0) translate(0,40)\"></polyline>"
                    + "</g></g>"
                    + "<g><g transform=\"translate(64,28)\"><g class=\"pl__arrows\" transform=\"rotate(45,16,52)\">"
                    + "<path fill=\"hsl(3,90%,55%)\" d=\"M17.998,1.506l13.892,43.594c.455,1.426-.56,2.899-1.998,2.899H2.108c-1.437,0-2.452-1.473-1.998-2.899L14.002,1.506c.64-2.008,3.356-2.008,3.996,0Z\"></path>"
                    + "<path fill=\"hsl(223,10%,90%)\" d=\"M14.009,102.499L.109,58.889c-.453-1.421,.559-2.889,1.991-2.889H29.899c1.433,0,2.444,1.468,1.991,2.889l-13.899,43.61c-.638,2.001-3.345,2.001-3.983,0Z\"></path>"
                    + "</g></g></g>"
                    + "<g mask=\"url(#mask2)\"><g transform=\"translate(64,28)\"><g class=\"pl__arrows\" transform=\"rotate(45,16,52)\">"
                    + "<path fill=\"hsl(333,90%,55%)\" d=\"M17.998,1.506l13.892,43.594c.455,1.426-.56,2.899-1.998,2.899H2.108c-1.437,0-2.452-1.473-1.998-2.899L14.002,1.506c.64-2.008,3.356-2.008,3.996,0Z\"></path>"
                    + "<path fill=\"hsl(223,90%,80%)\" d=\"M14.009,102.499L.109,58.889c-.453-1.421,.559-2.889,1.991-2.889H29.899c1.433,0,2.444,1.468,1.991,2.889l-13.899,43.61c-.638,2.001-3.345,2.001-3.983,0Z\"></path>"
                    + "</g></g></g>"
                    + "</svg>"
                    + "</body>"
                    + "</html>";

    private static final String RARE_COW_HTML =
            "<!DOCTYPE html>"
                    + "<html>"
                    + "<head>"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "<style>"
                    + "*{margin:0;padding:0;box-sizing:border-box;}"
                    + "body{background:transparent;width:100vw;height:100vh;display:flex;align-items:center;justify-content:center;overflow:hidden;}"
                    + ".container{position:relative;border-radius:50%;height:96px;width:96px;animation:rotate_3922 1.2s linear infinite;background-color:#9b59b6;background-image:linear-gradient(#9b59b6, #84cdfa, #5ad1cd);}"
                    + ".container span{position:absolute;border-radius:50%;height:100%;width:100%;background-color:#9b59b6;background-image:linear-gradient(#9b59b6, #84cdfa, #5ad1cd);}"
                    + ".container span:nth-of-type(1){filter:blur(5px);}"
                    + ".container span:nth-of-type(2){filter:blur(10px);}"
                    + ".container span:nth-of-type(3){filter:blur(25px);}"
                    + ".container span:nth-of-type(4){filter:blur(50px);}"
                    + ".container::after{content:'';position:absolute;top:10px;left:10px;right:10px;bottom:10px;background-color:#fff;border:solid 5px #ffffff;border-radius:50%;}"
                    + "@keyframes rotate_3922{from{transform:rotate(0deg);}to{transform:rotate(360deg);}}"
                    + "</style>"
                    + "</head>"
                    + "<body>"
                    + "<div class=\"container\"><span></span><span></span><span></span><span></span></div>"
                    + "</body>"
                    + "</html>";

    private final LoaderType loaderType;

    public FullscreenLoaderDialog(@NonNull Context context, LoaderType loaderType) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.loaderType = loaderType;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            // 状态栏/导航栏底色与毛玻璃遮罩同色：即使系统隐藏状态栏失效（部分 MIUI 等），
            // 顶部也不会露出 Activity 的白色状态栏
            window.setStatusBarColor(Color.parseColor("#B3000000"));
            window.setNavigationBarColor(Color.parseColor("#B3000000"));
            // 暗色底需要浅色系统图标
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // inset controller 在下方统一处理
            } else {
                window.getDecorView().setSystemUiVisibility(
                        window.getDecorView().getSystemUiVisibility()
                                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }

            // 延伸到状态栏/导航栏下方，避免顶部底部出现黑白条
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                // onCreate 阶段 setContentView 之前 DecorView 尚未创建，
                // 必须先触发 installDecor()，否则 getInsetsController() 内部对 mDecor 取方法会 NPE 崩溃
                window.getDecorView();
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                window.getDecorView().setSystemUiVisibility(flags);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BlurUtil.applyWindowBlur(window, 60);
                window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#B3000000")));
            } else {
                Drawable blurred = BlurUtil.blurActivityBackground(getOwnerActivity());
                if (blurred != null) {
                    ColorDrawable dim = new ColorDrawable(Color.parseColor("#B3000000"));
                    LayerDrawable layer = new LayerDrawable(new Drawable[]{blurred, dim});
                    window.setBackgroundDrawable(layer);
                } else {
                    window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#E6000000")));
                }
            }
        }

        WebView webView = new WebView(getContext());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.TRANSPARENT);
        String html = loaderType == LoaderType.RARE_COW ? RARE_COW_HTML : COMPASS_HTML;
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        setContentView(webView);

        // 双保险：部分 ROM（MIUI 等）不允许 Dialog 窗口覆盖状态栏区域，
        // 此时状态栏露出的是宿主 Activity 的底色——这里把宿主状态栏也同步切暗，
        // 关闭时恢复为页面底色，避免顶部出现白色断带
        android.app.Activity owner = getOwnerActivity();
        if (owner == null && getContext() instanceof android.app.Activity) {
            owner = (android.app.Activity) getContext();
        }
        if (owner != null) {
            Window aw = owner.getWindow();
            aw.setStatusBarColor(Color.parseColor("#B3000000"));
            aw.setNavigationBarColor(Color.parseColor("#B3000000"));
            androidx.core.view.WindowCompat.getInsetsController(aw, aw.getDecorView())
                    .setAppearanceLightStatusBars(false);
            setOnDismissListener(d -> {
                aw.setStatusBarColor(0xFFF2F2F7);
                aw.setNavigationBarColor(0xFFF2F2F7);
                androidx.core.view.WindowCompat.getInsetsController(aw, aw.getDecorView())
                        .setAppearanceLightStatusBars(true);
            });
        }

        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }
}
