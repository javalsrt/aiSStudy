package com.znxsgl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 题目生成服务：并行拆分题型 + 单飞锁 + Redis 共享缓存 + 题库持久化。
 *
 * 生成流程：
 *  1. 先用 cacheKey 查题库（Redis -> MySQL）；
 *  2. 未命中则本地 singleflight，保证同一份题只生成一次；
 *  3. 按 单选/判断/解析/填空 四类并行调用 LLM；
 *  4. 合并后写入 Redis + question_bank 表。
 */
@Service
public class QuestionGenerationService {

    private static final String SYSTEM_PROMPT = "你是专业出题专家，只输出纯JSON数组，不要任何解释文字。";
    private static final int PROMPT_CONTEXT_MAX_LEN = 4000;

    private final LlmService llmService;
    private final RagService ragService;
    private final JdbcTemplate jdbc;
    private final QuestionBankService bankService;
    private final ObjectMapper json = new ObjectMapper();

    /** 同一 cacheKey 的生成任务只保留一个 */
    private final ConcurrentHashMap<String, CompletableFuture<List<Map<String, Object>>>> inFlight = new ConcurrentHashMap<>();

    /** 调度线程：只负责推进 singleflight 任务，不直接做 LLM 调用 */
    private final ExecutorService dispatcherPool = Executors.newFixedThreadPool(4, daemonFactory("quiz-dispatch-"));
    /** LLM 线程池：并行调用大模型和各题型 */
    private final ExecutorService llmPool = Executors.newFixedThreadPool(16, daemonFactory("quiz-llm-"));
    /** 预生成线程池：避免和 singleflight 调度线程互相等待 */
    private final ExecutorService prewarmExecutor = Executors.newFixedThreadPool(2, daemonFactory("quiz-prewarm-"));

    public QuestionGenerationService(LlmService llmService, RagService ragService,
                                     JdbcTemplate jdbc, QuestionBankService bankService) {
        this.llmService = llmService;
        this.ragService = ragService;
        this.jdbc = jdbc;
        this.bankService = bankService;
    }

    /**
     * 获取一套题目：优先缓存/题库，未命中则按题型并行生成。
     */
    public List<Map<String, Object>> getOrGenerate(Long courseId, String subject,
                                                   String subjectType, int difficulty,
                                                   List<Long> chapterIds) {
        List<Long> sortedChapters = new ArrayList<>(chapterIds != null ? chapterIds : Collections.emptyList());
        Collections.sort(sortedChapters);
        String cacheKey = md5(courseId + "|" + difficulty + "|" + subject + "|" + subjectType + "|" + sortedChapters + "|" + contentVersion(courseId));

        // 1. 题库/Redis 缓存命中
        List<Map<String, Object>> cached = bankService.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            System.out.println("=== 题库命中: " + cacheKey + "，题目数=" + cached.size());
            return cached;
        }

        // 2. 单飞锁：同一 key 只有一个任务真正生成
        CompletableFuture<List<Map<String, Object>>> newFuture = new CompletableFuture<>();
        CompletableFuture<List<Map<String, Object>>> existing = inFlight.putIfAbsent(cacheKey, newFuture);
        CompletableFuture<List<Map<String, Object>>> future = existing != null ? existing : newFuture;

        if (existing == null) {
            final CompletableFuture<List<Map<String, Object>>> f = newFuture;
            dispatcherPool.submit(() -> {
                try {
                    // 双检：等锁期间可能已经被其他实例生成好了
                    List<Map<String, Object>> again = bankService.get(cacheKey);
                    if (again != null && !again.isEmpty()) {
                        f.complete(again);
                        return;
                    }
                    String context = loadContext(subject, sortedChapters);
                    if (context == null || context.trim().isEmpty()) {
                        throw new IllegalStateException("章节内容尚未准备好");
                    }
                    List<Map<String, Object>> questions = generateParallel(subject, subjectType, difficulty, context);
                    if (questions != null && !questions.isEmpty()) {
                        bankService.save(cacheKey, courseId, subject, difficulty, questions);
                    }
                    f.complete(questions);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    inFlight.remove(cacheKey, f);
                }
            });
        }

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new RuntimeException("题目生成超时，请稍后重试");
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            throw new RuntimeException(cause != null && cause.getMessage() != null ? cause.getMessage() : "题目生成失败", cause);
        }
    }

    /**
     * 按题型并行生成：单选 7、判断 3、解析 3、填空 2。
     */
    private List<Map<String, Object>> generateParallel(String subject, String subjectType,
                                                       int difficulty, String context) {
        Map<String, Integer> plan = new LinkedHashMap<>();
        plan.put("单选", 7);
        plan.put("判断", 3);
        plan.put("解析", 3);
        plan.put("填空", 2);

        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : plan.entrySet()) {
            final String type = entry.getKey();
            final int count = entry.getValue();
            futures.add(CompletableFuture.supplyAsync(
                    () -> generateForType(subject, subjectType, type, count, difficulty, context),
                    llmPool));
        }

        List<Map<String, Object>> all = new ArrayList<>();
        for (CompletableFuture<List<Map<String, Object>>> future : futures) {
            try {
                List<Map<String, Object>> part = future.get(3, TimeUnit.MINUTES);
                if (part != null && !part.isEmpty()) {
                    all.addAll(part);
                }
            } catch (Exception e) {
                System.out.println("=== 并行题型生成超时/异常: " + e.getMessage());
            }
        }

        for (int i = 0; i < all.size(); i++) {
            all.get(i).put("questionIndex", i + 1);
        }
        return all;
    }

    private List<Map<String, Object>> generateForType(String subject, String subjectType,
                                                      String type, int count,
                                                      int difficulty, String context) {
        String prompt = buildTypePrompt(subject, subjectType, type, count, difficulty, context);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String raw = llmService.chatQueued(SYSTEM_PROMPT, prompt);
                List<Map<String, Object>> questions = parseQuestions(raw);
                if (!questions.isEmpty()) {
                    System.out.println("=== 并行出题[" + type + "]成功 " + questions.size() + " 道");
                    return questions;
                }
                System.out.println("=== 并行出题[" + type + "]解析为空，第 " + (attempt + 1) + " 次");
            } catch (Exception e) {
                System.out.println("=== 并行出题[" + type + "]异常: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private String buildTypePrompt(String subject, String subjectType, String type,
                                   int count, int difficulty, String context) {
        String difficultyDesc = switch (difficulty) {
            case 1 -> "基础档（概念记忆，答案能直接在章节内容中找到）";
            case 3 -> "进阶档（综合分析，需要跨知识点推理）";
            default -> "中等档（理解应用，需要简单推理）";
        };
        String safeContext = truncate(context, PROMPT_CONTEXT_MAX_LEN);

        String schema;
        String rule;
        if ("单选".equals(type)) {
            schema = "[{\"type\":\"单选\",\"subject\":\"" + subject + "\",\"question\":\"...\",\"options\":[\"A.xxx\",\"B.xxx*\"]}]";
            rule = "- 选择题4个选项，正确答案末尾加 *";
        } else if ("判断".equals(type)) {
            schema = "[{\"type\":\"判断\",\"subject\":\"" + subject + "\",\"question\":\"...\",\"options\":[\"正确*\",\"错误\"]}]";
            rule = "- 判断题正确项加 *";
        } else if ("解析".equals(type)) {
            schema = "[{\"type\":\"解析\",\"subject\":\"" + subject + "\",\"question\":\"...\",\"answer\":\"...\"}]";
            rule = "- 解析题答案附在题后";
        } else {
            schema = "[{\"type\":\"填空\",\"subject\":\"" + subject + "\",\"question\":\"...\",\"answer\":\"...\"}]";
            rule = "- 填空题答案附在题后";
        }

        return String.format(
                "请生成 %d 道%s题。\n" +
                "科目：《%s》（%s类）\n" +
                "难度：%s\n" +
                "出题范围：仅基于以下章节内容出题，不要超纲：\n" +
                "---\n%s\n---\n" +
                "要求：\n%s\n" +
                "- 涉及数学公式时必须使用 LaTeX 语法：行内公式用 $...$ 包裹（如 $x^2+1$），独立公式用 $$...$$ 包裹，分数用 \\frac{}{}，禁止用 x^2/2! 这类纯文本形式表达公式。\n" +
                "- 只输出纯JSON数组，不要解释，不要Markdown代码块。\n" +
                "- 格式示例：%s",
                count, type, subject, subjectType, difficultyDesc, safeContext, rule, schema);
    }

    /** 章节/课时内容指纹：内容变化后 cacheKey 自动变化，避免返回旧题。 */
    private String contentVersion(Long courseId) {
        try {
            String chapterVersion = jdbc.queryForObject(
                    "SELECT COALESCE(DATE_FORMAT(MAX(update_time), '%Y%m%d%H%i%s'), '') " +
                    "FROM course_chapter WHERE course_id = ? AND deleted = 0",
                    String.class, courseId);
            String lessonVersion = jdbc.queryForObject(
                    "SELECT COALESCE(DATE_FORMAT(MAX(l.update_time), '%Y%m%d%H%i%s'), '') " +
                    "FROM course_lesson l JOIN course_chapter c ON l.chapter_id = c.id " +
                    "WHERE c.course_id = ? AND l.deleted = 0",
                    String.class, courseId);
            return (chapterVersion == null ? "" : chapterVersion) + "-" + (lessonVersion == null ? "" : lessonVersion);
        } catch (Exception e) {
            return "";
        }
    }
    /** 与 QuizController 原有解析逻辑保持一致。 */
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
                q.put("subject", n.path("subject").asText(subjectOrEmpty(n, "subject")));
                q.put("question", n.path("question").asText());
                JsonNode opts = n.path("options");
                if (opts.isArray()) {
                    List<String> ol = new ArrayList<>();
                    for (JsonNode o : opts) {
                        String txt = o.asText();
                        ol.add(txt.replace("*", ""));
                        if (txt.endsWith("*")) {
                            q.put("correctAnswer", txt);
                        }
                    }
                    q.put("options", ol);
                }
                if (n.has("answer")) {
                    q.put("correctAnswer", n.path("answer").asText());
                }
                list.add(q);
            }
        } catch (Exception e) {
            System.out.println("=== 题目解析失败: " + e.getMessage());
        }
        return list;
    }

    private String subjectOrEmpty(JsonNode n, String field) {
        return n.path(field).asText("");
    }

    private String loadContext(String subject, List<Long> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return "";
        }
        try {
            String context = ragService.retrieveByChapters(chapterIds, subject);
            if (context != null && !context.trim().isEmpty()) {
                return truncate(context, PROMPT_CONTEXT_MAX_LEN);
            }
        } catch (Exception e) {
            System.out.println("=== RAG 检索异常: " + e.getMessage());
        }
        try {
            String placeholders = chapterIds.stream().map(x -> "?").collect(Collectors.joining(","));
            List<String> contents = jdbc.queryForList(
                    "SELECT content FROM course_lesson WHERE chapter_id IN (" + placeholders + ") " +
                    "AND content IS NOT NULL AND content != ''",
                    String.class, chapterIds.toArray());
            if (contents != null && !contents.isEmpty()) {
                return truncate(String.join("\n---\n", contents), PROMPT_CONTEXT_MAX_LEN);
            }
        } catch (Exception e) {
            System.out.println("=== 课时内容回退加载失败: " + e.getMessage());
        }
        return "";
    }

    /**
     * 预生成指定课程题库（异步）：覆盖难度 1~3，已存在的直接跳过。
     */
    public void prewarmCourseAsync(Long courseId) {
        prewarmExecutor.submit(() -> {
            try {
                Map<String, Object> course = jdbc.queryForMap(
                        "SELECT course_name, course_type FROM course WHERE id = ?", courseId);
                String subject = String.valueOf(course.getOrDefault("course_name", ""));
                String courseType = String.valueOf(course.getOrDefault("course_type", ""));
                String subjectType = courseType.contains("公共") ? "公共" : "专业";
                List<Long> chapterIds = jdbc.queryForList(
                        "SELECT id FROM course_chapter WHERE course_id = ? AND deleted = 0 AND status = 1 " +
                        "ORDER BY sort_order, id", Long.class, courseId);
                if (chapterIds.isEmpty() || subject.isEmpty()) {
                    return;
                }
                for (int difficulty = 1; difficulty <= 3; difficulty++) {
                    try {
                        getOrGenerate(courseId, subject, subjectType, difficulty, chapterIds);
                    } catch (Exception e) {
                        System.out.println("=== 课程题库预生成失败 courseId=" + courseId
                                + " difficulty=" + difficulty + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("=== 课程题库预生成异常 courseId=" + courseId + ": " + e.getMessage());
            }
        });
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        dispatcherPool.shutdownNow();
        llmPool.shutdownNow();
        prewarmExecutor.shutdownNow();
    }
}