package com.znxsgl.student.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.znxsgl.student.R;
import com.znxsgl.student.model.ScheduleItem;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ScheduleFragment extends Fragment {

    private TextView tvWeekLabel, tvDateInfo, btnToday, tvSemesterLabel;
    private LinearLayout containerWeeks, gridBody;
    private LinearLayout rowHeader;
    private HorizontalScrollView scrollGrid;

    private int currentWeek = 1;
    private int maxWeekCount = 18;
    private int todayDayOfWeek = 1; // 1=周一 ... 7=周日
    private boolean isAnimating = false;
    private List<ScheduleItem> apiScheduleData = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 学期相关
    private List<Map<String, Object>> semesterList = new ArrayList<>();
    private String currentSemester = null;
    private String semesterStartDate = null; // yyyy-MM-dd
    private boolean semestersLoaded = false;

    // 动态作息（来自 /api/bell/today，服务端按 年级+周次单双周 自动解析；失败回退内置默认）
    private int[] slotNodes = {1, 2, 3, 4, 5, 6, 7, 8};
    private String[][] slots = null; // {label, start, end}，null 时用 SLOTS

    // ========== 常量 ==========
    // 默认作息兜底（后端无任何作息配置时才使用）
    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final String[][] SLOTS = {
        {"第1节", "08:10", "08:50"},
        {"第2节", "09:00", "09:40"},
        {"第3节", "09:50", "10:30"},
        {"第4节", "10:40", "11:20"},
        {"第5节", "15:10", "15:50"},
        {"第6节", "16:00", "16:40"},
        {"第7节", "19:50", "20:10"},
        {"第8节", "20:20", "21:00"},
    };

    /** 当前生效的节次轴（动态优先，兜底内置） */
    private String[][] effectiveSlots() {
        return slots != null ? slots : SLOTS;
    }

    /** 当前节次轴对应的小节号 */
    private int nodeAt(int slotIndex) {
        return slotIndex < slotNodes.length ? slotNodes[slotIndex] : slotIndex + 1;
    }

    private static final int[] COURSE_COLORS = {
        0xFFE8F0FE, // 浅蓝
        0xFFE8F8ED, // 浅绿
        0xFFFFF3E0, // 浅橙
        0xFFF3E8FF, // 浅紫
        0xFFFCE4EC, // 浅粉
        0xFFE0F7FA, // 浅青
        0xFFF9F0E0, // 浅黄
        0xFFEDE7F6, // 淡紫
        0xFFE8EAF6, // 靛蓝
        0xFFFBE9E7, // 淡红
        0xFFE0F2F1, // 浅墨绿
        0xFFFFF8E1, // 奶油黄
    };

    private static final int[] SLOT_COLORS = {
        0xFFE8F0FE, 0xFFE8F8ED, 0xFFFFF3E0,
        0xFFF3E8FF, 0xFFFCE4EC, 0xFFE0F7FA,
        0xFFF5F5F7,
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_schedule, container, false);
        tvWeekLabel = view.findViewById(R.id.tv_week_label);
        tvDateInfo = view.findViewById(R.id.tv_date_info);
        btnToday = view.findViewById(R.id.btn_today);
        tvSemesterLabel = view.findViewById(R.id.tv_semester_label);
        containerWeeks = view.findViewById(R.id.container_weeks);
        gridBody = view.findViewById(R.id.grid_body);
        rowHeader = view.findViewById(R.id.row_header);
        scrollGrid = view.findViewById(R.id.scroll_grid);

        // 计算今天的星期
        Calendar cal = Calendar.getInstance();
        todayDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7; // 周日=0, 周一=1...
        if (todayDayOfWeek == 0) todayDayOfWeek = 7; // 周日=7

        // 学期加载前清除默认日期文案，避免显示 XML 中的占位符
        if (tvDateInfo != null) tvDateInfo.setText("");

        tvWeekLabel.setOnClickListener(v -> showWeekPicker());
        if (tvSemesterLabel != null) {
            tvSemesterLabel.setOnClickListener(v -> showSemesterPicker());
        }
        btnToday.setOnClickListener(v -> {
            currentWeek = getCurrentWeek();
            fetchSchedule(currentWeek);
            buildWeekSelector();
            buildHeader();
        });

        // 只在学期加载成功后构建表头，避免初始使用「今天」日期
        loadSemesters();

        // 左右滑动切换周次（在 ScrollView 上检测，优先级高于滚动）
        scrollGrid.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean isSwiping = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        isSwiping = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);
                        // 确认是横向滑动后，禁止 ScrollView 拦截
                        if (!isSwiping && dx > dy && dx > 20) {
                            isSwiping = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (isSwiping) return true;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDx = event.getX() - startX;
                        if (isSwiping && Math.abs(totalDx) > 60) {
                            if (totalDx < 0 && currentWeek < maxWeekCount) animateWeekChange(1);
                            else if (totalDx > 0 && currentWeek > 1) animateWeekChange(-1);
                        }
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        isSwiping = false;
                        break;
                }
                return isSwiping;
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    // ========== 滑动切换动画 ==========
    private void animateWeekChange(int direction) {
        if (isAnimating || scrollGrid == null || scrollGrid.getWidth() <= 0) {
            // 降级：无动画
            currentWeek += direction;
            refreshAll();
            return;
        }
        isAnimating = true;
        View content = scrollGrid.getChildAt(0);
        int w = scrollGrid.getWidth();

        content.animate()
            .translationX(-direction * w * 0.5f)
            .alpha(0.3f)
            .setDuration(120)
            .setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    currentWeek += direction;
                    buildHeader();
                    buildWeekSelector();
                    // 预置到对面
                    content.setTranslationX(direction * w * 0.5f);
                    fetchScheduleAnimated(currentWeek, content, direction, w);
                }
            }).start();
    }

    // ========== API 请求 ==========
    private void loadSemesters() {
        SharedPreferences prefs = requireActivity().getSharedPreferences("znxsgl", 0);
        String token = prefs.getString("token", "");

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentSemesters("Bearer " + token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override
            public void onResponse(Call<List<Map<String, Object>>> call, Response<List<Map<String, Object>>> resp) {
                if (resp.isSuccessful() && resp.body() != null && !resp.body().isEmpty()) {
                    semesterList = resp.body();
                    semestersLoaded = true;
                    // 优先选择当前学期（isCurrent），其次选择进行中的学期，都没有则选第一个
                    String selectedName = null;
                    String selectedStart = null;
                    int selectedWeeks = 18;
                    for (Map<String, Object> s : semesterList) {
                        Object isCurrent = s.get("isCurrent");
                        boolean currentFlag = false;
                        if (isCurrent instanceof Boolean) {
                            currentFlag = (Boolean) isCurrent;
                        } else if (isCurrent instanceof Number) {
                            currentFlag = ((Number) isCurrent).intValue() == 1;
                        }
                        if (currentFlag) {
                            selectedName = s.get("name").toString();
                            selectedStart = s.get("startDate") != null ? s.get("startDate").toString() : null;
                            if (s.get("weekCount") != null) {
                                selectedWeeks = ((Number) s.get("weekCount")).intValue();
                            }
                            break;
                        }
                    }
                    if (selectedName == null) {
                        for (Map<String, Object> s : semesterList) {
                            String status = s.get("status") != null ? s.get("status").toString() : "";
                            if ("ongoing".equals(status)) {
                                selectedName = s.get("name").toString();
                                selectedStart = s.get("startDate") != null ? s.get("startDate").toString() : null;
                                if (s.get("weekCount") != null) {
                                    selectedWeeks = ((Number) s.get("weekCount")).intValue();
                                }
                                break;
                            }
                        }
                    }
                    if (selectedName == null && !semesterList.isEmpty()) {
                        Map<String, Object> first = semesterList.get(0);
                        selectedName = first.get("name").toString();
                        selectedStart = first.get("startDate") != null ? first.get("startDate").toString() : null;
                        if (first.get("weekCount") != null) {
                            selectedWeeks = ((Number) first.get("weekCount")).intValue();
                        }
                    }
                    currentSemester = selectedName;
                    semesterStartDate = selectedStart;
                    maxWeekCount = selectedWeeks;
                    mainHandler.post(() -> {
                        updateSemesterLabel();
                        currentWeek = getCurrentWeek();
                        buildWeekSelector();
                        buildHeader();
                        fetchSchedule(currentWeek);
                    });
                } else {
                    // 失败兜底：用旧逻辑
                    semestersLoaded = false;
                    currentSemester = null;
                    maxWeekCount = 18;
                    mainHandler.post(() -> {
                        currentWeek = getCurrentWeekFallback();
                        buildWeekSelector();
                        buildHeader();
                        fetchSchedule(currentWeek);
                    });
                }
            }
            @Override
            public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {
                semestersLoaded = false;
                currentSemester = null;
                maxWeekCount = 18;
                mainHandler.post(() -> {
                    currentWeek = getCurrentWeekFallback();
                    buildWeekSelector();
                    buildHeader();
                    fetchSchedule(currentWeek);
                    Toast.makeText(RetrofitClient.safeContext(getContext()), "学期加载失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updateSemesterLabel() {
        if (tvSemesterLabel == null) return;
        if (currentSemester != null) {
            tvSemesterLabel.setText(currentSemester);
            tvSemesterLabel.setVisibility(View.VISIBLE);
        } else {
            tvSemesterLabel.setVisibility(View.GONE);
        }
    }

    private void fetchSchedule(int week) {
        fetchScheduleAnimated(week, null, 0, 0);
    }

    private void fetchScheduleAnimated(int week, View content, int direction, int width) {
        SharedPreferences prefs = requireActivity().getSharedPreferences("znxsgl", 0);
        String token = prefs.getString("token", "");

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        // 先取作息（按当前周单双周解析），再取课表，保证节次轴与课程一致
        api.getBellToday("Bearer " + token, week).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                parseBellPeriods(resp.body());
                fetchScheduleInner(week, content, direction, width, token, api);
            }
            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                fetchScheduleInner(week, content, direction, width, token, api);
            }
        });
    }

    /** 解析作息接口的 periods，动态生成节次轴 */
    private void parseBellPeriods(Map<String, Object> body) {
        try {
            Object p = body == null ? null : body.get("periods");
            if (!(p instanceof List) || ((List<?>) p).isEmpty()) return;
            List<?> list = (List<?>) p;
            int[] nodes = new int[list.size()];
            String[][] arr = new String[list.size()][3];
            int idx = 0;
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                int node = m.get("node") instanceof Number ? ((Number) m.get("node")).intValue() : idx + 1;
                Object st = m.get("startTime"), et = m.get("endTime");
                if (st == null || et == null) continue;
                nodes[idx] = node;
                arr[idx] = new String[]{"第" + node + "节", st.toString(), et.toString()};
                idx++;
            }
            if (idx == 0) return;
            slotNodes = Arrays.copyOfRange(nodes, 0, idx);
            slots = Arrays.copyOfRange(arr, 0, idx);
        } catch (Exception ignored) {
        }
    }

    private void fetchScheduleInner(int week, View content, int direction, int width,
                                    String token, ApiService api) {
        api.getStudentSchedule("Bearer " + token, week, currentSemester).enqueue(new Callback<List<ScheduleItem>>() {
            @Override
            public void onResponse(Call<List<ScheduleItem>> call, Response<List<ScheduleItem>> resp) {
                if (resp.isSuccessful() && resp.body() != null) {
                    apiScheduleData = resp.body();
                } else {
                    apiScheduleData = new ArrayList<>();
                }
                mainHandler.post(() -> {
                    buildScheduleGrid();
                    if (content != null) {
                        content.animate()
                            .translationX(0).alpha(1f)
                            .setDuration(150)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override public void onAnimationEnd(Animator a) {
                                    isAnimating = false;
                                }
                            }).start();
                    }
                });
            }
            @Override
            public void onFailure(Call<List<ScheduleItem>> call, Throwable t) {
                apiScheduleData = new ArrayList<>();
                mainHandler.post(() -> {
                    Toast.makeText(RetrofitClient.safeContext(getContext()), "无法连接服务器", Toast.LENGTH_SHORT).show();
                    buildScheduleGrid();
                    if (content != null) {
                        content.animate().translationX(0).alpha(1f).setDuration(150)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override public void onAnimationEnd(Animator a) { isAnimating = false; }
                            }).start();
                    }
                });
            }
        });
    }

    // ========== 计算当前周 ==========
    private int getCurrentWeek() {
        if (semesterStartDate == null || semesterStartDate.isEmpty()) {
            return getCurrentWeekFallback();
        }
        try {
            // 以「开学日所在自然周的周一」为第 1 周起点，按真实星期换算当前教学周
            Calendar week1Mon = getWeekMonday(1);
            Calendar today = Calendar.getInstance();
            long diffMs = today.getTimeInMillis() - week1Mon.getTimeInMillis();
            int diffDays = (int) (diffMs / (1000 * 60 * 60 * 24));
            int week = (diffDays / 7) + 1;
            return Math.max(1, Math.min(maxWeekCount, week));
        } catch (Exception e) {
            return getCurrentWeekFallback();
        }
    }

    // 兜底计算（旧逻辑）
    private int getCurrentWeekFallback() {
        Calendar cal = Calendar.getInstance();
        Calendar start = Calendar.getInstance();
        start.set(2026, 2, 9); // 3月9日
        long diff = cal.getTimeInMillis() - start.getTimeInMillis();
        int days = (int) (diff / (1000 * 60 * 60 * 24));
        return Math.max(1, Math.min(maxWeekCount, (days / 7) + 1));
    }

    // ========== 表头 ==========
    private void buildHeader() {
        rowHeader.removeAllViews();
        int todayIdx = todayDayOfWeek - 1; // 0索引
        // 节次列
        TextView timeHeader = createHeaderCell("节次", 0xFF86868B, dp(36));
        timeHeader.setBackgroundColor(0xFFF5F5F7);
        rowHeader.addView(timeHeader);
        
        // 周一到周日，带日期和高亮
        // 日期锚定开学日所在自然周的周一 + 当前周次，对齐真实星期几，与上传/查看日期无关
        updateDateInfo();
        Calendar cal = getWeekMonday(currentWeek);
        SimpleDateFormat sdf = new SimpleDateFormat("M/d", Locale.CHINA);
        
        for (int i = 0; i < 7; i++) {
            String dateStr = sdf.format(cal.getTime());
            String label = DAY_NAMES[i] + "\n" + dateStr;
            boolean isToday = isTodayInWeek(currentWeek) && (i == todayIdx);
            
            LinearLayout cell = new LinearLayout(getContext());
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1));
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(isToday ? 0xFFE8F0FE : 0x00000000);
            cell.setPadding(0, dp(2), 0, dp(2));
            
            TextView tvDay = new TextView(getContext());
            tvDay.setText(DAY_NAMES[i]);
            tvDay.setTextSize(11);
            tvDay.setTextColor(isToday ? 0xFF5E6AD2 : 0xFF1D1D1F);
            tvDay.setGravity(Gravity.CENTER);
            tvDay.setTypeface(null, Typeface.BOLD);
            cell.addView(tvDay);
            
            TextView tvDate = new TextView(getContext());
            tvDate.setText(dateStr);
            tvDate.setTextSize(8);
            tvDate.setTextColor(isToday ? 0xFF5E6AD2 : 0xFF86868B);
            tvDate.setGravity(Gravity.CENTER);
            cell.addView(tvDate);
            
            rowHeader.addView(cell);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    /**
     * 计算第 week 周周一（表头第一列）的日期，对齐真实星期几。
     * 第 1 周周一 = 开学日所在自然周（周一开始）的周一；
     * 第 N 周周一 = 第 1 周周一 + (N-1)*7 天。
     * 这样即使暑假/寒假上传或查看课表，日期也始终显示为开学后的真实日历日期。
     * 学期日期缺失或解析失败时，回退为"今天所在周的周一"。
     */
    private Calendar getWeekMonday(int week) {
        Calendar cal = Calendar.getInstance();
        if (semesterStartDate == null || semesterStartDate.isEmpty()) {
            backToMonday(cal);
            cal.add(Calendar.DAY_OF_MONTH, (week - 1) * 7);
            return cal;
        }
        try {
            String[] parts = semesterStartDate.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]) - 1; // Calendar月份从0开始
            int day = Integer.parseInt(parts[2]);
            cal.set(year, month, day, 0, 0, 0);
            backToMonday(cal);
            cal.add(Calendar.DAY_OF_MONTH, (week - 1) * 7);
            return cal;
        } catch (Exception e) {
            backToMonday(cal);
            cal.add(Calendar.DAY_OF_MONTH, (week - 1) * 7);
            return cal;
        }
    }

    /** 将 cal 回退到所在自然周的周一（周日按前一周处理） */
    private void backToMonday(Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        cal.add(Calendar.DAY_OF_MONTH, -(dow == Calendar.SUNDAY ? 6 : dow - 2));
    }

    /**
     * 判断今天是否落在第 week 周的日期区间（周一 ~ 周六）内。
     * 暑假/寒假（今天不在任何课表周内）时返回 false，避免高亮日期不等于今天的列。
     */
    private boolean isTodayInWeek(int week) {
        Calendar weekMon = getWeekMonday(week);
        Calendar today = Calendar.getInstance();
        long todayMs = today.getTimeInMillis();
        long startMs = weekMon.getTimeInMillis();
        long endMs = startMs + 7L * 24 * 60 * 60 * 1000; // 周一 ~ 周日
        return todayMs >= startMs && todayMs <= endMs;
    }

    /**
     * 更新顶部右上角日期信息：显示当前查看周的学期日期区间（如 9/1 - 9/6），
     * 与上传/查看日期无关，暑假/寒假也始终显示开学后的真实日期。
     */
    private void updateDateInfo() {
        if (tvDateInfo == null) return;
        SimpleDateFormat sdf = new SimpleDateFormat("M/d", Locale.CHINA);
        Calendar start = getWeekMonday(currentWeek);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 6); // 周日
        tvDateInfo.setText(sdf.format(start.getTime()) + " - " + sdf.format(end.getTime()));
    }

    private TextView createHeaderCell(String text, int color, int fixedWidth) {
        TextView tv = new TextView(getContext());
        if (fixedWidth > 0) {
            tv.setLayoutParams(new LinearLayout.LayoutParams(fixedWidth, dp(36)));
        } else {
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        tv.setText(text); tv.setTextSize(11);
        tv.setTextColor(color); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    // ========== 周选择器 ==========
    private void buildWeekSelector() {
        containerWeeks.removeAllViews();
        tvWeekLabel.setText("第" + currentWeek + "周");
        for (int w = 1; w <= maxWeekCount; w++) {
            final int week = w;
            TextView tv = new TextView(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(30));
            lp.setMargins(dp(2), 0, dp(2), 0);
            tv.setLayoutParams(lp);
            tv.setText(String.valueOf(w)); tv.setTextSize(13); tv.setGravity(Gravity.CENTER);
            if (w == currentWeek) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF0A84FF); bg.setCornerRadius(dp(15));
                tv.setBackground(bg); tv.setTextColor(Color.WHITE);
            } else {
                tv.setBackground(null); tv.setTextColor(0xFF86868B);
            }
            tv.setOnClickListener(v -> { currentWeek = week; refreshAll(); });
            containerWeeks.addView(tv);
        }
    }

    // ========== 课表网格 ==========
    private void buildScheduleGrid() {
        gridBody.removeAllViews();
        String[][] arr = effectiveSlots();

        String prevEnd = null;
        for (int s = 0; s < arr.length; s++) {
            // 相邻节次间隔 ≥ 40 分钟时自动插入休息分隔条（适配任意学校的作息）
            if (prevEnd != null) {
                String breakText = breakLabel(prevEnd, arr[s][1]);
                if (breakText != null) {
                    gridBody.addView(buildBreakRow(breakText));
                    gridBody.addView(createHairline());
                }
            }
            gridBody.addView(buildSlotRow(s));
            gridBody.addView(createHairline());
            prevEnd = arr[s][2];
        }
    }

    /** 根据两节之间的间隔推断休息文案（替代写死的午休/晚休时间） */
    private String breakLabel(String prevEnd, String nextStart) {
        int gap = minuteOf(nextStart) - minuteOf(prevEnd);
        if (gap < 40) return null;
        int nextHour = minuteOf(nextStart) / 60;
        if (gap >= 120 && nextHour < 15) return " 午餐·午休 " + prevEnd + " — " + nextStart + " ";
        if (gap >= 120) return " 晚餐·晚休 " + prevEnd + " — " + nextStart + " ";
        return " 大课间 " + prevEnd + " — " + nextStart + " ";
    }

    private int minuteOf(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private LinearLayout buildSlotRow(int slotIndex) {
        String[][] arr = effectiveSlots();
        String start = arr[slotIndex][1];
        String end = arr[slotIndex][2];

        LinearLayout row = new LinearLayout(getContext());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        rp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rp); row.setOrientation(LinearLayout.HORIZONTAL);

        // 时间列
        LinearLayout timeCol = new LinearLayout(getContext());
        timeCol.setLayoutParams(new LinearLayout.LayoutParams(dp(36),
                LinearLayout.LayoutParams.MATCH_PARENT));
        timeCol.setOrientation(LinearLayout.VERTICAL);
        timeCol.setGravity(Gravity.CENTER);
        timeCol.setPadding(dp(2), dp(2), dp(2), dp(2));
        GradientDrawable timeBg = new GradientDrawable();
        timeBg.setColor(0xFFF5F5F7); timeBg.setCornerRadius(dp(6));
        timeCol.setBackground(timeBg);

        TextView tvStart = new TextView(getContext());
        tvStart.setText(start); tvStart.setTextSize(9);
        tvStart.setTextColor(0xFF1D1D1F); tvStart.setGravity(Gravity.CENTER);
        timeCol.addView(tvStart);

        TextView tvEnd = new TextView(getContext());
        tvEnd.setText(end); tvEnd.setTextSize(8);
        tvEnd.setTextColor(0xFF86868B); tvEnd.setGravity(Gravity.CENTER);
        timeCol.addView(tvEnd);
        row.addView(timeCol);

        // 7天课程格（周一至周日）
        for (int d = 1; d <= 7; d++) {
            row.addView(buildCourseCell(d, start, end, slotIndex));
        }
        return row;
    }

    private LinearLayout buildCourseCell(int dayOfWeek, String slotStart, String slotEnd, int slotIndex) {
        LinearLayout cell = new LinearLayout(getContext());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        cp.setMargins(dp(1), 0, dp(1), 0);
        cell.setLayoutParams(cp);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), dp(2), dp(2), dp(2));

        // 只在当前周高亮今天列（今天真实日期落在当前周才高亮）
        boolean isToday = isTodayInWeek(currentWeek) && (dayOfWeek == todayDayOfWeek);
        if (isToday) {
            GradientDrawable todayBg = new GradientDrawable();
            todayBg.setColor(0xFFF0F4FF);
            todayBg.setCornerRadius(dp(4));
            cell.setBackground(todayBg);
        }

        // 查找匹配的课程：优先按节次定位（适配任意作息），节次缺失时按时间交集兜底
        int node = nodeAt(slotIndex);
        String courseName = null;
        String classroom = null;
        for (ScheduleItem item : apiScheduleData) {
            if (item.getDayOfWeek() != dayOfWeek) continue;
            boolean hit = item.matchesNode(node)
                    || (item.getStartNode() <= 0 && item.matchesTimeSlot(slotStart, slotEnd));
            if (hit) {
                courseName = item.getCourseName();
                classroom = item.getClassroom();
                break;
            }
        }

        if (courseName != null) {
            int baseColor = COURSE_COLORS[Math.abs(courseName.hashCode()) % COURSE_COLORS.length];
            
            if (isToday) {
                // 今天列：左边加蓝条 + 课程颜色卡片
                cell.setOrientation(LinearLayout.HORIZONTAL);
                View strip = new View(getContext());
                strip.setLayoutParams(new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT));
                strip.setBackgroundColor(0xFF5E6AD2);
                cell.addView(strip);
                // 内嵌卡片
                LinearLayout card = new LinearLayout(getContext());
                card.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(baseColor); cardBg.setCornerRadius(dp(6));
                card.setBackground(cardBg);
                
                TextView tvName = new TextView(getContext());
                tvName.setText(courseName); tvName.setTextSize(10);
                tvName.setTextColor(0xFF1D1D1F); tvName.setGravity(Gravity.CENTER);
                tvName.setMaxLines(2);
                card.addView(tvName);
                if (classroom != null && !classroom.isEmpty()) {
                    String shortRoom = classroom.length() > 10 ? classroom.substring(0, 9) + "…" : classroom;
                    TextView tvRoom = new TextView(getContext());
                    tvRoom.setText(shortRoom); tvRoom.setTextSize(8);
                    tvRoom.setTextColor(0xFF86868B); tvRoom.setGravity(Gravity.CENTER);
                    tvRoom.setMaxLines(1);
                    card.addView(tvRoom);
                }
                cell.addView(card);
            } else {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(baseColor); bg.setCornerRadius(dp(6));
                cell.setBackground(bg);
                TextView tvName = new TextView(getContext());
                tvName.setText(courseName); tvName.setTextSize(10);
                tvName.setTextColor(0xFF1D1D1F); tvName.setGravity(Gravity.CENTER);
                tvName.setMaxLines(2);
                cell.addView(tvName);
                if (classroom != null && !classroom.isEmpty()) {
                    String shortRoom = classroom.length() > 10 ? classroom.substring(0, 9) + "…" : classroom;
                    TextView tvRoom = new TextView(getContext());
                    tvRoom.setText(shortRoom); tvRoom.setTextSize(8);
                    tvRoom.setTextColor(0xFF86868B); tvRoom.setGravity(Gravity.CENTER);
                    tvRoom.setMaxLines(1);
                    cell.addView(tvRoom);
                }
            }
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFAFAFA); bg.setCornerRadius(dp(6));
            cell.setBackground(bg);
        }
        return cell;
    }

    private LinearLayout buildBreakRow(String text) {
        LinearLayout row = new LinearLayout(getContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(8), 0);

        View spacer = new View(getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(1)));
        row.addView(spacer);

        LinearLayout sep = new LinearLayout(getContext());
        sep.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sep.setGravity(Gravity.CENTER);
        sep.setOrientation(LinearLayout.HORIZONTAL);

        View lineL = new View(getContext());
        lineL.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(1)));
        lineL.setBackgroundColor(0xFFFF9500);
        sep.addView(lineL);

        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(10); tv.setTextColor(0xFFFF9500); tv.setGravity(Gravity.CENTER);
        sep.addView(tv);

        View lineR = new View(getContext());
        lineR.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1));
        lineR.setBackgroundColor(0xFFFF9500);
        sep.addView(lineR);

        row.addView(sep);
        return row;
    }

    private View createHairline() {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(0xFFF0F0F3);
        return v;
    }

    // ========== 周数选择弹窗 ==========
    private void showWeekPicker() {
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(6);
        grid.setPadding(dp(16), dp(16), dp(16), dp(16));

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("选择周数").setView(grid)
                .setPositiveButton("关闭", null).create();

        for (int w = 1; w <= maxWeekCount; w++) {
            final int week = w;
            TextView tv = new TextView(getContext());
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(44); params.height = dp(40);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            tv.setLayoutParams(params);
            tv.setText(String.valueOf(w)); tv.setTextSize(16); tv.setGravity(Gravity.CENTER);
            if (w == currentWeek) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF0A84FF); bg.setCornerRadius(dp(20));
                tv.setBackground(bg); tv.setTextColor(Color.WHITE);
            } else {
                tv.setTextColor(0xFF1D1D1F);
            }
            final AlertDialog d = dialog;
            tv.setOnClickListener(v -> { currentWeek = week; d.dismiss(); refreshAll(); });
            grid.addView(tv);
        }
        dialog.show();
    }

    // ========== 学期选择弹窗 ==========
    private void showSemesterPicker() {
        if (semesterList == null || semesterList.isEmpty()) {
            Toast.makeText(RetrofitClient.safeContext(getContext()), "暂无可选学期", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[semesterList.size()];
        int selectedIndex = 0;
        for (int i = 0; i < semesterList.size(); i++) {
            Map<String, Object> s = semesterList.get(i);
            String name = s.get("name") != null ? s.get("name").toString() : "未知";
            String status = s.get("status") != null ? s.get("status").toString() : "";
            String statusLabel = "";
            switch (status) {
                case "ongoing": statusLabel = "（进行中）"; break;
                case "before": statusLabel = "（未开始）"; break;
                case "ended": statusLabel = "（已结束）"; break;
            }
            names[i] = name + statusLabel;
            if (name.equals(currentSemester)) selectedIndex = i;
        }

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("选择学期")
                .setSingleChoiceItems(names, selectedIndex, (d, which) -> {
                    Map<String, Object> s = semesterList.get(which);
                    currentSemester = s.get("name") != null ? s.get("name").toString() : null;
                    semesterStartDate = s.get("startDate") != null ? s.get("startDate").toString() : null;
                    if (s.get("weekCount") != null) {
                        maxWeekCount = ((Number) s.get("weekCount")).intValue();
                    } else {
                        maxWeekCount = 18;
                    }
                    updateSemesterLabel();
                    currentWeek = getCurrentWeek();
                    buildWeekSelector();
                    buildHeader();
                    fetchSchedule(currentWeek);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
    }

    private void refreshAll() {
        Calendar cal = Calendar.getInstance();
        todayDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7;
        if (todayDayOfWeek == 0) todayDayOfWeek = 7;
        buildWeekSelector();
        buildHeader();      // 切换周次时同步更新表头日期与顶部日期
        fetchSchedule(currentWeek);
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }
}
