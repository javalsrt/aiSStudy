package com.znxsgl.service;

import com.znxsgl.entity.Schedule;
import com.znxsgl.websocket.ScheduleWebSocketHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 课表变动通知服务
 * <p>
 * 负责在排课、调课、上架等操作后，向相关班级学生发送通知消息。
 * 包含短期去重机制，防止前端重复提交导致同一学生收到大量重复通知。
 */
@Service
public class ScheduleNotifyService {

    private final JdbcTemplate jdbc;
    private final ScheduleWebSocketHandler wsHandler;

    /**
     * 班级级全局通知缓存，key=课程名:班级名:通知内容摘要，value=上次发送时间戳。
     * 用于防止同一课程同一班级在 5 分钟内重复发送相同内容的通知。
     */
    private final Map<String, Long> globalNotifyCache = new ConcurrentHashMap<>();

    /**
     * 近期通知缓存，key=课程名:学生ID:通知内容hash，value=上次发送时间戳。
     * 用于防止同一内容在 60 秒内重复发送。
     */
    private final Map<String, Long> recentNotifyCache = new ConcurrentHashMap<>();

    /** 班级级全局去重时间窗口，单位毫秒（5 分钟） */
    private static final long GLOBAL_DEDUP_WINDOW_MS = 5 * 60_000;

    /** 学生级去重时间窗口，单位毫秒 */
    private static final long DEDUP_WINDOW_MS = 60_000;

    public ScheduleNotifyService(JdbcTemplate jdbc, ScheduleWebSocketHandler wsHandler) {
        this.jdbc = jdbc;
        this.wsHandler = wsHandler;
    }

    /**
     * 排课/调课后，给班级学生发送课程变动通知。
     * <p>
     * 去重策略（三层）：
     * 1. 班级级全局缓存：5 分钟内同一课程同一班级相同内容只发送一次，防止重复排课导致刷屏；
     * 2. 学生级内存缓存：60 秒内相同内容快速跳过；
     * 3. 数据库幂等：查询该学生最近 5 分钟是否已有完全相同内容的通知，有则跳过。
     * 三层结合可防止服务重启、集群部署或缓存失效导致的重复通知。
     *
     * @param courseName 课程名称
     * @param className  班级名称
     * @param teacherId  教师ID
     * @param slots      排课时段列表，每个 slot 包含 dayOfWeek、startNode、step、weeks 等字段
     */
    public synchronized void sendScheduleNotify(String courseName, String className, Long teacherId,
                                   List<Map<String, Object>> slots) {
        System.out.println("=== sendScheduleNotify 被调用: courseName=" + courseName
                + ", className=" + className + ", teacherId=" + teacherId
                + ", slotsSize=" + (slots == null ? 0 : slots.size())
                + ", thread=" + Thread.currentThread().getName());
        if (slots == null || slots.isEmpty()) {
            System.out.println("=== 发送排课通知跳过: slots 为空");
            return;
        }
        if (courseName == null || className == null || teacherId == null) {
            System.out.println("=== 发送排课通知参数非法: courseName=" + courseName + ", className=" + className + ", teacherId=" + teacherId);
            return;
        }
        try {
            // 获取教师姓名
            String teacherName = jdbc.queryForObject(
                    "SELECT real_name FROM teacher WHERE id = ?", String.class, teacherId);
            if (teacherName == null) {
                teacherName = "教师";
            }

            // 解析课程ID（聊天室键）：同名课程必须按「课程名 + 班级 + 教师」定位到唯一课程
            Long courseId = firstLong(
                    "SELECT c.id FROM course c " +
                    "JOIN course_class cc ON cc.course_id = c.id " +
                    "JOIN class_info ci ON ci.id = cc.class_id " +
                    "WHERE c.course_name = ? AND ci.class_name = ? AND c.teacher_id = ? LIMIT 1",
                    courseName, className, teacherId);
            if (courseId == null) {
                courseId = firstLong(
                        "SELECT id FROM course WHERE course_name = ? AND teacher_id = ? ORDER BY id LIMIT 1",
                        courseName, teacherId);
            }

            // 生成排课摘要
            String scheduleSummary = buildScheduleSummary(slots);
            // 【课程通知】作为统一前缀，便于后续清理同一课程的历史通知，避免红点累积
            String dbContent = "【课程通知】课程「" + courseName + "」排课已更新\n" + scheduleSummary;
            String toastContent = "📋 " + courseName + " 排课更新\n" + scheduleSummary;

            long now = System.currentTimeMillis();

            // 第一层去重：班级级全局去重，5 分钟内相同内容只发送一次
            String globalKey = courseName + ":" + className + ":" + scheduleSummary;
            Long globalLastSent = globalNotifyCache.get(globalKey);
            if (globalLastSent != null && now - globalLastSent < GLOBAL_DEDUP_WINDOW_MS) {
                System.out.println("=== 发送排课通知被班级级去重拦截: globalKey=" + globalKey);
                return;
            }
            // 清理过期缓存，避免内存无限增长
            globalNotifyCache.entrySet().removeIf(e -> now - e.getValue() > GLOBAL_DEDUP_WINDOW_MS);
            recentNotifyCache.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
            globalNotifyCache.put(globalKey, now);

            // 给该班级所有学生插入通知消息并推送
            List<Long> rawStudentIds;
            try {
                rawStudentIds = jdbc.queryForList(
                        "SELECT u.id FROM user u JOIN class_info ci ON ci.id = u.class_id " +
                        "WHERE ci.class_name = ? AND u.role = 1", Long.class, className);
            } catch (Exception e) {
                System.out.println("=== 发送排课通知查询学生列表异常: courseName=" + courseName
                        + ", className=" + className + ", error=" + e.getMessage());
                e.printStackTrace();
                return;
            }
            // 防止班级/用户脏数据导致同一学生重复接收
            Set<Long> studentIds = new LinkedHashSet<>(rawStudentIds);
            System.out.println("=== 发送排课通知: " + courseName + ", 班级=" + className
                    + ", 教师=" + teacherName + ", 学生数=" + studentIds.size()
                    + " (原始=" + rawStudentIds.size() + "), 摘要=" + scheduleSummary.replace("\n", " | "));
            // 异常数据告警：一个班级学生数不太可能超过 200，超过时打印警告便于排查
            if (studentIds.size() > 200 || rawStudentIds.size() - studentIds.size() > 10) {
                System.out.println("=== 发送排课通知学生数异常: courseName=" + courseName
                        + ", 班级=" + className + ", 去重后=" + studentIds.size()
                        + ", 去重前=" + rawStudentIds.size());
            }

            int dbInserted = 0;
            int wsPushed = 0;
            int wsSkipped = 0;
            for (Long sid : studentIds) {
                try {
                    // 第二层去重：进程内 60 秒缓存
                    String cacheKey = courseName + ":" + sid + ":" + dbContent;
                    Long lastSent = recentNotifyCache.get(cacheKey);
                    if (lastSent != null && now - lastSent < DEDUP_WINDOW_MS) {
                        System.out.println("=== 排课通知学生级去重跳过: sid=" + sid + ", cacheKey=" + cacheKey);
                        continue;
                    }

                    // 第三层去重：数据库最近 5 分钟是否有完全相同内容的通知
                    Integer recentCount = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM chat_message WHERE course_id <=> ? AND user_id = ? " +
                            "AND sender_role = 'teacher' AND content = ? AND created_at >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)",
                            Integer.class, courseId, sid, dbContent);
                    if (recentCount != null && recentCount > 0) {
                        System.out.println("=== 排课通知数据库去重跳过: sid=" + sid + ", recentCount=" + recentCount);
                        recentNotifyCache.put(cacheKey, now);
                        continue;
                    }
                    recentNotifyCache.put(cacheKey, now);

                    // 删除该课程该学生的旧课程通知（含新前缀与历史表述），避免消息红点累积
                    // 诊断：先打印该学生该课程下所有教师消息的内容前缀，便于排查异常数据
                    try {
                        List<String> oldContents = jdbc.queryForList(
                                "SELECT content FROM chat_message WHERE course_id <=> ? AND user_id = ? " +
                                "AND sender_role = 'teacher' LIMIT 50", String.class, courseId, sid);
                        if (oldContents.size() > 1) {
                            System.out.println("=== 排课通知诊断: sid=" + sid + ", 旧教师消息数=" + oldContents.size());
                            for (int i = 0; i < Math.min(oldContents.size(), 5); i++) {
                                String c = oldContents.get(i);
                                System.out.println("=== 排课通知诊断内容" + i + ": " +
                                        (c != null ? c.replace("\n", " | ").substring(0, Math.min(c.length(), 80)) : "null"));
                            }
                        }
                    } catch (Exception diagEx) {
                        System.out.println("=== 排课通知诊断查询异常: sid=" + sid + ", error=" + diagEx.getMessage());
                    }

                    int deleted = jdbc.update(
                            "DELETE FROM chat_message WHERE course_id <=> ? AND user_id = ? " +
                            "AND sender_role = 'teacher' " +
                            "AND (content LIKE '%【课程通知】%' OR content LIKE '%排课已更新%' OR content LIKE '课程「%')",
                            courseId, sid);
                    // 插入新的排课通知
                    int inserted = jdbc.update(
                            "INSERT INTO chat_message (course_name, course_id, user_id, sender_name, sender_role, content, created_at) " +
                            "VALUES (?, ?, ?, ?, 'teacher', ?, NOW())",
                            courseName, courseId, sid, teacherName, dbContent);
                    dbInserted += inserted;
                    System.out.println("=== 排课通知已落库: sid=" + sid + ", deletedOld=" + deleted + ", inserted=" + inserted);

                    // WebSocket 实时推送
                    Map<String, Object> wsData = new LinkedHashMap<>();
                    wsData.put("courseId", courseId);
                    wsData.put("courseName", courseName);
                    wsData.put("content", toastContent);
                    wsData.put("scheduleInfo", scheduleSummary);
                    boolean pushed = wsHandler.sendToUser(sid, "schedule_update", wsData);
                    if (pushed) {
                        wsPushed++;
                    } else {
                        wsSkipped++;
                    }
                } catch (Exception ex) {
                    System.out.println("=== 排课通知单学生发送异常: sid=" + sid + ", error=" + ex.getMessage());
                    ex.printStackTrace();
                }
            }
            System.out.println("=== 发送排课通知完成: courseName=" + courseName + ", className=" + className
                    + ", dbInserted=" + dbInserted + ", wsPushed=" + wsPushed + ", wsSkipped=" + wsSkipped);
        } catch (Exception e) {
            System.out.println("=== 发送排课通知失败: courseName=" + courseName + ", className=" + className + ", error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 调课后，给班级学生发送单条课程变动通知。
     *
     * @param courseName 课程名称
     * @param classId    班级ID
     * @param teacherId  教师ID
     * @param schedule   调整后的课表记录
     * @param reason     调课原因
     */
    public void sendScheduleAdjustNotify(String courseName, Long classId, Long teacherId,
                                         Schedule schedule, String reason) {
        System.out.println("=== sendScheduleAdjustNotify 被调用: courseName=" + courseName
                + ", classId=" + classId + ", teacherId=" + teacherId
                + ", scheduleId=" + (schedule != null ? schedule.getId() : "null")
                + ", thread=" + Thread.currentThread().getName());
        if (courseName == null || classId == null || teacherId == null || schedule == null) {
            System.out.println("=== 调课通知参数非法: courseName=" + courseName + ", classId=" + classId
                    + ", teacherId=" + teacherId + ", schedule=" + schedule);
            return;
        }
        try {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("dayOfWeek", schedule.getDayOfWeek());
            slot.put("startNode", schedule.getStartNode());
            slot.put("step", schedule.getStep() != null ? schedule.getStep() : 1);
            slot.put("weeks", schedule.getWeeks());
            slot.put("classroom", schedule.getClassroom());
            slot.put("reason", reason != null ? reason : "教师调课");

            String className;
            try {
                className = jdbc.queryForObject(
                        "SELECT class_name FROM class_info WHERE id = ?", String.class, classId);
            } catch (Exception e) {
                System.out.println("=== 调课通知查询班级名称异常: classId=" + classId + ", error=" + e.getMessage());
                e.printStackTrace();
                className = "";
            }
            if (className == null) {
                className = "";
            }
            System.out.println("=== 调课通知入口: courseName=" + courseName + ", classId=" + classId
                    + ", className=" + className + ", teacherId=" + teacherId
                    + ", dayOfWeek=" + schedule.getDayOfWeek() + ", startNode=" + schedule.getStartNode()
                    + ", weeks=" + schedule.getWeeks() + ", reason=" + reason);
            sendScheduleNotify(courseName, className, teacherId, Collections.singletonList(slot));
        } catch (Exception e) {
            System.out.println("=== 调课通知入口异常: courseName=" + courseName + ", classId=" + classId + ", error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 根据 slot 列表构建排课摘要字符串。
     * 调课通知会额外包含教室与调课原因。
     */
    private String buildScheduleSummary(List<Map<String, Object>> slots) {
        String[] dayNames = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<String> summaries = new ArrayList<>();
        for (Map<String, Object> slot : slots) {
            if (slot == null) continue;
            try {
                int dow = slot.get("dayOfWeek") instanceof Number ? ((Number) slot.get("dayOfWeek")).intValue() : 1;
                int startNode = slot.get("startNode") instanceof Number ? ((Number) slot.get("startNode")).intValue() : 1;
                int step = slot.get("step") instanceof Number ? ((Number) slot.get("step")).intValue() : 1;
                String weeksJson = (String) slot.getOrDefault("weeks", "[]");
                String classroom = (String) slot.getOrDefault("classroom", "");
                String reason = (String) slot.getOrDefault("reason", "");

                List<Integer> weekNumbers = parseWeekNumbers(weeksJson);
                String weekStr = weekNumbers.isEmpty() ? "" : formatWeekRange(weekNumbers) + " · ";

                int endNode = startNode + step - 1;
                String nodeDesc = startNode + "-" + endNode + "节";
                StringBuilder sb = new StringBuilder();
                sb.append(weekStr).append(dayNames[dow]).append(" ").append(nodeDesc);
                if (!classroom.isEmpty()) {
                    sb.append(" · ").append(classroom);
                }
                if (!reason.isEmpty()) {
                    sb.append("\n原因：").append(reason);
                }
                summaries.add(sb.toString());
            } catch (Exception e) {
                System.out.println("=== buildScheduleSummary 单条 slot 解析异常: slot=" + slot + ", error=" + e.getMessage());
                e.printStackTrace();
            }
        }
        return String.join("\n", summaries);
    }

    /** 解析 weeks JSON 数组（如 "[1,2,3]"）为周数列表 */
    private List<Integer> parseWeekNumbers(String weeksJson) {
        List<Integer> list = new ArrayList<>();
        if (weeksJson == null || weeksJson.isEmpty() || "[]".equals(weeksJson)) {
            return list;
        }
        String stripped = weeksJson.replaceAll("[\\[\\]\\s]", "");
        if (stripped.isEmpty()) {
            return list;
        }
        for (String s : stripped.split(",")) {
            try {
                list.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }

    /** 将周数列表格式化为简洁范围，如 [1,2,3,5,6] → "第1-3,5-6周" */
    private String formatWeekRange(List<Integer> weeks) {
        if (weeks.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(weeks);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder("第");
        int start = sorted.get(0);
        int end = start;
        for (int i = 1; i < sorted.size(); i++) {
            int w = sorted.get(i);
            if (w == end + 1) {
                end = w;
            } else {
                if (start == end) {
                    sb.append(start);
                } else {
                    sb.append(start).append("-").append(end);
                }
                sb.append(",");
                start = end = w;
            }
        }
        if (start == end) {
            sb.append(start);
        } else {
            sb.append(start).append("-").append(end);
        }
        sb.append("周");
        return sb.toString();
    }

    /** 取首行首列（Long），无结果返回 null */
    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
