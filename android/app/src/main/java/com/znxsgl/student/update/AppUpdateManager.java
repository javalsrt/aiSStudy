package com.znxsgl.student.update;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.znxsgl.student.R;
import com.znxsgl.student.model.AppVersionInfo;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.widget.G2Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * App 检查更新 / 下载安装包 / 拉起系统安装器。
 *
 * 版本信息来源：GET /api/app/latest（后端 application.yml 的 app.update.* 配置）。
 * 安装包下载到 cacheDir，通过已有的 FileProvider 暴露给系统安装器。
 */
public final class AppUpdateManager {

    private static final String PREFS = "znxsgl";
    private static final String KEY_LAST_CHECK = "app_last_update_check";
    /** 启动时自动检查的最小间隔：一天 */
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final String APK_PREFIX = "znxsgl-update-";

    private static boolean checking = false;
    private static boolean dialogShowing = false;

    private final Activity activity;
    private final boolean manual;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private okhttp3.Call downloadCall;
    private boolean downloading;

    private AppUpdateManager(Activity activity, boolean manual) {
        this.activity = activity;
        this.manual = manual;
    }

    /** 启动时静默检查：同一设备一天最多请求一次 */
    public static void checkOnLaunch(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, 0);
        long last = prefs.getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        check(activity, false);
    }

    /** 手动检查（设置页入口）：无更新或失败时给出 Toast 反馈 */
    public static void check(Activity activity, boolean manual) {
        if (activity == null || activity.isFinishing()) return;
        if (checking || dialogShowing) {
            if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        new AppUpdateManager(activity, manual).fetch();
    }

    private void fetch() {
        try {
            ApiService api = RetrofitClient.getInstance().create(ApiService.class);
            api.getLatestAppVersion().enqueue(new Callback<AppVersionInfo>() {
                @Override
                public void onResponse(Call<AppVersionInfo> call, Response<AppVersionInfo> response) {
                    checking = false;
                    AppVersionInfo info = response.body();
                    if (!response.isSuccessful() || info == null) {
                        if (manual) toast("检查更新失败，请稍后重试");
                        return;
                    }
                    if (info.getVersionCode() > currentVersionCode()) {
                        showDialog(info);
                    } else if (manual) {
                        toast("已是最新版本 v" + currentVersionName());
                    }
                }

                @Override
                public void onFailure(Call<AppVersionInfo> call, Throwable t) {
                    checking = false;
                    if (manual) toast("网络异常，检查更新失败");
                }
            });
        } catch (Exception e) {
            checking = false;
            if (manual) toast("检查更新失败");
        }
    }

    private void showDialog(AppVersionInfo info) {
        if (activity.isFinishing()) return;

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCancelable(!info.isForceUpdate());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            window.setLayout((int) (dm.widthPixels * 0.86f), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.45f);
        }

        TextView title = dialog.findViewById(R.id.tv_update_title);
        TextView version = dialog.findViewById(R.id.tv_update_version);
        TextView log = dialog.findViewById(R.id.tv_update_log);
        TextView later = dialog.findViewById(R.id.btn_update_later);
        TextView now = dialog.findViewById(R.id.btn_update_now);

        title.setText("发现新版本 v" + safe(info.getVersionName()));
        version.setText("当前版本 v" + currentVersionName());
        if (info.getChangelog() != null && !info.getChangelog().trim().isEmpty()) {
            log.setText(info.getChangelog());
        } else {
            log.setVisibility(View.GONE);
        }

        later.setBackground(G2Drawable.pressed(activity, 0xFFF2F2F7,
                Color.TRANSPARENT, 0f, 10f, 0x12000000));
        now.setBackground(G2Drawable.pressed(activity, 0xFF0A84FF,
                Color.TRANSPARENT, 0f, 10f, 0x22000000));

        if (info.isForceUpdate()) later.setVisibility(View.GONE);
        later.setOnClickListener(v -> dismiss());
        now.setOnClickListener(v -> startDownload(info, now, later));

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            if (downloadCall != null && !downloadCall.isCanceled()) downloadCall.cancel();
        });
        dialogShowing = true;
        dialog.show();
    }

    private void startDownload(AppVersionInfo info, TextView now, TextView later) {
        if (downloading) return;
        String url = info.getDownloadUrl();
        if (url == null || url.trim().isEmpty()) {
            toast("下载地址无效，请联系管理员");
            return;
        }

        File apk = apkFile(info.getVersionCode());
        if (apk.exists() && apk.length() > 0) {
            install(apk);
            return;
        }

        downloading = true;
        now.setEnabled(false);
        later.setEnabled(false);
        now.setText("下载中…");

        ProgressBar progressBar = dialog.findViewById(R.id.pb_update);
        TextView percent = dialog.findViewById(R.id.tv_update_percent);
        View progressBox = dialog.findViewById(R.id.ll_update_progress);
        progressBox.setVisibility(View.VISIBLE);

        File tmp = new File(activity.getCacheDir(), APK_PREFIX + info.getVersionCode() + ".part");

        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder().url(url).build();
            downloadCall = client.newCall(request);
            try (okhttp3.Response response = downloadCall.execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("HTTP " + response.code());
                }
                long total = response.body().contentLength();
                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    long lastPost = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastPost > 120) {
                            lastPost = nowMs;
                            int pct = total > 0 ? (int) (downloaded * 100 / total) : -1;
                            main.post(() -> updateProgress(progressBar, percent, pct));
                        }
                    }
                    out.flush();
                }
                if (total > 0 && tmp.length() < total) throw new IOException("下载不完整");
                if (apk.exists() && !apk.delete()) throw new IOException("无法覆盖旧安装包");
                if (!tmp.renameTo(apk)) throw new IOException("保存安装包失败");
                main.post(() -> {
                    downloading = false;
                    install(apk);
                });
            } catch (Exception e) {
                if (tmp.exists() && !tmp.delete()) {
                    // 删除失败不影响重试，下次下载会覆盖
                }
                main.post(() -> {
                    downloading = false;
                    if (dialog != null && dialog.isShowing()) {
                        now.setEnabled(true);
                        later.setEnabled(true);
                        now.setText("立即更新");
                        progressBox.setVisibility(View.GONE);
                        toast("下载失败，请检查网络后重试");
                    }
                });
            }
        }).start();
    }

    private void updateProgress(ProgressBar bar, TextView percent, int value) {
        if (value >= 0) {
            bar.setProgress(value);
            percent.setText(value + "%");
        } else {
            percent.setText("下载中…");
        }
    }

    /** 下载完成后拉起系统安装器；未授权「安装未知应用」时先跳到系统设置 */
    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast("请允许本应用安装未知来源应用后重试");
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        dismiss();
    }

    private File apkFile(int versionCode) {
        return new File(activity.getCacheDir(), APK_PREFIX + versionCode + ".apk");
    }

    @SuppressWarnings("deprecation")
    private int currentVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void toast(String message) {
        if (!activity.isFinishing()) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
