package com.znxsgl.student.fragment;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.znxsgl.student.MainActivity;

import com.znxsgl.student.widget.IOSSwitch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.znxsgl.student.CourseDetailActivity;
import com.znxsgl.student.ExamHomeworkActivity;
import com.znxsgl.student.RichTextRenderer;
import com.znxsgl.student.UiMsg;
import com.znxsgl.student.R;
import com.znxsgl.student.model.StudentCourse;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.network.WebSocketManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ProfileFragment extends Fragment implements WebSocketManager.OnChatUpdateListener {

    private RecyclerView rvCourses;
    private CourseAdapter adapter;
    private final List<StudentCourse> courseList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean isFirstResume = true;
    private TextView tvStatHours, tvStatQuiz, tvStatRate;
    /** 未读数按课程ID索引：同名课程（多门「英语」）必须互不干扰 */
    private final Map<Long, Integer> unreadMap = new HashMap<>();

    // 学习时长实时刷新
    private final Runnable refreshFocusTotal = new Runnable() {
        @Override
        public void run() {
            loadFocusTotal();
            mainHandler.postDelayed(this, 10000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);

        prefs = requireActivity().getSharedPreferences("znxsgl", 0);

        // 显示用户信息
        String realName = prefs.getString("realName", "学生");
        String username = prefs.getString("username", "");

        TextView tvAvatar = view.findViewById(R.id.tv_avatar);
        TextView tvName = view.findViewById(R.id.tv_real_name);
        TextView tvInfo = view.findViewById(R.id.tv_student_info);

        tvAvatar.setText(String.valueOf(realName.charAt(0)));
        tvName.setText(realName);
        tvInfo.setText("学号: " + username);

        // 设置入口
        view.findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsMenu());

        // 学习时长
        tvStatHours = view.findViewById(R.id.tv_stat_hours);
        loadFocusTotal();

        // 答题统计
        tvStatQuiz = view.findViewById(R.id.tv_stat_quiz);
        tvStatRate = view.findViewById(R.id.tv_stat_rate);
        loadQuizStats();

        // 收藏题目入口
        view.findViewById(R.id.ll_bookmarks).setOnClickListener(v -> showBookmarks());
        loadBookmarkCount(view);

        // 通讯录入口
        view.findViewById(R.id.ll_contacts).setOnClickListener(v -> showContacts());
        view.findViewById(R.id.ll_leave).setOnClickListener(v -> openLeave("LEAVE"));
        view.findViewById(R.id.ll_outing).setOnClickListener(v -> openLeave("OUTING"));

        // 课程列表
        rvCourses = view.findViewById(R.id.rv_courses);
        rvCourses.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CourseAdapter();
        rvCourses.setAdapter(adapter);

        // 注册聊天推送监听
        WebSocketManager.getInstance().addChatListener(this);
        loadUnreadCounts();

        // 长按拖拽排序
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                Collections.swap(courseList, from, to);
                adapter.notifyItemMoved(from, to);
                saveCourseOrder();
                return true;
            }
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
        });
        helper.attachToRecyclerView(rvCourses);

        loadCourses();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从其他页面返回：强制刷新一次（可能有新消息/新统计）
        refreshProfileData(true);
        // 实时轮询学习时长
        mainHandler.removeCallbacks(refreshFocusTotal);
        mainHandler.post(refreshFocusTotal);
    }

    // 切 Tab 节流：隐藏再显示时不再每次全量请求，避免每次切换等数秒
    private long lastHeavyRefreshAt = 0;
    private long lastUnreadRefreshAt = 0;
    private static final long HEAVY_REFRESH_MS = 30_000;
    private static final long UNREAD_REFRESH_MS = 5_000;

    private void refreshProfileData(boolean force) {
        if (!isAdded() || prefs == null) return;
        long now = System.currentTimeMillis();
        if (force || now - lastHeavyRefreshAt > HEAVY_REFRESH_MS) {
            lastHeavyRefreshAt = now;
            loadCourses();
            loadFocusTotal();
            loadQuizStats();
            if (getView() != null) loadBookmarkCount(getView());
        }
        // 未读红点仍保持较短新鲜度（5 秒），但不会连带整页数据重拉
        if (force || now - lastUnreadRefreshAt > UNREAD_REFRESH_MS) {
            lastUnreadRefreshAt = now;
            loadUnreadCounts();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(refreshFocusTotal);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        WebSocketManager.getInstance().removeChatListener(this);
    }

    /** hide() 不触发 onPause，处理 Tab 切换 */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            mainHandler.removeCallbacks(refreshFocusTotal);
        } else {
            // 切回本 Tab：30 秒内直接复用已有数据，界面立即可见，不再等网络
            refreshProfileData(false);
            mainHandler.removeCallbacks(refreshFocusTotal);
            mainHandler.post(refreshFocusTotal);
        }
    }

    /** 加载累计学习总时长（分钟） */
    private void loadFocusTotal() {
        String token = "Bearer " + prefs.getString("token", "");
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getFocusTotal(token).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!isAdded() || tvStatHours == null) return;
                if (resp.isSuccessful() && resp.body() != null) {
                    Object secs = resp.body().get("totalSeconds");
                    if (secs instanceof Number) {
                        mainHandler.post(() -> {
                            int minutes = ((Number) secs).intValue() / 60;
                            tvStatHours.setText(String.valueOf(minutes));
                        });
                    }
                }
            }
            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {}
        });
    }

    /** 加载累计答题统计：完成题目数、正确率 */
    private void loadQuizStats() {
        String token = "Bearer " + prefs.getString("token", "");
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getQuizStats(token).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!isAdded() || tvStatQuiz == null || tvStatRate == null) return;
                if (resp.isSuccessful() && resp.body() != null) {
                    mainHandler.post(() -> {
                        Object answered = resp.body().get("totalAnswered");
                        Object accuracy = resp.body().get("accuracy");
                        tvStatQuiz.setText(answered instanceof Number ? String.valueOf(((Number) answered).intValue()) : "0");
                        tvStatRate.setText(accuracy != null ? accuracy.toString() : "0.0%");
                    });
                }
            }
            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {}
        });
    }

    /** 供 MainActivity WebSocket 回调，刷新课程列表 */
    public void loadCoursesIfAdded() {
        if (isAdded()) loadCourses();
    }

    /** 加载收藏数量 */
    private void loadBookmarkCount(View view) {
        String token = "Bearer " + prefs.getString("token", "");
        RetrofitClient.getInstance().create(ApiService.class)
                .getBookmarks(token).enqueue(new Callback<List<Map<String, Object>>>() {
                    @Override public void onResponse(Call<List<Map<String, Object>>> c,
                                                      Response<List<Map<String, Object>>> r) {
                        if (!isAdded()) return;
                        if (r.isSuccessful() && r.body() != null) {
                            TextView tv = view.findViewById(R.id.tv_bookmark_count);
                            if (tv != null) tv.setText(String.valueOf(r.body().size()));
                        }
                    }
                    @Override public void onFailure(Call<List<Map<String, Object>>> c, Throwable t) {}
                });
    }

    /** 展示设置菜单：课程图标生成、退出登录 */
    private void showSettingsMenu() {
        if (!isAdded()) return;
        // iOS 风格底部弹出面板
        Dialog dialog = new Dialog(getContext());
        dialog.setContentView(R.layout.dialog_settings);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.45f);
        }

        // 题目自动跳转开关（默认开启，持久化到 prefs）
        IOSSwitch switchAutoJump = dialog.findViewById(R.id.switch_auto_jump);
        if (switchAutoJump != null) {
            switchAutoJump.setChecked(prefs.getBoolean("quiz_auto_jump", true));
            switchAutoJump.setOnCheckedChangeListener(checked ->
                    prefs.edit().putBoolean("quiz_auto_jump", checked).apply());
        }
        View rowAutoJump = dialog.findViewById(R.id.row_auto_jump);
        if (rowAutoJump != null && switchAutoJump != null) {
            rowAutoJump.setOnClickListener(v -> switchAutoJump.toggle());
        }

        View rowIconGen = dialog.findViewById(R.id.row_icon_gen);
        if (rowIconGen != null) {
            rowIconGen.setOnClickListener(v -> {
                dialog.dismiss();
                showIconGenerator();
            });
        }

        // 请求地址（服务器 / 本地）
        TextView tvBaseUrl = dialog.findViewById(R.id.tv_base_url);
        if (tvBaseUrl != null) {
            tvBaseUrl.setText(RetrofitClient.isServer() ? "教务系统" : "本地离线");
        }
        View rowBaseUrl = dialog.findViewById(R.id.row_base_url);
        if (rowBaseUrl != null) {
            rowBaseUrl.setOnClickListener(v -> {
                dialog.dismiss();
                showBaseUrlPicker();
            });
        }

        View rowLogout = dialog.findViewById(R.id.row_logout);
        if (rowLogout != null) {
            rowLogout.setOnClickListener(v -> {
                dialog.dismiss();
                doLogout();
            });
        }

        TextView btnCancel = dialog.findViewById(R.id.btn_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** 请求地址切换：教务系统 / 本地离线（与设置面板同款底部卡片） */
    private void showBaseUrlPicker() {
        if (!isAdded()) return;
        Dialog dialog = new Dialog(getContext());
        dialog.setContentView(R.layout.dialog_base_url);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.45f);
        }

        boolean server = RetrofitClient.isServer();
        ImageView checkServer = dialog.findViewById(R.id.check_server);
        ImageView checkLocal = dialog.findViewById(R.id.check_local);
        checkServer.setVisibility(server ? View.VISIBLE : View.INVISIBLE);
        checkLocal.setVisibility(server ? View.INVISIBLE : View.VISIBLE);

        dialog.findViewById(R.id.row_server).setOnClickListener(v -> {
            dialog.dismiss();
            switchBaseUrl(RetrofitClient.URL_SERVER, "教务系统");
        });
        dialog.findViewById(R.id.row_local).setOnClickListener(v -> {
            dialog.dismiss();
            switchBaseUrl(RetrofitClient.URL_LOCAL, "本地离线");
        });
        dialog.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void switchBaseUrl(String url, String label) {
        if (url.equals(RetrofitClient.getBaseUrl())) {
            Toast.makeText(RetrofitClient.safeContext(getContext()),
                    "当前已是 " + label, Toast.LENGTH_SHORT).show();
            return;
        }
        RetrofitClient.setBaseUrl(requireContext(), url);
        Toast.makeText(RetrofitClient.safeContext(getContext()),
                "已切换到 " + label + "，请重新登录", Toast.LENGTH_LONG).show();
        // 两套环境的 token 不通用，切换后回到登录页
        doLogout();
    }

    /** 课程图标生成入口 */
    private void showIconGenerator() {
        if (!isAdded()) return;
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("课程图标生成")
                .setMessage("为课程生成专属 AI 封面图，让课程卡片更直观。")
                .setPositiveButton("选择课程", (d, w) -> {
                    // TODO: 接入 AI 图标生成能力
                    Toast.makeText(RetrofitClient.safeContext(getContext()), "课程图标生成功能开发中...", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 退出登录 */
    private void doLogout() {
        // 保留记住密码的账号信息与姓名，退出后进入一键登录页
        String savedUsername = prefs.getString("saved_username", "");
        String savedPassword = prefs.getString("saved_password", "");
        boolean remember = prefs.getBoolean("remember", false);
        String realName = prefs.getString("realName", "");

        // 请求地址属于设备级设置，退出登录时保留
        String baseUrl = RetrofitClient.getBaseUrl();

        prefs.edit().clear().apply();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("remember", remember)
                .putString("saved_username", savedUsername)
                .putString("saved_password", savedPassword)
                .putString("realName", realName)
                .putString(RetrofitClient.KEY_BASE_URL, baseUrl)
                // 设备级标记：保留后登录页不会再执行一次「首次启动清空」
                .putBoolean("first_launch_done", true);
        editor.apply();

        com.znxsgl.student.network.WebSocketManager.getInstance().disconnect();

        Class<?> target = (remember && !savedUsername.isEmpty() && !savedPassword.isEmpty())
                ? com.znxsgl.student.QuickLoginActivity.class
                : com.znxsgl.student.LoginActivity.class;
        Intent intent = new Intent(getActivity(), target);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    /** 展示收藏列表：ViewPager2 竖滑卡片（与错题分析一致） */
    private void showBookmarks() {
        if (!isAdded()) return;
        String token = "Bearer " + prefs.getString("token", "");
        android.app.ProgressDialog pd = new android.app.ProgressDialog(getContext());
        pd.setMessage("加载中...");
        pd.show();

        RetrofitClient.getInstance().create(ApiService.class)
                .getBookmarks(token).enqueue(new Callback<List<Map<String, Object>>>() {
                    @Override public void onResponse(Call<List<Map<String, Object>>> c,
                                                      Response<List<Map<String, Object>>> r) {
                        pd.dismiss();
                        if (!isAdded()) return;
                        if (!r.isSuccessful() || r.body() == null || r.body().isEmpty()) {
                            Toast.makeText(RetrofitClient.safeContext(getContext()), "暂无收藏", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        List<Map<String, Object>> list = r.body();

                        // inflate 错题分析式的全屏 Dialog
                        View root = LayoutInflater.from(getContext())
                                .inflate(R.layout.dialog_wrong_analysis, null);
                        ViewPager2 vp = root.findViewById(R.id.vp_questions);
                        root.findViewById(R.id.ll_subjects).setVisibility(View.GONE);
                        root.findViewById(R.id.tv_page).setVisibility(View.GONE);

                        vp.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
                        vp.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                            @NonNull @Override
                            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                                return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext())
                                        .inflate(R.layout.item_wrong_question, p, false)) {};
                            }
                            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                                Map<String, Object> m = list.get(pos);
                                String question = m.get("question") != null ? m.get("question").toString() : "";
                                String error = m.get("errorReason") != null ? m.get("errorReason").toString() : "";
                                String improve = m.get("improve") != null ? m.get("improve").toString() : "";
                                // 把 question 显示在知识点的位置
                                RichTextRenderer.set(h.itemView.findViewById(R.id.tv_question), question);
                                ((TextView) h.itemView.findViewById(R.id.tv_error)).setText(error);
                                ((TextView) h.itemView.findViewById(R.id.tv_improve)).setText(improve);
                            }
                            @Override public int getItemCount() { return list.size(); }
                        });

                        // 关闭按钮
                        root.findViewById(R.id.btn_close).setOnClickListener(v2 -> {
                            ViewParent p = root.getParent();
                            while (p != null && !(p instanceof android.app.Dialog)) p = p.getParent();
                            if (p instanceof android.app.Dialog) ((android.app.Dialog) p).dismiss();
                        });

                        // 隐藏底部的明白了/收藏按钮
                        root.findViewById(R.id.btn_understand).setVisibility(View.GONE);
                        root.findViewById(R.id.btn_bookmark).setVisibility(View.GONE);

                        // 页码
                        final TextView tvPage = root.findViewById(R.id.tv_page);
                        vp.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                            @Override public void onPageSelected(int pos) {
                                String subj = list.get(pos).get("subject") != null
                                        ? list.get(pos).get("subject").toString() : "";
                                tvPage.setVisibility(View.VISIBLE);
                                tvPage.setText(subj + "  " + (pos + 1) + "/" + list.size());
                            }
                        });

                        android.app.Dialog dlg = new android.app.Dialog(getContext(),
                                android.R.style.Theme_Translucent_NoTitleBar);
                        dlg.setContentView(root);
                        dlg.setCanceledOnTouchOutside(true);
                        dlg.setOnShowListener(d -> {
                            android.view.Window w = dlg.getWindow();
                            if (w != null) {
                                w.getDecorView().setPadding(0,0,0,0);
                                w.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                                w.getDecorView().setOutlineProvider(null);
                                w.getDecorView().setElevation(0);
                                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                                lp.copyFrom(w.getAttributes());
                                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                                lp.gravity = Gravity.CENTER;
                                lp.dimAmount = 0f;
                                w.setAttributes(lp);
                                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                            }
                        });
                        dlg.show();
                    }
                    @Override public void onFailure(Call<List<Map<String, Object>>> c, Throwable t) {
                        pd.dismiss();
                    }
                });
    }

    /** 展示通讯录：同班同学 + 任课教师 */
    private void showContacts() {
        if (!isAdded()) return;
        String token = "Bearer " + prefs.getString("token", "");
        android.app.ProgressDialog pd = new android.app.ProgressDialog(getContext());
        pd.setMessage("加载中...");
        pd.show();

        RetrofitClient.getInstance().create(ApiService.class)
                .getStudentContacts(token).enqueue(new Callback<Map<String, Object>>() {
                    @Override public void onResponse(Call<Map<String, Object>> c,
                                                      Response<Map<String, Object>> r) {
                        pd.dismiss();
                        if (!isAdded()) return;
                        if (!r.isSuccessful() || r.body() == null) {
                            Toast.makeText(RetrofitClient.safeContext(getContext()), "加载失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openContactsDialog(r.body());
                    }
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                        pd.dismiss();
                        if (!isAdded()) return;
                        Toast.makeText(RetrofitClient.safeContext(getContext()), UiMsg.network(t), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void openContactsDialog(Map<String, Object> data) {
        View root = LayoutInflater.from(getContext()).inflate(R.layout.dialog_contacts, null);
        LinearLayout llClassmates = root.findViewById(R.id.ll_classmates);
        LinearLayout llTeachers = root.findViewById(R.id.ll_teachers);
        LinearLayout llCadres = root.findViewById(R.id.ll_cadres);
        TextView tvNoClassmates = root.findViewById(R.id.tv_no_classmates);
        TextView tvNoTeachers = root.findViewById(R.id.tv_no_teachers);
        TextView tvNoCadres = root.findViewById(R.id.tv_no_cadres);
        TextView tvCadreTitle = root.findViewById(R.id.tv_cadre_title);

        List<Map<String, Object>> classmates = (List<Map<String, Object>>) data.get("classmates");
        List<Map<String, Object>> teachers = (List<Map<String, Object>>) data.get("teachers");

        // 本班班主任
        TextView tvHead = root.findViewById(R.id.tv_head_teacher);
        String headName = str(data.get("headTeacher"));
        int headId = intOf(data.get("headTeacherId"));
        if (!headName.isEmpty()) {
            tvHead.setText("班主任：" + headName);
            tvHead.setVisibility(View.VISIBLE);
        }

        // ---------- 任课教师：校级职务 > 班主任 > 职称高 ----------
        TextView tvTeacherTitle = root.findViewById(R.id.tv_teacher_title);
        if (teachers == null || teachers.isEmpty()) {
            tvNoTeachers.setVisibility(View.VISIBLE);
        } else {
            tvTeacherTitle.setText("任课教师 · " + teachers.size());
            List<Map<String, Object>> sorted = new ArrayList<>(teachers);
            Collections.sort(sorted, (a, b) -> {
                // 本班班主任固定置顶
                boolean headA = headId > 0 && intOf(a.get("teacherId")) == headId;
                boolean headB = headId > 0 && intOf(b.get("teacherId")) == headId;
                if (headA != headB) return headA ? -1 : 1;
                int r = teacherRank(a) - teacherRank(b);
                if (r != 0) return r;
                r = titleRank(str(b.get("title"))) - titleRank(str(a.get("title")));
                if (r != 0) return r;
                return str(a.get("realName")).compareTo(str(b.get("realName")));
            });
            for (Map<String, Object> item : sorted) {
                String name = str(item.get("realName"));
                String title = str(item.get("title"));
                String duty = str(item.get("duty"));
                String courses = str(item.get("courseNames"));
                int isLeader = intOf(item.get("isLeader"));
                boolean isHead = headId > 0 && intOf(item.get("teacherId")) == headId;

                String badge;
                int badgeColor;
                if (isHead) {
                    badge = "班主任";                              // 本班班主任优先展示
                    badgeColor = 0xFF3B6EF6;                       // 班主任：主题蓝
                } else if (isLeader == 1 || isSchoolOffice(duty)) {
                    badge = duty.isEmpty() ? "校级领导" : duty;   // 教导主任 / 副校长……
                    badgeColor = 0xFFF59E0B;                       // 领导：琥珀色
                } else if ("班主任".equals(duty)) {
                    badge = "班主任";
                    badgeColor = 0xFF3B6EF6;
                } else if (!duty.isEmpty() && !"教师".equals(duty)) {
                    badge = duty;                                  // 其他自定义职务原样显示
                    badgeColor = 0xFF3B6EF6;
                } else if (!title.isEmpty()) {
                    badge = title;                                 // 职称：教授/副教授/讲师/助教
                    badgeColor = 0xFF9CA3AF;
                } else {
                    badge = "教师";
                    badgeColor = 0xFF9CA3AF;
                }

                // 副标题：任教课程 + 职称 + 行政职务（班主任身份已在徽标上体现）
                StringBuilder sub = new StringBuilder(courses.isEmpty() ? "任课教师" : courses);
                if (!title.isEmpty()) sub.append(" · ").append(title);
                if (!duty.isEmpty() && !"教师".equals(duty) && !"班主任".equals(duty)) {
                    sub.append(" · ").append(duty);
                }
                addContactItem(llTeachers, name, sub.toString(), badge, badgeColor);
            }
        }

        // ---------- 班级干部 ----------
        List<Map<String, Object>> cadres = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        if (classmates != null) {
            for (Map<String, Object> item : classmates) {
                if (str(item.get("position")).isEmpty()) others.add(item);
                else cadres.add(item);
            }
        }
        Collections.sort(cadres, (a, b) -> {
            int r = cadreRank(str(a.get("position"))) - cadreRank(str(b.get("position")));
            if (r != 0) return r;
            return str(a.get("realName")).compareTo(str(b.get("realName")));
        });
        if (cadres.isEmpty()) {
            tvNoCadres.setVisibility(View.VISIBLE);
        } else {
            tvCadreTitle.setText("班级干部 · " + cadres.size());
            for (Map<String, Object> item : cadres) {
                String no = str(item.get("studentNo"));
                addContactItem(llCadres, str(item.get("realName")),
                        no.isEmpty() ? "班干部" : "学号 " + no,
                        str(item.get("position")), 0xFF10B981);
            }
        }

        // ---------- 同班同学 ----------
        TextView tvClassmateTitle = root.findViewById(R.id.tv_classmate_title);
        if (others.isEmpty()) {
            tvNoClassmates.setVisibility(View.VISIBLE);
        } else {
            tvClassmateTitle.setText("同班同学 · " + others.size());
            for (Map<String, Object> item : others) {
                String no = str(item.get("studentNo"));
                addContactItem(llClassmates, str(item.get("realName")),
                        no.isEmpty() ? "暂无学号" : "学号 " + no, null, 0);
            }
        }

        android.app.Dialog dlg = new android.app.Dialog(getContext(),
                android.R.style.Theme_Translucent_NoTitleBar);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        root.findViewById(R.id.btn_close).setOnClickListener(v -> dlg.dismiss());

        // 卡片阴影：圆角 + elevation，配合窗口透明背景形成投影
        root.setElevation(dp(24));
        root.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        root.setClipToOutline(false);

        dlg.setOnShowListener(d -> {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                int side = dp(20);
                w.getDecorView().setPadding(side, 0, side, 0);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(w.getAttributes());
                // 限制最大宽度：两侧留出可见的遮罩背景，平板/大屏上不至于拉满
                int screenW = getResources().getDisplayMetrics().widthPixels;
                lp.width = Math.min(screenW - side * 2, dp(380));
                // 高度放宽到 82%，留出更多滚动空间，避免拥挤
                lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82);
                lp.gravity = Gravity.CENTER;
                lp.dimAmount = 0.55f;
                w.setAttributes(lp);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
        dlg.show();
    }

    /** 班干部职务排序（与后台职务字典一致） */
    private static final String[] CADRE_ORDER = {
            "班长", "副班长", "团支书", "学习委员", "纪律委员", "生活委员",
            "文娱委员", "体育委员", "心理委员", "宣传委员", "劳动委员", "课代表"
    };

    /** 教师排序权重：0 校级职务，1 班主任，2 其他职务，3 普通教师 */
    private int teacherRank(Map<String, Object> t) {
        String duty = str(t.get("duty"));
        if (intOf(t.get("isLeader")) == 1 || isSchoolOffice(duty)) return 0;
        if ("班主任".equals(duty)) return 1;
        if (!duty.isEmpty() && !"教师".equals(duty)) return 2;
        return 3;
    }

    /** 校级 / 处室职务：校长、副校长、书记、教导主任等 */
    private boolean isSchoolOffice(String duty) {
        return duty.contains("校长") || duty.contains("书记") || duty.contains("主任");
    }

    /** 职称高低：教授 4 > 副教授 3 > 讲师 2 > 助教 1 > 未填 0 */
    private int titleRank(String title) {
        switch (title) {
            case "教授": return 4;
            case "副教授": return 3;
            case "讲师": return 2;
            case "助教": return 1;
            default: return 0;
        }
    }

    private int cadreRank(String position) {
        for (int i = 0; i < CADRE_ORDER.length; i++) {
            if (CADRE_ORDER[i].equals(position)) return i;
        }
        return CADRE_ORDER.length;
    }

    private void addContactItem(ViewGroup parent, String name, String sub) {
        addContactItem(parent, name, sub, null, 0);
    }

    private void addContactItem(ViewGroup parent, String name, String sub, String badge, int badgeColor) {
        View item = LayoutInflater.from(getContext()).inflate(R.layout.item_contact, parent, false);
        TextView tvAvatar = item.findViewById(R.id.tv_avatar);
        TextView tvName = item.findViewById(R.id.tv_name);
        TextView tvSub = item.findViewById(R.id.tv_sub);
        TextView tvBadge = item.findViewById(R.id.tv_badge);

        tvAvatar.setText(name.isEmpty() ? "?" : String.valueOf(name.charAt(0)));
        tvName.setText(name);
        tvSub.setText(sub);
        if (badge != null && !badge.isEmpty()) {
            tvBadge.setText(badge);
            tvBadge.setVisibility(View.VISIBLE);
            if (badgeColor != 0) {
                tvBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(badgeColor));
            }
        } else {
            tvBadge.setVisibility(View.GONE);
        }
        parent.addView(item);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void loadCourses() {
        String token = prefs.getString("token", "");
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentCourses("Bearer " + token).enqueue(new Callback<List<StudentCourse>>() {
            @Override
            public void onResponse(Call<List<StudentCourse>> call, Response<List<StudentCourse>> resp) {
                List<StudentCourse> items = (resp.isSuccessful() && resp.body() != null)
                        ? resp.body() : new ArrayList<>();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    courseList.clear();
                    courseList.addAll(items);
                    restoreCourseOrder();
                    applyChatSorting();
                    adapter.notifyDataSetChanged();
                    rvCourses.requestLayout();
                });
            }
            @Override
            public void onFailure(Call<List<StudentCourse>> call, Throwable t) {
                mainHandler.post(() ->
                    Toast.makeText(RetrofitClient.safeContext(getContext()), UiMsg.network(t), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ========== Adapter ==========
    private static final String[] ICONS = {"📖","💻","📱","🌐","🎨","🔧","✍️","🗣️","🎬","📋","👥","📜","🏛️","🛡️"};

    private class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_course, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            StudentCourse c = courseList.get(pos);
            holder.icon.setText(ICONS[pos % ICONS.length]);

            if (c.isActive()) {
                holder.name.setText(c.getCourseName());
                holder.name.setAlpha(1.0f);
                holder.icon.setAlpha(1.0f);
                holder.status.setVisibility(View.GONE);
            } else if (c.isPublished()) {
                holder.name.setText(c.getCourseName());
                holder.name.setAlpha(0.55f);
                holder.icon.setAlpha(0.55f);
                holder.status.setVisibility(View.VISIBLE);
                holder.status.setText("无课");
            } else {
                holder.name.setText(c.getCourseName());
                holder.name.setAlpha(0.45f);
                holder.icon.setAlpha(0.45f);
                holder.status.setVisibility(View.VISIBLE);
                holder.status.setText("已下架");
            }

            // 学期标签（暑假班、寒假班等）
            String sem = c.getSemester();
            if (sem != null && !sem.isEmpty()) {
                if (sem.contains("暑假") || sem.contains("暑期")) {
                    holder.semesterTag.setVisibility(View.VISIBLE);
                    holder.semesterTag.setText("暑假班");
                    holder.semesterTag.setTextColor(0xFFFF9500);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_orange);
                } else if (sem.contains("寒假")) {
                    holder.semesterTag.setVisibility(View.VISIBLE);
                    holder.semesterTag.setText("寒假班");
                    holder.semesterTag.setTextColor(0xFF0A84FF);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_blue);
                } else if (sem.contains("培训")) {
                    holder.semesterTag.setVisibility(View.VISIBLE);
                    holder.semesterTag.setText("培训班");
                    holder.semesterTag.setTextColor(0xFFAF52DE);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_purple);
                } else {
                    holder.semesterTag.setVisibility(View.GONE);
                }
            } else {
                holder.semesterTag.setVisibility(View.GONE);
            }

            // 未读红点（从unreadMap实时获取，按课程ID匹配）
            int unread = unreadMap.getOrDefault(c.getCourseId(), 0);
            if (unread > 0) {
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                holder.badge.setVisibility(View.GONE);
            }

            // 课程卡片底部优先显示最新消息摘要，没有消息时才显示排课信息
            if (c.getLastMessage() != null && !c.getLastMessage().isEmpty()) {
                holder.info.setText(c.getLastMessage());
            } else if (c.getScheduleInfo() != null && !c.getScheduleInfo().isEmpty()) {
                holder.info.setText(c.getScheduleInfo());
            } else if (c.getTeacherName() != null && !c.getTeacherName().isEmpty()) {
                holder.info.setText("教师：" + c.getTeacherName());
            } else {
                holder.info.setText("暂无排课信息");
            }

            // 渲染考试/作业待办入口
            bindPendingExams(holder, c);
        }

        @SuppressWarnings("unchecked")
        private void bindPendingExams(VH holder, StudentCourse c) {
            holder.llPendingExams.removeAllViews();
            List<Map<String, Object>> exams = c.getPendingExams();
            if (exams == null || exams.isEmpty()) {
                holder.hsvPendingExams.setVisibility(View.GONE);
                return;
            }
            holder.hsvPendingExams.setVisibility(View.VISIBLE);

            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);

            for (Map<String, Object> exam : exams) {
                Object idObj = exam.get("id");
                Object typeObj = exam.get("type");
                Object titleObj = exam.get("title");
                Object submitStatusObj = exam.get("submitStatus");
                Object startTimeObj = exam.get("startTime");
                if (idObj == null) continue;

                long examId = ((Number) idObj).longValue();
                String type = typeObj != null ? typeObj.toString() : "exam";
                String title = titleObj != null ? titleObj.toString() : "";
                int submitStatus = submitStatusObj instanceof Number ? ((Number) submitStatusObj).intValue() : 0;

                // 判断是否到达开始时间（未到也允许点击进入查看详情，但显示"待开始"标签）
                boolean started = true;
                String startTimeText = "";
                if (startTimeObj != null) {
                    try {
                        java.util.Date d;
                        if (startTimeObj instanceof Long) {
                            d = new java.util.Date(((Long) startTimeObj).longValue());
                        } else if (startTimeObj instanceof String) {
                            d = sdf.parse((String) startTimeObj);
                        } else {
                            d = null;
                        }
                        if (d != null) {
                            started = now >= d.getTime();
                            startTimeText = sdf.format(d);
                        }
                    } catch (Exception ignored) {}
                }

                TextView tag = new TextView(holder.itemView.getContext());
                String label = "exam".equals(type) ? "考试" : "作业";
                String statusText;
                if (submitStatus == 2) {
                    statusText = "已完成";
                } else if (submitStatus == 1) {
                    statusText = "继续作答";
                } else if (!started) {
                    statusText = "待开始 · " + startTimeText;
                } else {
                    statusText = "开始作答";
                }
                tag.setText(label + "：" + title + "（" + statusText + "）");
                tag.setTextSize(11);
                tag.setPadding(16, 8, 16, 8);

                int textColor;
                int bgColor;
                if (submitStatus == 2) {
                    textColor = 0xFF86868B;
                    bgColor = 0xFFF5F5F7;
                } else if (!started) {
                    textColor = 0xFFAF52DE;
                    bgColor = 0xFFF5EBFF;
                } else if ("exam".equals(type)) {
                    textColor = 0xFFFF9500;
                    bgColor = 0xFFFFF3E2;
                } else {
                    textColor = 0xFF0A84FF;
                    bgColor = 0xFFE5F1FF;
                }
                tag.setTextColor(textColor);

                GradientDrawable drawable = new GradientDrawable();
                drawable.setColor(bgColor);
                drawable.setCornerRadius(16);
                tag.setBackground(drawable);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(12);
                tag.setLayoutParams(lp);

                // 所有未完成/未开始的标签都可点击（进入详情页，ExamHomeworkActivity 会校验开始时间）
                if (submitStatus != 2) {
                    // 明确设置可点击：解决外部NestScrollView拦截、父布局无焦点导致的点击失效
                    tag.setClickable(true);
                    tag.setFocusable(true);
                    tag.setOnClickListener(v -> {
                        Intent intent = new Intent(getContext(), ExamHomeworkActivity.class);
                        intent.putExtra("exam_id", examId);
                        intent.putExtra("exam_title", title);
                        intent.putExtra("exam_type", type);
                        intent.putExtra("course_name", c.getCourseName());
                        startActivity(intent);
                    });
                }

                holder.llPendingExams.addView(tag);
            }
        }

        @Override
        public int getItemCount() { return courseList.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView icon, name, info, status, badge, semesterTag;
            HorizontalScrollView hsvPendingExams;
            LinearLayout llPendingExams;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.tv_course_icon);
                name = v.findViewById(R.id.tv_course_name);
                info = v.findViewById(R.id.tv_course_info);
                status = v.findViewById(R.id.tv_course_status);
                badge = v.findViewById(R.id.tv_badge);
                semesterTag = v.findViewById(R.id.tv_semester_tag);
                hsvPendingExams = v.findViewById(R.id.hsv_pending_exams);
                llPendingExams = v.findViewById(R.id.ll_pending_exams);

                // 解决：外层 RecyclerView + NestedScrollView 双重滚动容器导致子 exam 标签"点不到"
                // hsvPendingExams 触摸时，告诉所有父View不要拦截横向/点击手势
                hsvPendingExams.setOnTouchListener((view, ev) -> {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (view.getParent().getParent() != null) {
                        try {
                            ((android.view.ViewParent) view.getParent().getParent())
                                    .requestDisallowInterceptTouchEvent(true);
                        } catch (Exception ignored) {}
                    }
                    return false;
                });

                v.setOnClickListener(v2 -> {
                    StudentCourse c = courseList.get(getAdapterPosition());
                    Intent intent = new Intent(getContext(), CourseDetailActivity.class);
                    intent.putExtra("course_name", c.getCourseName());
                    intent.putExtra("course_id", c.getCourseId());
                    startActivity(intent);
                });
            }
        }
    }

    private void saveCourseOrder() {
        StringBuilder sb = new StringBuilder();
        for (StudentCourse c : courseList) {
            if (sb.length() > 0) sb.append(",");
            sb.append(c.getCourseId());
        }
        prefs.edit().putString("course_order", sb.toString()).apply();
    }

    private void restoreCourseOrder() {
        String saved = prefs.getString("course_order", "");
        if (saved.isEmpty() || courseList.size() <= 1) return;
        String[] ids = saved.split(",");
        List<StudentCourse> ordered = new ArrayList<>();
        for (String idStr : ids) {
            long cid = Long.parseLong(idStr);
            for (StudentCourse c : courseList) {
                if (c.getCourseId() == cid) { ordered.add(c); break; }
            }
        }
        for (StudentCourse c : courseList) {
            if (!ordered.contains(c)) ordered.add(c);
        }
        if (ordered.size() == courseList.size()) {
            courseList.clear();
            courseList.addAll(ordered);
        }
    }

    /**
     * 会话列表式排序（QQ/微信语义）：
     * 1) 有未读消息的课程置顶，未读越多越靠前；
     * 2) 已读完但最近有消息的课程，按最后一条消息时间倒序（老师刚发过言的课程会自动上浮）；
     * 3) 完全没有聊天记录的课程排在最后，保持用户手动拖拽顺序。
     */
    private void applyChatSorting() {
        if (courseList.size() <= 1) return;
        List<StudentCourse> unread = new ArrayList<>();
        List<StudentCourse> recent = new ArrayList<>();
        List<StudentCourse> rest = new ArrayList<>();
        for (StudentCourse c : courseList) {
            int n = unreadMap.getOrDefault(c.getCourseId(), c.getUnreadCount());
            if (n > 0) unread.add(c);
            else if (c.getLastMessageAt() > 0) recent.add(c);
            else rest.add(c);
        }
        if (unread.isEmpty() && recent.isEmpty()) return;
        unread.sort((a, b) -> {
            int na = unreadMap.getOrDefault(a.getCourseId(), a.getUnreadCount());
            int nb = unreadMap.getOrDefault(b.getCourseId(), b.getUnreadCount());
            if (na != nb) return Integer.compare(nb, na);
            return Long.compare(b.getLastMessageAt(), a.getLastMessageAt());
        });
        // 已经读完但最近有消息的课程，按消息时间倒序跟随在未读之后
        recent.sort((a, b) -> Long.compare(b.getLastMessageAt(), a.getLastMessageAt()));
        courseList.clear();
        courseList.addAll(unread);
        courseList.addAll(recent);
        courseList.addAll(rest);
    }

    /** 打开请假/外出一体页（type=LEAVE / OUTING） */
    private void openLeave(String type) {
        if (!isAdded()) return;
        android.content.Intent it = new android.content.Intent(requireContext(), com.znxsgl.student.LeaveActivity.class);
        it.putExtra(com.znxsgl.student.LeaveActivity.EXTRA_TYPE, type);
        startActivity(it);
    }

    /** 刷新未读数：统一由 MainActivity 拉取后下发，避免重复请求 */
    private void loadUnreadCounts() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).refreshUnread();
        }
    }

    /** 由 MainActivity 下发的最新未读数（key = 课程ID），同时驱动课程卡片红点 */
    public void applyUnreadCounts(Map<Long, Integer> counts) {
        unreadMap.clear();
        if (counts != null) unreadMap.putAll(counts);
        applyChatSorting();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onChatUpdate(Long courseId, String courseName, String senderName, String content) {
        // 用户正在该课程聊天窗口里：不弹提示（红点与列表由 MainActivity 统一处理）
        Long active = MainActivity.getActiveChatCourseId();
        if (active != null && active.equals(courseId)) return;
        mainHandler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()),
                courseName + " 新消息: " + content, Toast.LENGTH_SHORT).show());
    }
}
