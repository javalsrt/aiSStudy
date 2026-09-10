package com.znxsgl.controller;

import com.znxsgl.websocket.ScheduleWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * 教师数据统计接口
 *
 * 权限：仅教师或管理员可访问。
 */
@RestController
@RequestMapping("/api/teacher")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class TeacherStatsController {

    private final JdbcTemplate jdbc;
    private final ScheduleWebSocketHandler wsHandler;

    public TeacherStatsController(JdbcTemplate jdbc, ScheduleWebSocketHandler wsHandler) {
        this.jdbc = jdbc;
        this.wsHandler = wsHandler;
    }

    /** 教师/管理员数据总览：管理员查看全校学生，教师仅查看所教班级学生 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        List<Long> classIds = Collections.emptyList();
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            classIds = jdbc.queryForList(
                "SELECT DISTINCT cc.class_id FROM course_class cc " +
                "JOIN course c ON c.id = cc.course_id " +
                "WHERE c.teacher_id = ?", Long.class, realTeacherId);

            if (classIds.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("totalStudents", 0);
                empty.put("onlineToday", 0);
                empty.put("avgFocusMinutes", 0);
                empty.put("students", Collections.emptyList());
                return ResponseEntity.ok(empty);
            }
        }

        // 构建学生查询条件（管理员查全校 role=1，教师按班级查）
        String studentCondition;
        List<Object> params = new ArrayList<>();
        if (isAdmin) {
            studentCondition = "role = 1";
        } else {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < classIds.size(); i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
                params.add(classIds.get(i));
            }
            studentCondition = "class_id IN (" + placeholders + ") AND role = 1";
        }

        // 2. 总学生数
        int totalStudents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user WHERE " + studentCondition,
            Integer.class, params.toArray());

        // 3. 实时在线人数（基于 WebSocket 连接状态）
        Set<Long> onlineIds = wsHandler.getOnlineStudentIds();
        List<Long> studentIds = jdbc.queryForList(
            "SELECT id FROM user WHERE " + studentCondition,
            Long.class, params.toArray());
        int onlineToday = 0;
        for (Long sid : studentIds) {
            if (onlineIds.contains(sid)) onlineToday++;
        }

        // 4. 平均学习时长（分钟）
        Double avgSecObj = jdbc.queryForObject(
            "SELECT AVG(t.sec) FROM (" +
            "  SELECT SUM(f.duration_seconds) AS sec FROM focus_session f " +
            "  WHERE f.user_id IN (SELECT id FROM user WHERE " + studentCondition + ") " +
            "  AND DATE(f.finished_at) = CURDATE() " +
            "  GROUP BY f.user_id" +
            ") t", Double.class, params.toArray());
        int avgFocusMinutes = avgSecObj != null ? (int)(avgSecObj / 60) : 0;

        // 5. 活跃学生列表（按今日专注时长排序）
        List<Map<String, Object>> students = jdbc.queryForList(
            "SELECT u.id, u.real_name AS realName, u.student_no AS studentNo, " +
            "  ci.class_name AS className, " +
            "  COALESCE(SUM(f.duration_seconds), 0) AS todaySeconds, " +
            "  COALESCE(SUM(f2.duration_seconds), 0) AS totalSeconds " +
            "FROM user u " +
            "LEFT JOIN class_info ci ON ci.id = u.class_id " +
            "LEFT JOIN focus_session f ON f.user_id = u.id AND DATE(f.finished_at) = CURDATE() " +
            "LEFT JOIN focus_session f2 ON f2.user_id = u.id " +
            "WHERE u." + studentCondition + " " +
            "GROUP BY u.id, u.real_name, u.student_no, ci.class_name " +
            "ORDER BY todaySeconds DESC LIMIT 20",
            params.toArray());

        // 用 WebSocket 实时在线状态替换数据库 last_login 判断
        for (Map<String, Object> s : students) {
            Object idObj = s.get("id");
            Long uid = idObj instanceof Number ? ((Number) idObj).longValue() : null;
            s.put("online", uid != null && onlineIds.contains(uid) ? 1 : 0);
        }

        // 5. 刷题正确率
        double quizAccuracy = 0;
        try {
            Double acc = jdbc.queryForObject(
                "SELECT IFNULL(AVG(correct), 0) FROM (" +
                " SELECT CASE WHEN user_answer = correct_answer THEN 100 ELSE 0 END AS correct" +
                " FROM quiz_answer WHERE session_id IN (" +
                "  SELECT id FROM quiz_session WHERE user_id IN (SELECT id FROM user WHERE " + studentCondition + ")" +
                " )) t", Double.class, params.toArray());
            quizAccuracy = acc != null ? acc : 0;
        } catch(Exception ignored) {}

        // 6. 各班级平均学习时长排行（替代原"各学科正确率"，更适合管理员总览）
        List<Map<String, Object>> classFocusRanking = new ArrayList<>();
        try {
            String classCondition;
            List<Object> classParams = new ArrayList<>(params);
            if (isAdmin) {
                classCondition = "WHERE u.role = 1";
                classParams.clear();
            } else {
                classCondition = "WHERE u." + studentCondition;
            }
            classFocusRanking = jdbc.queryForList(
                "SELECT ci.class_name AS name, " +
                "  ROUND(COALESCE(AVG(total_seconds), 0) / 60, 1) AS avgMinutes, " +
                "  COUNT(DISTINCT u.id) AS studentCount " +
                "FROM class_info ci " +
                "JOIN user u ON u.class_id = ci.id " +
                "LEFT JOIN (" +
                "  SELECT user_id, SUM(duration_seconds) AS total_seconds " +
                "  FROM focus_session " +
                "  GROUP BY user_id" +
                ") t ON t.user_id = u.id " +
                classCondition + " " +
                "GROUP BY ci.id, ci.class_name " +
                "ORDER BY avgMinutes DESC LIMIT 10",
                classParams.toArray());
        } catch(Exception ignored) {}

        Map<String, Object> result = new HashMap<>();
        result.put("totalStudents", totalStudents);
        result.put("onlineToday", onlineToday);
        result.put("avgFocusMinutes", avgFocusMinutes);
        result.put("quizAccuracy", Math.round(quizAccuracy));
        result.put("classFocusRanking", classFocusRanking);
        result.put("students", students);
        return ResponseEntity.ok(result);
    }

    /**
     * 班级学习概览（多指标汇总，学习统计页面未选中学生时的右侧默认面板）。
     * 教师仅返回其授课班级，管理员返回全校班级。
     */
    @GetMapping("/class-summary")
    public ResponseEntity<List<Map<String, Object>>> getClassSummary(Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        // 1. 班级基本信息与学生数
        StringBuilder baseSql = new StringBuilder(
                "SELECT ci.id AS classId, ci.class_name AS name, " +
                        "ci.grade AS grade, ci.major AS major, " +
                        "COUNT(DISTINCT u.id) AS studentCount " +
                        "FROM class_info ci " +
                        "JOIN user u ON u.class_id = ci.id AND u.role = 1 ");
        List<Object> baseParams = new ArrayList<>();
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            baseSql.append("WHERE ci.id IN (SELECT cc.class_id FROM course_class cc " +
                    "JOIN course c ON c.id = cc.course_id WHERE c.teacher_id = ?) ");
            baseParams.add(realTeacherId);
        }
        baseSql.append("GROUP BY ci.id, ci.class_name, ci.grade, ci.major ORDER BY ci.id");

        List<Map<String, Object>> summary = jdbc.queryForList(baseSql.toString(), baseParams.toArray());
        if (summary.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // 提取班级 ID 列表
        List<Long> classIds = new ArrayList<>();
        for (Map<String, Object> row : summary) {
            Long id = toLong(row.get("classId"));
            if (id != null) classIds.add(id);
        }
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < classIds.size(); i++) {
            if (i > 0) ph.append(",");
            ph.append("?");
        }
        String inClause = ph.toString();
        Object[] classIdParams = classIds.toArray();

        // 2. 人均学习时长（分钟），口径对齐 classFocusRanking
        Map<Long, Double> avgMinutesMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT u.class_id AS classId, " +
                            "ROUND(COALESCE(AVG(t.sec), 0) / 60, 1) AS avgMinutes " +
                            "FROM user u " +
                            "LEFT JOIN (SELECT user_id, SUM(duration_seconds) AS sec FROM focus_session GROUP BY user_id) t " +
                            "ON t.user_id = u.id " +
                            "WHERE u.role = 1 AND u.class_id IN (" + inClause + ") " +
                            "GROUP BY u.class_id", classIdParams);
            for (Map<String, Object> r : rows) {
                avgMinutesMap.put(toLong(r.get("classId")), toDouble(r.get("avgMinutes")));
            }
        } catch (Exception ignored) {}

        // 3. 刷题正确率（%），口径对齐 /stats 的 quizAccuracy
        Map<Long, Double> accuracyMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT u.class_id AS classId, " +
                            "ROUND(IFNULL(AVG(t.correct), 0), 1) AS accuracy " +
                            "FROM (SELECT qs.user_id AS userId, " +
                            "  CASE WHEN qa.user_answer = qa.correct_answer THEN 100 ELSE 0 END AS correct " +
                            "  FROM quiz_answer qa JOIN quiz_session qs ON qs.id = qa.session_id) t " +
                            "JOIN user u ON u.id = t.userId " +
                            "WHERE u.role = 1 AND u.class_id IN (" + inClause + ") " +
                            "GROUP BY u.class_id", classIdParams);
            for (Map<String, Object> r : rows) {
                accuracyMap.put(toLong(r.get("classId")), toDouble(r.get("accuracy")));
            }
        } catch (Exception ignored) {}

        // 4. 章节完成率（%），按学生个人口径聚合到班级
        Map<Long, Double> completionRateMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT sub.class_id AS classId, " +
                            "ROUND(COALESCE(SUM(sub.completed) * 100.0 / NULLIF(SUM(sub.total), 0), 0), 1) AS completionRate " +
                            "FROM (" +
                            "  SELECT s.id AS studentId, s.class_id AS class_id, " +
                            "    COUNT(DISTINCT cc.id) AS total, " +
                            "    COUNT(DISTINCT CASE WHEN crp.chapter_id IS NOT NULL THEN cc.id END) AS completed " +
                            "  FROM user s " +
                            "  JOIN course_chapter cc ON cc.deleted = 0 " +
                            "  LEFT JOIN chapter_read_progress crp ON crp.chapter_id = cc.id AND crp.user_id = s.id " +
                            "  WHERE s.role = 1 AND s.class_id IN (" + inClause + ") " +
                            "    AND cc.course_id IN (SELECT DISTINCT cc2.course_id FROM course_class cc2 WHERE cc2.class_id = s.class_id) " +
                            "  GROUP BY s.id, s.class_id" +
                            ") sub GROUP BY sub.class_id", classIdParams);
            for (Map<String, Object> r : rows) {
                completionRateMap.put(toLong(r.get("classId")), toDouble(r.get("completionRate")));
            }
        } catch (Exception ignored) {}

        // 5. 考试平均分（仅统计考试，不含作业）
        Map<Long, Double> examAvgScoreMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT eh.class_id AS classId, " +
                            "ROUND(COALESCE(AVG(es.total_score), 0), 1) AS examAvgScore " +
                            "FROM exam_homework eh " +
                            "JOIN exam_submission es ON es.exam_homework_id = eh.id " +
                            "WHERE eh.type = 'exam' AND eh.status IN (1, 2) AND eh.class_id IN (" + inClause + ") " +
                            "GROUP BY eh.class_id", classIdParams);
            for (Map<String, Object> r : rows) {
                examAvgScoreMap.put(toLong(r.get("classId")), toDouble(r.get("examAvgScore")));
            }
        } catch (Exception ignored) {}

        // 6. 课程数
        Map<Long, Integer> courseCountMap = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT cc.class_id AS classId, COUNT(DISTINCT cc.course_id) AS courseCount " +
                            "FROM course_class cc WHERE cc.class_id IN (" + inClause + ") " +
                            "GROUP BY cc.class_id", classIdParams);
            for (Map<String, Object> r : rows) {
                courseCountMap.put(toLong(r.get("classId")), toInt(r.get("courseCount")));
            }
        } catch (Exception ignored) {}

        // 7. 在线人数（WebSocket 实时在线，按班级分组）
        Set<Long> onlineIds = wsHandler.getOnlineStudentIds();
        Map<Long, Integer> onlineCountMap = new HashMap<>();
        List<Map<String, Object>> classStudents = jdbc.queryForList(
                "SELECT id, class_id FROM user WHERE role = 1 AND class_id IN (" + inClause + ")",
                classIdParams);
        for (Map<String, Object> r : classStudents) {
            Long uid = toLong(r.get("id"));
            Long cid = toLong(r.get("class_id"));
            if (uid != null && cid != null && onlineIds.contains(uid)) {
                onlineCountMap.merge(cid, 1, Integer::sum);
            }
        }

        // 8. 合并结果
        for (Map<String, Object> row : summary) {
            Long classId = toLong(row.get("classId"));
            row.put("avgMinutes", avgMinutesMap.getOrDefault(classId, 0.0));
            row.put("accuracy", accuracyMap.getOrDefault(classId, 0.0));
            row.put("completionRate", completionRateMap.getOrDefault(classId, 0.0));
            row.put("examAvgScore", examAvgScoreMap.getOrDefault(classId, 0.0));
            row.put("courseCount", courseCountMap.getOrDefault(classId, 0));
            row.put("onlineCount", onlineCountMap.getOrDefault(classId, 0));
        }

        return ResponseEntity.ok(summary);
    }

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static Double toDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private Long getRealTeacherId(Long userId) {
        try {
            Map<String, Object> u = jdbc.queryForMap("SELECT real_name FROM user WHERE id = ?", userId);
            String realName = (String) u.get("real_name");
            return jdbc.queryForObject(
                "SELECT id FROM teacher WHERE real_name = ? LIMIT 1", Long.class, realName);
        } catch (Exception e) {
            return userId;
        }
    }

    /** 班级统计（学习统计页面）：管理员可查看任意班级 */
    @GetMapping("/class-stats/{classId}")
    public ResponseEntity<Map<String, Object>> classStats(@PathVariable Long classId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            // 校验班级归属当前教师
            if (!classBelongsToTeacher(classId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }
        // 班级学生专注排行
        List<Map<String, Object>> focusRank = jdbc.queryForList(
            "SELECT u.id, u.real_name AS name, u.student_no AS studentNo, " +
            "  COALESCE(SUM(f.duration_seconds), 0) AS totalSeconds " +
            "FROM user u LEFT JOIN focus_session f ON f.user_id = u.id " +
            "WHERE u.class_id = ? AND u.role = 1 " +
            "GROUP BY u.id ORDER BY totalSeconds DESC", classId);

        // 该班级各课程提问量
        List<Map<String, Object>> courseQuestions = jdbc.queryForList(
            "SELECT cm.course_name AS name, COUNT(*) AS count " +
            "FROM chat_message cm JOIN user u ON u.id = cm.user_id " +
            "WHERE u.class_id = ? AND cm.sender_role = 'student' " +
            "GROUP BY cm.course_name ORDER BY count DESC", classId);

        // 班级学生总数
        int total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user WHERE class_id = ? AND role = 1", Integer.class, classId);

        Map<String, Object> result = new HashMap<>();
        result.put("focusRank", focusRank);
        result.put("courseQuestions", courseQuestions);
        result.put("totalStudents", total);
        return ResponseEntity.ok(result);
    }

    /** 学生个人统计：管理员可查看任意学生 */
    @GetMapping("/student-stats/{studentId}")
    public ResponseEntity<Map<String, Object>> studentStats(@PathVariable Long studentId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            // 校验学生归属当前教师班级
            if (!studentBelongsToTeacher(studentId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }

        // 基本信息（改用 queryForList + 判空，避免 EmptyResultDataAccessException）
        List<Map<String, Object>> infoRows = jdbc.queryForList(
            "SELECT u.real_name AS name, u.student_no AS studentNo, ci.class_name AS className " +
            "FROM user u LEFT JOIN class_info ci ON ci.id = u.class_id WHERE u.id = ?", studentId);
        if (infoRows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "学生不存在"));
        }
        Map<String, Object> info = infoRows.get(0);

        // 7天专注趋势（DATE_FORMAT 返回字符串，避免 java.sql.Date 序列化为时间戳）
        List<Map<String, Object>> focusTrend = jdbc.queryForList(
            "SELECT DATE_FORMAT(finished_at,'%Y-%m-%d') AS date, SUM(duration_seconds)/60 AS minutes " +
            "FROM focus_session WHERE user_id = ? AND finished_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(finished_at,'%Y-%m-%d') ORDER BY date", studentId);

        // 总专注时长
        int totalFocus = jdbc.queryForObject(
            "SELECT COALESCE(SUM(duration_seconds),0) FROM focus_session WHERE user_id = ?",
            Integer.class, studentId);

        // 各课程提问次数
        List<Map<String, Object>> questions = jdbc.queryForList(
            "SELECT course_name AS name, COUNT(*) AS count FROM chat_message " +
            "WHERE user_id = ? AND sender_role = 'student' " +
            "GROUP BY course_name ORDER BY count DESC", studentId);

        // 本学期登录天数
        // 简化实现：项目无独立登录日志表，last_login 为单字段每次登录被覆盖，
        // 无法统计历史登录天数。此处返回 last_login 是否存在（0 或 1）。
        // 未来可新增登录日志表后改为 COUNT(DISTINCT DATE(login_time))。
        int loginDays = jdbc.queryForObject(
            "SELECT CASE WHEN last_login IS NOT NULL THEN 1 ELSE 0 END FROM user WHERE id = ?",
            Integer.class, studentId);

        // 班级平均专注（用于对比）
        Double classAvgSec = jdbc.queryForObject(
            "SELECT AVG(t.sec) FROM (SELECT SUM(f.duration_seconds) AS sec FROM focus_session f " +
            "WHERE f.user_id IN (SELECT id FROM user WHERE class_id = " +
            "(SELECT class_id FROM user WHERE id = ?) AND role = 1) GROUP BY f.user_id) t",
            Double.class, studentId);

        // 最新六维评分
        List<Map<String, Object>> scores = new ArrayList<>();
        try {
            Map<String, Object> scoreRow = jdbc.queryForMap(
                "SELECT s.scores FROM quiz_session s WHERE s.user_id = ? AND s.status = 'evaluated' ORDER BY s.created_at DESC LIMIT 1",
                studentId);
            if (scoreRow != null && scoreRow.get("scores") != null) {
                String scoresJson = scoreRow.get("scores").toString();
                // scores是JSON Map格式 {"逻辑思维力":8, "判断决策力":7, ...}
                // 转化为列表
                scoresJson = scoresJson.replaceAll("[{}\"]", "");
                for (String pair : scoresJson.split(",")) {
                    String[] kv = pair.split(":");
                    if (kv.length == 2) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("name", kv[0].trim());
                        try { item.put("value", Integer.parseInt(kv[1].trim())); }
                        catch (NumberFormatException e) { item.put("value", 0); }
                        scores.add(item);
                    }
                }
            }
        } catch (Exception ignored) {}

        Map<String, Object> result = new HashMap<>();
        result.put("info", info);
        result.put("focusTrend", focusTrend);
        result.put("totalFocusMinutes", totalFocus / 60);
        result.put("questions", questions);
        result.put("loginDays", loginDays);
        result.put("classAvgMinutes", classAvgSec != null ? (int)(classAvgSec / 60) : 0);
        result.put("scores", scores);
        return ResponseEntity.ok(result);
    }

    /** 学生专注与刷题记录 */
    @GetMapping("/student/{studentId}/focus-quiz")
    public ResponseEntity<Map<String, Object>> getStudentFocusQuiz(
            @PathVariable Long studentId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            if (!studentBelongsToTeacher(studentId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }

        // 专注会话
        List<Map<String, Object>> focusSessions = jdbc.queryForList(
                "SELECT id, duration_seconds AS durationSeconds, " +
                        "started_at AS startedAt, finished_at AS finishedAt, created_at AS createdAt " +
                        "FROM focus_session WHERE user_id = ? ORDER BY finished_at DESC LIMIT 100",
                studentId);
        for (Map<String, Object> fs : focusSessions) {
            Integer secs = fs.get("durationSeconds") instanceof Number
                    ? ((Number) fs.get("durationSeconds")).intValue() : 0;
            fs.put("durationMinutes", secs / 60);
        }

        // 刷题会话
        List<Map<String, Object>> quizSessions = jdbc.queryForList(
                "SELECT id, subject, difficulty, session_no AS sessionNo, " +
                        "total_questions AS totalQuestions, answered_count AS answeredCount, " +
                        "correct_count AS correctCount, skip_count AS skipCount, " +
                        "total_duration_sec AS totalDurationSec, scores, " +
                        "created_at AS createdAt " +
                        "FROM quiz_session WHERE user_id = ? AND status IN ('completed','evaluated') " +
                        "ORDER BY created_at DESC LIMIT 100",
                studentId);
        for (Map<String, Object> qs : quizSessions) {
            int total = qs.get("totalQuestions") instanceof Number
                    ? ((Number) qs.get("totalQuestions")).intValue() : 0;
            int correct = qs.get("correctCount") instanceof Number
                    ? ((Number) qs.get("correctCount")).intValue() : 0;
            qs.put("accuracy", total > 0 ? (correct * 100 / total) + "%" : "0%");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("focusSessions", focusSessions);
        data.put("quizSessions", quizSessions);
        return ResponseEntity.ok(data);
    }

    /** 教师查看学生单次测评报告详情（含每题作答明细） */
    @GetMapping("/quiz-session/{sessionId}/report")
    public ResponseEntity<Map<String, Object>> getQuizSessionReport(
            @PathVariable Long sessionId, Authentication auth) {
        // 查询会话基本信息
        Map<String, Object> session;
        try {
            session = jdbc.queryForMap(
                    "SELECT s.user_id AS userId, s.subject, s.difficulty, s.session_no AS sessionNo, " +
                            "s.total_questions AS totalQuestions, s.answered_count AS answeredCount, " +
                            "s.correct_count AS correctCount, s.skip_count AS skipCount, " +
                            "s.total_duration_sec AS totalDurationSec, s.scores, s.strengths, " +
                            "s.weaknesses, s.suggestion, s.study_plan AS studyPlan, s.status, " +
                            "s.created_at AS createdAt " +
                            "FROM quiz_session s WHERE s.id = ?", sessionId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "测评记录不存在"));
        }

        Long studentUserId = session.get("userId") instanceof Number
                ? ((Number) session.get("userId")).longValue() : null;
        if (studentUserId == null) {
            return ResponseEntity.status(404).body(Map.of("error", "测评记录不存在"));
        }

        // 权限：管理员可查全部，教师仅可查自己学生
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            if (!studentBelongsToTeacher(studentUserId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }

        // 学生信息（报告头部展示）
        Map<String, Object> studentInfo;
        try {
            studentInfo = jdbc.queryForMap(
                    "SELECT u.real_name AS realName, u.student_no AS studentNo, " +
                            "ci.class_name AS className " +
                            "FROM user u LEFT JOIN class_info ci ON ci.id = u.class_id " +
                            "WHERE u.id = ?", studentUserId);
        } catch (Exception e) {
            studentInfo = new HashMap<>();
        }

        // 每题作答明细
        List<Map<String, Object>> answers = jdbc.queryForList(
                "SELECT question_index AS questionIndex, question_type AS questionType, " +
                        "question, options, user_answer AS userAnswer, " +
                        "correct_answer AS correctAnswer, is_correct AS isCorrect, " +
                        "duration_sec AS durationSec, modified_count AS modifiedCount " +
                        "FROM quiz_answer WHERE session_id = ? ORDER BY question_index",
                sessionId);

        // 解析 JSON 字段为前端可直接使用的结构
        session.remove("userId");
        session.put("scores", parseScoresJson(session.get("scores")));
        session.put("strengths", parseStringListJson(session.get("strengths")));
        session.put("weaknesses", parseStringListJson(session.get("weaknesses")));
        session.put("studyPlan", parseStringListJson(session.get("studyPlan")));

        Map<String, Object> data = new HashMap<>();
        data.put("session", session);
        data.put("student", studentInfo);
        data.put("answers", answers);
        return ResponseEntity.ok(data);
    }

    /** 解析六维评分JSON（{"逻辑思维力":8,...}）为 [{name,value}] 列表 */
    private List<Map<String, Object>> parseScoresJson(Object json) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (json == null) return list;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.toString());
            if (node.isObject()) {
                Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue().asInt(0));
                    list.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 解析字符串数组JSON（["...","..."]）为 List<String> */
    private List<String> parseStringListJson(Object json) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.toString());
            if (node.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : node) {
                    String text = item.asText("");
                    if (!text.isEmpty()) list.add(text);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 学生章节学习进度 */
    @GetMapping("/student/{studentId}/chapters")
    public ResponseEntity<List<Map<String, Object>>> getStudentChapterProgress(
            @PathVariable Long studentId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            if (!studentBelongsToTeacher(studentId, realTeacherId)) {
                return ResponseEntity.status(403).body(Collections.emptyList());
            }
        }

        // 按课程聚合章节完成情况
        List<Map<String, Object>> courses = jdbc.queryForList(
                "SELECT c.id AS courseId, c.course_name AS courseName, " +
                        "COUNT(DISTINCT cc.id) AS totalChapters, " +
                        "COUNT(DISTINCT CASE WHEN crp.chapter_id IS NOT NULL THEN cc.id END) AS completedChapters " +
                        "FROM course c " +
                        "JOIN course_chapter cc ON cc.course_id = c.id AND cc.deleted = 0 " +
                        "LEFT JOIN chapter_read_progress crp ON crp.chapter_id = cc.id AND crp.user_id = ? " +
                        "WHERE c.id IN (SELECT DISTINCT course_id FROM course_class cc2 " +
                        "  JOIN user u ON u.class_id = cc2.class_id WHERE u.id = ?) " +
                        "GROUP BY c.id, c.course_name",
                studentId, studentId);

        for (Map<String, Object> course : courses) {
            Long courseId = course.get("courseId") instanceof Number
                    ? ((Number) course.get("courseId")).longValue() : null;
            if (courseId == null) continue;

            int total = course.get("totalChapters") instanceof Number
                    ? ((Number) course.get("totalChapters")).intValue() : 0;
            int completed = course.get("completedChapters") instanceof Number
                    ? ((Number) course.get("completedChapters")).intValue() : 0;
            course.put("completionRate", total > 0 ? Math.round(completed * 100.0 / total) : 0);

            List<Map<String, Object>> chapters = jdbc.queryForList(
                    "SELECT cc.id AS chapterId, cc.chapter_no AS chapterNo, " +
                            "cc.chapter_name AS chapterName, cc.description, " +
                            "CASE WHEN crp.chapter_id IS NOT NULL THEN 1 ELSE 0 END AS completed, " +
                            "crp.completed_at AS completedAt " +
                            "FROM course_chapter cc " +
                            "LEFT JOIN chapter_read_progress crp ON crp.chapter_id = cc.id AND crp.user_id = ? " +
                            "WHERE cc.course_id = ? AND cc.deleted = 0 " +
                            "ORDER BY cc.chapter_no",
                    studentId, courseId);
            course.put("chapters", chapters);
        }

        return ResponseEntity.ok(courses);
    }

    /** 学生考试/作业提交记录 */
    @GetMapping("/student/{studentId}/exams")
    public ResponseEntity<List<Map<String, Object>>> getStudentExamRecords(
            @PathVariable Long studentId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Long realTeacherId = null;
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            realTeacherId = getRealTeacherId(teacherUserId);
            if (!studentBelongsToTeacher(studentId, realTeacherId)) {
                return ResponseEntity.status(403).body(Collections.emptyList());
            }
        }

        StringBuilder sql = new StringBuilder(
                "SELECT eh.id AS examId, eh.type, eh.title, eh.course_id AS courseId, " +
                        "eh.class_id AS classId, eh.total_score AS totalScore, " +
                        "eh.pass_score AS passScore, eh.start_time AS startTime, " +
                        "eh.end_time AS endTime, eh.status, " +
                        "es.id AS submissionId, es.total_score AS score, " +
                        "es.duration_sec AS durationSec, es.submitted_at AS submittedAt, " +
                        "es.status AS submissionStatus, " +
                        "c.course_name AS courseName, ci.class_name AS className " +
                        "FROM exam_homework eh " +
                        "LEFT JOIN exam_submission es ON es.exam_homework_id = eh.id AND es.user_id = ? " +
                        "LEFT JOIN course c ON c.id = eh.course_id " +
                        "LEFT JOIN class_info ci ON ci.id = eh.class_id " +
                        "WHERE eh.status = 1 ");
        List<Object> params = new ArrayList<>();
        params.add(studentId);
        if (!isAdmin) {
            sql.append("AND eh.teacher_id = ? ");
            params.add(realTeacherId);
        }
        sql.append("ORDER BY eh.created_at DESC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        for (Map<String, Object> row : rows) {
            int total = row.get("totalScore") instanceof Number
                    ? ((Number) row.get("totalScore")).intValue() : 0;
            int pass = row.get("passScore") instanceof Number
                    ? ((Number) row.get("passScore")).intValue() : 0;
            Object scoreObj = row.get("score");
            Integer score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : null;
            row.put("isPass", score != null && total > 0 && score >= pass);
            row.put("hasSubmitted", scoreObj != null);
            row.put("score", score);
        }
        return ResponseEntity.ok(rows);
    }

    /** 学生某次考试/作业的每题作答明细 */
    @GetMapping("/student/{studentId}/exam/{examId}/details")
    public ResponseEntity<Map<String, Object>> getStudentExamDetails(
            @PathVariable Long studentId, @PathVariable Long examId, Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Long realTeacherId = null;
        if (!isAdmin) {
            Long teacherUserId = (Long) auth.getPrincipal();
            realTeacherId = getRealTeacherId(teacherUserId);
            if (!studentBelongsToTeacher(studentId, realTeacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
            // 校验考试属于该教师
            Integer owner = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exam_homework WHERE id = ? AND teacher_id = ?",
                    Integer.class, examId, realTeacherId);
            if (owner == null || owner == 0) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限查看该考试"));
            }
        }

        // 考试基本信息
        List<Map<String, Object>> examRows = jdbc.queryForList(
                "SELECT type, title, total_score AS totalScore, pass_score AS passScore " +
                        "FROM exam_homework WHERE id = ?", examId);
        if (examRows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "考试不存在"));
        }
        Map<String, Object> examInfo = examRows.get(0);

        // 学生提交记录
        List<Map<String, Object>> subRows = jdbc.queryForList(
                "SELECT id, total_score AS totalScore, duration_sec AS durationSec, " +
                        "submitted_at AS submittedAt " +
                        "FROM exam_submission WHERE exam_homework_id = ? AND user_id = ?",
                examId, studentId);
        if (subRows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "学生尚未提交"));
        }
        Map<String, Object> submission = subRows.get(0);
        Long submissionId = submission.get("id") instanceof Number
                ? ((Number) submission.get("id")).longValue() : null;

        // 每题作答明细
        List<Map<String, Object>> answers = jdbc.queryForList(
                "SELECT question_index AS questionIndex, question_type AS questionType, " +
                        "question, options, user_answer AS userAnswer, " +
                        "correct_answer AS correctAnswer, is_correct AS isCorrect, " +
                        "score AS userScore, max_score AS maxScore, ai_score AS aiScore, " +
                        "ai_comment AS aiComment, teacher_comment AS teacherComment " +
                        "FROM exam_submission_answer WHERE submission_id = ? " +
                        "ORDER BY question_index",
                submissionId);

        Map<String, Object> result = new HashMap<>();
        result.put("examInfo", examInfo);
        result.put("submission", submission);
        result.put("answers", answers);
        return ResponseEntity.ok(result);
    }

    /** 学习时长趋势（近7天）：管理员查看全校，教师查看所教班级 */
    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> getTrend(Authentication auth) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        String userCondition;
        List<Object> params = new ArrayList<>();
        if (isAdmin) {
            userCondition = "f.user_id IN (SELECT id FROM user WHERE role = 1)";
        } else {
            Long teacherUserId = (Long) auth.getPrincipal();
            Long realTeacherId = getRealTeacherId(teacherUserId);
            List<Long> userIds = jdbc.queryForList(
                "SELECT DISTINCT u.id FROM user u " +
                "JOIN course_class cc ON cc.class_id = u.class_id " +
                "JOIN course c ON c.id = cc.course_id " +
                "WHERE c.teacher_id = ? AND u.role = 1", Long.class, realTeacherId);

            if (userIds.isEmpty()) return ResponseEntity.ok(Collections.emptyList());

            StringBuilder ph = new StringBuilder();
            for (int i = 0; i < userIds.size(); i++) {
                if (i > 0) ph.append(",");
                ph.append("?");
                params.add(userIds.get(i));
            }
            userCondition = "f.user_id IN (" + ph + ")";
        }

        List<Map<String, Object>> trend = jdbc.queryForList(
            "SELECT DATE_FORMAT(finished_at,'%Y-%m-%d') AS date, " +
            "  COALESCE(SUM(duration_seconds)/60, 0) AS minutes " +
            "FROM focus_session f " +
            "WHERE " + userCondition + " AND finished_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(finished_at,'%Y-%m-%d') ORDER BY date",
            params.toArray());

        return ResponseEntity.ok(trend);
    }

    /** 校验班级是否归属当前教师（通过 course_class + course 关联） */
    private boolean classBelongsToTeacher(Long classId, Long teacherId) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                "WHERE cc.class_id = ? AND c.teacher_id = ?", Integer.class, classId, teacherId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 校验学生是否归属当前教师班级（通过 user.class_id + course_class + course 关联） */
    private boolean studentBelongsToTeacher(Long studentId, Long teacherId) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user u " +
                "JOIN course_class cc ON cc.class_id = u.class_id " +
                "JOIN course c ON c.id = cc.course_id " +
                "WHERE u.id = ? AND u.role = 1 AND c.teacher_id = ?", Integer.class, studentId, teacherId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
