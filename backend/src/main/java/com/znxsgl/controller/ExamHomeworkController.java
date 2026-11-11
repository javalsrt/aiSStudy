package com.znxsgl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.entity.ExamHomework;
import com.znxsgl.entity.ExamQuestion;
import com.znxsgl.mapper.ExamHomeworkMapper;
import com.znxsgl.mapper.ExamQuestionMapper;
import com.znxsgl.service.LlmService;
import com.znxsgl.service.RagService;
import com.znxsgl.service.ExamHomeworkNotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

/**
 * 教师端：考试/作业发布与管理
 */
@RestController
@RequestMapping("/api/exam-homework")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class ExamHomeworkController {

    private final ExamHomeworkMapper examHomeworkMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final LlmService llmService;
    private final RagService ragService;
    private final JdbcTemplate jdbc;
    private final ExamHomeworkNotifyService notifyService;
    private final ObjectMapper json = new ObjectMapper();

    private static final String SYSTEM_PROMPT = "你是专业出题专家，只输出纯JSON数组，不要任何解释文字。";

    // ===== 性能优化：并发执行 + 结果缓存 + 批次扩容 =====
    /** 单题型单批最大题目数：3→5，减少HTTP调用次数 */
    private static final int MAX_BATCH_PER_TYPE = 5;
    /** Prompt上下文最大长度：6000→4000，降低token传输和LLM处理耗时 */
    private static final int PROMPT_CONTEXT_MAX_LEN = 4000;

    /** 专用线程池：IO密集型(LLM HTTP调用)，核心=8 最大=16，60s回收，避免吃掉Tomcat请求线程 */
    private static final ExecutorService LLM_POOL = new ThreadPoolExecutor(
            8, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                private final AtomicInteger idx = new AtomicInteger(1);
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "llm-gen-" + idx.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** 出题结果缓存(15分钟自动过期)，key=参数指纹，value=GenerateResult */
    private static final ConcurrentHashMap<String, CacheEntry> QUESTION_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L;

    static {
        // 守护线程：每5分钟清理一次过期缓存
        Thread cleaner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5 * 60 * 1000L);
                    long now = System.currentTimeMillis();
                    QUESTION_CACHE.entrySet().removeIf(e -> now - e.getValue().createdAt > CACHE_TTL_MS);
                } catch (InterruptedException ignored) { break; }
            }
        }, "question-cache-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private record CacheEntry(GenerateResult result, long createdAt) {}

    /** 参数指纹：对"subject+context+题型排序+难度+数量"做MD5，避免超长字符串做key */
    private static String cacheKey(String subject, String context, List<String> types, String diff, int count) {
        List<String> sortedTypes = new ArrayList<>(types);
        Collections.sort(sortedTypes);
        String raw = subject + "|" + (context == null ? "" : context) + "|"
                + String.join(",", sortedTypes) + "|" + diff + "|" + count;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    public ExamHomeworkController(ExamHomeworkMapper examHomeworkMapper,
                                  ExamQuestionMapper examQuestionMapper,
                                  LlmService llmService,
                                  RagService ragService,
                                  JdbcTemplate jdbc,
                                  ExamHomeworkNotifyService notifyService) {
        this.examHomeworkMapper = examHomeworkMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.llmService = llmService;
        this.ragService = ragService;
        this.jdbc = jdbc;
        this.notifyService = notifyService;
    }

    /** 发布考试/作业 */
    @PostMapping("/publish")
    @Transactional
    public ResponseEntity<Map<String, Object>> publish(@RequestBody Map<String, Object> body, Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        if (teacherId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "无法识别教师身份"));
        }

        Long classId = toLong(body.get("classId"));
        if (classId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择班级"));
        }
        if (!isAdmin(auth) && !classBelongsToTeacher(classId, teacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限发布到该班级"));
        }

        // 基础字段校验
        String type = safeStr(body, "type");
        String title = safeStr(body, "title");
        if (!"exam".equals(type) && !"homework".equals(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "类型错误"));
        }
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "标题不能为空"));
        }

        LocalDateTime startTime = parseTime(body.get("startTime"));
        LocalDateTime endTime = parseTime(body.get("endTime"));
        if (startTime == null || endTime == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "时间格式错误"));
        }
        if (!endTime.isAfter(startTime)) {
            return ResponseEntity.badRequest().body(Map.of("error", "截止时间必须晚于开始时间"));
        }

        String publishMode = safeStr(body, "publishMode", "immediate");
        LocalDateTime scheduledTime = "scheduled".equals(publishMode)
                ? parseTime(body.get("scheduledTime")) : null;
        if ("scheduled".equals(publishMode) && scheduledTime == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请设置定时发布时间"));
        }

        int timeLimit = toInt(body.get("timeLimit"), 0);
        int totalScore = toInt(body.get("totalScore"), 100);
        int passScore = toInt(body.get("passScore"), 60);
        if (passScore < 0 || passScore > totalScore) {
            return ResponseEntity.badRequest().body(Map.of("error", "及格分必须在0到总分之间"));
        }

        Long courseId = toLong(body.get("courseId"));
        String questionMode = safeStr(body, "questionMode", "ai-range");
        List<String> questionTypes = parseStringList(body.get("questionTypes"));
        if (questionTypes.isEmpty()) questionTypes = List.of("single_choice", "multiple_choice", "true_false");
        String difficulty = safeStr(body, "difficulty", "medium");
        int questionCount = toInt(body.get("questionCount"), 10);

        // 解析题目
        List<QuestionItem> questions = parseQuestionItems(body.get("questions"));
        if (questions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "题目不能为空"));
        }
        if (questions.size() != questionCount) {
            questionCount = questions.size();
        }

        // 保存主表
        ExamHomework exam = new ExamHomework();
        exam.setType(type);
        exam.setTitle(title);
        exam.setDescription(safeStr(body, "description"));
        exam.setCourseId(courseId);
        exam.setClassId(classId);
        exam.setTeacherId(teacherId);
        exam.setStartTime(startTime);
        exam.setEndTime(endTime);
        exam.setTimeLimit(timeLimit);
        exam.setTotalScore(totalScore);
        exam.setPassScore(passScore);
        exam.setPublishMode(publishMode);
        exam.setScheduledTime(scheduledTime);
        exam.setQuestionMode(questionMode);
        exam.setQuestionTypes(jsonValue(questionTypes));
        exam.setDifficulty(difficulty);
        exam.setQuestionCount(questionCount);
        // 定时发布初始为草稿，立即发布直接进行中
        exam.setStatus("scheduled".equals(publishMode) ? 0 : 1);
        exam.setEditCount(0);
        exam.setMaxEditCount(2);

        examHomeworkMapper.insert(exam);
        Long examId = exam.getId();

        // 保存题目
        saveQuestions(examId, questions);

        // 通知学生（通过独立Service，带DB唯一索引+内存+先查已发送三重幂等去重）
        System.out.println("[ExamNotify] Controller 调用通知: examId=" + examId + ", classId=" + classId);
        notifyService.notifyStudents(examId, classId, courseId, title, type);

        return ResponseEntity.ok(Map.of("id", examId, "msg", "发布成功"));
    }

    /** 教师修改已发布的考试/作业（限制最多2次） */
    @PostMapping("/{id}/update")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body,
                                                       Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);

        ExamHomework exam = examHomeworkMapper.selectById(id);
        if (exam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "考试/作业不存在"));
        }
        if (!isAdmin(auth)) {
            if (!exam.getTeacherId().equals(teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限修改"));
            }
            if (exam.getEditCount() != null && exam.getMaxEditCount() != null
                    && exam.getEditCount() >= exam.getMaxEditCount()) {
                return ResponseEntity.status(403).body(Map.of("error",
                        "已达到最大修改次数（" + exam.getMaxEditCount() + "次）"));
            }
        }

        // 已有人作答则禁止修改题目，只能改基础信息
        int submissionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_submission WHERE exam_homework_id = ?", Integer.class, id);
        boolean hasSubmission = submissionCount > 0;

        // 基础信息允许修改
        String title = safeStr(body, "title");
        if (!title.isEmpty()) exam.setTitle(title);
        exam.setDescription(safeStr(body, "description", exam.getDescription()));

        LocalDateTime startTime = parseTime(body.get("startTime"));
        LocalDateTime endTime = parseTime(body.get("endTime"));
        if (startTime != null) exam.setStartTime(startTime);
        if (endTime != null) {
            if (!endTime.isAfter(exam.getStartTime())) {
                return ResponseEntity.badRequest().body(Map.of("error", "截止时间必须晚于开始时间"));
            }
            exam.setEndTime(endTime);
        }

        Integer timeLimit = toIntNull(body.get("timeLimit"));
        if (timeLimit != null) exam.setTimeLimit(timeLimit);

        Integer totalScore = toIntNull(body.get("totalScore"));
        if (totalScore != null) exam.setTotalScore(totalScore);

        Integer passScore = toIntNull(body.get("passScore"));
        if (passScore != null) {
            if (passScore < 0 || passScore > exam.getTotalScore()) {
                return ResponseEntity.badRequest().body(Map.of("error", "及格分必须在0到总分之间"));
            }
            exam.setPassScore(passScore);
        }

        // 无人作答时才允许替换题目
        if (!hasSubmission) {
            List<QuestionItem> questions = parseQuestionItems(body.get("questions"));
            if (!questions.isEmpty()) {
                // 删除旧题目
                jdbc.update("DELETE FROM exam_question WHERE exam_homework_id = ?", id);
                saveQuestions(id, questions);
                exam.setQuestionCount(questions.size());
            }
        }

        exam.setEditCount(exam.getEditCount() == null ? 1 : exam.getEditCount() + 1);
        examHomeworkMapper.updateById(exam);

        return ResponseEntity.ok(Map.of("id", id, "msg", "修改成功",
                "remainingEdit", Math.max(0, exam.getMaxEditCount() - exam.getEditCount())));
    }

    /** 列表查询 */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);

        StringBuilder sql = new StringBuilder(
                "SELECT e.*, c.class_name AS className, co.course_name AS courseName " +
                "FROM exam_homework e " +
                "LEFT JOIN class_info c ON c.id = e.class_id " +
                "LEFT JOIN course co ON co.id = e.course_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (!admin) {
            sql.append("AND e.teacher_id = ? ");
            params.add(teacherId);
        }
        if (classId != null) {
            sql.append("AND e.class_id = ? ");
            params.add(classId);
        }
        if (type != null && !type.isEmpty()) {
            sql.append("AND e.type = ? ");
            params.add(type);
        }
        if (status != null && !status.isEmpty()) {
            int statusInt = switch (status) {
                case "published" -> 1;
                case "draft" -> 0;
                case "ended" -> 2;
                case "archived" -> 3;
                default -> -1;
            };
            if (statusInt >= 0) {
                sql.append("AND e.status = ? ");
                params.add(statusInt);
            }
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append("AND (e.title LIKE ? OR c.class_name LIKE ?) ");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        sql.append("ORDER BY e.created_at DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((pageNum - 1) * pageSize);

        List<Map<String, Object>> list = jdbc.queryForList(sql.toString(), params.toArray());
        for (Map<String, Object> row : list) {
            enrichStatusLabel(row);
            enrichStatistics(row);
        }

        // 总数
        String countSql = "SELECT COUNT(*) FROM exam_homework e " +
                "LEFT JOIN class_info c ON c.id = e.class_id WHERE 1=1 ";
        List<Object> countParams = new ArrayList<>();
        if (!admin) {
            countSql += "AND e.teacher_id = ? ";
            countParams.add(teacherId);
        }
        if (classId != null) {
            countSql += "AND e.class_id = ? ";
            countParams.add(classId);
        }
        if (type != null && !type.isEmpty()) {
            countSql += "AND e.type = ? ";
            countParams.add(type);
        }
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            int statusInt = switch (status) {
                case "published" -> 1;
                case "draft" -> 0;
                case "ended" -> 2;
                case "archived" -> 3;
                default -> -1;
            };
            if (statusInt >= 0) {
                countSql += "AND e.status = ? ";
                countParams.add(statusInt);
            }
        }
        if (keyword != null && !keyword.isEmpty()) {
            countSql += "AND (e.title LIKE ? OR c.class_name LIKE ?) ";
            countParams.add("%" + keyword + "%");
            countParams.add("%" + keyword + "%");
        }
        int total = jdbc.queryForObject(countSql, Integer.class, countParams.toArray());

        return ResponseEntity.ok(Map.of("list", list, "total", total, "pageNum", pageNum, "pageSize", pageSize));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);

        ExamHomework exam = examHomeworkMapper.selectById(id);
        if (exam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "不存在"));
        }
        if (!admin && !exam.getTeacherId().equals(teacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        examHomeworkMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("msg", "删除成功"));
    }

    /** 状态切换：上架/下架/草稿 */
    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> toggleStatus(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body,
                                                            Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);

        ExamHomework exam = examHomeworkMapper.selectById(id);
        if (exam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "不存在"));
        }
        if (!admin && !exam.getTeacherId().equals(teacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }

        Integer newStatus = toIntNull(body.get("status"));
        if (newStatus == null || newStatus < 0 || newStatus > 3) {
            return ResponseEntity.badRequest().body(Map.of("error", "状态值错误"));
        }
        exam.setStatus(newStatus);
        examHomeworkMapper.updateById(exam);
        return ResponseEntity.ok(Map.of("msg", "状态已更新", "status", newStatus));
    }

    /** AI 按范围出题 */
    @PostMapping("/generate-by-range")
    public ResponseEntity<Map<String, Object>> generateByRange(@RequestBody Map<String, Object> body) {
        Long courseId = toLong(body.get("courseId"));
        List<Long> chapterIds = parseLongList(body.get("chapterIds"));
        List<String> questionTypes = parseStringList(body.get("questionTypes"));
        String difficulty = safeStr(body, "difficulty", "medium");
        int count = toInt(body.get("count"), 10);

        if (courseId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "courseId不能为空"));
        }
        if (questionTypes.isEmpty()) {
            questionTypes = List.of("single_choice", "multiple_choice", "true_false");
        }
        if (count <= 0 || count > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "题目数量应在1-50之间"));
        }

        // 获取课程基本信息
        Map<String, Object> courseInfo;
        try {
            courseInfo = jdbc.queryForMap(
                "SELECT course_name, course_type, description, semester FROM course WHERE id = ?", courseId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "课程不存在"));
        }
        String courseName = (String) courseInfo.getOrDefault("course_name", "本课程");
        String courseType = (String) courseInfo.getOrDefault("course_type", "专业课程");
        String courseDesc = (String) courseInfo.getOrDefault("description", "");
        String semester = (String) courseInfo.getOrDefault("semester", "");

        // 1. 优先 RAG 检索
        String context = null;
        try {
            if (chapterIds != null && !chapterIds.isEmpty()) {
                context = ragService.retrieveByChapters(chapterIds, courseName);
            } else {
                context = ragService.retrieveContext(courseName, courseName + " 知识点");
            }
        } catch (Exception e) {
            System.out.println("=== RAG 检索异常，尝试降级: " + e.getMessage());
        }

        // 2. RAG 未返回内容时，从课程章节和课程描述回退构造上下文
        if (context == null || context.trim().isEmpty()) {
            StringBuilder fallback = new StringBuilder();
            fallback.append("课程名称：").append(courseName).append("\n");
            fallback.append("课程类型：").append(courseType).append("\n");
            if (semester != null && !semester.isEmpty()) {
                fallback.append("学期：").append(semester).append("\n");
            }
            if (courseDesc != null && !courseDesc.isEmpty()) {
                fallback.append("课程描述：").append(courseDesc).append("\n");
            }
            // 尝试加载章节（course_chapter 表字段：chapter_name、description）
            try {
                String sql = chapterIds != null && !chapterIds.isEmpty()
                    ? "SELECT chapter_name AS title, description AS content FROM course_chapter WHERE id IN (" + chapterIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ") AND status = 1 ORDER BY sort_order, id"
                    : "SELECT chapter_name AS title, description AS content FROM course_chapter WHERE course_id = ? AND status = 1 ORDER BY sort_order, id";
                List<Map<String, Object>> chapters = (chapterIds != null && !chapterIds.isEmpty())
                    ? jdbc.queryForList(sql)
                    : jdbc.queryForList(sql, courseId);
                if (!chapters.isEmpty()) {
                    fallback.append("\n=== 课程章节 ===\n");
                    for (Map<String, Object> ch : chapters) {
                        String title = (String) ch.getOrDefault("title", "");
                        String content = (String) ch.getOrDefault("content", "");
                        if (!title.isEmpty()) fallback.append("\n章节：").append(title).append("\n");
                        if (content != null && !content.isEmpty()) {
                            // 去除 markdown 图片/HTML 标签压缩体积
                            String clean = content.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                                                  .replaceAll("<[^>]+>", " ")
                                                  .replaceAll("\\s+", " ");
                            if (clean.length() > 1500) clean = clean.substring(0, 1500) + "...";
                            fallback.append(clean).append("\n");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("=== 加载章节内容跳过: " + e.getMessage());
            }
            // 如果章节仍然没内容，加上通用的引导性上下文，保证至少能生成题目
            if (fallback.length() < 200) {
                fallback.append("\n参考范围：包含 ").append(courseName).append(" 基础概念、核心原理、")
                        .append(courseType.contains("实践") ? "编程实践" : "典型应用").append("、常见问题等知识点。");
            }
            context = fallback.toString();
            System.out.println("=== 使用回退上下文生成题目，长度: " + context.length());
        }

        if (context == null || context.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "课程内容尚未准备好"));
        }

        GenerateResult result = generateBatchQuestions(courseName, context, questionTypes, difficulty, count);
        if (result.questions().isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "题目生成失败", "questions", List.of(), "failedTypes", result.failedTypes()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("questions", result.questions());
        if (!result.failedTypes().isEmpty()) {
            resp.put("error", "部分题型生成失败：" + String.join("、", result.failedTypes()));
            resp.put("failedTypes", result.failedTypes());
        }
        return ResponseEntity.ok(resp);
    }

    /** AI 按文档出题 */
    @PostMapping("/generate-by-document")
    public ResponseEntity<Map<String, Object>> generateByDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("questionTypes") String questionTypesStr,
            @RequestParam("difficulty") String difficulty,
            @RequestParam("count") int count) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传文档"));
        }
        if (count <= 0 || count > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "题目数量应在1-50之间"));
        }

        String extractedText;
        try {
            extractedText = extractDocumentText(file);
        } catch (Exception e) {
            System.out.println("=== 文档提取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "文档解析失败：" + e.getMessage()));
        }
        if (extractedText == null || extractedText.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未能从文档中提取到文字内容"));
        }

        List<String> questionTypes = Arrays.stream(questionTypesStr.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (questionTypes.isEmpty()) questionTypes = List.of("single_choice", "multiple_choice", "true_false");

        String context = truncate(extractedText, 8000);
        GenerateResult result = generateBatchQuestions("上传文档内容", context, questionTypes, difficulty, count);
        if (result.questions().isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "题目生成失败", "questions", List.of(), "failedTypes", result.failedTypes()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("questions", result.questions());
        if (!result.failedTypes().isEmpty()) {
            resp.put("error", "部分题型生成失败：" + String.join("、", result.failedTypes()));
            resp.put("failedTypes", result.failedTypes());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 按题型分批生成题目（优化版）。
     * 优化点：
     *   1) 相同参数命中缓存直接返回（15分钟），避免重复调用
     *   2) 多题型之间通过 CompletableFuture 在专用线程池并发执行
     *   3) 每批最多 MAX_BATCH_PER_TYPE（已扩容到5）道，降低总请求数
     *   4) 单批次失败不影响其他题型，保留成功部分
     */
    private GenerateResult generateBatchQuestions(String subject, String context,
                                                  List<String> questionTypes,
                                                  String difficulty, int totalCount) {
        // ---- 1. 缓存命中快速返回 ----
        String key = cacheKey(subject, context, questionTypes, difficulty, totalCount);
        CacheEntry cached = QUESTION_CACHE.get(key);
        long nowTs = System.currentTimeMillis();
        if (cached != null && (nowTs - cached.createdAt) <= CACHE_TTL_MS) {
            System.out.println("=== 出题结果命中缓存，key=" + key.substring(0, 8) + "...，直接返回 "
                    + cached.result.questions().size() + " 道题");
            return cached.result;
        }

        List<Map<String, Object>> all = Collections.synchronizedList(new ArrayList<>());
        List<String> failedTypes = Collections.synchronizedList(new ArrayList<>());
        int base = totalCount / questionTypes.size();
        int remainder = totalCount % questionTypes.size();

        // ---- 2. 多题型并行生成 ----
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < questionTypes.size(); i++) {
            final String type = questionTypes.get(i);
            final int typeTotal = base + (i == questionTypes.size() - 1 ? remainder : 0);
            if (typeTotal <= 0) continue;

            futures.add(CompletableFuture.runAsync(() -> {
                long t0 = System.currentTimeMillis();
                List<Map<String, Object>> typeQuestions = generateForType(subject, context, type, difficulty, typeTotal);
                long cost = System.currentTimeMillis() - t0;
                if (typeQuestions.isEmpty()) {
                    failedTypes.add(typeLabel(type));
                    System.out.println("=== 并发出题[" + typeLabel(type) + "]失败，耗时 " + cost + "ms");
                } else {
                    all.addAll(typeQuestions);
                    System.out.println("=== 并发出题[" + typeLabel(type) + "]成功 " + typeQuestions.size()
                            + " 道，耗时 " + cost + "ms");
                }
            }, LLM_POOL));
        }

        // 等待全部题型完成（最长任务时间，而非累加）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            System.out.println("=== 并发生成等待异常（仍返回已生成部分）: " + e.getMessage());
        }

        // ---- 3. 重新编号并均分分数 ----
        List<Map<String, Object>> finalList = new ArrayList<>(all);
        if (!finalList.isEmpty()) {
            int perScore = Math.max(1, 100 / finalList.size());
            int bonus = 100 - perScore * finalList.size();
            for (int i = 0; i < finalList.size(); i++) {
                Map<String, Object> q = finalList.get(i);
                q.put("score", perScore + (i < bonus ? 1 : 0));
            }
        }
        GenerateResult result = new GenerateResult(finalList, new ArrayList<>(failedTypes));

        // ---- 4. 成功结果入缓存（题目非空才存）----
        if (!result.questions().isEmpty()) {
            QUESTION_CACHE.put(key, new CacheEntry(result, nowTs));
            System.out.println("=== 出题结果已缓存：key=" + key.substring(0, 8)
                    + "... 题目数=" + result.questions().size());
        }
        return result;
    }

    private List<Map<String, Object>> generateForType(String subject, String context,
                                                      String type, String difficulty, int typeTotal) {
        List<Map<String, Object>> all = new ArrayList<>();
        int generated = 0;
        int batchNo = 0;
        while (generated < typeTotal) {
            int batchCount = Math.min(MAX_BATCH_PER_TYPE, typeTotal - generated);
            batchNo++;
            try {
                String prompt = buildPrompt(subject, List.of(type), difficulty, batchCount, context);
                String raw = callLlmWithRetry(prompt);
                System.out.println("=== AI出题[" + typeLabel(type) + " #" + batchNo + " x " + batchCount + "]原始返回前300: " +
                        (raw != null ? raw.substring(0, Math.min(300, raw.length())) : "null"));

                List<Map<String, Object>> batch = parseQuestions(raw);
                if (batch.isEmpty()) {
                    System.out.println("=== AI出题[" + typeLabel(type) + " #" + batchNo + "]解析结果为空");
                    return all; // 该题型中断，保留已生成部分
                }
                // 不再严格按题型过滤，LLM 偶发返回题型名不一致时仍保留题目
                all.addAll(batch);
                generated += batch.size();
            } catch (Exception e) {
                System.out.println("=== AI出题[" + typeLabel(type) + " #" + batchNo + "]异常: " + e.getMessage());
                return all; // 该题型中断，保留已生成部分
            }
        }
        return all;
    }

    private String callLlmWithRetry(String prompt) {
        String raw = llmService.chat(SYSTEM_PROMPT, prompt);
        if (raw == null || raw.trim().isEmpty()) {
            System.out.println("=== LLM 首次返回空，准备重试一次");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            raw = llmService.chat(SYSTEM_PROMPT, prompt);
        }
        return raw;
    }

    private record GenerateResult(List<Map<String, Object>> questions, List<String> failedTypes) {}

    /** 查看详情（教师） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id, Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);

        ExamHomework exam = examHomeworkMapper.selectById(id);
        if (exam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "不存在"));
        }
        if (!admin && !exam.getTeacherId().equals(teacherId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        List<ExamQuestion> questions = examQuestionMapper.findByExamHomeworkId(id);
        Map<String, Object> result = objectToMap(exam);
        result.put("questions", questions);
        return ResponseEntity.ok(result);
    }

    /** 查看该考试/作业的学生提交列表（含答案详情） */
    @GetMapping("/{id}/submissions")
    public ResponseEntity<List<Map<String, Object>>> submissions(@PathVariable Long id, Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);
        ExamHomework exam = examHomeworkMapper.selectById(id);
        if (exam == null) return ResponseEntity.badRequest().body(Collections.emptyList());
        if (!admin && !exam.getTeacherId().equals(teacherId))
            return ResponseEntity.status(403).body(Collections.emptyList());

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.id, s.user_id AS userId, u.real_name AS studentName, u.username AS studentNo, " +
            "       s.total_score AS totalScore, s.auto_score AS autoScore, s.submitted_at AS submitTime, s.status " +
            "FROM exam_submission s LEFT JOIN user u ON u.id = s.user_id " +
            "WHERE s.exam_homework_id = ? ORDER BY s.submitted_at DESC", id);
        for (Map<String, Object> r : rows) {
            Long sid = ((Number) r.get("id")).longValue();
            List<Map<String, Object>> answers = jdbc.queryForList(
                "SELECT a.id, a.question_id AS questionId, a.question_type AS questionType, " +
                "       a.question AS questionContent, a.user_answer AS userAnswer, a.ai_score AS autoScore, " +
                "       a.score AS finalScore, a.ai_comment AS aiComment, COALESCE(a.max_score, q.score) AS maxScore, " +
                "       a.score_adjust_count AS adjustCount, a.teacher_comment AS teacherComment, " +
                "       a.correct_answer AS referenceAnswer " +
                "FROM exam_submission_answer a LEFT JOIN exam_question q ON q.id = a.question_id " +
                "WHERE a.submission_id = ? ORDER BY a.question_index, q.id", sid);
            r.put("answers", answers);
        }
        return ResponseEntity.ok(rows);
    }

    /** 教师调整单题得分（仅允许对简答题调整，最多2次） */
    @PostMapping("/submission-answer/{answerId}/adjust-score")
    @Transactional
    public ResponseEntity<Map<String, Object>> adjustScore(@PathVariable Long answerId,
                                                           @RequestBody Map<String, Object> body,
                                                           Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        Long teacherId = getRealTeacherId(teacherUserId);
        boolean admin = isAdmin(auth);

        Map<String, Object> ansRow;
        try {
            ansRow = jdbc.queryForMap(
                "SELECT a.id, a.submission_id AS submissionId, a.question_id AS questionId, " +
                "       a.question_type AS questionType, a.ai_score AS aiScore, a.score AS finalScore, " +
                "       a.score_adjust_count AS adjustCount, " +
                "       COALESCE(a.max_score, (SELECT q.score FROM exam_question q WHERE q.id = a.question_id)) AS maxScore, " +
                "       a.teacher_comment AS teacherComment " +
                "FROM exam_submission_answer a WHERE a.id = ?", answerId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "答题记录不存在"));
        }

        Long submissionId = ((Number) ansRow.get("submissionId")).longValue();
        Map<String, Object> examOwner = jdbc.queryForMap(
            "SELECT e.teacher_id AS teacherId, e.id AS examId FROM exam_submission s " +
            "JOIN exam_homework e ON e.id = s.exam_homework_id WHERE s.id = ?", submissionId);
        Long examTeacherId = (Long) examOwner.get("teacherId");
        if (!admin && (examTeacherId == null || !examTeacherId.equals(teacherId)))
            return ResponseEntity.status(403).body(Map.of("error", "无权调整此答卷"));

        String questionType = (String) ansRow.get("questionType");
        if (!"short_answer".equals(questionType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅允许调整简答题评分"));
        }

        int currentCount = ansRow.get("adjustCount") == null ? 0 : ((Number) ansRow.get("adjustCount")).intValue();
        final int MAX_ADJUST = 2;
        if (currentCount >= MAX_ADJUST && !admin) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "已达到最大调整次数（" + MAX_ADJUST + "次）"));
        }

        int oldFinal = ansRow.get("finalScore") == null ? 0 : ((Number) ansRow.get("finalScore")).intValue();
        int newScore = toInt(body.get("finalScore"), oldFinal);
        int max = ansRow.get("maxScore") == null ? 100 : ((Number) ansRow.get("maxScore")).intValue();
        if (newScore < 0) newScore = 0;
        if (newScore > max) newScore = max;
        String teacherComment = safeStr(body.get("teacherComment"),
                ansRow.get("teacherComment") == null ? "" : String.valueOf(ansRow.get("teacherComment")));

        int delta = newScore - oldFinal;
        jdbc.update("UPDATE exam_submission_answer SET score=?, score_adjust_count=?, teacher_comment=? WHERE id=?",
                newScore, currentCount + 1, teacherComment, answerId);
        if (delta != 0) {
            jdbc.update("UPDATE exam_submission SET total_score = COALESCE(total_score,0) + ? WHERE id = ?",
                    delta, submissionId);
        }
        return ResponseEntity.ok(Map.of(
                "msg", "评分已更新",
                "newScore", newScore,
                "remainingAdjust", Math.max(0, MAX_ADJUST - (currentCount + 1))
        ));
    }

    // ===== 辅助方法 =====

    private void saveQuestions(Long examId, List<QuestionItem> questions) {
        int idx = 1;
        for (QuestionItem q : questions) {
            ExamQuestion eq = new ExamQuestion();
            eq.setExamHomeworkId(examId);
            eq.setQuestionIndex(idx++);
            eq.setQuestionType(q.type);
            eq.setContent(q.content);
            eq.setOptions(jsonValue(q.options));
            eq.setAnswer(q.answer);
            eq.setScore(q.score);
            eq.setDifficulty(q.difficulty);
            examQuestionMapper.insert(eq);
        }
    }

    private String buildPrompt(String subject, List<String> questionTypes, String difficulty, int count, String context) {
        String typeDesc = questionTypes.stream().map(this::typeLabel).collect(Collectors.joining("、"));
        String diffDesc = switch (difficulty) {
            case "easy" -> "简单（概念记忆）";
            case "hard" -> "困难（综合分析）";
            default -> "中等（理解应用）";
        };

        return String.format(
                "基于材料生成 %d 道%s题，难度%s。\n" +
                "材料：%s\n" +
                "输出纯JSON数组，每题字段：type、content、options、answer、score、difficulty。\n" +
                "type必须使用英文代码：单选=single_choice，多选=multiple_choice，判断=true_false，填空=fill_blank，简答=short_answer。\n" +
                "规则：单选/多选各4个选项，正确选项末尾加*；判断题选项=[\"正确\",\"错误\"]，正确加*；填空题和简答题直接给答案，答案尽量简短。",
                count, typeDesc, diffDesc, truncate(context, PROMPT_CONTEXT_MAX_LEN));
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "single_choice" -> "单选题";
            case "multiple_choice" -> "多选题";
            case "true_false" -> "判断题";
            case "fill_blank" -> "填空题";
            case "short_answer" -> "简答题";
            default -> type;
        };
    }

    /** LLM 返回的题型名可能是中文标签，统一映射为英文 code */
    private String normalizeType(Object raw) {
        if (raw == null) return "single_choice";
        String s = raw.toString().trim().toLowerCase();
        return switch (s) {
            case "single_choice", "单选", "单选题", "single" -> "single_choice";
            case "multiple_choice", "多选", "多选题", "multiple" -> "multiple_choice";
            case "true_false", "判断", "判断题", "truefalse", "tf" -> "true_false";
            case "fill_blank", "填空", "填空题", "fill", "blank" -> "fill_blank";
            case "short_answer", "简答", "简答题", "short", "answer" -> "short_answer";
            default -> s;
        };
    }

    private List<Map<String, Object>> parseQuestions(String raw) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        String clean = raw.trim();

        // 去掉 markdown 代码块标记
        if (clean.startsWith("```")) {
            int start = clean.indexOf("[");
            int end = clean.lastIndexOf("]");
            if (start >= 0 && end > start) {
                clean = clean.substring(start, end + 1);
            } else {
                // 去掉首尾 ``` 行
                clean = clean.replaceAll("^```(json|\\s)*\\n?", "").replaceAll("\\n?```\\s*$", "").trim();
            }
        }

        // 尝试完整解析
        JsonNode arr = tryParseJsonArray(clean);
        if (arr == null && clean.startsWith("[") && !clean.trim().endsWith("]")) {
            // 可能因输出过长被截断，尝试从后往前找最后一个完整对象并补全
            String repaired = repairTruncatedArray(clean);
            arr = tryParseJsonArray(repaired);
            if (arr == null) {
                System.out.println("=== 截断修复后仍解析失败，原始返回前500字符: " +
                        clean.substring(0, Math.min(500, clean.length())));
            }
        }
        if (arr == null) {
            System.out.println("=== 题目 JSON 解析失败，原始返回前500字符: " +
                    clean.substring(0, Math.min(500, clean.length())));
            return list;
        }

        for (JsonNode n : arr) {
            try {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("type", normalizeType(n.path("type").asText("single_choice")));
                q.put("content", n.path("content").asText(n.path("question").asText("")));
                JsonNode opts = n.path("options");
                List<String> options = new ArrayList<>();
                List<String> correctList = new ArrayList<>();
                if (opts.isArray()) {
                    for (JsonNode o : opts) {
                        String txt = o.asText();
                        if (txt.endsWith("*")) {
                            correctList.add(txt.substring(0, txt.length() - 1));
                        }
                        options.add(txt.replace("*", ""));
                    }
                }
                q.put("options", options);
                String correct = String.join(",", correctList);
                if (n.has("answer")) {
                    JsonNode ansNode = n.path("answer");
                    String ansText;
                    if (ansNode.isArray()) {
                        // 数组型答案：["A","C","D"] 或 ["文本1","文本2"]，逗号连接
                        List<String> parts = new ArrayList<>();
                        for (JsonNode a : ansNode) {
                            String s = a.asText().replace("*", "").trim();
                            if (!s.isEmpty()) parts.add(s);
                        }
                        ansText = String.join(",", parts);
                    } else {
                        ansText = ansNode.asText().replace("*", "").trim();
                    }
                    // 仅当显式答案非空时才覆盖从 * 推断的 correctList，更稳妥
                    if (!ansText.isEmpty()) {
                        correct = ansText;
                    }
                }
                q.put("answer", correct);
                q.put("score", n.path("score").asInt(10));
                q.put("difficulty", n.path("difficulty").asText("medium"));
                if (!q.get("content").toString().isEmpty()) {
                    list.add(q);
                }
            } catch (Exception e) {
                System.out.println("=== 单题解析失败: " + e.getMessage());
            }
        }
        return list;
    }

    private JsonNode tryParseJsonArray(String s) {
        try {
            JsonNode node = json.readTree(s);
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String repairTruncatedArray(String raw) {
        if (!raw.startsWith("[")) return raw;
        // 去掉开头的 [ 和末尾空白，从后往前找匹配的 }
        String body = raw.substring(1).trim();
        int depth = 0;
        int lastCompleteEnd = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    lastCompleteEnd = i;
                }
            }
        }
        if (lastCompleteEnd > 0) {
            return "[" + body.substring(0, lastCompleteEnd + 1) + "]";
        }
        // 兜底：找最后一个 }
        int lastBrace = body.lastIndexOf('}');
        if (lastBrace > 0) {
            return "[" + body.substring(0, lastBrace + 1) + "]";
        }
        return raw + "]";
    }

    private List<QuestionItem> parseQuestionItems(Object obj) {
        List<QuestionItem> list = new ArrayList<>();
        if (obj == null) return list;
        try {
            List<Map<String, Object>> raw = json.readValue(json.writeValueAsString(obj), List.class);
            for (Map<String, Object> m : raw) {
                QuestionItem q = new QuestionItem();
                q.type = safeStr(m, "type", "single_choice");
                q.content = safeStr(m, "content");
                if (q.content.isEmpty()) q.content = safeStr(m, "question");
                q.options = parseStringList(m.get("options"));
                q.answer = safeStr(m, "answer");
                q.score = toInt(m.get("score"), 10);
                q.difficulty = safeStr(m, "difficulty", "medium");
                list.add(q);
            }
        } catch (Exception e) {
            System.out.println("=== 题目参数解析失败: " + e.getMessage());
        }
        return list;
    }

    private String extractDocumentText(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename();
        if (name == null) name = "";
        String l = name.toLowerCase();
        if (l.endsWith(".txt") || l.endsWith(".md")) {
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (l.endsWith(".docx")) {
            return extractDocxText(file);
        }
        if (l.endsWith(".pdf")) {
            return extractPdfText(file);
        }
        if (l.endsWith(".doc")) {
            Path tmp = Files.createTempFile("upload_", ".doc");
            file.transferTo(tmp.toFile());
            String result = llmService.analyzeDocument("请提取这个文档的文本内容", tmp.toFile().getAbsolutePath(), null);
            Files.deleteIfExists(tmp);
            return result;
        }
        throw new IllegalArgumentException("不支持的文件类型：仅支持 PDF、DOCX、DOC、TXT、MD");
    }

    private String extractDocxText(MultipartFile file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String[] parts = xml.split("<w:t[^>]*>");
                    for (String part : parts) {
                        int end = part.indexOf("</w:t>");
                        if (end >= 0) sb.append(part, 0, end);
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractPdfText(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while ((pos = content.indexOf("BT", pos)) >= 0) {
            int end = content.indexOf("ET", pos);
            if (end < 0) break;
            String block = content.substring(pos + 2, end);
            int tjPos = 0;
            while ((tjPos = block.indexOf("Tj", tjPos)) >= 0) {
                int start = block.lastIndexOf("(", tjPos);
                int stop = block.indexOf(")", tjPos);
                if (start >= 0 && stop > start) {
                    sb.append(block, start + 1, stop);
                }
                tjPos += 2;
            }
            pos = end + 2;
        }
        return sb.toString().trim();
    }

    private Map<String, Object> objectToMap(Object obj) {
        try {
            return json.readValue(json.writeValueAsString(obj), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void enrichStatusLabel(Map<String, Object> row) {
        Object status = row.get("status");
        int s = status instanceof Number ? ((Number) status).intValue() : 0;
        row.put("statusLabel", switch (s) {
            case 0 -> "草稿";
            case 1 -> "进行中";
            case 2 -> "已结束";
            case 3 -> "已下架";
            default -> "未知";
        });
    }

    /** 给教师列表补充完成人数、平均分数、时间描述等统计字段 */
    private void enrichStatistics(Map<String, Object> row) {
        Long examId = toLong(row.get("id"));
        if (examId == null) return;
        try {
            // 班级总人数
            Long classId = toLong(row.get("class_id"));
            Integer totalStudents = 0;
            if (classId != null) {
                totalStudents = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user WHERE class_id = ? AND role = 1", Integer.class, classId);
            }
            // 已提交人数
            Integer submittedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exam_submission WHERE exam_homework_id = ? AND status = 'completed'",
                    Integer.class, examId);
            // 平均分数（仅已提交的）
            Double avgScore = jdbc.queryForObject(
                    "SELECT AVG(total_score) FROM exam_submission WHERE exam_homework_id = ? AND status = 'completed'",
                    Double.class, examId);
            // 最高分
            Integer maxScore = jdbc.queryForObject(
                    "SELECT MAX(total_score) FROM exam_submission WHERE exam_homework_id = ? AND status = 'completed'",
                    Integer.class, examId);

            row.put("totalStudents", totalStudents != null ? totalStudents : 0);
            row.put("submittedCount", submittedCount != null ? submittedCount : 0);
            row.put("avgScore", avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
            row.put("maxScore", maxScore);
            row.put("completionRate", totalStudents != null && totalStudents > 0
                    ? Math.round(submittedCount * 100.0 / totalStudents) : 0);

            // 时间信息格式化
            Object startTime = row.get("start_time");
            Object endTime = row.get("end_time");
            Object timeLimit = row.get("time_limit");
            row.put("startTime", startTime != null ? startTime.toString() : "");
            row.put("endTime", endTime != null ? endTime.toString() : "");
            row.put("timeLimit", timeLimit != null ? timeLimit : 0);

            // 动态状态：即使 status=1，如果时间已过也显示已结束/已截止
            LocalDateTime now = LocalDateTime.now();
            Object dbStart = row.get("start_time");
            Object dbEnd = row.get("end_time");
            if (dbEnd instanceof java.sql.Timestamp t && now.isAfter(t.toLocalDateTime())) {
                row.put("statusLabel", "已截止");
                row.put("timeStatus", "已截止");
            } else if (dbStart instanceof java.sql.Timestamp t && now.isBefore(t.toLocalDateTime())) {
                row.put("timeStatus", "未开始");
            } else {
                row.put("timeStatus", "进行中");
            }
        } catch (Exception e) {
            System.out.println("=== enrichStatistics 异常 examId=" + examId + ": " + e.getMessage());
        }
    }

    private Long getRealTeacherId(Long userId) {
        try {
            // 先尝试通过 teacher 表的 user_id 直接关联（更可靠）
            try {
                return jdbc.queryForObject(
                        "SELECT id FROM teacher WHERE user_id = ? LIMIT 1", Long.class, userId);
            } catch (Exception ignored) {}
            // 兜底：通过 real_name 匹配
            Map<String, Object> u = jdbc.queryForMap("SELECT real_name FROM user WHERE id = ?", userId);
            String realName = (String) u.get("real_name");
            if (realName == null || realName.trim().isEmpty()) {
                return userId;
            }
            return jdbc.queryForObject("SELECT id FROM teacher WHERE real_name = ? LIMIT 1", Long.class, realName);
        } catch (Exception e) {
            return userId;
        }
    }

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

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private LocalDateTime parseTime(Object obj) {
        if (obj == null) return null;
        String s = obj.toString();
        if (s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.valueOf(obj.toString()); } catch (Exception e) { return null; }
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj == null) return defaultVal;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return defaultVal; }
    }

    private Integer toIntNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return null; }
    }

    private String safeStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }

    private String safeStr(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v != null && !v.toString().isEmpty() ? v.toString() : defaultVal;
    }

    private String safeStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private String safeStr(Object obj, String defaultVal) {
        return obj != null && !obj.toString().isEmpty() ? obj.toString() : defaultVal;
    }

    private List<String> parseStringList(Object obj) {
        List<String> list = new ArrayList<>();
        if (obj == null) return list;
        try {
            if (obj instanceof List) {
                for (Object o : (List<?>) obj) {
                    if (o != null) list.add(o.toString());
                }
            } else if (obj instanceof String) {
                String s = (String) obj;
                if (s.startsWith("[") && s.endsWith("]")) {
                    return parseStringList(json.readValue(s, List.class));
                }
                list.addAll(Arrays.asList(s.split(",")));
            }
        } catch (Exception e) {
            System.out.println("=== parseStringList 失败: " + e.getMessage());
        }
        return list.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private List<Long> parseLongList(Object obj) {
        List<Long> list = new ArrayList<>();
        if (obj == null) return list;
        try {
            if (obj instanceof List) {
                for (Object o : (List<?>) obj) {
                    if (o instanceof Number) list.add(((Number) o).longValue());
                    else list.add(Long.valueOf(o.toString()));
                }
            }
        } catch (Exception e) {
            System.out.println("=== parseLongList 失败: " + e.getMessage());
        }
        return list;
    }

    private String jsonValue(Object obj) {
        try { return json.writeValueAsString(obj); } catch (Exception e) { return "null"; }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static class QuestionItem {
        String type;
        String content;
        List<String> options;
        String answer;
        int score;
        String difficulty;
    }
}
