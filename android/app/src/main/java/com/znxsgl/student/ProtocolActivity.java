package com.znxsgl.student;

import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 用户服务协议 / 隐私政策展示页。
 * 通过 intent extra 传入标题与 HTML 内容本地渲染，不依赖网络。
 */
public class ProtocolActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CONTENT = "content";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protocol);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String content = getIntent().getStringExtra(EXTRA_CONTENT);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(title == null ? getString(R.string.user_agreement) : title);
        }

        ProgressBar progressBar = findViewById(R.id.progress_bar);
        WebView webView = findViewById(R.id.web_view);
        webView.setBackgroundColor(getResources().getColor(R.color.background, null));
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(ProgressBar.GONE);
                } else {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                }
            }
        });

        String html = wrapHtml(content == null ? "" : content);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String wrapHtml(String body) {
        return "<!DOCTYPE html>"
                + "<html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<style>"
                + "body{font-family:system-ui,-apple-system,sans-serif;line-height:1.7;color:#1D1D1F;padding:16px;margin:0;background:#F2F2F7;}"
                + "h1{font-size:18px;color:#5E6AD2;margin-bottom:12px;}"
                + "p{margin:10px 0;font-size:14px;}"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
