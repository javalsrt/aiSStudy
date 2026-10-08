package com.znxsgl.student.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    private TextView tvWeekLabel, tvWeatherInfo, tvOnlineInfo, btnToday;
    private LinearLayout containerWeeks, gridBody;
    private LinearLayout rowHeader;
    private HorizontalScrollView scrollGrid;
    private View skeletonLoading;
    private ObjectAnimator skeletonAnim;

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
        tvWeatherInfo = view.findViewById(R.id.tv_weather_info);
        tvOnlineInfo = view.findViewById(R.id.tv_online_info);
        btnToday = view.findViewById(R.id.btn_today);
        containerWeeks = view.findViewById(R.id.container_weeks);
        gridBody = view.findViewById(R.id.grid_body);
        rowHeader = view.findViewById(R.id.row_header);
        scrollGrid = view.findViewById(R.id.scroll_grid);
        skeletonLoading = view.findViewById(R.id.skeleton_loading);

        // 计算今天的星期
        Calendar cal = Calendar.getInstance();
        todayDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7; // 周日=0, 周一=1...
        if (todayDayOfWeek == 0) todayDayOfWeek = 7; // 周日=7

        // 学期加载前清除默认文案，避免显示 XML 中的占位符
        if (tvWeatherInfo != null) tvWeatherInfo.setText("");

        tvWeekLabel.setOnClickListener(v -> showWeekPicker());
        btnToday.setOnClickListener(v -> {
            currentWeek = getCurrentWeek();
            fetchSchedule(currentWeek);
            buildWeekSelector();
            buildHeader();
        });

        // 只在学期加载成功后构建表头，避免初始使用「今天」日期
        loadSemesters();

        // 加载当地天气（IP 定位 + Open-Meteo，30 分钟缓存）
        loadWeather();

        // 边缘感知滑动切换周次：
        // 平时横向滑动 = 正常滚动课表（周日也能看到）；
        // 只有当课表已经滚到对应尽头（最左/最右）时再滑，才切换上一周/下一周。
        scrollGrid.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean startAtRightEdge = false;
            private boolean startAtLeftEdge = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        // 记录按下时表格是否已在尽头（-1=向左滚到头，1=向右滚到头）
                        startAtRightEdge = !scrollGrid.canScrollHorizontally(1);
                        startAtLeftEdge = !scrollGrid.canScrollHorizontally(-1);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDx = event.getX() - startX;
                        float totalDy = event.getY() - startY;
                        // 横向明显大于纵向才算滑动意图，避免误触
                        if (Math.abs(totalDx) > 80 && Math.abs(totalDx) > Math.abs(totalDy) * 1.5f) {
                            if (totalDx < 0 && startAtRightEdge && currentWeek < maxWeekCount) {
                                animateWeekChange(1);   // 在最右端继续左滑 → 下一周
                            } else if (totalDx > 0 && startAtLeftEdge && currentWeek > 1) {
                                animateWeekChange(-1);  // 在最左端继续右滑 → 上一周
                            }
                        }
                        break;
                }
                // 始终不消费事件，把滚动交还给 HorizontalScrollView
                return false;
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到页面时刷新天气与好友在线状态
        loadWeather();
        loadOnlineInfo();
    }

    // ========== 滑动切换动画 ==========
    private void animateWeekChange(int direction) {
        if (isAnimating || scrollGrid == null || scrollGrid.getWidth() <= 0) {
            // 降级：无动画
            currentWeek += direction;
            refreshAll();
            scrollGrid.post(() -> scrollGrid.scrollTo(0, 0));
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
                    // 切周后回到最左（周一），避免停留在边缘导致反向滑动误判
                    scrollGrid.post(() -> scrollGrid.smoothScrollTo(0, 0));
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
                    // 选学期优先级：
                    // 1) 日期范围包含今天的学期（最可靠，不依赖后端 isCurrent 标记是否更新）
                    // 2) isCurrent=1 的学期
                    // 3) 第一个学期
                    String selectedName = null;
                    String selectedStart = null;
                    int selectedWeeks = 18;
                    Calendar todayCal = Calendar.getInstance();

                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

                    // 1) 日期包含今天
                    for (Map<String, Object> s : semesterList) {
                        Object startObj = s.get("startDate"), endObj = s.get("endDate");
                        if (!(startObj instanceof String) || !(endObj instanceof String)) continue;
                        java.util.Date sd = df.parse(startObj.toString(), new java.text.ParsePosition(0));
                        java.util.Date ed = df.parse(endObj.toString(), new java.text.ParsePosition(0));
                        if (sd == null || ed == null) continue;
                        if (!todayCal.getTime().before(sd) && !todayCal.getTime().after(ed)) {
                            selectedName = s.get("name").toString();
                            selectedStart = startObj.toString();
                            if (s.get("weekCount") instanceof Number) {
                                selectedWeeks = ((Number) s.get("weekCount")).intValue();
                            }
                            break;
                        }
                    }
                    // 2) isCurrent 标记
                    if (selectedName == null) {
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
                                if (s.get("weekCount") instanceof Number) {
                                    selectedWeeks = ((Number) s.get("weekCount")).intValue();
                                }
                                break;
                            }
                        }
                    }
                    // 3) 第一个
                    if (selectedName == null && !semesterList.isEmpty()) {
                        Map<String, Object> first = semesterList.get(0);
                        selectedName = first.get("name").toString();
                        selectedStart = first.get("startDate") != null ? first.get("startDate").toString() : null;
                        if (first.get("weekCount") instanceof Number) {
                            selectedWeeks = ((Number) first.get("weekCount")).intValue();
                        }
                    }
                    currentSemester = selectedName;
                    semesterStartDate = selectedStart;
                    maxWeekCount = selectedWeeks;
                    mainHandler.post(() -> {
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

    private void fetchSchedule(int week) {
        fetchScheduleAnimated(week, null, 0, 0);
    }

    private void fetchScheduleAnimated(int week, View content, int direction, int width) {
        // 进入加载态：非切周动画时展示骨架屏（切周有滑动过渡，不叠加骨架）
        showSkeleton(content == null);
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
                    showSkeleton(false);
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
                    showSkeleton(false);
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
        TextView timeHeader = createHeaderCell("节次", 0xFF8E8E93, dp(36));
        timeHeader.setBackgroundColor(0xFFF5F5F7);
        rowHeader.addView(timeHeader);
        
        // 周一到周日，带日期和高亮
        // 日期锚定开学日所在自然周的周一 + 当前周次，对齐真实星期几，与上传/查看日期无关
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
            tvDay.setTextColor(isToday ? 0xFF0A84FF : 0xFF1D1D1F);
            tvDay.setGravity(Gravity.CENTER);
            tvDay.setTypeface(fontMedium());
            cell.addView(tvDay);
            
            TextView tvDate = new TextView(getContext());
            tvDate.setText(dateStr);
            tvDate.setTextSize(10);
            tvDate.setTextColor(isToday ? 0xFF0A84FF : 0xFF8E8E93);
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

    private TextView createHeaderCell(String text, int color, int fixedWidth) {
        TextView tv = new TextView(getContext());
        if (fixedWidth > 0) {
            tv.setLayoutParams(new LinearLayout.LayoutParams(fixedWidth, dp(36)));
        } else {
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        tv.setText(text); tv.setTextSize(11);
        tv.setTextColor(color); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(fontMedium());
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
                tv.setBackground(null); tv.setTextColor(0xFF8E8E93);
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
        tvStart.setText(start); tvStart.setTextSize(10);
        tvStart.setTextColor(0xFF1D1D1F); tvStart.setGravity(Gravity.CENTER);
        timeCol.addView(tvStart);

        TextView tvEnd = new TextView(getContext());
        tvEnd.setText(end); tvEnd.setTextSize(10);
        tvEnd.setTextColor(0xFF8E8E93); tvEnd.setGravity(Gravity.CENTER);
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
            // 今天列：不另加蓝条/白卡，直接把课程色加深一档以示强调
            int bgColor = isToday ? darkenColor(baseColor, 0.85f) : baseColor;

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor); bg.setCornerRadius(dp(6));
            cell.setBackground(bg);

            TextView tvName = new TextView(getContext());
            tvName.setText(courseName); tvName.setTextSize(11);
            tvName.setTextColor(0xFF1D1D1F); tvName.setGravity(Gravity.CENTER);
            tvName.setMaxLines(2);
            cell.addView(tvName);
            if (classroom != null && !classroom.isEmpty()) {
                String shortRoom = classroom.length() > 10 ? classroom.substring(0, 9) + "…" : classroom;
                TextView tvRoom = new TextView(getContext());
                tvRoom.setText(shortRoom); tvRoom.setTextSize(10);
                tvRoom.setTextColor(0xFF8E8E93); tvRoom.setGravity(Gravity.CENTER);
                tvRoom.setMaxLines(1);
                cell.addView(tvRoom);
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
        tv.setTextSize(11); tv.setTextColor(0xFFFF9500); tv.setGravity(Gravity.CENTER);
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
        // iOS 风格底部弹出面板
        Dialog dialog = new Dialog(getContext());
        dialog.setContentView(R.layout.dialog_week_picker);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.45f);
        }

        TextView btnCancel = dialog.findViewById(R.id.btn_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        // 学期内容移入切换周目弹窗：显示当前学期，点击可切换
        TextView tvDialogSemester = dialog.findViewById(R.id.tv_dialog_semester);
        if (tvDialogSemester != null) {
            tvDialogSemester.setText(currentSemester != null ? currentSemester : "未加载学期");
            tvDialogSemester.setOnClickListener(v -> {
                dialog.dismiss();
                showSemesterPicker();
            });
        }

        GridLayout grid = dialog.findViewById(R.id.gl_weeks);
        for (int w = 1; w <= maxWeekCount; w++) {
            final int week = w;
            TextView tv = new TextView(getContext());
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(44); params.height = dp(40);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            tv.setLayoutParams(params);
            tv.setText(String.valueOf(w)); tv.setTextSize(15); tv.setGravity(Gravity.CENTER);
            if (w == currentWeek) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF0A84FF); bg.setCornerRadius(dp(8));
                tv.setBackground(bg); tv.setTextColor(Color.WHITE);
                tv.setTypeface(fontMedium());
            } else {
                tv.setTextColor(0xFF1D1D1F);
                tv.setBackgroundResource(R.drawable.bg_sheet_row);
            }
            tv.setOnClickListener(v -> { currentWeek = week; dialog.dismiss(); refreshAll(); });
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
        int selectedIndex = 0;
        for (int i = 0; i < semesterList.size(); i++) {
            String name = semesterList.get(i).get("name") != null
                    ? semesterList.get(i).get("name").toString() : "未知";
            if (name.equals(currentSemester)) selectedIndex = i;
        }

        // iOS 风格底部弹出面板
        Dialog dialog = new Dialog(getContext());
        dialog.setContentView(R.layout.dialog_semester_picker);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.45f);
        }

        TextView btnCancel = dialog.findViewById(R.id.btn_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        LinearLayout list = dialog.findViewById(R.id.ll_semester_list);
        for (int i = 0; i < semesterList.size(); i++) {
            final Map<String, Object> s = semesterList.get(i);
            String name = s.get("name") != null ? s.get("name").toString() : "未知";
            String status = s.get("status") != null ? s.get("status").toString() : "";
            String statusLabel = switch (status) {
                case "ongoing" -> "进行中";
                case "before" -> "未开始";
                case "ended" -> "已结束";
                default -> "";
            };
            final boolean selected = (i == selectedIndex);

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            row.setBackgroundResource(R.drawable.bg_sheet_row);
            row.setClickable(true);

            TextView tvName = new TextView(getContext());
            tvName.setText(name);
            tvName.setTextSize(16);
            tvName.setTextColor(selected ? 0xFF0A84FF : 0xFF1D1D1F);
            tvName.setTypeface(selected ? fontMedium() : fontRegular());
            row.addView(tvName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!statusLabel.isEmpty()) {
                TextView tvStatus = new TextView(getContext());
                tvStatus.setText(statusLabel);
                tvStatus.setTextSize(12);
                tvStatus.setTextColor(0xFF8E8E93);
                row.addView(tvStatus);
            }

            if (selected) {
                ImageView ivCheck = new ImageView(getContext());
                ivCheck.setImageResource(R.drawable.ic_check);
                ivCheck.setColorFilter(0xFF0A84FF);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(18), dp(18));
                clp.leftMargin = dp(8);
                row.addView(ivCheck, clp);
            }

            row.setOnClickListener(v -> {
                dialog.dismiss();
                currentSemester = s.get("name") != null ? s.get("name").toString() : null;
                semesterStartDate = s.get("startDate") != null ? s.get("startDate").toString() : null;
                if (s.get("weekCount") != null) {
                    maxWeekCount = ((Number) s.get("weekCount")).intValue();
                } else {
                    maxWeekCount = 18;
                }
                currentWeek = getCurrentWeek();
                buildWeekSelector();
                buildHeader();
                fetchSchedule(currentWeek);
            });
            list.addView(row);

            if (i < semesterList.size() - 1) {
                View sep = new View(getContext());
                sep.setBackgroundResource(R.color.hairline);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                slp.leftMargin = dp(20);
                list.addView(sep, slp);
            }
        }
        dialog.show();
    }

    private void refreshAll() {
        Calendar cal = Calendar.getInstance();
        todayDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 6) % 7;
        if (todayDayOfWeek == 0) todayDayOfWeek = 7;
        buildWeekSelector();
        buildHeader();      // 切换周次时同步更新表头日期
        fetchSchedule(currentWeek);
    }

    // ========== 当地天气（IP 定位 + Open-Meteo，30 分钟缓存） ==========
    private static String cachedWeatherText = null;
    private static long cachedWeatherAt = 0L;
    private static final long WEATHER_CACHE_MS = 30 * 60 * 1000L;

    private void loadWeather() {
        if (tvWeatherInfo == null) return;
        long now = System.currentTimeMillis();
        if (cachedWeatherText != null && now - cachedWeatherAt < WEATHER_CACHE_MS) {
            tvWeatherInfo.setText(dayGreeting() + " · " + cachedWeatherText);
            return;
        }
        tvWeatherInfo.setText(dayGreeting());
        executor.execute(() -> {
            String text = fetchWeatherText();
            if (text != null) {
                cachedWeatherText = text;
                cachedWeatherAt = System.currentTimeMillis();
            }
            String finalText = text != null ? dayGreeting() + " · " + text : dayGreeting();
            mainHandler.post(() -> {
                if (tvWeatherInfo != null && isAdded()) tvWeatherInfo.setText(finalText);
            });
        });
    }

    /** 按当前时段返回问候语：早上 / 中午 / 下午 / 半晚 / 夜晚 */
    private String dayGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 11) return "早上好 🌅";
        if (hour >= 11 && hour < 13) return "中午好 ☀️";
        if (hour >= 13 && hour < 18) return "下午好 🌤️";
        if (hour >= 18 && hour < 21) return "半晚好 🌆";
        return "夜晚好 🌙";
    }

    /** 将颜色按比例加深（用于今天列的课程强调） */
    private int darkenColor(int color, float factor) {
        int r = (int) (android.graphics.Color.red(color) * factor);
        int g = (int) (android.graphics.Color.green(color) * factor);
        int b = (int) (android.graphics.Color.blue(color) * factor);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** 请求 Open-Meteo 当前天气，返回如「南宁武鸣区 ☀️ 33°C」；失败返回 null */
    private String fetchWeatherText() {
        try {
            String[] loc = locateByIp();
            double lat = loc != null ? Double.parseDouble(loc[0]) : 39.9042;
            double lon = loc != null ? Double.parseDouble(loc[1]) : 116.4074;
            String fallbackCity = loc != null && loc.length > 2 ? loc[2] : "";
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code&timezone=auto");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try {
                JSONObject obj = new JSONObject(readAll(conn));
                JSONObject cur = obj.getJSONObject("current");
                double temp = cur.getDouble("temperature_2m");
                int code = cur.optInt("weather_code", -1);
                String place = fetchDistrictName(lat, lon, fallbackCity);
                String icon = weatherEmoji(code);
                StringBuilder sb = new StringBuilder();
                if (!place.isEmpty()) sb.append(place).append(" ");
                if (!icon.isEmpty()) sb.append(icon).append(" ");
                sb.append(Math.round(temp)).append("°C");
                return sb.toString();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过 IP 粗定位获取经纬度与城市名（无需定位权限），失败返回 null */
    private String[] locateByIp() {
        try {
            URL url = new URL("http://ip-api.com/json/?fields=lat,lon,city&lang=zh-CN");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try {
                JSONObject obj = new JSONObject(readAll(conn));
                if (obj.has("lat") && obj.has("lon")) {
                    return new String[]{
                            String.valueOf(obj.getDouble("lat")),
                            String.valueOf(obj.getDouble("lon")),
                            obj.optString("city", "")};
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 反查区县级地名（Nominatim 逆地理编码，失败降级为 IP 城市名） */
    private String fetchDistrictName(double lat, double lon, String fallbackCity) {
        try {
            URL url = new URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2"
                    + "&lat=" + lat + "&lon=" + lon + "&zoom=10&accept-language=zh-CN");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "aiStudyApp/1.0");
            try {
                JSONObject obj = new JSONObject(readAll(conn));
                JSONObject addr = obj.optJSONObject("address");
                if (addr != null) {
                    String city = addr.optString("city", addr.optString("province", ""));
                    String district = addr.optString("county",
                            addr.optString("suburb", addr.optString("city_district", "")));
                    String cityName = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
                    if (!district.isEmpty()) return cityName + district; // 如「南宁武鸣区」
                    if (!city.isEmpty()) return city;
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {
        }
        return fallbackCity == null ? "" : fallbackCity;
    }

    private String readAll(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** WMO 天气码 → 简短中文描述 */
    private String weatherDesc(int code) {
        if (code == 0) return "晴";
        if (code >= 1 && code <= 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "雨";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "雪";
        if (code >= 95) return "雷雨";
        return "";
    }

    /** WMO 天气码 → 彩色 emoji 图标 */
    private String weatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code == 1 || code == 2) return "🌤️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "";
    }

    // ========== 好友在线（同班同学 WebSocket 实时在线） ==========
    private void loadOnlineInfo() {
        if (tvOnlineInfo == null) return;
        try {
            SharedPreferences prefs = requireActivity().getSharedPreferences("znxsgl", 0);
            String token = prefs.getString("token", "");
            ApiService api = RetrofitClient.getInstance().create(ApiService.class);
            api.getOnlineClassmates("Bearer " + token).enqueue(new Callback<Map<String, Object>>() {
                @Override
                public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                    int count = 0;
                    if (resp.isSuccessful() && resp.body() != null
                            && resp.body().get("count") instanceof Number) {
                        count = ((Number) resp.body().get("count")).intValue();
                    }
                    final int c = count;
                    mainHandler.post(() -> {
                        if (tvOnlineInfo == null || !isAdded()) return;
                        if (c > 0) {
                            tvOnlineInfo.setText("● " + c + "人在线");
                            tvOnlineInfo.setVisibility(View.VISIBLE);
                        } else {
                            tvOnlineInfo.setVisibility(View.GONE);
                        }
                    });
                }
                @Override
                public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                    mainHandler.post(() -> {
                        if (tvOnlineInfo != null) tvOnlineInfo.setVisibility(View.GONE);
                    });
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ========== 骨架屏加载动画（模仿 animate-pulse 呼吸效果） ==========
    private void showSkeleton(boolean show) {
        if (skeletonLoading == null) return;
        if (show) {
            skeletonLoading.setVisibility(View.VISIBLE);
            skeletonLoading.setAlpha(1f);
            if (skeletonAnim == null) {
                skeletonAnim = ObjectAnimator.ofFloat(skeletonLoading, View.ALPHA, 1f, 0.35f);
                skeletonAnim.setDuration(750);
                skeletonAnim.setRepeatCount(ValueAnimator.INFINITE);
                skeletonAnim.setRepeatMode(ValueAnimator.REVERSE);
            }
            if (!skeletonAnim.isRunning()) skeletonAnim.start();
        } else {
            if (skeletonAnim != null && skeletonAnim.isRunning()) skeletonAnim.cancel();
            skeletonLoading.setAlpha(1f);
            skeletonLoading.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (skeletonAnim != null) {
            skeletonAnim.cancel();
            skeletonAnim = null;
        }
        skeletonLoading = null;
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** HarmonyOS Sans 字重（动态 UI 与全局字体保持一致） */
    private android.graphics.Typeface fontMedium() {
        return androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.harmonyos_sans_sc_medium);
    }

    private android.graphics.Typeface fontRegular() {
        return androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.harmonyos_sans_sc_regular);
    }
}
