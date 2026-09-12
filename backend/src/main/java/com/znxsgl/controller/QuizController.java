package com.znxsgl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.entity.QuestionBookmark;
import com.znxsgl.entity.QuizAnswer;
import com.znxsgl.entity.QuizSession;
import com.znxsgl.mapper.QuestionBookmarkMapper;
import com.znxsgl.mapper.QuizAnswerMapper;
import com.znxsgl.mapper.QuizSessionMapper;
import com.znxsgl.mapper.UserMapper;
import com.znxsgl.service.LlmService;
import com.znxsgl.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final LlmService llmService;
    private final QuizSessionMapper sessionMapper;
    private final QuizAnswerMapper answerMapper;
    private final QuestionBookmarkMapper bookmarkMapper;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbc;
    private final RagService ragService;
    private final ObjectMapper json = new ObjectMapper();

    // 出题结果缓存：key=MD5指纹（课程+难度+科目+题型+已学章节），value=题目+过期时间（15分钟）
    // 同课同档位的学生并发刷题时只调一次 AI，其余直接命中缓存，大幅降低 AI 调用压力
    private final ConcurrentHashMap<String, CachedQuestions> questionCache = new ConcurrentHashMap<>();

    /** 出题缓存条目 */
    private static class CachedQuestions {
        final List<Map<String, Object>> questions;
        final long expireAt;
        CachedQuestions(List<Map<String, Object>> questions, long expireAt) {
            this.questions = questions;
            this.expireAt = expireAt;
        }
    }

    // 异步出题：固定线程池（并发 20，与全局 20/s 限流配合，任务在池内排队执行）
    // 高并发下 100 人同时点「开始刷题」：请求立即返回 taskId，不占 Tomcat 线程、不阻塞、不报错
    private final ExecutorService quizExecutor = Executors.newFixedThreadPool(20);
    private final ConcurrentHashMap<String, QuizTask> quizTasks = new ConcurrentHashMap<>();

    /** 异步出题任务状态 */
    private static class QuizTask {
        volatile String status = "pending"; // pending / done / error
        volatile Map<String, Object> result;
        volatile String error;
        volatile long expireAt; // 0 表示未完成，完成后记录过期时间（10 分钟）
    }

    public QuizController(LlmService llmService, QuizSessionMapper sessionMapper,
                          QuizAnswerMapper answerMapper, QuestionBookmarkMapper bookmarkMapper,
                          UserMapper userMapper, JdbcTemplate jdbc, RagService ragService) {
        this.llmService = llmService;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.bookmarkMapper = bookmarkMapper;
        this.userMapper = userMapper;
        this.jdbc = jdbc;
        this.ragService = ragService;
    }

    /**
     * 生成题目（异步）：立即返回 taskId，不阻塞。
     * 真正的出题（查进度→RAG→调AI）在线程池排队执行，高并发下所有人都能秒响应，
     * 互不影响（即使不同专业/不同进度同时点）。
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String subject = body.get("subject") != null ? body.get("subject").toString() : null;
        String subjectType = body.get("subjectType") != null ? body.get("subjectType").toString() : "专业";

        if (subject == null || subject.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "subject不能为空"));
        }
        if (body.get("courseId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "courseId不能为空"));
        }
        Long courseId = Long.valueOf(body.get("courseId").toString());

        // 惰性清理已过期任务，防止 map 无限增长
        quizTasks.entrySet().removeIf(e -> e.getValue().expireAt != 0
                && e.getValue().expireAt < System.currentTimeMillis());

        String taskId = UUID.randomUUID().toString();
        QuizTask task = new QuizTask();
        quizTasks.put(taskId, task);
        quizExecutor.submit(() -> doGenerate(userId, subject, subjectType, courseId, task));
        return ResponseEntity.ok(Map.of("taskId", taskId));
    }

    /** 异步出题结果查询：前端轮询（pending / done / error） */
    @GetMapping("/generate/result")
    public ResponseEntity<Map<String, Object>> generateResult(@RequestParam String taskId) {
        QuizTask task = quizTasks.get(taskId);
        if (task == null) {
            return ResponseEntity.ok(Map.of("status", "error", "error", "出题任务已过期，请重新开始"));
        }
        if ("done".equals(task.status)) {
            Map<String, Object> r = new HashMap<>(task.result);
            r.put("status", "done");
            return ResponseEntity.ok(r);
        }
        if ("error".equals(task.status)) {
            return ResponseEntity.ok(Map.of("status", "error", "error", task.error));
        }
        return ResponseEntity.ok(Map.of("status", "pending"));
    }

    /** 后台出题任务：不占请求线程，在固定线程池内排队执行 */
    private void doGenerate(Long userId, String subject, String subjectType, Long courseId, QuizTask task) {
        try {
            // 查当前难度档位（无记录默认1）
            int difficulty = 1;
            try {
                Integer d = jdbc.queryForObject(
                        "SELECT difficulty FROM user_course_difficulty WHERE user_id = ? AND course_id = ?",
                        Integer.class, userId, courseId);
                if (d != null) difficulty = d;
            } catch (Exception ignored) {}

            // 查已完成章节
            List<Long> chapterIds = jdbc.queryForList(
                    "SELECT DISTINCT chapter_id FROM chapter_read_progress WHERE user_id = ? AND course_id = ?",
                    Long.class, userId, courseId);
            if (chapterIds.isEmpty()) {
                task.status = "error";
                task.error = "请先学习章节内容再答题";
                return;
            }

            // RAG 按章节检索
            String chapterContext = ragService.retrieveByChapters(chapterIds, subject);
            if (chapterContext == null || chapterContext.isEmpty()) {
                task.status = "error";
                task.error = "章节内容尚未准备好";
                return;
            }

            String prompt = buildAdaptivePrompt(subject, subjectType, difficulty, chapterContext);

            // 出题缓存：按 课程+难度+科目+题型+已学章节 的 MD5 指纹缓存 15 分钟，
            // 同课同档位学生并发刷题只调一次 AI，其余直接命中缓存
            List<Long> sortedChapters = new ArrayList<>(chapterIds);
            Collections.sort(sortedChapters);
            String cacheKey = md5(courseId + "|" + difficulty + "|" + subject + "|" + subjectType + "|" + sortedChapters);
            CachedQuestions cached = questionCache.get(cacheKey);
            List<Map<String, Object>> questions;
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                questions = cached.questions;
                System.out.println("=== 出题缓存命中: " + cacheKey);
            } else {
                // 排队式调用：高并发时阻塞等待令牌，而不是直接抛"繁忙"拒绝
                String raw = llmService.chatQueued("你是专业出题专家，只输出纯JSON数组，不要任何解释文字。", prompt);
                System.out.println("=== AI出题原始返回: " + (raw != null ? raw.substring(0, Math.min(300, raw.length())) : "null"));
                questions = parseQuestions(raw);
                if (!questions.isEmpty()) {
                    questionCache.put(cacheKey, new CachedQuestions(questions, System.currentTimeMillis() + 15 * 60 * 1000L));
                }
            }
            if (questions.isEmpty()) {
                task.status = "error";
                String fail = com.znxsgl.service.LlmService.getLastFailure();
                task.error = "题目生成失败，请稍后重试" + (fail == null || fail.isEmpty() ? "" : "（" + fail + "）");
                return;
            }

            int sessionNo = sessionMapper.countByUser(userId) + 1;
            QuizSession session = new QuizSession();
            session.setUserId(userId); session.setSubject(subject);
            session.setCourseId(courseId); session.setDifficulty(difficulty);
            session.setChapterScope(jsonValue(chapterIds));
            session.setSubjectType(subjectType); session.setSessionNo(sessionNo);
            session.setTotalQuestions(questions.size()); session.setStatus("pending");
            session.setCreatedAt(LocalDateTime.now());
            sessionMapper.insert(session);

            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", session.getId());
            result.put("sessionNo", sessionNo);
            result.put("subject", subject);
            result.put("difficulty", difficulty);
            result.put("questions", questions);
            task.result = result;
            task.status = "done";
        } catch (Exception e) {
            System.out.println("=== 后台出题异常: " + e.getMessage());
            e.printStackTrace();
            task.status = "error";
            task.error = "出题失败，请稍后重试";
        } finally {
            // 结果保留 10 分钟供前端轮询
            task.expireAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        }
    }

    /** 提交评估 */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        List<Map<String, Object>> answers = (List<Map<String, Object>>) body.get("answers");

        QuizSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权提交"));
        }

        int answered = 0, correct = 0, skip = 0, totalSec = 0;
        int earlyCorrect = 0, earlyTotal = 0, midCorrect = 0, midTotal = 0, lateCorrect = 0, lateTotal = 0;
        int total = answers.size(), third = total / 3;
        List<QuizAnswer> savedAnswers = new ArrayList<>();
        // 主观题（解析/填空）待AI语义判定：pendingJudge[i] = {answers下标, savedAnswers下标}
        List<int[]> pendingJudge = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            Map<String, Object> a = answers.get(i);
            QuizAnswer qa = new QuizAnswer();
            qa.setSessionId(sessionId);
            qa.setQuestionIndex(i + 1);
            qa.setQuestionType(safeStr(a, "questionType"));
            qa.setSubject(safeStr(a, "subject"));
            qa.setQuestion(safeStr(a, "question"));
            qa.setOptions(a.get("options") != null ? jsonValue(a.get("options")) : null);
            qa.setUserAnswer(safeStr(a, "userAnswer"));
            qa.setCorrectAnswer(safeStr(a, "correctAnswer"));
            int dur = a.get("durationSec") instanceof Number ? ((Number) a.get("durationSec")).intValue() : 0;
            qa.setDurationSec(dur);
            totalSec += dur;

            String ua = qa.getUserAnswer();
            String ca = qa.getCorrectAnswer();
            int isCorrect = -2;
            if (ua != null && !ua.isEmpty() && !"不会".equals(ua)) {
                answered++;
                if (isSubjective(qa.getQuestionType())) {
                    // 主观题答案不唯一，先占位，稍后由AI按理解程度判定
                    isCorrect = 0;
                    pendingJudge.add(new int[]{i, savedAnswers.size()});
                } else {
                    boolean ok = isAnswerCorrect(ua, ca, qa.getQuestionType());
                    isCorrect = ok ? 1 : 0;
                    if (ok) correct++;
                }
            } else if ("不会".equals(ua)) {
                isCorrect = -1; skip++;
            } else {
                skip++;
            }

            qa.setIsCorrect(isCorrect);
            qa.setModifiedCount(a.get("modifiedCount") instanceof Number ? ((Number) a.get("modifiedCount")).intValue() : 0);
            qa.setCreatedAt(LocalDateTime.now());
            answerMapper.insert(qa);
            savedAnswers.add(qa);
        }

        // 主观题AI语义判分：按要点理解程度判定是否正确（答案表述不唯一）
        if (!pendingJudge.isEmpty()) {
            Map<Integer, Boolean> judged = judgeSubjectiveAnswers(savedAnswers, pendingJudge);
            for (int[] p : pendingJudge) {
                boolean ok = judged.getOrDefault(p[0], false);
                QuizAnswer qa = savedAnswers.get(p[1]);
                qa.setIsCorrect(ok ? 1 : 0);
                if (ok) correct++;
                if (p[0] < third) earlyCorrect++;
                else if (p[0] >= total - third) lateCorrect++;
                else midCorrect++;
            }
        }

        for (int i = 0; i < answers.size(); i++) {
            if (i < third) earlyTotal++;
            else if (i >= total - third) lateTotal++;
            else midTotal++;
        }

        session.setAnsweredCount(answered); session.setCorrectCount(correct);
        session.setSkipCount(skip); session.setTotalDurationSec(totalSec);
        session.setStatus("completed");
        sessionMapper.updateById(session);

        String r1 = earlyTotal > 0 ? (earlyCorrect * 100 / earlyTotal) + "%" : "0%";
        String r2 = midTotal > 0 ? (midCorrect * 100 / midTotal) + "%" : "0%";
        String r3 = lateTotal > 0 ? (lateCorrect * 100 / lateTotal) + "%" : "0%";

        String evalPrompt = buildEvaluatePrompt(answers, userId, totalSec, r1, r2, r3, skip);
        // 排队式调用：并发提交评估时阻塞等待令牌，而不是抛"繁忙"拒绝（评分失败也不影响主流程）
        String evalRaw = llmService.chatQueued("你是大学生能力测评专家，只输出JSON，不要多余文字。评分严格稳定。", evalPrompt);
        System.out.println("=== AI评估返回: " + (evalRaw != null ? evalRaw.substring(0, Math.min(200, evalRaw.length())) : "null"));

        Map<String, Object> evalResult = parseEvalResult(evalRaw);
        if (evalResult != null) {
            // 修复AI返回"N"表示无法评估的情况
            Object scoresObj = evalResult.get("scores");
            if (scoresObj instanceof Map) {
                Map<String, Object> fixed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) scoresObj).entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof Number)) fixed.put(e.getKey(), 0); // "N"→0
                    else fixed.put(e.getKey(), v);
                }
                scoresObj = fixed;
                evalResult.put("scores", scoresObj);
            }
            session.setScores(jsonValue(scoresObj));
            session.setStrengths(jsonValue(evalResult.get("strengths")));
            session.setWeaknesses(jsonValue(evalResult.get("weaknesses")));
            session.setSuggestion((String) evalResult.get("suggestion"));
            session.setStudyPlan(jsonValue(evalResult.get("study_plan")));
            session.setStatus("evaluated");
        }
        sessionMapper.updateById(session);
        vectorizeSummary(userId, session, evalResult);

        // 自适应难度更新（失败不影响主流程）
        try {
            double correctRate = total > 0 ? (double) correct / total : 0;
            double skipRate = total > 0 ? (double) skip / total : 0;
            double avgDur = total > 0 ? (double) totalSec / total : 0;

            Long courseId = session.getCourseId();
            int currentDifficulty = session.getDifficulty() != null ? session.getDifficulty() : 1;
            if (courseId != null) {
                List<Map<String, Object>> recent = jdbc.queryForList(
                        "SELECT correct_count, answered_count, skip_count, total_duration_sec, total_questions " +
                        "FROM quiz_session WHERE user_id = ? AND course_id = ? AND status IN ('completed','evaluated') " +
                        "ORDER BY created_at DESC LIMIT 2", userId, courseId);
                int newDifficulty = decideDifficulty(currentDifficulty, recent, correctRate, skipRate, avgDur);
                jdbc.update("INSERT INTO user_course_difficulty(user_id, course_id, difficulty, updated_at) " +
                        "VALUES(?,?,?,NOW()) ON DUPLICATE KEY UPDATE difficulty = ?, updated_at = NOW()",
                        userId, courseId, newDifficulty, newDifficulty);
            }
        } catch (Exception e) {
            System.out.println("=== 难度更新失败: " + e.getMessage());
        }

        // 收集本次错题/空题，便于报告页直接展示
        List<Map<String, String>> wrongAnswers = new ArrayList<>();
        for (QuizAnswer qa : savedAnswers) {
            Integer ic = qa.getIsCorrect();
            if (ic != null && ic != 1) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("questionType", qa.getQuestionType());
                item.put("question", qa.getQuestion());
                item.put("userAnswer", qa.getUserAnswer());
                item.put("correctAnswer", qa.getCorrectAnswer());
                wrongAnswers.add(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId); result.put("correctCount", correct);
        result.put("skipCount", skip); result.put("totalDurationSec", totalSec);
        result.put("wrongAnswers", wrongAnswers);
        if (evalResult != null) result.putAll(evalResult);
        return ResponseEntity.ok(result);
    }

    // ===== Prompt =====

    private String buildAdaptivePrompt(String subject, String type, int difficulty, String chapterContext) {
        String difficultyDesc;
        String difficultyReq;
        if (difficulty == 1) {
            difficultyDesc = "基础档（概念定义和基本事实记忆题，答案能直接在章节内容中找到）";
            difficultyReq = "题目以概念定义和基本事实记忆为主，答案能直接在章节内容中找到";
        } else if (difficulty == 3) {
            difficultyDesc = "进阶档（综合分析题，跨知识点推理）";
            difficultyReq = "题目为综合分析题，需要跨知识点推理";
        } else {
            difficultyDesc = "中等档（理解应用题，需要简单推理）";
            difficultyReq = "题目为理解应用题，需要简单推理";
        }

        return String.format(
                "请生成15道题目：选择题7题、判断题3题、解析题3题、填空题2题。\n" +
                "科目：《%s》（%s类）\n" +
                "本次出题难度档位：%s\n" +
                "出题范围：仅基于以下章节内容出题，不要超纲：\n" +
                "---\n%s\n---\n" +
                "要求：\n" +
                "- %s\n" +
                "- 选择题4选项，正确答案末尾加*\n" +
                "- 判断题正确项加*\n" +
                "- 解析题和填空题答案附在题后\n" +
                "- 只输出JSON数组格式：[{\"type\":\"单选\",\"subject\":\"%s\",\"question\":\"...\",\"options\":[\"A.x\",\"B.y*\"]}," +
                "{\"type\":\"判断\",\"subject\":\"%s\",\"question\":\"...\",\"options\":[\"正确*\",\"错误\"]}," +
                "{\"type\":\"解析\",\"subject\":\"%s\",\"question\":\"...\"}," +
                "{\"type\":\"填空\",\"subject\":\"%s\",\"question\":\"...\",\"answer\":\"答案\"}]",
                subject, type, difficultyDesc, chapterContext, difficultyReq,
                subject, subject, subject, subject);
    }

    private String buildEvaluatePrompt(List<Map<String, Object>> answers, Long userId,
                                        int totalSec, String r1, String r2, String r3, int skip) {
        String studentName = userMapper.selectById(userId).getRealName();
        StringBuilder ansText = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            Map<String, Object> a = answers.get(i);
            int dur = a.get("durationSec") instanceof Number ? ((Number) a.get("durationSec")).intValue() : 0;
            ansText.append(String.format("Q%d: %s → %s (%ds)\n",
                    i + 1,
                    a.getOrDefault("question", ""),
                    a.getOrDefault("userAnswer", "未作答"),
                    dur));
        }
        return String.format(
                "学生：%s | 总题数：%d | 总耗时：%ds | 跳过：%d | 正确率趋势：%s/%s/%s\n作答：\n%s\n" +
                "输出JSON(scores必须根据实际作答表现评分0-10)：{\"scores\":{\"逻辑思维力\":0,\"判断决策力\":0,\"专注耐力\":0,\"专业学习力\":0,\"信息检索力\":0,\"自律执行力\":0}," +
                "\"strengths\":[],\"weaknesses\":[],\"suggestion\":\"\",\"study_plan\":[]}",
                studentName, answers.size(), totalSec, skip, r1, r2, r3, ansText);
    }

    // ===== 解析 =====

    private List<Map<String, Object>> parseQuestions(String raw) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        try {
            String clean = raw.trim();
            if (clean.startsWith("```")) {
                int s = clean.indexOf("["), e = clean.lastIndexOf("]");
                if (s >= 0 && e > s) clean = clean.substring(s, e + 1);
            }
            if (clean.startsWith("[") && !clean.endsWith("]")) {
                int last = clean.lastIndexOf("}");
                if (last > 0) clean = clean.substring(0, last + 1) + "]";
                else clean += "]";
            }
            JsonNode arr = json.readTree(clean);
            if (!arr.isArray()) return list;
            for (int i = 0; i < arr.size(); i++) {
                Map<String, Object> q = new LinkedHashMap<>();
                JsonNode n = arr.get(i);
                q.put("questionIndex", i + 1);
                q.put("questionType", n.path("type").asText());
                q.put("subject", n.path("subject").asText(""));
                q.put("question", n.path("question").asText());
                JsonNode opts = n.path("options");
                if (opts.isArray()) {
                    List<String> ol = new ArrayList<>();
                    for (JsonNode o : opts) {
                        String txt = o.asText();
                        ol.add(txt.replace("*", "")); // 去掉*显示
                        if (txt.endsWith("*")) q.put("correctAnswer", txt); // 存原始带*的作为正确答案
                    }
                    q.put("options", ol);
                }
                if (n.has("answer")) q.put("correctAnswer", n.path("answer").asText());
                list.add(q);
            }
        } catch (Exception e) {
            System.out.println("=== 题目解析失败: " + e.getMessage());
        }
        return list;
    }

    private Map<String, Object> parseEvalResult(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String clean = raw.trim();
            if (clean.startsWith("```")) {
                int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
                if (s >= 0 && e > s) clean = clean.substring(s, e + 1);
            }
            // 截断修复：补全未闭合的括号和字段
            if (!clean.endsWith("}")) {
                int lastQuote = clean.lastIndexOf("\"");
                clean = clean.substring(0, Math.max(lastQuote, clean.length() - 1)) + "]}";
            }
            if (!clean.startsWith("{")) clean = "{" + clean;
            return json.readValue(clean, Map.class);
        } catch (Exception e) {
            System.out.println("=== 评估解析失败: " + e.getMessage());
            return null;
        }
    }

    private boolean isAnswerCorrect(String user, String correct, String type) {
        if (user == null || correct == null) return false;
        String u = user.replace("*", "").replaceAll("[A-D]\\.\\s*", "").trim();
        String c = correct.replace("*", "").replaceAll("[A-D]\\.\\s*", "").trim();
        if ("判断".equals(type)) {
            u = u.replace("正确", "对").replace("错误", "错");
            c = c.replace("正确", "对").replace("错误", "错");
        }
        return u.equalsIgnoreCase(c);
    }

    /** 主观题类型：答案表述不唯一，需按语义理解程度判定 */
    private boolean isSubjective(String type) {
        return "解析".equals(type) || "简答".equals(type) || "填空".equals(type);
    }

    /**
     * 主观题AI语义判分：一次批量判定所有解析/填空题。
     * 不要求学生答案与参考答案逐字相同，按要点覆盖与理解程度判定。
     * AI 调用失败时降级为字符 bigram 相似度兜底。
     */
    private Map<Integer, Boolean> judgeSubjectiveAnswers(List<QuizAnswer> savedAnswers, List<int[]> pendingJudge) {
        Map<Integer, Boolean> result = new HashMap<>();

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < pendingJudge.size(); k++) {
            QuizAnswer qa = savedAnswers.get(pendingJudge.get(k)[1]);
            sb.append(String.format("%d.[%s] %s\n  学生回答：%s\n  参考答案：%s\n\n",
                    k + 1, qa.getQuestionType(), qa.getQuestion(),
                    qa.getUserAnswer() != null ? qa.getUserAnswer() : "未作答",
                    qa.getCorrectAnswer() != null ? qa.getCorrectAnswer() : ""));
        }
        String prompt =
                "你是阅卷老师。以下主观题（解析题/填空题）的参考答案仅供参考，学生回答的措辞不必与参考答案相同。\n" +
                "请逐题判断学生回答是否理解正确：只要关键要点/核心概念表达正确、语义一致即判 true；\n" +
                "表述完整准确判 true；只覆盖部分要点但无错误概念也判 true（理解即可）；出现概念错误或答非所问判 false。\n" +
                "只输出JSON数组，不要多余文字：[{\"index\":1,\"correct\":true},{\"index\":2,\"correct\":false}]\n\n" +
                sb;

        try {
            String raw = llmService.chatQueued("你是阅卷老师，只输出JSON数组。", prompt);
            System.out.println("=== 主观题AI判分返回: " + (raw != null ? raw.substring(0, Math.min(200, raw.length())) : "null"));
            JsonNode arr = readJsonArray(raw);
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    int idx = n.path("index").asInt(-1) - 1; // 转回0基下标
                    if (idx >= 0 && idx < pendingJudge.size()) {
                        result.put(pendingJudge.get(idx)[0], n.path("correct").asBoolean(false));
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("=== 主观题AI判分失败，降级相似度判定: " + e.getMessage());
        }

        // 未被AI判定的题目（调用失败/解析失败）用相似度兜底
        for (int k = 0; k < pendingJudge.size(); k++) {
            int answerIdx = pendingJudge.get(k)[0];
            if (!result.containsKey(answerIdx)) {
                QuizAnswer qa = savedAnswers.get(pendingJudge.get(k)[1]);
                result.put(answerIdx, similarityCorrect(qa.getUserAnswer(), qa.getCorrectAnswer()));
            }
        }
        return result;
    }

    /** 相似度兜底判定：字符 bigram Dice 系数 >= 0.35 视为理解正确 */
    private boolean similarityCorrect(String user, String correct) {
        if (user == null || correct == null || user.trim().isEmpty() || correct.trim().isEmpty()) return false;
        return diceSimilarity(user, correct) >= 0.35;
    }

    /** 字符 bigram Dice 相似度（0~1） */
    private double diceSimilarity(String a, String b) {
        String s1 = a.replaceAll("\\s+", "");
        String s2 = b.replaceAll("\\s+", "");
        if (s1.length() < 2 || s2.length() < 2) {
            return s1.equals(s2) ? 1.0 : 0.0;
        }
        Map<String, Integer> m1 = new HashMap<>();
        for (int i = 0; i < s1.length() - 1; i++) {
            m1.merge(s1.substring(i, i + 2), 1, Integer::sum);
        }
        int overlap = 0, total = 0;
        Map<String, Integer> m2 = new HashMap<>();
        for (int i = 0; i < s2.length() - 1; i++) {
            m2.merge(s2.substring(i, i + 2), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : m1.entrySet()) {
            overlap += Math.min(e.getValue(), m2.getOrDefault(e.getKey(), 0));
        }
        for (int v : m1.values()) total += v;
        for (int v : m2.values()) total += v;
        return total == 0 ? 0.0 : 2.0 * overlap / total;
    }

    /** 从AI返回文本中提取JSON数组（容错处理） */
    private JsonNode readJsonArray(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String clean = raw.trim();
            if (clean.startsWith("```")) {
                int s = clean.indexOf("["), e = clean.lastIndexOf("]");
                if (s >= 0 && e > s) clean = clean.substring(s, e + 1);
            }
            int s = clean.indexOf("["), e = clean.lastIndexOf("]");
            if (s < 0 || e <= s) return null;
            return json.readTree(clean.substring(s, e + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private void vectorizeSummary(Long userId, QuizSession session, Map<String, Object> eval) {
        try {
            if (eval == null) return;
            String summary = String.format("学生第%d次测评（%s）：六维分已保存。%s",
                    session.getSessionNo(), session.getSubject(),
                    eval.getOrDefault("suggestion", ""));
            jdbc.update("INSERT INTO document_vector (course_name, doc_name, content_chunk, created_at) VALUES (?,?,?,NOW())",
                    "学生测评记录", "测评#" + session.getSessionNo(), summary);
        } catch (Exception ignored) {}
    }

    /** MD5 指纹（用于出题缓存 key） */
    private String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private String safeStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }

    private String jsonValue(Object obj) {
        try { return json.writeValueAsString(obj); } catch (Exception e) { return "null"; }
    }

    /**
     * 保守策略：连续2次同方向才升降
     * 升档条件：correctRate>=0.8 AND skipRate<=0.1 AND avgDur<=60
     * 降档条件：correctRate<=0.4 OR skipRate>=0.3
     */
    private int decideDifficulty(int current, List<Map<String, Object>> recent,
                                  double correctRate, double skipRate, double avgDur) {
        if (recent.size() < 2) return current;

        boolean thisUp = correctRate >= 0.8 && skipRate <= 0.1 && avgDur <= 60;
        boolean thisDown = correctRate <= 0.4 || skipRate >= 0.3;

        Map<String, Object> last = recent.get(1);
        int lastTotal = toInt(last.get("total_questions"));
        double lastCorrectRate = lastTotal > 0 ? (double) toInt(last.get("correct_count")) / lastTotal : 0;
        double lastSkipRate = lastTotal > 0 ? (double) toInt(last.get("skip_count")) / lastTotal : 0;
        double lastAvgDur = lastTotal > 0 ? (double) toInt(last.get("total_duration_sec")) / lastTotal : 0;
        boolean lastUp = lastCorrectRate >= 0.8 && lastSkipRate <= 0.1 && lastAvgDur <= 60;
        boolean lastDown = lastCorrectRate <= 0.4 || lastSkipRate >= 0.3;

        if (thisUp && lastUp && current < 3) return current + 1;
        if (thisDown && lastDown && current > 1) return current - 1;
        return current;
    }

    private int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    // ===== 错题解析（带缓存） =====

    /** 错题解析：缓存分析结果，错题数不变时直接返回缓存 */
    @PostMapping("/wrong-analysis")
    public ResponseEntity<Map<String, Object>> wrongAnalysis(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String studentName = getUserRealName(userId);

        try {
            // 1. 查询最新测评时间戳作为缓存key（只在新测试时失效）
            String hashSQL = "SELECT COALESCE(MAX(s.id), 0) FROM quiz_session s " +
                    "JOIN quiz_answer a ON a.session_id = s.id " +
                    "WHERE s.user_id = ? AND (a.is_correct = 0 OR a.is_correct = -1 OR a.is_correct = -2)";
            String currentHash = String.valueOf(jdbc.queryForObject(hashSQL, Integer.class, userId));

            // 2. 检查缓存
            Map<String, Object> cached = null;
            try {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT cache_hash, analysis_json FROM wrong_analysis_cache WHERE user_id = ?", userId);
                String cachedHash = String.valueOf(row.get("cache_hash"));
                String cachedJson = (String) row.get("analysis_json");
                if (currentHash.equals(cachedHash) && cachedJson != null && !cachedJson.isEmpty()) {
                    cached = json.readValue(cachedJson, Map.class);
                }
            } catch (Exception ignored) {}

            if (cached != null) {
                cached.put("cached", true);
                System.out.println("=== 错题解析: 使用缓存（session_hash=" + currentHash + "）");
                return ResponseEntity.ok(cached);
            }

            // 3. 新测试→重新分析
            List<QuizAnswer> wrongList = answerMapper.findWrongByUser(userId);
            if (wrongList.isEmpty()) {
                return ResponseEntity.ok(Map.of("wrongCount", 0, "studentName", studentName,
                        "summary", studentName + "同学暂时没有错题，表现优秀！"));
            }

            // 限制单次分析错题数量，避免 prompt 过长导致 AI 接口超时
            final int MAX_WRONG_COUNT = 30;
            boolean truncated = false;
            if (wrongList.size() > MAX_WRONG_COUNT) {
                wrongList = wrongList.subList(0, MAX_WRONG_COUNT);
                truncated = true;
            }

            // 按科目分组（subject为空时兜底使用session.subject）
            Map<String, List<QuizAnswer>> bySubject = new LinkedHashMap<>();
            for (QuizAnswer a : wrongList) {
                String raw = a.getSubject();
                String subj;
                if (raw != null && !raw.trim().isEmpty()) {
                    subj = raw.trim();
                } else {
                    // 兜底：从session获取科目
                    QuizSession sess = sessionMapper.selectById(a.getSessionId());
                    String sessSubj = (sess != null) ? sess.getSubject() : null;
                    subj = (sessSubj != null && !sessSubj.trim().isEmpty()) ? sessSubj.trim() : "其他";
                }
                bySubject.computeIfAbsent(subj, k -> new ArrayList<>()).add(a);
            }

            List<Map<String, Object>> stats = new ArrayList<>();
            for (String subj : bySubject.keySet()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("subject", subj);
                s.put("count", bySubject.get(subj).size());
                stats.add(s);
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("你是大学生学习导师。请分析以下学生的错题，对每题给出：\n");
            prompt.append("1. 知识点解析  2. 学生错误原因  3. 改进建议\n\n");
            prompt.append("学生：").append(studentName).append("\n");
            prompt.append("错题总数：").append(wrongList.size()).append("，覆盖科目：");
            prompt.append(String.join("、", bySubject.keySet())).append("\n\n");

            int num = 1;
            for (Map.Entry<String, List<QuizAnswer>> entry : bySubject.entrySet()) {
                prompt.append("== ").append(entry.getKey()).append(" ==\n");
                for (QuizAnswer a : entry.getValue()) {
                    String status = a.getIsCorrect() != null && a.getIsCorrect() == -2 ? "（不会）" :
                            a.getIsCorrect() != null && a.getIsCorrect() == -1 ? "（跳过）" : "（答错）";
                    prompt.append(String.format("%d.[%s] %s %s\n  你的答案：%s\n  正确答案：%s\n  用时：%ds\n\n",
                            num++, a.getQuestionType(), a.getQuestion(), status,
                            a.getUserAnswer() != null ? a.getUserAnswer() : "未作答",
                            a.getCorrectAnswer() != null ? a.getCorrectAnswer() : "",
                            a.getDurationSec() != null ? a.getDurationSec() : 0));
                }
            }
            prompt.append("输出JSON：{\"summary\":\"整体评价(100字内)\",\"bySubject\":[{\"subject\":\"科目\",\"analysis\":\"该科分析(150字)\",\"items\":[{\"question\":\"题目\",\"knowledge\":\"知识点\",\"errorReason\":\"错误原因\",\"improve\":\"改进建议\"}]}]}");

            System.out.println("=== 错题解析 Prompt 长度: " + prompt.length());
            String aiResp;
            try {
                aiResp = llmService.chat(userId, "你是一个学习分析助手，请用中文回答。", prompt.toString());
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                System.out.println("=== 错题解析 AI 调用失败: " + msg);
                if (msg != null && msg.contains("超时")) {
                    return ResponseEntity.ok(Map.of("error", "AI 分析响应超时，请稍后重试"));
                }
                if (msg != null && (msg.contains("频繁") || msg.contains("繁忙"))) {
                    return ResponseEntity.ok(Map.of("error", msg));
                }
                return ResponseEntity.ok(Map.of("error", "AI 分析服务暂时不可用，请稍后重试"));
            }
            System.out.println("=== AI错题解析返回: " + (aiResp != null ? aiResp.substring(0, Math.min(300, aiResp.length())) : "null"));

            if (aiResp == null || aiResp.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of("error", "AI 分析服务暂时不可用，请稍后重试"));
            }

            Map<String, Object> eval = parseEvalResult(aiResp);
            // 不依赖 AI 返回的 items 顺序，用 wrongList 重建（确保 answerId 100%准确）
            List<Map<String, Object>> rebuiltSubjects = new ArrayList<>();
            for (String subj : bySubject.keySet()) {
                Map<String, Object> rs = new LinkedHashMap<>();
                rs.put("subject", subj);
                rs.put("count", bySubject.get(subj).size());
                List<Map<String, Object>> rebuiltItems = new ArrayList<>();
                for (QuizAnswer a : bySubject.get(subj)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("question", a.getQuestion() != null ? a.getQuestion() : "");
                    item.put("answerId", a.getId());
                    // 从 AI 结果中找到对应分析
                    String aiKnowledge = "", aiError = "", aiImprove = "";
                    if (eval != null && eval.get("bySubject") instanceof List) {
                        for (Object so : (List<?>) eval.get("bySubject")) {
                            Map<String, Object> aiSubj = (Map<String, Object>) so;
                            if (subj.equals(aiSubj.get("subject"))) {
                                if (aiSubj.get("items") instanceof List) {
                                    for (Object io : (List<?>) aiSubj.get("items")) {
                                        Map<String, Object> aiItem = (Map<String, Object>) io;
                                        String aiQ = (String) aiItem.get("question");
                                        String dbQ = a.getQuestion();
                                        if (aiQ != null && dbQ != null &&
                                                (dbQ.contains(aiQ) || aiQ.contains(dbQ.substring(0, Math.min(20, dbQ.length()))))) {
                                            aiKnowledge = safeStr(aiItem, "knowledge");
                                            aiError = safeStr(aiItem, "errorReason");
                                            aiImprove = safeStr(aiItem, "improve");
                                            break;
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                    item.put("knowledge", aiKnowledge.isEmpty() ? "（分析生成中）" : aiKnowledge);
                    item.put("errorReason", aiError.isEmpty() ? "（分析生成中）" : aiError);
                    item.put("improve", aiImprove.isEmpty() ? "（分析生成中）" : aiImprove);
                    rebuiltItems.add(item);
                }
                rs.put("items", rebuiltItems);
                rebuiltSubjects.add(rs);
            }

            String summary = eval != null && eval.get("summary") != null
                    ? eval.get("summary").toString() : aiResp;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("wrongCount", wrongList.size());
            result.put("bySubject", rebuiltSubjects);  // 使用重建的
            result.put("studentName", studentName);
            result.put("summary", summary);
            result.put("cached", false);
            result.put("truncated", truncated);

            // 4. 写入缓存
            try {
                String jsonStr = trimEvalResult(json.writeValueAsString(result));
                jdbc.update("REPLACE INTO wrong_analysis_cache (user_id, cache_hash, analysis_json, updated_at) VALUES (?,?,?,NOW())",
                        userId, currentHash, jsonStr);
                System.out.println("=== 错题解析: 缓存已更新（session_hash=" + currentHash + "）");
            } catch (Exception ignored) {}

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok(Map.of("error", "分析失败：" + e.getMessage()));
        }
    }

    /** 截断过长的 JSON（防缓存撑爆） */
    private String trimEvalResult(String json) {
        if (json != null && json.length() > 50000) {
            return json.substring(0, 50000);
        }
        return json;
    }

    // ===== 收藏 & 明白标记 =====

    @PostMapping("/toggle-bookmark")
    public ResponseEntity<Map<String, Object>> toggleBookmark(Authentication auth, @RequestBody Map<String, Object> body) {
        Long userId = (Long) auth.getPrincipal();
        try {
            String question = safeStr(body, "question");
            String subject = safeStr(body, "subject");
            var existing = bookmarkMapper.findByUserAndQuestion(userId, question);
            if (existing != null) {
                bookmarkMapper.deleteById(existing.getId());
                return ResponseEntity.ok(Map.of("bookmarked", false, "msg", "已取消收藏"));
            }
            QuestionBookmark bm = new QuestionBookmark();
            bm.setUserId(userId);
            bm.setQuestion(question);
            bm.setSubject(subject);
            bm.setQuestionType(safeStr(body, "questionType"));
            bm.setUserAnswer(safeStr(body, "userAnswer"));
            bm.setCorrectAnswer(safeStr(body, "correctAnswer"));
            bm.setKnowledge(safeStr(body, "knowledge"));
            bm.setErrorReason(safeStr(body, "errorReason"));
            bm.setImprove(safeStr(body, "improve"));
            bookmarkMapper.insert(bm);
            // 收藏后也从错题分析移除（优先答案ID匹配）
            Object aid = body.get("answerId");
            if (aid instanceof Number && ((Number) aid).longValue() > 0) {
                jdbc.update("UPDATE quiz_answer SET understood = 1 WHERE id = ?", ((Number) aid).longValue());
            } else if (question != null && !question.isEmpty()) {
                String key = question.length() > 60
                        ? question.substring(0, 30) + question.substring(question.length() - 30)
                        : question;
                jdbc.update("UPDATE quiz_answer a JOIN quiz_session s ON a.session_id = s.id " +
                        "SET a.understood = 1 WHERE s.user_id = ? " +
                        "AND (a.question LIKE CONCAT('%',?,'%') OR ? LIKE CONCAT('%',a.question,'%'))",
                        userId, key, key);
            }
            jdbc.update("DELETE FROM wrong_analysis_cache WHERE user_id = ?", userId);
            return ResponseEntity.ok(Map.of("bookmarked", true, "msg", "已收藏"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mark-understood")
    public ResponseEntity<Map<String, Object>> markUnderstood(Authentication auth, @RequestBody Map<String, Object> body) {
        Long userId = (Long) auth.getPrincipal();
        try {
            // 优先用 answerId 精确匹配
            Object aid = body.get("answerId");
            int updated = 0;
            if (aid instanceof Number && ((Number) aid).longValue() > 0) {
                updated = jdbc.update("UPDATE quiz_answer SET understood = 1 WHERE id = ?", ((Number) aid).longValue());
            } else {
                // 降级：question 文本模糊匹配
                String question = safeStr(body, "question");
                String key = question.length() > 60
                        ? question.substring(0, 30) + question.substring(question.length() - 30)
                        : question;
                updated = jdbc.update("UPDATE quiz_answer a JOIN quiz_session s ON a.session_id = s.id " +
                        "SET a.understood = 1 WHERE s.user_id = ? " +
                        "AND (a.question LIKE CONCAT('%',?,'%') OR ? LIKE CONCAT('%',a.question,'%'))",
                        userId, key, key);
            }
            jdbc.update("DELETE FROM wrong_analysis_cache WHERE user_id = ?", userId);
            System.out.println("=== mark-understood: userId=" + userId + " updated=" + updated);
            return ResponseEntity.ok(Map.of("msg", "已标记", "updated", updated));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> quizCount(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM quiz_session WHERE user_id = ?", Integer.class, userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /** 累计答题统计：完成题目数、正确题目数、正确率 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> quizStats(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COALESCE(SUM(answered_count), 0) AS totalAnswered, " +
                "COALESCE(SUM(correct_count), 0) AS totalCorrect " +
                "FROM quiz_session WHERE user_id = ? AND status IN ('completed','evaluated')",
                userId);
        int totalAnswered = ((Number) row.get("totalAnswered")).intValue();
        int totalCorrect = ((Number) row.get("totalCorrect")).intValue();
        double accuracy = totalAnswered > 0 ? (double) totalCorrect * 100 / totalAnswered : 0;
        return ResponseEntity.ok(Map.of(
                "totalAnswered", totalAnswered,
                "totalCorrect", totalCorrect,
                "accuracy", String.format(Locale.getDefault(), "%.1f%%", accuracy)));
    }

    @GetMapping("/bookmarks")
    public ResponseEntity<List<QuestionBookmark>> bookmarks(Authentication auth) {
        return ResponseEntity.ok(bookmarkMapper.findByUser((Long) auth.getPrincipal()));
    }

    /**
     * 复习加强：返回最近一次测评的薄弱点、学习计划及推荐复习课程
     */
    @GetMapping("/review-plan")
    public ResponseEntity<Map<String, Object>> reviewPlan(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        try {
            Map<String, Object> latest = jdbc.queryForMap(
                    "SELECT id, course_id, subject, subject_type, difficulty, scores, weaknesses, " +
                    "suggestion, study_plan, correct_count, total_questions " +
                    "FROM quiz_session WHERE user_id = ? AND status = 'evaluated' " +
                    "ORDER BY created_at DESC LIMIT 1", userId);

            Long courseId = latest.get("course_id") instanceof Number
                    ? ((Number) latest.get("course_id")).longValue() : null;
            String subject = latest.get("subject") != null ? latest.get("subject").toString() : "";

            // 解析薄弱点
            List<String> weaknesses = parseJsonStringList(latest.get("weaknesses"));
            // 解析学习计划
            List<String> studyPlan = parseJsonStringList(latest.get("study_plan"));
            // 解析能力维度
            Map<String, Object> scores = new LinkedHashMap<>();
            try {
                Object scoresObj = latest.get("scores");
                if (scoresObj != null && !scoresObj.toString().isEmpty()) {
                    scores = json.readValue(scoresObj.toString(), Map.class);
                }
            } catch (Exception e) {
                System.out.println("=== review-plan 解析 scores 失败: " + e.getMessage());
            }

            // 推荐复习：取该课程最近未充分掌握的章节（未读过或得分最低的维度）
            List<Map<String, Object>> recommended = new ArrayList<>();
            if (courseId != null) {
                try {
                    List<Map<String, Object>> chapters = jdbc.queryForList(
                            "SELECT cc.id, cc.chapter_no, cc.chapter_name, cc.description, " +
                            "IF(crp.chapter_id IS NULL, 0, 1) AS completed " +
                            "FROM course_chapter cc " +
                            "LEFT JOIN chapter_read_progress crp ON crp.chapter_id = cc.id AND crp.user_id = ? " +
                            "WHERE cc.course_id = ? ORDER BY cc.chapter_no",
                            userId, courseId);
                    // 优先推荐未完成的章节，最多 3 个
                    int count = 0;
                    for (Map<String, Object> ch : chapters) {
                        Integer completed = (Integer) ch.get("completed");
                        if (completed != null && completed == 0 && count < 3) {
                            recommended.add(ch);
                            count++;
                        }
                    }
                    // 未完成的不足 3 个则补齐前 3 个章节
                    if (recommended.size() < 3) {
                        for (Map<String, Object> ch : chapters) {
                            if (!recommended.contains(ch) && recommended.size() < 3) {
                                recommended.add(ch);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("=== review-plan 推荐章节失败: " + e.getMessage());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("subject", subject);
            result.put("courseId", courseId);
            result.put("difficulty", latest.get("difficulty"));
            result.put("accuracy", buildAccuracy(latest));
            result.put("scores", scores);
            result.put("weaknesses", weaknesses);
            result.put("suggestion", latest.get("suggestion") != null ? latest.get("suggestion") : "");
            result.put("studyPlan", studyPlan);
            result.put("recommendedChapters", recommended);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.out.println("=== review-plan 查询失败: " + e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "subject", "",
                    "courseId", null,
                    "accuracy", "0%",
                    "scores", Map.of(),
                    "weaknesses", List.of(),
                    "suggestion", "暂无测评记录，先去专注刷题完成一次测评吧。",
                    "studyPlan", List.of(),
                    "recommendedChapters", List.of()));
        }
    }

    private String buildAccuracy(Map<String, Object> session) {
        Object correct = session.get("correct_count");
        Object total = session.get("total_questions");
        int c = correct instanceof Number ? ((Number) correct).intValue() : 0;
        int t = total instanceof Number ? ((Number) total).intValue() : 0;
        if (t <= 0) return "0%";
        return (c * 100 / t) + "%";
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonStringList(Object raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.toString().isEmpty()) return list;
        try {
            Object parsed = json.readValue(raw.toString(), Object.class);
            if (parsed instanceof List) {
                for (Object o : (List<Object>) parsed) {
                    if (o != null) list.add(o.toString());
                }
            }
        } catch (Exception e) {
            System.out.println("=== review-plan 解析 JSON 列表失败: " + e.getMessage());
        }
        return list;
    }

    private String getUserRealName(Long userId) {
        try {
            var u = userMapper.selectById(userId);
            return u != null && u.getRealName() != null ? u.getRealName() : "同学";
        } catch (Exception e) { return "同学"; }
    }
}
