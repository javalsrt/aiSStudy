package com.znxsgl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校园 AI 助手：分层知识编排。
 * <p>
 * 层级（优先级从高到低）：
 * 1) 校园信息（校规/校内制度）——管理员维护的底层知识，命中即以此为准；
 * 2) 课程资料（RAG 向量检索）；
 * 3) 个人数据（课表/成绩/错题/签到，SQL 只查本人）；
 * 4) 通用常识（明确标注仅供参考）。
 * <p>
 * 数据类问题禁止模型自由发挥：所有数字均由 SQL 查出后作为「事实」注入 Prompt。
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final JdbcTemplate jdbc;
    private final SchoolInfoService schoolInfoService;
    private final RagService ragService;
    private final LlmService llmService;
    private final BellTimeService bellTimeService;

    public AssistantService(JdbcTemplate jdbc, SchoolInfoService schoolInfoService,
                            RagService ragService, LlmService llmService,
                            BellTimeService bellTimeService) {
        this.jdbc = jdbc;
        this.schoolInfoService = schoolInfoService;
        this.ragService = ragService;
        this.llmService = llmService;
        this.bellTimeService = bellTimeService;
    }

    /** 同问题 60 秒内直接复用答案：防止连点/重复提问触发限流，也省 token */
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> answerCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000;
    private static final int CACHE_MAX = 300;

    public Map<String, Object> ask(Long userId, String message) {
        String cacheKey = userId + "|" + message.trim();
        Object[] cached = answerCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - (long) cached[0] < CACHE_TTL_MS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = new LinkedHashMap<>((Map<String, Object>) cached[1]);
            hit.put("cached", true);
            return hit;
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        // ===== 1) 校园信息（底层知识，最高优先级） =====
        List<Map<String, Object>> schoolHits = schoolInfoService.retrieve(message, 3);
        if (!schoolHits.isEmpty()) {
            context.append("【校园信息（学校官方规定，最高优先级）】\n");
            for (Map<String, Object> hit : schoolHits) {
                context.append("●《").append(hit.get("title")).append("》（").append(hit.get("category")).append("）\n")
                        .append(trim(str(hit.get("content")), 700)).append("\n\n");
                sources.add(Map.of("type", "school", "title", String.valueOf(hit.get("title")),
                        "category", String.valueOf(hit.get("category"))));
            }
        }

        // ===== 1.5) 班级与班主任（组织结构，来自教师职务数据） =====
        String org = buildOrgContext(userId, message);
        if (!org.isBlank()) {
            context.append("【班级与班主任信息（系统实时数据）】\n").append(org).append("\n");
            sources.add(Map.of("type", "org", "title", "班主任信息"));
        }

        // ===== 2) 课程资料（RAG） =====
        String courseName = matchCourse(userId, message);
        if (courseName != null) {
            try {
                String rag = ragService.retrieveContext(courseName, message);
                if (rag != null && !rag.isBlank()) {
                    context.append("【课程资料（").append(courseName).append("）】\n")
                            .append(trim(rag, 1500)).append("\n\n");
                    sources.add(Map.of("type", "course", "title", courseName));
                }
            } catch (Exception e) {
                log.warn("课程资料检索失败 course={}: {}", courseName, e.getMessage());
            }
        }

        // ===== 3) 个人数据（只查本人） =====
        String personal = buildPersonalContext(userId, message, sources);
        if (!personal.isBlank()) {
            context.append("【该学生的个人数据（系统实时查询，禁止编造）】\n").append(personal).append("\n");
        }

        // ===== 4) 调模型 =====
        String system = buildSystemPrompt(context.toString());
        String answer;
        try {
            answer = llmService.chat(userId, system, message);
        } catch (Exception e) {
            log.warn("AI 助手调用失败: {}", e.getMessage());
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("频繁") || msg.contains("繁忙")) {
                // 命中 LlmService 的用户级限流：给可执行提示，而不是"服务不可用"
                answer = "你提问得有点快，稍等几秒再问我吧。";
            } else {
                answer = "抱歉，AI 服务暂时不可用，请稍后再试。";
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = "抱歉，我暂时无法回答这个问题，请换个说法或咨询班主任。";
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("answer", answer.trim());
        resp.put("sources", sources);
        resp.put("hasSchoolInfo", !schoolHits.isEmpty());
        resp.put("hasPersonalData", !personal.isBlank());

        if (answerCache.size() > CACHE_MAX) answerCache.clear();
        answerCache.put(cacheKey, new Object[]{System.currentTimeMillis(), resp});
        return resp;
    }

    private String buildSystemPrompt(String context) {
        String layers = context.isBlank()
                ? "（本次未检索到校园信息、课程资料或个人数据）"
                : context;
        return """
                你是「智学职达」校园 AI 助手，服务中职学生。回答必须遵守以下优先级：
                1. 【校园信息】是学校官方规定，涉及校规、作息、宿舍、请假、奖助、收费等问题时，**只能**依据它回答；
                   如果校园信息里没有相关内容，必须明确说「学校暂未收录该规定，建议咨询班主任或学生处」，禁止自行编造校规。
                2. 【课程资料】用于回答课程知识、章节重点、习题解析。
                3. 【个人数据】用于回答「我的课表/成绩/错题」等，数字必须与给定数据一致，不得四舍五入或编造。
                4. 以上都不涉及的问题可以正常回答，但要说明「以下内容为通用建议，仅供参考」。
                要求：中文作答；简洁分点；不要输出 Markdown 表格；不要提及"根据提供的资料"这类元话术，直接给结论。

                ===== 可用资料 =====
                """ + layers;
    }

    /**
     * 班主任 / 领导 / 请假审批人相关问答。
     * 只回答系统里真实存在的班级与教师，避免模型编造姓名。
     */
    private String buildOrgContext(Long userId, String message) {
        boolean askHead = message.contains("班主任") || message.contains("班主住");
        boolean askLeader = message.contains("领导") || message.contains("教导主任") || message.contains("谁审批")
                || message.contains("找谁批") || message.contains("请假找谁");
        if (!askHead && !askLeader) return "";

        StringBuilder sb = new StringBuilder();

        if (askHead) {
            // 1) 本人班级的班主任
            List<Map<String, Object>> mine = jdbc.queryForList(
                    "SELECT ci.class_name AS className, ci.grade, t.real_name AS teacherName, t.duty, t.phone " +
                            "FROM user u LEFT JOIN class_info ci ON ci.id = u.class_id " +
                            "LEFT JOIN teacher t ON t.id = ci.head_teacher_id WHERE u.id = ?", userId);
            if (!mine.isEmpty() && mine.get(0).get("className") != null) {
                Map<String, Object> r = mine.get(0);
                sb.append("· 该生所在班级：").append(r.get("className"))
                        .append("，班主任：").append(str(r.get("teacherName")));
                if (r.get("phone") != null) sb.append("（电话 ").append(r.get("phone")).append("）");
                sb.append("\n");
            }
            // 2) 问题里点名的班级
            List<Map<String, Object>> all = jdbc.queryForList(
                    "SELECT ci.class_name AS className, t.real_name AS teacherName, t.duty, t.is_leader AS isLeader " +
                            "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id");
            int matched = 0;
            for (Map<String, Object> r : all) {
                String cn = str(r.get("className"));
                if (!"未填写".equals(cn) && cn.length() >= 3 && message.contains(cn)) {
                    sb.append("· ").append(cn).append(" 班主任：").append(str(r.get("teacherName"))).append("\n");
                    matched++;
                }
            }
            // 3) 未点名任何班级：给概览（前 8 个班）
            if (matched == 0) {
                sb.append("· 全校班级班主任（部分）：");
                int n = 0;
                for (Map<String, Object> r : all) {
                    if (n++ >= 8) break;
                    sb.append("\n  - ").append(str(r.get("className"))).append("：").append(str(r.get("teacherName")));
                }
                sb.append("\n");
            }
        }

        if (askLeader) {
            List<Map<String, Object>> leaders = jdbc.queryForList(
                    "SELECT real_name AS name, duty FROM teacher WHERE is_leader = 1 ORDER BY id");
            if (!leaders.isEmpty()) {
                sb.append("· 校级领导：");
                for (Map<String, Object> l : leaders) {
                    sb.append(str(l.get("name"))).append("（").append(str(l.get("duty"))).append("）、");
                }
                sb.setLength(sb.length() - 1);
                sb.append("\n· 请假审批规则：3 天以内由班主任审批；超过 3 天由班主任初审后报校级领导终审。\n");
            }
        }
        return sb.toString();
    }

    /** 命中学生课程名则返回，用于触发 RAG */
    private String matchCourse(Long userId, String message) {
        try {
            List<String> names = jdbc.queryForList(
                    "SELECT DISTINCT c.course_name FROM course c WHERE c.id IN (" +
                            " SELECT cc.course_id FROM course_class cc JOIN user u ON u.class_id = cc.class_id WHERE u.id = ?" +
                            " UNION SELECT s.course_id FROM schedule s WHERE s.user_id = ?)", String.class, userId, userId);
            for (String n : names) {
                if (n != null && n.length() >= 2 && message.contains(n)) return n;
            }
        } catch (Exception e) {
            log.debug("课程匹配失败: {}", e.getMessage());
        }
        return null;
    }

    /** 个人数据：按问题意图选择性查询，避免每次都全表扫 */
    private String buildPersonalContext(Long userId, String message, List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        boolean askScore = containsAny(message, "成绩", "分数", "考试", "考了", "排名", "及格");
        boolean askWrong = containsAny(message, "错题", "做错", "错误", "薄弱");
        boolean askSchedule = containsAny(message, "课表", "上课", "几节课", "今天有课", "明天有课");
        boolean askCheckIn = containsAny(message, "签到", "出勤", "点名");
        boolean askProfile = containsAny(message, "我的", "学号", "班级", "专业", "老师", "我是谁");
        boolean askFocus = containsAny(message, "学习时长", "学了多久", "专注", "时长", "学习时间");
        boolean askQuiz = containsAny(message, "正确率", "答题", "练习", "刷题", "做题", "做了多少题");
        boolean askChapter = containsAny(message, "章节", "学到哪", "进度", "第几章", "看完");
        boolean askHomework = containsAny(message, "作业", "未交", "待完成", "没交", "任务", "待办");
        boolean askLeave = containsAny(message, "请假", "外出", "批了", "审批");
        boolean askBookmark = containsAny(message, "收藏");

        boolean matched = askScore || askWrong || askSchedule || askCheckIn || askFocus
                || askQuiz || askChapter || askHomework || askLeave || askBookmark || askProfile;
        // 没命中任何具体意图：输出完整学习概览，避免「我最近学得怎么样」答不出数据
        boolean overview = !matched;

        if (askProfile || overview) {
            List<Map<String, Object>> p = jdbc.queryForList(
                    "SELECT u.real_name AS name, u.student_no AS studentNo, ci.class_name AS className, " +
                            "ci.major, ci.grade, ci.department FROM user u LEFT JOIN class_info ci ON ci.id = u.class_id " +
                            "WHERE u.id = ?", userId);
            if (!p.isEmpty()) {
                Map<String, Object> r = p.get(0);
                sb.append("· 基本信息：").append(str(r.get("name"))).append("，学号 ").append(str(r.get("studentNo")))
                        .append("，").append(str(r.get("className"))).append("（").append(str(r.get("major")))
                        .append("，").append(str(r.get("grade"))).append("）\n");
            }
        }

        if (askScore || overview) {
            List<Map<String, Object>> exams = jdbc.queryForList(
                    "SELECT h.title, h.total_score AS fullScore, s.total_score, " +
                            "DATE_FORMAT(s.submitted_at, '%m-%d %H:%i') AS t " +
                            "FROM exam_submission s JOIN exam_homework h ON h.id = s.exam_homework_id " +
                            "WHERE s.user_id = ? AND s.submitted_at IS NOT NULL ORDER BY s.submitted_at DESC LIMIT 5",
                    userId);
            if (!exams.isEmpty()) {
                sb.append("· 最近考试：\n");
                for (Map<String, Object> e : exams) {
                    sb.append("  - ").append(str(e.get("title"))).append("：")
                            .append(fmt(e.get("total_score"))).append("/").append(fmt(e.get("fullScore")))
                            .append(" 分（").append(str(e.get("t"))).append("）\n");
                }
                sources.add(Map.of("type", "personal", "title", "考试成绩"));
            }
        }

        if (askWrong || overview) {
            List<Map<String, Object>> byCourse = jdbc.queryForList(
                    "SELECT qs.subject AS subject, COUNT(*) AS c FROM quiz_answer a " +
                            "JOIN quiz_session qs ON qs.id = a.session_id " +
                            "WHERE qs.user_id = ? AND a.is_correct = 0 GROUP BY qs.subject ORDER BY c DESC LIMIT 5",
                    userId);
            if (!byCourse.isEmpty()) {
                sb.append("· 错题分布（按科目）：\n");
                for (Map<String, Object> r : byCourse) {
                    sb.append("  - ").append(str(r.get("subject"))).append("：").append(fmt(r.get("c"))).append(" 题\n");
                }
                sources.add(Map.of("type", "personal", "title", "错题统计"));
            }
        }

        if (askSchedule) {
            int dow = LocalDate.now().getDayOfWeek().getValue();
            Integer week = bellTimeService.weekNumberOf(LocalDate.now());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT s.course_name, s.start_node, s.step, s.weeks, LEFT(s.start_time,5) AS st, LEFT(s.end_time,5) AS et " +
                            "FROM schedule s JOIN user u ON u.id = s.user_id " +
                            "WHERE u.class_id = (SELECT class_id FROM user WHERE id = ?) AND s.day_of_week = ? AND s.status = 1 " +
                            "ORDER BY s.start_node", userId, dow);
            List<Map<String, Object>> today = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (week != null && !matchesWeek(str(r.get("weeks")), week)) continue;
                today.add(r);
            }
            if (!today.isEmpty()) {
                sb.append("· 今天（周").append(dow).append("）的课表：\n");
                for (Map<String, Object> r : today) {
                    sb.append("  - 第").append(r.get("start_node")).append("节起（")
                            .append(str(r.get("st"))).append("-").append(str(r.get("et"))).append("）")
                            .append(str(r.get("course_name"))).append("，共").append(r.get("step")).append("节\n");
                }
                sources.add(Map.of("type", "personal", "title", "今日课表"));
            } else {
                sb.append("· 今天（周").append(dow).append("）没有排课记录。\n");
            }
        }

        if (askCheckIn || overview) appendCheckIn(sb, userId, sources);

        // ===== 新增：学习时长 / 练习正确率 / 章节进度 / 待办 / 请假 / 收藏 / 难度 =====
        if (askFocus || overview) appendFocus(sb, userId, sources);
        if (askQuiz || askWrong || overview) appendQuiz(sb, userId, sources, askWrong || overview);
        if (askChapter || overview) appendChapter(sb, userId, sources);
        if (askHomework || overview) appendHomework(sb, userId, sources);
        if (askLeave || overview) appendLeave(sb, userId, sources);
        if (askBookmark || overview) appendBookmarks(sb, userId, sources);
        if (overview) appendDifficulty(sb, userId, sources);
        return sb.toString();
    }

    /** 专注学习时长 */
    private void appendFocus(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(CASE WHEN DATE(started_at) = CURDATE() THEN duration_seconds ELSE 0 END), 0) AS todaySec, " +
                            "COALESCE(SUM(CASE WHEN YEARWEEK(started_at, 1) = YEARWEEK(CURDATE(), 1) THEN duration_seconds ELSE 0 END), 0) AS weekSec, " +
                            "COALESCE(SUM(duration_seconds), 0) AS totalSec, COUNT(*) AS cnt " +
                            "FROM focus_session WHERE user_id = ?", userId);
            long today = num(r.get("todaySec")) / 60;
            long week = num(r.get("weekSec")) / 60;
            long total = num(r.get("totalSec")) / 60;
            sb.append("· 专注学习时长：今天 ").append(today).append(" 分钟，本周 ").append(week)
                    .append(" 分钟，累计 ").append(total).append(" 分钟（共 ")
                    .append(fmt(r.get("cnt"))).append(" 次专注）\n");
            sources.add(Map.of("type", "personal", "title", "学习时长"));
        } catch (Exception e) {
            log.debug("学习时长查询失败: {}", e.getMessage());
        }
    }

    /** AI 练习答题情况（含错题分布与薄弱点） */
    private void appendQuiz(StringBuilder sb, Long userId, List<Map<String, Object>> sources, boolean withWrong) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT COUNT(*) AS sessions, COALESCE(SUM(answered_count), 0) AS answered, " +
                            "COALESCE(SUM(correct_count), 0) AS correct FROM quiz_session WHERE user_id = ?", userId);
            long sessions = num(r.get("sessions"));
            if (sessions > 0) {
                long answered = num(r.get("answered"));
                long correct = num(r.get("correct"));
                String rate = answered == 0 ? "-" : (Math.round(correct * 1000.0 / answered) / 10.0) + "%";
                sb.append("· AI 练习：共 ").append(sessions).append(" 次，答题 ").append(answered)
                        .append(" 道，答对 ").append(correct).append(" 道，正确率 ").append(rate).append("\n");
                sources.add(Map.of("type", "personal", "title", "答题统计"));
            } else {
                sb.append("· AI 练习：暂无答题记录\n");
            }
        } catch (Exception e) {
            log.debug("答题统计查询失败: {}", e.getMessage());
        }
        if (!withWrong) return;

        try {
            List<Map<String, Object>> last = jdbc.queryForList(
                    "SELECT subject, weaknesses FROM quiz_session WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
            if (!last.isEmpty() && last.get(0).get("weaknesses") != null) {
                sb.append("· 最近一次练习薄弱点：")
                        .append(trim(str(last.get(0).get("weaknesses")), 120)).append("\n");
            }
        } catch (Exception e) {
            log.debug("薄弱点查询失败: {}", e.getMessage());
        }
    }

    /** 章节学习进度 */
    private void appendChapter(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT c.course_name AS courseName, COUNT(DISTINCT p.chapter_id) AS done, " +
                            "(SELECT COUNT(*) FROM course_chapter cc WHERE cc.course_id = c.id AND cc.deleted = 0) AS total " +
                            "FROM chapter_read_progress p JOIN course c ON c.id = p.course_id " +
                            "WHERE p.user_id = ? GROUP BY c.id, c.course_name ORDER BY done DESC LIMIT 5", userId);
            if (rows.isEmpty()) {
                sb.append("· 章节学习进度：暂无已完成章节\n");
                return;
            }
            sb.append("· 章节学习进度：\n");
            for (Map<String, Object> r : rows) {
                sb.append("  - ").append(str(r.get("courseName"))).append("：已完成 ")
                        .append(fmt(r.get("done"))).append("/").append(fmt(r.get("total"))).append(" 章\n");
            }
            sources.add(Map.of("type", "personal", "title", "章节进度"));
        } catch (Exception e) {
            log.debug("章节进度查询失败: {}", e.getMessage());
        }
    }

    /** 待完成考试 / 作业 */
    private void appendHomework(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            List<Map<String, Object>> todos = jdbc.queryForList(
                    "SELECT e.title, e.type, DATE_FORMAT(e.end_time, '%m-%d %H:%i') AS deadline " +
                            "FROM exam_homework e JOIN user u ON u.class_id = e.class_id " +
                            "LEFT JOIN exam_submission s ON s.exam_homework_id = e.id AND s.user_id = u.id " +
                            "WHERE u.id = ? AND e.status = 1 AND e.end_time > NOW() AND s.id IS NULL " +
                            "ORDER BY e.end_time LIMIT 5", userId);
            if (todos.isEmpty()) {
                sb.append("· 待完成考试/作业：暂无\n");
                return;
            }
            sb.append("· 待完成考试/作业（").append(todos.size()).append(" 项）：\n");
            for (Map<String, Object> t : todos) {
                sb.append("  - ").append(str(t.get("title")))
                        .append("（截止 ").append(str(t.get("deadline"))).append("）\n");
            }
            sources.add(Map.of("type", "personal", "title", "待办考试作业"));
        } catch (Exception e) {
            log.debug("待办查询失败: {}", e.getMessage());
        }
    }

    /** 签到 / 出勤 */
    private void appendCheckIn(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM check_in_record r JOIN check_in c ON c.id = r.check_in_id " +
                            "WHERE r.student_id = ? AND YEAR(r.checked_at) = YEAR(CURDATE()) " +
                            "AND MONTH(r.checked_at) = MONTH(CURDATE())", Integer.class, userId);
            List<String> last = jdbc.queryForList(
                    "SELECT DATE_FORMAT(checked_at, '%m-%d %H:%i') FROM check_in_record " +
                            "WHERE student_id = ? ORDER BY checked_at DESC LIMIT 1", String.class, userId);
            sb.append("· 本月签到：").append(cnt == null ? 0 : cnt).append(" 次");
            if (!last.isEmpty()) sb.append("，最近一次 ").append(last.get(0));
            sb.append("\n");
            sources.add(Map.of("type", "personal", "title", "签到记录"));
        } catch (Exception e) {
            log.debug("签到查询失败: {}", e.getMessage());
        }
    }

    /** 请假 / 外出记录 */
    private void appendLeave(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT type, status, DATE_FORMAT(start_time, '%m-%d %H:%i') AS s, " +
                            "DATE_FORMAT(end_time, '%m-%d %H:%i') AS e FROM leave_request " +
                            "WHERE user_id = ? ORDER BY id DESC LIMIT 3", userId);
            if (rows.isEmpty()) {
                sb.append("· 请假记录：暂无\n");
                return;
            }
            sb.append("· 最近请假/外出记录：\n");
            for (Map<String, Object> r : rows) {
                String st = str(r.get("status"));
                String text = switch (st) {
                    case "PENDING_TEACHER" -> "待班主任审批";
                    case "PENDING_LEADER" -> "待领导终审";
                    case "APPROVED" -> "已通过（待返校打卡）";
                    case "REJECTED" -> "已驳回";
                    case "RETURNED" -> "已返校打卡";
                    case "CANCELED" -> "已取消";
                    default -> st;
                };
                sb.append("  - ").append("LEAVE".equals(str(r.get("type"))) ? "请假" : "临时外出")
                        .append(" ").append(str(r.get("s"))).append(" 至 ").append(str(r.get("e")))
                        .append("，").append(text).append("\n");
            }
            sources.add(Map.of("type", "personal", "title", "请假记录"));
        } catch (Exception e) {
            log.debug("请假记录查询失败: {}", e.getMessage());
        }
    }

    /** 题目收藏 */
    private void appendBookmarks(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM question_bookmark WHERE user_id = ?", Integer.class, userId);
            sb.append("· 题目收藏：").append(cnt == null ? 0 : cnt).append(" 道");
            if (cnt != null && cnt > 0) {
                List<String> subjects = jdbc.queryForList(
                        "SELECT subject FROM question_bookmark WHERE user_id = ? AND subject IS NOT NULL " +
                                "GROUP BY subject ORDER BY COUNT(*) DESC LIMIT 3", String.class, userId);
                if (!subjects.isEmpty()) sb.append("（").append(String.join("、", subjects)).append("）");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("收藏查询失败: {}", e.getMessage());
        }
    }

    /** 自评难度较高的课程 */
    private void appendDifficulty(StringBuilder sb, Long userId, List<Map<String, Object>> sources) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT c.course_name AS courseName, d.difficulty FROM user_course_difficulty d " +
                            "JOIN course c ON c.id = d.course_id WHERE d.user_id = ? " +
                            "ORDER BY d.difficulty DESC LIMIT 3", userId);
            if (rows.isEmpty()) return;
            sb.append("· 自评难度较高的课程：");
            for (Map<String, Object> r : rows) {
                sb.append(str(r.get("courseName"))).append("（").append(fmt(r.get("difficulty"))).append(" 级）");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("课程难度查询失败: {}", e.getMessage());
        }
    }

    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private boolean matchesWeek(String weeks, int week) {
        if (weeks == null || weeks.isBlank()) return true;
        for (int w : ScheduleConflictChecker.parseWeeks(weeks)) {
            if (w == week) return true;
        }
        return weeks.contains(String.valueOf(week));
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String str(Object o) {
        return o == null ? "未填写" : String.valueOf(o);
    }

    private String fmt(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return o == null ? "-" : String.valueOf(o);
    }
}
