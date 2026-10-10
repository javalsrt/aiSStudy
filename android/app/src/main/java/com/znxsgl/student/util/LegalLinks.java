package com.znxsgl.student.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import com.znxsgl.student.R;

/**
 * 登录页底部合规信息的链接绑定（ICP备案 / 公安备案 / 举报邮箱）。
 * <p>三个登录相关页面共用同一份文案与链接，避免多处硬编码不一致。
 */
public final class LegalLinks {

    /** ICP 备案（工信部） */
    public static final String URL_ICP = "https://beian.miit.gov.cn/";
    /** 公安备案（全国互联网安全管理服务平台） */
    public static final String URL_GONGAN =
            "https://beian.mps.gov.cn/#/query/webSearch?code=45070202002848";
    /** 举报邮箱 */
    public static final String MAIL_REPORT = "j2647046805@163.com";

    private LegalLinks() {
    }

    /** 绑定登录页脚（布局中需包含 layout/include_login_legal.xml，无该视图时静默跳过） */
    public static void bind(Activity activity) {
        bind(activity, R.id.tv_legal_icp, URL_ICP);
        bind(activity, R.id.layout_legal_gongan, URL_GONGAN);
        bind(activity, R.id.tv_legal_mail, "mailto:" + MAIL_REPORT);
    }

    private static void bind(Activity activity, int viewId, String url) {
        View v = activity.findViewById(viewId);
        if (v == null) return;
        v.setOnClickListener(x -> open(activity, url));
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // 设备无浏览器/邮件客户端时静默失败，不影响登录
        }
    }
}
