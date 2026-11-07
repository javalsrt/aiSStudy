package com.znxsgl.student.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.znxsgl.student.R;
import com.znxsgl.student.UiMsg;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 校园 AI 助手（底部导航同级页面）：
 * 校内规定（底层知识）、课程资料、个人成绩课表问答。
 */
public class AiAssistantFragment extends Fragment {

    private String token;
    private LinearLayout boxMessages;
    private LinearLayout boxSuggestions;
    private GridLayout gridCapabilities;
    private View welcomeCard;
    private EditText etInput;
    private Button btnSend;
    private ScrollView scroll;
    private boolean loading = false;
    private boolean quickLoaded = false;
    /** 会话序号：点「新建会话」后自增，用来丢弃上一轮还没回来的回答 */
    private int sessionSeq = 0;

    private static final String[][] FALLBACK_CAPS = {
            {"校园规定", "作息/宿舍/请假"},
            {"课程问答", "章节重点解析"},
            {"我的成绩", "考试与答题"},
            {"错题复盘", "薄弱知识点"},
            {"我的课表", "今天上什么课"},
            {"就业升学", "专业与升学"}
    };

    private static final String[] FALLBACK_SUGGESTIONS = {
            "宿舍几点熄灯？",
            "请假需要什么流程？",
            "我上次考试考了多少分？",
            "我哪门课错题最多？",
            "今天我有几节课？"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ai_assistant, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SharedPreferences prefs = requireContext().getSharedPreferences("znxsgl", Context.MODE_PRIVATE);
        token = "Bearer " + prefs.getString("token", "");

        boxMessages = view.findViewById(R.id.boxMessages);
        boxSuggestions = view.findViewById(R.id.boxSuggestions);
        gridCapabilities = view.findViewById(R.id.gridCapabilities);
        welcomeCard = view.findViewById(R.id.welcomeCard);
        etInput = view.findViewById(R.id.etInput);
        btnSend = view.findViewById(R.id.btnSend);

        // 右上角「+」：开启新会话
        view.findViewById(R.id.btnNewChat).setOnClickListener(v -> startNewSession());
        scroll = view.findViewById(R.id.scroll);

        btnSend.setOnClickListener(v -> submit());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });

        if (gridCapabilities.getChildCount() == 0) {
            renderFallback();
            loadQuick();
        }
    }

    /** 拉取欢迎页能力与推荐问题（只拉一次，切页不重复请求） */
    private void loadQuick() {
        if (quickLoaded || !isAdded()) return;
        quickLoaded = true;
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getAssistantQuick(token).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!isAdded() || getView() == null || !resp.isSuccessful() || resp.body() == null) return;
                View v = getView();
                Map<String, Object> data = resp.body();
                Object greeting = data.get("greeting");
                if (greeting != null) {
                    ((TextView) v.findViewById(R.id.tvGreeting)).setText(String.valueOf(greeting));
                }
                Object subtitle = data.get("subtitle");
                if (subtitle != null) {
                    ((TextView) v.findViewById(R.id.tvSubtitle)).setText(String.valueOf(subtitle));
                }
                Object caps = data.get("capabilities");
                if (caps instanceof List && !((List<?>) caps).isEmpty()) {
                    gridCapabilities.removeAllViews();
                    for (Object o : (List<?>) caps) {
                        if (o instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) o;
                            addCapability(String.valueOf(m.get("title")), String.valueOf(m.get("desc")));
                        }
                    }
                }
                Object suggestions = data.get("suggestions");
                if (suggestions instanceof List && !((List<?>) suggestions).isEmpty()) {
                    boxSuggestions.removeAllViews();
                    for (Object o : (List<?>) suggestions) addSuggestion(String.valueOf(o));
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                // 保留兜底内容
            }
        });
    }

    private void renderFallback() {
        gridCapabilities.removeAllViews();
        boxSuggestions.removeAllViews();
        for (String[] c : FALLBACK_CAPS) addCapability(c[0], c[1]);
        for (String s : FALLBACK_SUGGESTIONS) addSuggestion(s);
    }

    private void addCapability(String title, String desc) {
        Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(roundBg(0xFFF5F7FF, 12, 0xFFE6ECFF));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        box.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF2B3A67);
        box.addView(t);

        TextView d = new TextView(ctx);
        d.setText(desc);
        d.setTextSize(10.5f);
        d.setTextColor(0xFF8A93A6);
        d.setPadding(0, dp(3), 0, 0);
        box.addView(d);

        box.setOnClickListener(v -> {
            String q = "校园规定".equals(title) ? "学校有哪些规定？" : title;
            etInput.setText(q);
            etInput.setSelection(q.length());
        });
        gridCapabilities.addView(box);
    }

    private void addSuggestion(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF4A5468);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setOnClickListener(v -> {
            etInput.setText(text);
            submit();
        });
        boxSuggestions.addView(t);
    }

    private void submit() {
        if (loading || !isAdded()) return;
        String text = etInput.getText() == null ? "" : etInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(requireContext(), "请输入问题", Toast.LENGTH_SHORT).show();
            return;
        }
        etInput.setText("");
        hideWelcome();
        addBubble(text, true);

        final TextView loadingBubble = addBubble("正在思考…", false);
        final int seq = sessionSeq;
        setLoading(true);

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        Map<String, String> body = new HashMap<>();
        body.put("message", text);
        api.askAssistant(token, body).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!isAdded() || seq != sessionSeq) return; // 已开启新会话，丢弃旧回答
                setLoading(false);
                if (!resp.isSuccessful() || resp.body() == null) {
                    loadingBubble.setText("服务暂时不可用，请稍后再试");
                    return;
                }
                Map<String, Object> data = resp.body();
                Object answer = data.get("answer");
                String ans = answer == null ? "抱歉，我没有理解这个问题。" : String.valueOf(answer);
                Object src = data.get("sources");
                if (src instanceof List) {
                    List<String> titles = new ArrayList<>();
                    for (Object o : (List<?>) src) {
                        if (o instanceof Map) {
                            Object t = ((Map<?, ?>) o).get("title");
                            if (t != null) titles.add(String.valueOf(t));
                        }
                    }
                    if (!titles.isEmpty()) {
                        // 来源只保留前 3 项，避免长串来源把回答挤得更密
                        String joined = TextUtils.join("、", titles.subList(0, Math.min(3, titles.size())));
                        if (titles.size() > 3) joined += " 等 " + titles.size() + " 项";
                        ans += "\n\n来源：" + joined;
                    }
                }
                loadingBubble.setText(ans);
                scrollToBottom();
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                if (!isAdded() || seq != sessionSeq) return;
                setLoading(false);
                loadingBubble.setText(UiMsg.network(t));
                scrollToBottom();
            }
        });
    }

    /** 新建会话：清空消息区、回到欢迎页，并作废上一轮还没返回的回答 */
    private void startNewSession() {
        sessionSeq++;
        if (boxMessages != null) boxMessages.removeAllViews();
        if (etInput != null) etInput.setText("");
        if (welcomeCard != null) welcomeCard.setVisibility(View.VISIBLE);
        setLoading(false);
        if (scroll != null) scroll.fullScroll(View.FOCUS_UP);
        Toast.makeText(requireContext(), "已开启新会话", Toast.LENGTH_SHORT).show();
    }

    private void hideWelcome() {
        if (welcomeCard != null && welcomeCard.getVisibility() == View.VISIBLE) {
            welcomeCard.setVisibility(View.GONE);
        }
    }

    private void setLoading(boolean v) {
        loading = v;
        btnSend.setEnabled(!v);
        btnSend.setAlpha(v ? 0.6f : 1f);
    }

    private TextView addBubble(String text, boolean me) {
        Context ctx = requireContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(me ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        // 回答通常多段，行距放宽一些，避免文字挤在一起
        tv.setLineSpacing(dp(5), 1.15f);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.82);
        tv.setMaxWidth(maxW);
        if (me) {
            tv.setTextColor(Color.WHITE);
            tv.setBackground(roundBg(0xFF3B6EF6, 14, 0));
        } else {
            tv.setTextColor(0xFF1F2937);
            tv.setBackground(roundBg(0xFFFFFFFF, 14, 0xFFECECF1));
        }
        row.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        boxMessages.addView(row);
        scrollToBottom();
        return tv;
    }

    private void scrollToBottom() {
        if (scroll == null) return;
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private GradientDrawable roundBg(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
