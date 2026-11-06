package com.znxsgl.student.network;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.znxsgl.student.LoginActivity;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    /** 线上服务器（nginx 反代 /api/ 与 /ws/） */
    public static final String URL_SERVER = "http://8.163.105.116:8081";
    /** 本地开发：模拟器固定用 10.0.2.2（自动映射到宿主机 localhost:8080） */
    public static final String URL_LOCAL = "http://10.0.2.2:8080";

    private static final String PREFS_NAME = "znxsgl";
    public static final String KEY_BASE_URL = "base_url";

    /** 当前请求地址：默认服务器，可在 App「设置 → 请求地址」中切换为本地并持久化 */
    public static String getBaseUrl() {
        if (appContext == null) return URL_SERVER;
        return appContext.getSharedPreferences(PREFS_NAME, 0).getString(KEY_BASE_URL, URL_SERVER);
    }

    public static boolean isServer() {
        return URL_SERVER.equals(getBaseUrl());
    }

    /**
     * 切换请求地址。切换后旧 token 在新服务器上无效，调用方应引导用户重新登录。
     * 同时销毁 Retrofit 单例，下一次请求按新地址重建。
     */
    public static void setBaseUrl(Context context, String url) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx != null) {
            appContext = ctx;
            // 用 commit 保证紧接着的 getBaseUrl() 读到新值
            ctx.getSharedPreferences(PREFS_NAME, 0).edit().putString(KEY_BASE_URL, url).commit();
        }
        instance = null;
    }

    /**
     * 将后端返回的图片/文件 URL 转换为可访问的完整 URL。
     * 处理后缀相对路径、历史数据中的 localhost、以及已是完整 URL 的情况。
     */
    public static String toFullUrl(String url) {
        if (url == null || url.isEmpty() || "#".equals(url)) return url;
        String base = getBaseUrl();
        // 兼容历史数据里硬编码的 localhost
        if (url.startsWith("http://localhost:8080")) {
            return base + url.substring("http://localhost:8080".length());
        }
        if (url.startsWith("https://localhost:8080")) {
            return base + url.substring("https://localhost:8080".length());
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        // 相对路径：确保 base 末尾没有 /，url 开头有 /
        String prefix = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String suffix = url.startsWith("/") ? url : "/" + url;
        return prefix + suffix;
    }

    private static Retrofit instance;
    private static Context appContext;

    /** 在 Application 中初始化，用于全局 401 拦截跳转 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * 获取可用 Context：优先使用传入的 context；当传入为 null（如 Fragment 已销毁后
     * 异步回调中调用 getContext()）时，降级为 Application 上下文，避免 Toast 等组件 NPE。
     */
    public static Context safeContext(Context context) {
        return context != null ? context : appContext;
    }

    public static Retrofit getInstance() {
        if (instance == null) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);

            // 401/403 拦截器：token 过期或账号在其他设备登录时跳转登录页
            Interceptor authInterceptor = chain -> {
                Response response = chain.proceed(chain.request());
                int code = response.code();
                if ((code == 401 || code == 403) && appContext != null) {
                    // 在主线程提示并跳转登录页
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(appContext, "登录已过期，请重新登录", Toast.LENGTH_LONG).show();
                        // 清除本地 token
                        SharedPreferences prefs = appContext.getSharedPreferences("znxsgl", 0);
                        prefs.edit().remove("token").remove("userId").apply();
                        // 跳转登录页
                        Intent intent = new Intent(appContext, LoginActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        appContext.startActivity(intent);
                    });
                }
                return response;
            };

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(authInterceptor)
                    .addInterceptor(logging)
                    .build();

            instance = new Retrofit.Builder()
                    .baseUrl(getBaseUrl())
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return instance;
    }
}
