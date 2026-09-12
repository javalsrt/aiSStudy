package com.znxsgl.student;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.znxsgl.student.animation.DecryptedTextAnimation;
import com.znxsgl.student.animation.ShinyTextAnimation;
import com.znxsgl.student.model.LoginRequest;
import com.znxsgl.student.model.LoginResponse;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import retrofit2.Call;
import retrofit2.Callback;

public class LoginActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "znxsgl";
    private static final String KEY_REMEMBER = "remember";
    private static final String KEY_SAVED_USERNAME = "saved_username";
    private static final String KEY_SAVED_PASSWORD = "saved_password";
    private static final String KEY_FIRST_LAUNCH = "first_launch_done";

    private EditText etUsername, etPassword;
    private Button btnLogin;
    private ProgressBar progressBar;
    private CheckBox cbRemember, cbAgree;
    private TextView tvAgree, tvBrandName, tvBrandDesc;
    private ApiService apiService;
    private DecryptedTextAnimation decryptedAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 初始化 RetrofitClient 的全局 Context（用于 401 拦截跳转）
        RetrofitClient.init(this);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 首次启动强制登录：清除本地数据（防止被系统备份还原出的旧登录态直接跳过登录）
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, false)) {
            prefs.edit().clear().putBoolean(KEY_FIRST_LAUNCH, true).apply();
        }

        // 已登录直接进主页
        String token = prefs.getString("token", null);
        if (token != null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        // 记住密码且保存了账号密码时，跳一键登录页
        if (prefs.getBoolean(KEY_REMEMBER, false)
                && !TextUtils.isEmpty(prefs.getString(KEY_SAVED_USERNAME, ""))
                && !TextUtils.isEmpty(prefs.getString(KEY_SAVED_PASSWORD, ""))) {
            startActivity(new Intent(this, QuickLoginActivity.class));
            finish();
            return;
        }

        apiService = RetrofitClient.getInstance().create(ApiService.class);

        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);
        cbRemember = findViewById(R.id.cb_remember);
        cbAgree = findViewById(R.id.cb_agree);
        tvAgree = findViewById(R.id.tv_agree);
        tvBrandName = findViewById(R.id.tv_brand_name);
        tvBrandDesc = findViewById(R.id.tv_brand_desc);

        // 品牌动画
        ShinyTextAnimation.applyShimmer(tvBrandName);
        decryptedAnimation = new DecryptedTextAnimation(tvBrandDesc, getString(R.string.brand_desc));
        decryptedAnimation.start();

        // 协议未勾选时登录按钮禁用
        btnLogin.setEnabled(false);
        updateLoginButtonState();
        cbAgree.setOnCheckedChangeListener((buttonView, isChecked) -> updateLoginButtonState());

        setupAgreementText();

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void updateLoginButtonState() {
        boolean checked = cbAgree.isChecked();
        btnLogin.setEnabled(checked);
        btnLogin.setAlpha(checked ? 1f : 0.55f);
    }

    private void setupAgreementText() {
        String prefix = getString(R.string.agree_protocol_prefix);
        String user = getString(R.string.user_agreement);
        String and = getString(R.string.protocol_and);
        String privacy = getString(R.string.privacy_policy);

        String fullText = prefix + user + and + privacy;
        SpannableString spannable = new SpannableString(fullText);

        int userStart = prefix.length();
        int userEnd = userStart + user.length();
        int andStart = userEnd;
        int andEnd = andStart + and.length();
        int privacyStart = andEnd;
        int privacyEnd = privacyStart + privacy.length();

        spannable.setSpan(new ProtocolClickSpan(getString(R.string.protocol_title_user), buildUserAgreement()),
                userStart, userEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ProtocolClickSpan(getString(R.string.protocol_title_privacy), buildPrivacyPolicy()),
                privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        tvAgree.setText(spannable);
        tvAgree.setMovementMethod(LinkMovementMethod.getInstance());
        tvAgree.setHighlightColor(Color.TRANSPARENT);

        tvAgree.setOnClickListener(v -> {
            if (tvAgree.getSelectionStart() == -1 && tvAgree.getSelectionEnd() == -1) {
                cbAgree.setChecked(!cbAgree.isChecked());
            }
        });
    }

    private String buildUserAgreement() {
        return "<h1>用户服务协议</h1>"
                + "<p>欢迎使用本学习平台。本协议是您与本平台之间关于使用平台服务所订立的协议。</p>"
                + "<p>1. 您应当保证注册账号时提供真实、准确、完整的身份信息。</p>"
                + "<p>2. 您不得利用本平台从事违法违规活动，不得发布或传播不良信息。</p>"
                + "<p>3. 平台提供的课程内容、试题资料等知识产权归平台或权利人所有，仅供个人学习使用。</p>"
                + "<p>4. 平台有权根据运营需要调整服务内容，并保留对本协议进行修改的权利。</p>"
                + "<p>5. 如您违反本协议约定，平台有权暂停或终止向您提供服务。</p>";
    }

    private String buildPrivacyPolicy() {
        return "<h1>隐私政策</h1>"
                + "<p>我们非常重视您的个人信息保护。本政策说明我们如何收集、使用和保护您的个人信息。</p>"
                + "<p>1. 我们会在您注册、登录、使用学习功能时收集必要的账号信息及学习行为数据。</p>"
                + "<p>2. 我们仅在为您提供服务所需的范围内使用上述信息，不会向第三方出售您的个人信息。</p>"
                + "<p>3. 我们采取符合行业标准的安全措施保护您的数据，防止未经授权的访问、泄露或篡改。</p>"
                + "<p>4. 您可以随时联系平台管理方查询、更正或删除您的个人信息。</p>"
                + "<p>5. 继续使用本平台服务即视为您同意本隐私政策的全部内容。</p>";
    }

    private void attemptLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.error_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!cbAgree.isChecked()) {
            Toast.makeText(this, R.string.toast_agree_required, Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        btnLogin.setText(R.string.login_loading);
        progressBar.setVisibility(View.VISIBLE);

        doLogin(username, password);
    }

    private void doLogin(String username, String password) {
        LoginRequest request = new LoginRequest(username, password);
        apiService.login(request).enqueue(new Callback<LoginResponse>() {
            @Override
            public void onResponse(Call<LoginResponse> call, retrofit2.Response<LoginResponse> response) {
                btnLogin.setEnabled(true);
                btnLogin.setText(R.string.login_btn);
                progressBar.setVisibility(View.GONE);
                updateLoginButtonState();

                if (response.isSuccessful() && response.body() != null) {
                    LoginResponse data = response.body();
                    if (data.getToken() != null) {
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit()
                                .putString("token", data.getToken())
                                .putString("realName", data.getRealName())
                                .putString("username", data.getUsername())
                                .putLong("userId", data.getUserId())
                                .putInt("role", data.getRole())
                                .apply();

                        handleRememberPassword(prefs, username, password);

                        Toast.makeText(LoginActivity.this,
                                "欢迎，" + data.getRealName(), Toast.LENGTH_SHORT).show();

                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    } else {
                        Toast.makeText(LoginActivity.this,
                                data.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    try {
                        String errBody = response.errorBody().string();
                        Toast.makeText(LoginActivity.this, errBody, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(LoginActivity.this, R.string.error_auth, Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onFailure(Call<LoginResponse> call, Throwable t) {
                btnLogin.setEnabled(true);
                btnLogin.setText(R.string.login_btn);
                progressBar.setVisibility(View.GONE);
                updateLoginButtonState();
                Toast.makeText(LoginActivity.this,
                        R.string.error_network + ": " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleRememberPassword(SharedPreferences prefs, String username, String password) {
        SharedPreferences.Editor editor = prefs.edit();
        if (cbRemember.isChecked()) {
            editor.putBoolean(KEY_REMEMBER, true)
                    .putString(KEY_SAVED_USERNAME, username)
                    .putString(KEY_SAVED_PASSWORD, password);
        } else {
            editor.remove(KEY_REMEMBER)
                    .remove(KEY_SAVED_USERNAME)
                    .remove(KEY_SAVED_PASSWORD);
        }
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ShinyTextAnimation.stopShimmer(tvBrandName);
        if (decryptedAnimation != null) {
            decryptedAnimation.stop();
        }
    }

    private class ProtocolClickSpan extends ClickableSpan {
        private final String title;
        private final String content;

        ProtocolClickSpan(String title, String content) {
            this.title = title;
            this.content = content;
        }

        @Override
        public void onClick(@NonNull View widget) {
            Intent intent = new Intent(LoginActivity.this, ProtocolActivity.class);
            intent.putExtra(ProtocolActivity.EXTRA_TITLE, title);
            intent.putExtra(ProtocolActivity.EXTRA_CONTENT, content);
            startActivity(intent);
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            super.updateDrawState(ds);
            ds.setColor(getResources().getColor(R.color.primary, null));
            ds.setUnderlineText(false);
        }
    }
}
