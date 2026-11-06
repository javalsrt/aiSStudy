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
        boolean askProfile = containsAny(message, "我的", "学号", "班级", "专业", "老师");

        if (askProfile || (!askScore && !askWrong && !askSchedule && !askCheckIn)) {
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

        if (askScore) {
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

        if (askWrong) {
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

        if (askCheckIn) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM check_in_record r JOIN check_in c ON c.id = r.check_in_id " +
                            "WHERE r.student_id = ? AND YEAR(r.checked_at) = YEAR(CURDATE()) " +
                            "AND MONTH(r.checked_at) = MONTH(CURDATE())", Integer.class, userId);
            sb.append("· 本月签到次数：").append(cnt == null ? 0 : cnt).append(" 次\n");
            sources.add(Map.of("type", "personal", "title", "签到记录"));
        }
        return sb.toString();
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
