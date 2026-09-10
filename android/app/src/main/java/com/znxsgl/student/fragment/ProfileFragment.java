package com.znxsgl.student.fragment;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.znxsgl.student.CourseDetailActivity;
import com.znxsgl.student.ExamHomeworkActivity;
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
    private final Map<String, Integer> unreadMap = new HashMap<>();

    // 学习时长实时刷新
    private final Runnable refreshFocusTotal = new Runnable() {
        @Override
        public void run() {
            loadFocusTotal();
            mainHandler.postDelayed(this, 10000);
        }
    };

    private static final String[] ICONS = {"📖","💻","📱","🌐","🎨","🔧","✍️","🗣️","🎬","📋","👥","📜","🏛️","🛡️"};

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
        if (isAdded() && prefs != null) {
            loadCourses();
            loadUnreadCounts();
            loadFocusTotal();
            loadQuizStats();
            loadBookmarkCount(getView());
        }
        // 实时轮询学习时长
        mainHandler.removeCallbacks(refreshFocusTotal);
        mainHandler.post(refreshFocusTotal);
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
            loadFocusTotal();
            loadQuizStats();
            loadCourses();
            loadUnreadCounts();
            if (getView() != null) loadBookmarkCount(getView());
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
        String[] items = {"课程图标生成", "退出登录"};
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("设置")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showIconGenerator();
                    } else if (which == 1) {
                        doLogout();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 课程图标生成入口 */
    private void showIconGenerator() {
        if (!isAdded()) return;
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("课程图标生成")
                .setMessage("为课程生成专属 AI 封面图，让课程卡片更直观。")
                .setPositiveButton("选择课程", (d, w) -> {
                    // TODO: 接入 AI 图标生成能力
                    Toast.makeText(getContext(), "课程图标生成功能开发中...", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 退出登录 */
    private void doLogout() {
        // 保留记住密码的账号信息，退出后进入一键登录页
        String savedUsername = prefs.getString("saved_username", "");
        String savedPassword = prefs.getString("saved_password", "");
        boolean remember = prefs.getBoolean("remember", false);

        prefs.edit().clear().apply();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("remember", remember)
                .putString("saved_username", savedUsername)
                .putString("saved_password", savedPassword);
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
                            Toast.makeText(getContext(), "暂无收藏", Toast.LENGTH_SHORT).show();
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
                                ((TextView) h.itemView.findViewById(R.id.tv_question)).setText(question);
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
                            Toast.makeText(getContext(), "加载失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openContactsDialog(r.body());
                    }
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                        pd.dismiss();
                        if (!isAdded()) return;
                        Toast.makeText(getContext(), "加载失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void openContactsDialog(Map<String, Object> data) {
        View root = LayoutInflater.from(getContext()).inflate(R.layout.dialog_contacts, null);
        LinearLayout llClassmates = root.findViewById(R.id.ll_classmates);
        LinearLayout llTeachers = root.findViewById(R.id.ll_teachers);
        TextView tvNoClassmates = root.findViewById(R.id.tv_no_classmates);
        TextView tvNoTeachers = root.findViewById(R.id.tv_no_teachers);

        List<Map<String, Object>> classmates = (List<Map<String, Object>>) data.get("classmates");
        List<Map<String, Object>> teachers = (List<Map<String, Object>>) data.get("teachers");

        // 教师置顶显示
        if (teachers == null || teachers.isEmpty()) {
            tvNoTeachers.setVisibility(View.VISIBLE);
        } else {
            for (Map<String, Object> item : teachers) {
                String name = item.get("realName") != null ? item.get("realName").toString() : "";
                String title = item.get("title") != null ? item.get("title").toString() : "";
                String courses = item.get("courseNames") != null ? item.get("courseNames").toString() : "";
                String sub = (title.isEmpty() ? "" : title + " · ") + courses;
                addContactItem(llTeachers, name, sub);
            }
        }

        if (classmates == null || classmates.isEmpty()) {
            tvNoClassmates.setVisibility(View.VISIBLE);
        } else {
            for (Map<String, Object> item : classmates) {
                String name = item.get("realName") != null ? item.get("realName").toString() : "";
                String no = item.get("studentNo") != null ? item.get("studentNo").toString() : "";
                if (no.isEmpty()) no = "暂无";
                addContactItem(llClassmates, name, "学号: " + no);
            }
        }

        android.app.Dialog dlg = new android.app.Dialog(getContext(),
                android.R.style.Theme_Translucent_NoTitleBar);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        root.findViewById(R.id.btn_close).setOnClickListener(v -> dlg.dismiss());
        dlg.setOnShowListener(d -> {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.getDecorView().setPadding(40, 0, 40, 0);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(w.getAttributes());
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                // 限制弹窗高度为屏幕 75%，避免条目过多时底部被截断
                lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.75);
                lp.gravity = Gravity.CENTER;
                lp.dimAmount = 0.5f;
                w.setAttributes(lp);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
        dlg.show();
    }

    private void addContactItem(ViewGroup parent, String name, String sub) {
        View item = LayoutInflater.from(getContext()).inflate(R.layout.item_contact, parent, false);
        TextView tvAvatar = item.findViewById(R.id.tv_avatar);
        TextView tvName = item.findViewById(R.id.tv_name);
        TextView tvSub = item.findViewById(R.id.tv_sub);

        tvAvatar.setText(name.isEmpty() ? "?" : String.valueOf(name.charAt(0)));
        tvName.setText(name);
        tvSub.setText(sub);
        parent.addView(item);
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
                    adapter.notifyDataSetChanged();
                    rvCourses.requestLayout();
                });
            }
            @Override
            public void onFailure(Call<List<StudentCourse>> call, Throwable t) {
                mainHandler.post(() ->
                    Toast.makeText(getContext(), "加载失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ========== Adapter ==========
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
                    holder.semesterTag.setTextColor(0xFFFA8C16);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_orange);
                } else if (sem.contains("寒假")) {
                    holder.semesterTag.setVisibility(View.VISIBLE);
                    holder.semesterTag.setText("寒假班");
                    holder.semesterTag.setTextColor(0xFF1890FF);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_blue);
                } else if (sem.contains("培训")) {
                    holder.semesterTag.setVisibility(View.VISIBLE);
                    holder.semesterTag.setText("培训班");
                    holder.semesterTag.setTextColor(0xFF722ED1);
                    holder.semesterTag.setBackgroundResource(R.drawable.bg_tag_purple);
                } else {
                    holder.semesterTag.setVisibility(View.GONE);
                }
            } else {
                holder.semesterTag.setVisibility(View.GONE);
            }

            // 未读红点（从unreadMap实时获取）
            int unread = unreadMap.getOrDefault(c.getCourseName(), 0);
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
                    textColor = 0xFF722ED1;
                    bgColor = 0xFFF9F0FF;
                } else if ("exam".equals(type)) {
                    textColor = 0xFFFA8C16;
                    bgColor = 0xFFFFF7E6;
                } else {
                    textColor = 0xFF1890FF;
                    bgColor = 0xFFE6F7FF;
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

    /** 加载未读消息数 */
    private void loadUnreadCounts() {
        String token = "Bearer " + prefs.getString("token", "");
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getUnreadChatCount(token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override public void onResponse(Call<List<Map<String, Object>>> call,
                                              Response<List<Map<String, Object>>> resp) {
                if (!isAdded() || resp.body() == null) return;
                unreadMap.clear();
                for (Map<String, Object> row : resp.body()) {
                    String cn = (String) row.get("courseName");
                    Object cnt = row.get("count");
                    unreadMap.put(cn, cnt instanceof Number ? ((Number) cnt).intValue() : 0);
                }
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {}
        });
    }

    @Override
    public void onChatUpdate(String courseName, String senderName, String content) {
        // 收到实时推送后重新从服务端拉取未读数和课程列表，避免本地状态与后端不一致
        loadUnreadCounts();
        loadCourses();
        // 可选：显示Toast提示
        mainHandler.post(() ->
            Toast.makeText(getContext(), courseName + " 新消息: " + content, Toast.LENGTH_SHORT).show());
    }
}
