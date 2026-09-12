package com.znxsgl.controller;

import com.znxsgl.entity.Course;
import com.znxsgl.entity.CourseImportRecord;
import com.znxsgl.entity.Schedule;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.CourseImportRecordMapper;
import com.znxsgl.mapper.CourseMapper;
import com.znxsgl.mapper.ScheduleMapper;
import com.znxsgl.mapper.UserMapper;
import com.znxsgl.service.BellTimeService;
import com.znxsgl.service.LlmService;
import com.znxsgl.service.ScheduleImportTeacherMatcher;
import com.znxsgl.service.ScheduleNotifyService;
import com.znxsgl.service.SemesterService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 课表导入接口
 *
 * 权限：仅教师或管理员可调用。
 */
@RestController
@RequestMapping("/api/schedule/import")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class ScheduleImportController {

    private final BellTimeService bellTimeService;
    private final LlmService llmService;
    private final JdbcTemplate jdbc;
    private final ScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;
    private final UserMapper userMapper;
    private final ScheduleNotifyService notifyService;
    private final SemesterService semesterService;
    private final ScheduleImportTeacherMatcher teacherMatcher;
    private final TransactionTemplate transactionTemplate;
    private final CourseImportRecordMapper importRecordMapper;
    private final Path uploadDir;

    /**
     * 已处理的上架/排课请求幂等键缓存。
     * key=requestId，value=处理时间戳，5 分钟后过期。
     * 配合前端生成的唯一 requestId，可防止网络重试或用户快速点击导致的重复排课/重复通知。
     */
    private final Map<String, Long> processedRequestIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REQUEST_ID_TTL_MS = 5 * 60 * 1000;

    /**
     * 导入互斥锁桶：按导入内容（课程|班级|学期）哈希取模加锁，串行化同一批数据的并发导入。
     * 原因：doConfirmImport 采用"先删旧记录、再逐学生逐周插入"，并发提交同一批数据时
     * 多个事务在 schedule 表上互相等待形成死锁（DeadlockLoserDataAccessException），
     * 且"先删后插"在并发下会重复写入排课记录。导入为低频管理操作，串行化代价可忽略。
     */
    private static final int IMPORT_LOCK_BUCKETS = 64;
    private final Object[] importLocks = new Object[IMPORT_LOCK_BUCKETS];

    public ScheduleImportController(LlmService llmService, JdbcTemplate jdbc,
                                     ScheduleMapper scheduleMapper,
                                     CourseMapper courseMapper,
                                     UserMapper userMapper,
                                     ScheduleNotifyService notifyService,
                                     SemesterService semesterService,
                                     ScheduleImportTeacherMatcher teacherMatcher,
                                     TransactionTemplate transactionTemplate,
                                     CourseImportRecordMapper importRecordMapper,
                                     BellTimeService bellTimeService) {
        this.bellTimeService = bellTimeService;
        this.llmService = llmService;
        this.jdbc = jdbc;
        this.scheduleMapper = scheduleMapper;
        this.courseMapper = courseMapper;
        this.userMapper = userMapper;
        this.notifyService = notifyService;
        this.semesterService = semesterService;
        this.teacherMatcher = teacherMatcher;
        this.transactionTemplate = transactionTemplate;
        this.importRecordMapper = importRecordMapper;
        for (int i = 0; i < IMPORT_LOCK_BUCKETS; i++) {
            importLocks[i] = new Object();
        }
        try {
            uploadDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "znxsgl_schedule"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 由导入条目内容计算互斥锁 key（课程|班级|学期 拼接） */
    private String buildImportLockKey(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (Map<String, Object> item : items) {
                sb.append(item.get("courseName")).append('|')
                  .append(item.get("className")).append('|')
                  .append(item.getOrDefault("semester", "")).append(';');
            }
        }
        return sb.toString();
    }

    /** 获取当前学期名称，若未配置则抛业务异常阻止导入 */
    private String requireCurrentSemester() {
        String name = semesterService.getCurrentSemesterName();
        if (name == null) {
            throw new IllegalStateException("当前学期未配置，请联系管理员在后台切换学期后再导入课表");
        }
        return name;
    }

    /** 判断当前登录用户是否是管理员 */
    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 获取当前登录用户的角色值（1=学生 2=教师 3=管理员） */
    private int getUserRole(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userMapper.selectById(userId);
        return user != null ? user.getRole() : 1;
    }

    /** 第一步：上传文件 → AI 提取课表 → 校验 → 返回预览 */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            String fileName = file.getOriginalFilename();
            Path saved = uploadDir.resolve(UUID.randomUUID() + "_" + fileName);
            file.transferTo(saved.toFile());

            // 读取文件内容（不再支持图片识别）
            String text;
            if (isImage(fileName)) {
                return ResponseEntity.badRequest().body(Map.of("error", "暂不支持图片识别导入，请上传 Excel/Word/PDF/TXT 文件"));
            }

            String lname = fileName.toLowerCase();
            if (lname.endsWith(".xlsx") || lname.endsWith(".xlsm")) {
                text = extractXlsxText(saved);
            } else if (lname.endsWith(".docx")) {
                text = extractDocxText(saved);
            } else if (lname.endsWith(".pdf")) {
                text = extractPdfText(saved);
            } else if (lname.endsWith(".xls")) {
                text = "（旧版 .xls 格式暂不支持，请另存为 .xlsx）";
            } else {
                text = new String(Files.readAllBytes(saved));
            }

            if (text == null || text.trim().isEmpty()) {
                text = "（文件为空或无法解析）";
            }

            // 解码 HTML/XML 数字实体 (&#xxxxx; → 对应字符)，减小 AI 输入体积
            text = decodeXmlEntities(text);

            System.out.println("=== 课表导入文件内容前200字: " + (text.length() > 200 ? text.substring(0, 200) : text));

            // 标准表头 xlsx 优先直接解析（不依赖 AI，稳定可靠）；非标准格式回退 AI
            List<Map<String, Object>> items = null;
            if (lname.endsWith(".xlsx") || lname.endsWith(".xlsm")) {
                try {
                    items = tryParseStandardXlsx(saved);
                    if (items != null) {
                        System.out.println("=== 标准表头直接解析 " + items.size() + " 条（跳过 AI）");
                    }
                } catch (Exception ex) {
                    System.out.println("=== 标准表头解析异常，回退 AI: " + ex.getMessage());
                    items = null;
                }
            }

            if (items == null) {
                String aiPrompt = "文件内容：\n" + (text.length() > 8000 ? text.substring(0, 8000) : text);
                text = llmService.chat(buildExtractPrompt(), aiPrompt);

                Files.deleteIfExists(saved);

                if (text == null || text.trim().isEmpty()) {
                    return ResponseEntity.ok(Map.of("error", "AI 识别失败，未返回有效内容，请重试"));
                }

                // 解析 AI 返回的 JSON
                items = parseAIResponse(text);
            } else {
                Files.deleteIfExists(saved);
            }
            List<Map<String, Object>> errors = new ArrayList<>();
            List<Map<String, Object>> preview = new ArrayList<>();

            boolean admin = isAdmin(auth);
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                Map<String, Object> result = validateItem(item, i + 1);
                if (result.containsKey("errors")) {
                    errors.add(result);
                } else {
                    // 管理员导入时标记教师匹配状态，便于前端提示与修正
                    if (admin) {
                        String teacherName = (String) result.get("teacherName");
                        ScheduleImportTeacherMatcher.MatchResult match = teacherMatcher.match(teacherName);
                        result.put("teacherMatchStatus", match.getStatus());
                        result.put("teacherId", match.getTeacherId());
                        result.put("matchedTeacherName", match.getTeacherName());
                        result.put("teacherSuggestions", match.getSuggestions().stream()
                                .map(s -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("teacherId", s.getTeacherId());
                                    m.put("teacherName", s.getTeacherName());
                                    m.put("distance", s.getDistance());
                                    return m;
                                })
                                .collect(java.util.stream.Collectors.toList()));
                        // 完全未匹配到的教师视为错误，必须修正后才能导入
                        if ("unmatched".equals(match.getStatus())) {
                            List<String> errMsgs = new ArrayList<>();
                            String base = "未匹配到教师「" + (teacherName != null && !teacherName.isEmpty() ? teacherName : "未知") + "」";
                            if (!match.getSuggestions().isEmpty()) {
                                base += "，您是否想输入：" + match.getSuggestions().get(0).getTeacherName();
                            }
                            errMsgs.add(base);
                            Map<String, Object> err = new HashMap<>();
                            err.put("row", i + 1);
                            err.put("courseName", result.get("courseName"));
                            err.put("errors", errMsgs);
                            errors.add(err);
                            continue;
                        }
                    } else {
                        result.put("teacherMatchStatus", "matched");
                    }
                    preview.add(result);
                }
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("total", items.size());
            resp.put("success", preview.size());
            resp.put("errors", errors);
            resp.put("preview", preview);
            resp.put("raw", text.substring(0, Math.min(300, text.length())) + "...");
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok(Map.of("error", "处理失败：" + e.getMessage()));
        }
    }

    /** 第二步：确认导入（导入即上架，并自动补充剩余课时至 credit） */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少导入数据"));
        }
        // 并发导入同一批数据会产生死锁/重复排课：按内容哈希取模互斥串行化
        Object lock = importLocks[Math.floorMod(buildImportLockKey(items).hashCode(), IMPORT_LOCK_BUCKETS)];
        synchronized (lock) {
            return doConfirm(body, auth);
        }
    }

    /** confirm 的实际处理逻辑（由 confirm 加互斥锁后调用） */
    private ResponseEntity<Map<String, Object>> doConfirm(Map<String, Object> body, Authentication auth) {
        boolean admin = isAdmin(auth);
        Long userId = (Long) auth.getPrincipal();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        String fileName = body.get("fileName") != null ? body.get("fileName").toString() : null;
        if (items == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少导入数据"));
        }

        // 教师：用自己的身份
        // 管理员：从每条记录的 teacherName 中匹配教师
        Long defaultTeacherId = null;
        if (!admin) {
            defaultTeacherId = resolveTeacherId(userId);
            if (defaultTeacherId == null) {
                return ResponseEntity.ok(Map.of("error", "未找到教师身份，请确认当前账户为教师账户"));
            }
        }

        // 写库主体放入事务：任一条目插入过程中抛异常则整体回滚，避免脏数据
        final Long finalDefaultTeacherId = defaultTeacherId;
        Map<String, Object> resp = transactionTemplate.execute(status ->
            doConfirmImport(items, admin, finalDefaultTeacherId));

        // 记录导入日志（在事务外执行，避免影响主事务）
        if (resp != null) {
            saveImportRecord(userId, fileName, items, resp);
        }
        return ResponseEntity.ok(resp);
    }

    /** confirm 的写库主体（在事务内执行） */
    private Map<String, Object> doConfirmImport(List<Map<String, Object>> items,
                                                boolean admin, Long defaultTeacherId) {
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();

        // 预聚合：按课程名推算总课时（各条目 step × 周次数 之和），供 credit 缺失时兜底
        Map<String, Integer> estimatedCreditByCourse = estimateCreditByCourse(items);

        for (Map<String, Object> item : items) {
            String courseName = (String) item.get("courseName");
            int dayOfWeek = ((Number) item.get("dayOfWeek")).intValue();
            String startTime = (String) item.get("startTime");
            String endTime = (String) item.get("endTime");
            int startNode = ((Number) item.get("startNode")).intValue();
            int step = ((Number) item.get("step")).intValue();
            String classroom = (String) item.getOrDefault("classroom", "");
            String semester = (String) item.getOrDefault("semester", requireCurrentSemester());
            String className = (String) item.get("className");
            String teacherName = (String) item.getOrDefault("teacherName", "");

            // 周次强制规范化：无法解析的坏格式绝不写库（否则 JSON_CONTAINS 过滤失效）
            String weeksRaw = String.valueOf(item.getOrDefault("weeks", ""));
            String weeksJson = normalizeWeeks(weeksRaw);
            if ("[]".equals(weeksJson)) {
                skipped++;
                messages.add(courseName + " 周次格式无法解析「" + weeksRaw + "」，已跳过（应为 [1,2,3] 或 1-16 等形式）");
                continue;
            }

            // 课时数（AI可能返回 credit 或 totalHours）；缺失时按本次导入条目推算，推算不出保留32兜底
            int estimatedCredit = estimatedCreditByCourse.getOrDefault(courseName, 0);
            Object creditObj = item.getOrDefault("credit", item.getOrDefault("totalHours", null));
            int credit;
            if (creditObj != null) {
                credit = ((Number) creditObj).intValue();
            } else {
                credit = estimatedCredit > 0 ? estimatedCredit : 32; // 默认32课时（每周2节×16周）
            }
            // 限制课时不超过数据库 decimal(6,1) 上限 999，防止写入超界
            credit = Math.min(credit, 999);
            estimatedCredit = Math.min(estimatedCredit, 999);

            // 确定本条记录的教师ID
            Long itemTeacherId = defaultTeacherId;
            if (admin) {
                // 优先采用前端在预览阶段修正/确认过的 teacherId
                Object teacherIdObj = item.get("teacherId");
                if (teacherIdObj != null) {
                    itemTeacherId = ((Number) teacherIdObj).longValue();
                } else {
                    ScheduleImportTeacherMatcher.MatchResult match = teacherMatcher.match(teacherName);
                    itemTeacherId = match.getTeacherId();
                }
                if (itemTeacherId == null) {
                    skipped++;
                    messages.add(courseName + " 未匹配到教师「" + (teacherName != null && !teacherName.isEmpty() ? teacherName : "未知") + "」，已跳过");
                    continue;
                }
            }

            // 确保 course 表中存在此课程（含课时）
            Long courseId = ensureCourse(courseName, itemTeacherId, semester, credit, estimatedCredit);

            // 查找班级ID
            Long classId = null;
            if (className != null && !className.isEmpty()) {
                classId = matchClass(className);
            }

            if (classId == null) {
                skipped++;
                messages.add(courseName + " 未匹配到班级「" + (className != null ? className : "未知") + "」，已跳过");
                continue;
            }

            // 确保 course_class 关联
            ensureCourseClass(courseId, classId, semester);

            // EXTRA 学期需在 semester_class 中关联班级，否则学生端查不到课表
            linkExtraSemesterClass(semester, classId, messages);

            // 去重：删除该班级该课程在同一时间段的旧记录（不再按完整 weeks 匹配，因为已按周拆分）
            int deletedOld = jdbc.update(
                "DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.day_of_week = ? " +
                "AND s.start_node = ? AND s.step = ? AND s.semester = ?",
                courseName, classId, dayOfWeek, startNode, step, semester);
            if (deletedOld > 0) {
                messages.add(courseName + "(" + className + " 周" + dayOfWeek + " " + startTime + "-" + endTime + ") 已覆盖旧排课 " + deletedOld + " 条");
            }

            // 查找班级学生
            List<Long> studentIds = jdbc.queryForList(
                "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);
            if (studentIds.isEmpty()) {
                skipped++;
                messages.add(courseName + "(" + className + ") 班级中无学生用户，已跳过");
                continue;
            }

            // 在 item 上缓存解析结果，便于后续自动填充剩余课时复用
            item.put("__teacherId", itemTeacherId);
            item.put("__classId", classId);
            item.put("__courseId", courseId);
            item.put("__credit", credit);
            item.put("__weeksJson", weeksJson);

            // 导入的课程直接上架并写入排课记录（按周拆分：每周一条记录）
            List<Integer> importWeeks = parseWeekNumbers(weeksJson);
            if (importWeeks.isEmpty()) {
                // 未解析到周次时默认按第 1 周写入，避免数据丢失
                importWeeks = Collections.singletonList(1);
            }
            LocalTime parsedStartTime = LocalTime.parse(startTime, DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime parsedEndTime = LocalTime.parse(endTime, DateTimeFormatter.ofPattern("HH:mm"));
            for (int week : importWeeks) {
                String singleWeekJson = "[" + week + "]";
                for (Long sid : studentIds) {
                    Schedule s = new Schedule();
                    s.setUserId(sid);
                    s.setCourseId(courseId);
                    s.setCourseName(courseName);
                    s.setDayOfWeek(dayOfWeek);
                    s.setStartTime(parsedStartTime);
                    s.setEndTime(parsedEndTime);
                    s.setStartNode(startNode);
                    s.setStep(step);
                    s.setClassroom(classroom);
                    s.setSemester(semester);
                    s.setWeeks(singleWeekJson);
                    s.setStatus(1); // 导入即上架
                    scheduleMapper.insert(s);
                }
            }

            imported++;
            messages.add(courseName + "(" + className + " 周" + dayOfWeek + " " + startTime + "-" + endTime + ") 已导入并上架");
        }

        // 按课程自动填充剩余课时：要求上多少课时就排多少课时
        int autoFilled = autoFillRemainingCredits(items, messages);

        Map<String, Object> resp = new HashMap<>();
        resp.put("imported", imported);
        resp.put("autoFilled", autoFilled);
        resp.put("skipped", skipped);
        resp.put("messages", messages);
        return resp;
    }

    /**
     * 按课程自动填充剩余课时。
     * 规则：同一课程（courseName+className+semester+teacherId）已排课时之和小于 credit 时，
     * 在学期所有周次的空闲时间段按 step=1 自动补充，直到满足 credit。
     *
     * @return 自动填充并写入的排课条数
     */
    private int autoFillRemainingCredits(List<Map<String, Object>> items, List<String> messages) {
        int autoFilled = 0;
        // 按课程分组：courseName + className + semester + teacherId
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Long teacherId = (Long) item.get("__teacherId");
            String key = item.get("courseName") + "|" + item.get("className") + "|"
                    + item.getOrDefault("semester", "") + "|" + teacherId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        for (List<Map<String, Object>> groupItems : groups.values()) {
            if (groupItems.isEmpty()) continue;
            Map<String, Object> first = groupItems.get(0);
            Long teacherId = (Long) first.get("__teacherId");
            Long classId = (Long) first.get("__classId");
            Long courseId = (Long) first.get("__courseId");
            Integer creditObj = (Integer) first.get("__credit");
            if (teacherId == null || classId == null || courseId == null || creditObj == null) {
                continue;
            }
            int credit = creditObj;

            // 已排课时 = 各条目 step × 周次数 之和（按周拆分后实际产生的课时数）
            int scheduledCredit = groupItems.stream()
                    .mapToInt(i -> {
                        int step = ((Number) i.get("step")).intValue();
                        String itemWeeksJson = (String) i.get("__weeksJson");
                        if (itemWeeksJson == null || "[]".equals(itemWeeksJson)) {
                            return step;
                        }
                        List<Integer> itemWeeks = parseWeekNumbers(itemWeeksJson);
                        return step * Math.max(itemWeeks.size(), 1);
                    })
                    .sum();
            int remaining = credit - scheduledCredit;
            System.out.println("=== autoFill: course=" + first.get("courseName") + " credit=" + credit + " scheduled=" + scheduledCredit + " remaining=" + remaining);
            if (remaining <= 0) continue;

            String courseName = (String) first.get("courseName");
            String className = (String) first.get("className");
            String semester = (String) first.getOrDefault("semester", requireCurrentSemester());
            String classroom = (String) first.getOrDefault("classroom", "");
            String weeksJson = (String) first.get("__weeksJson");
            if (weeksJson == null || "[]".equals(weeksJson)) {
                System.out.println("=== autoFill: skip, weeksJson empty");
                continue;
            }

            List<Long> studentIds = jdbc.queryForList(
                    "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);
            if (studentIds.isEmpty()) {
                messages.add(courseName + "(" + className + ") 班级中无学生用户，自动补充已跳过");
                continue;
            }

            // 候选时段：优先在已有排课日期的相邻节次填充，再全局扫描周一到周六、第1到第8节
            List<int[]> candidates = new ArrayList<>();
            for (Map<String, Object> item : groupItems) {
                int dow = ((Number) item.get("dayOfWeek")).intValue();
                int sn = ((Number) item.get("startNode")).intValue();
                int st = ((Number) item.get("step")).intValue();
                // 向后扩展
                for (int n = sn + st; n <= 8; n++) {
                    candidates.add(new int[]{dow, n});
                }
                // 向前扩展
                for (int n = sn - 1; n >= 1; n--) {
                    candidates.add(new int[]{dow, n});
                }
            }
            for (int dow = 1; dow <= 6; dow++) {
                for (int n = 1; n <= 8; n++) {
                    candidates.add(new int[]{dow, n});
                }
            }

            // 填充周次：使用学期总周数，不局限于导入文件中的周次，便于把剩余课时排到任意周
            int semesterWeekCount = resolveSemesterWeekCount(semester);
            List<Integer> fillWeeks = new ArrayList<>();
            for (int w = 1; w <= semesterWeekCount; w++) fillWeeks.add(w);
            System.out.println("=== autoFill: fillWeeks=" + fillWeeks);

            Set<String> tried = new HashSet<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            for (int[] c : candidates) {
                if (remaining <= 0) break;
                int dow = c[0];
                int n = c[1];
                String key = dow + "-" + n;
                if (tried.contains(key)) continue;
                tried.add(key);

                String[] times = getNodeTimeRange(n);
                if (times == null) continue;
                LocalTime start = LocalTime.parse(times[0], fmt);
                LocalTime end = LocalTime.parse(times[1], fmt);
                String startTime = times[0];
                String endTime = times[1];

                // 依次尝试学期内的每一周，找到不冲突的周次再写入
                boolean placed = false;
                for (int targetWeek : fillWeeks) {
                    String singleWeekJson = "[" + targetWeek + "]";
                    boolean conflict = checkConflictDetailed(classId, teacherId, classroom, dow, startTime, endTime, singleWeekJson, courseName);
                    System.out.println("=== autoFill: try week=" + targetWeek + " dow=" + dow + " node=" + n + " conflict=" + conflict);
                    if (conflict) continue;

                    // 写入排课记录（每周一条）
                    for (Long sid : studentIds) {
                        Schedule s = new Schedule();
                        s.setUserId(sid);
                        s.setCourseId(courseId);
                        s.setCourseName(courseName);
                        s.setDayOfWeek(dow);
                        s.setStartTime(start);
                        s.setEndTime(end);
                        s.setStartNode(n);
                        s.setStep(1);
                        s.setClassroom(classroom);
                        s.setSemester(semester);
                        s.setWeeks(singleWeekJson);
                        s.setStatus(1);
                        scheduleMapper.insert(s);
                    }

                    remaining--;
                    autoFilled++;
                    placed = true;
                    messages.add(courseName + "(" + className + " 第" + targetWeek + "周 周" + dow + " " + startTime + "-" + endTime + ") 已自动补充并上架");
                    System.out.println("=== autoFill: placed week=" + targetWeek + " dow=" + dow + " node=" + n);
                    break;
                }
            }

            if (remaining > 0) {
                messages.add(courseName + "(" + className + ") 剩余 " + remaining + " 课时因无空闲时段未自动排满");
                System.out.println("=== autoFill: remaining " + remaining + " not filled");
            }
        }
        return autoFilled;
    }

    /** 根据学期名称解析总周数，未找到则默认 18 周 */
    private int resolveSemesterWeekCount(String semester) {
        try {
            if (semester != null && !semester.isEmpty()) {
                com.znxsgl.dto.SemesterDTO dto = semesterService.getSemesterWithStatusByName(semester);
                if (dto != null && dto.getWeekCount() != null && dto.getWeekCount() > 0) {
                    return dto.getWeekCount();
                }
            }
        } catch (Exception e) {
            System.out.println("=== autoFill: resolveSemesterWeekCount error: " + e.getMessage());
        }
        return 18;
    }

    /** 获取小节对应的时间段：优先读作息配置表（通用表），无配置回退内置表 */
    private String[] getNodeTimeRange(int node) {
        try {
            LocalTime st = bellTimeService.nodeStartTime(null, null, node);
            LocalTime et = bellTimeService.nodeEndTime(null, null, node);
            if (st != null && et != null) {
                return new String[]{st.toString(), et.toString()};
            }
        } catch (Exception ignore) { }
        switch (node) {
            case 1: return new String[]{"08:10", "08:50"};
            case 2: return new String[]{"09:00", "09:40"};
            case 3: return new String[]{"09:50", "10:30"};
            case 4: return new String[]{"10:40", "11:20"};
            case 5: return new String[]{"15:10", "15:50"};
            case 6: return new String[]{"16:00", "16:40"};
            case 7: return new String[]{"19:50", "20:10"};
            case 8: return new String[]{"20:20", "21:00"};
            default: return null;
        }
    }

    /** 保存导入记录 */
    private void saveImportRecord(Long userId, String fileName,
                                   List<Map<String, Object>> items,
                                   Map<String, Object> resp) {
        try {
            User user = userMapper.selectById(userId);
            String userName = user != null ? user.getRealName() : null;

            // 取第一条记录的学期作为记录学期
            String semester = null;
            if (items != null && !items.isEmpty()) {
                semester = (String) items.get(0).getOrDefault("semester", requireCurrentSemester());
            }
            if (semester == null) {
                semester = requireCurrentSemester();
            }

            Integer imported = (Integer) resp.get("imported");
            Integer autoFilled = (Integer) resp.get("autoFilled");
            Integer skipped = (Integer) resp.get("skipped");
            @SuppressWarnings("unchecked")
            List<String> messages = (List<String>) resp.get("messages");

            CourseImportRecord record = new CourseImportRecord();
            record.setFileName(fileName);
            record.setImportedBy(userId);
            record.setImportedByName(userName);
            record.setSemester(semester);
            record.setTotalCount(items != null ? items.size() : 0);
            record.setSuccessCount((imported != null ? imported : 0) + (autoFilled != null ? autoFilled : 0));
            record.setSkipCount(skipped != null ? skipped : 0);
            record.setMessages(messages != null ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(messages) : "[]");
            importRecordMapper.insert(record);
        } catch (Exception e) {
            // 记录日志不应影响主流程
            System.err.println("保存导入记录失败: " + e.getMessage());
        }
    }

    /** 查询导入记录（管理员看全部，教师看自己） */
    @GetMapping("/records")
    public ResponseEntity<List<CourseImportRecord>> listRecords(Authentication auth) {
        boolean admin = isAdmin(auth);
        Long userId = (Long) auth.getPrincipal();

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseImportRecord> qw =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (!admin) {
            qw.eq(CourseImportRecord::getImportedBy, userId);
        }
        qw.orderByDesc(CourseImportRecord::getCreatedAt);
        return ResponseEntity.ok(importRecordMapper.selectList(qw));
    }

    /**
     * 按课程名聚合推算本学期总课时：同一课程所有条目的 step × 周次数 之和。
     * 系统中 credit 表示本课程在所有周次的总课时，因此按按周拆分后的实际课时估算。
     */
    private Map<String, Integer> estimateCreditByCourse(List<Map<String, Object>> items) {
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> item : items) {
            String courseName = (String) item.get("courseName");
            Object stepObj = item.get("step");
            if (courseName == null || courseName.isEmpty() || stepObj == null) continue;
            int step = ((Number) stepObj).intValue();
            String weeksRaw = String.valueOf(item.getOrDefault("weeks", ""));
            String weeksJson = normalizeWeeks(weeksRaw);
            int weekCount = "[]".equals(weeksJson) ? 1 : parseWeekNumbers(weeksJson).size();
            result.merge(courseName, step * Math.max(weekCount, 1), Integer::sum);
        }
        return result;
    }

    /**
     * EXTRA 类型学期自动关联班级到 semester_class。
     * 学生端查询课表时 EXTRA 学期按 semester_class 过滤班级，缺关联则学生查不到课表。
     */
    private void linkExtraSemesterClass(String semester, Long classId, List<String> messages) {
        if (semester == null || semester.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, semester_type FROM semester WHERE name = ? LIMIT 1", semester);
        if (rows.isEmpty()) {
            // 不自动创建学期，仅提示管理员补建
            String warn = "学期「" + semester + "」不存在于 semester 表，EXTRA 学期需先创建并配置日期，否则学生端不可见";
            if (!messages.contains(warn)) messages.add(warn);
            return;
        }
        if (!"EXTRA".equals(rows.get(0).get("semester_type"))) return;
        Long semesterId = ((Number) rows.get(0).get("id")).longValue();
        // 先查后插 + INSERT IGNORE 双保险，无论表上有无唯一约束均幂等
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM semester_class WHERE semester_id = ? AND class_id = ?",
            Integer.class, semesterId, classId);
        if (count == null || count == 0) {
            jdbc.update("INSERT IGNORE INTO semester_class (semester_id, class_id) VALUES (?, ?)",
                semesterId, classId);
        }
    }

    /** 通过 userId 反查 teacher 表的 id */
    private Long resolveTeacherId(Long userId) {
        List<Long> ids = jdbc.queryForList(
            "SELECT t.id FROM teacher t " +
            "JOIN user u ON u.real_name = t.real_name " +
            "WHERE u.id = ? LIMIT 1", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 确保 course 表中存在该课程，不存在则创建（含本学期总课时 credit）。
     * 已有记录的 credit 为 null/0 时，或明显小于本次导入的总课时时，进行纠正。
     */
    private Long ensureCourse(String courseName, Long teacherId, String semester, int credit, int estimatedCredit) {
        // 先查是否存在同教师同课程名
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM course WHERE course_name = ? AND teacher_id = ? LIMIT 1",
            Long.class, courseName, teacherId);
        if (!ids.isEmpty()) {
            // 更新课时（如果已有记录的 credit 为 null 或 0）
            jdbc.update("UPDATE course SET credit = ? WHERE id = ? AND (credit IS NULL OR credit = 0)",
                new java.math.BigDecimal(credit), ids.get(0));
            // 已有课时小于本次导入推算的总课时时纠正，避免排课弹窗误报"已超课时"
            if (estimatedCredit > 0) {
                // 限制最大值不超过 999，避免超出数据库 decimal(6,1) 范围
                int safeCredit = Math.min(estimatedCredit, 999);
                jdbc.update("UPDATE course SET credit = ? WHERE id = ? AND credit < ?",
                    new java.math.BigDecimal(safeCredit), ids.get(0), new java.math.BigDecimal(safeCredit));
            }
            return ids.get(0);
        }

        // 不存在则创建
        Course course = new Course();
        course.setCourseName(courseName);
        course.setTeacherId(teacherId);
        course.setSemester(semester);
        course.setCredit(new java.math.BigDecimal(credit));
        courseMapper.insert(course);
        return course.getId();
    }

    /** 确保 course_class 关联表存在记录 */
    private void ensureCourseClass(Long courseId, Long classId, String semester) {
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course_class WHERE course_id = ? AND class_id = ?",
            Integer.class, courseId, classId);
        if (count == 0) {
            // semester 为空时回退到当前学期；仍为空则使用占位字符串避免 NOT NULL 约束报错
            String finalSemester = semester != null ? semester : requireCurrentSemester();
            jdbc.update("INSERT INTO course_class (course_id, class_id, semester) VALUES (?, ?, ?)",
                courseId, classId, finalSemester);
        }
    }

    /** 增强班级名称匹配：支持简写如"计算机2301班"匹配"计算机科学与技术2023级1班" */
    private Long matchClass(String className) {
        // 策略1: 直接 LIKE 模糊匹配
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM class_info WHERE class_name LIKE CONCAT('%',?,'%') LIMIT 1",
            Long.class, className);
        if (!ids.isEmpty()) return ids.get(0);

        // 策略2: 提取中文前缀 + 数字部分，在内存中做匹配
        // "计算机2301班" → 中文前缀="计算机", 数字部分="2301"
        String chinesePrefix = "";
        StringBuilder allDigits = new StringBuilder();
        for (char c : className.toCharArray()) {
            if (Character.isDigit(c)) {
                allDigits.append(c);
            } else if (allDigits.length() == 0 && c != '班' && c != '级') {
                chinesePrefix += c;
            }
        }
        String numStr = allDigits.toString();
        System.out.println("=== 班级匹配: 输入=" + className + ", 中文前缀=" + chinesePrefix + ", 数字=" + numStr);

        // 拉取所有班级，在 Java 内存中匹配
        List<Map<String, Object>> allClasses = jdbc.queryForList(
            "SELECT id, class_name FROM class_info");
        for (Map<String, Object> row : allClasses) {
            Long cid = ((Number) row.get("id")).longValue();
            String dbName = (String) row.get("class_name");

            // 检查数据库班级名是否包含中文前缀（支持别名映射）
            if (matchesClassPrefix(chinesePrefix, dbName)) {
                // 提取数据库班级名中的数字
                StringBuilder dbDigits = new StringBuilder();
                for (char c : dbName.toCharArray()) {
                    if (Character.isDigit(c)) dbDigits.append(c);
                }
                String dbNum = dbDigits.toString();

                if (numStr.length() >= 4 && dbNum.length() >= 6) {
                    // "2301" vs "202301"
                    // 比较：输入年级(23) 对应 数据库年级(2023 的后两位), 输入班号(01) 对应 数据库班号
                    String inputGrade = numStr.substring(0, 2);   // "23"
                    String inputClass = numStr.substring(2);       // "01"
                    String dbGrade = dbNum.substring(0, 4);        // "2023"
                    String dbClass = dbNum.substring(4);           // "01"

                    if (dbGrade.endsWith(inputGrade) && dbClass.equals(inputClass)) {
                        System.out.println("=== 班级匹配成功: " + className + " → " + dbName + " (id=" + cid + ")");
                        return cid;
                    }
                    // 去掉前导0再比较
                    String inputClassNoPad = inputClass.replaceFirst("^0+", "");
                    String dbClassNoPad = dbClass.replaceFirst("^0+", "");
                    if (dbGrade.endsWith(inputGrade) && dbClassNoPad.equals(inputClassNoPad)) {
                        System.out.println("=== 班级匹配成功(去0): " + className + " → " + dbName + " (id=" + cid + ")");
                        return cid;
                    }
                } else if (numStr.length() >= 2 && dbNum.length() >= 4) {
                    // 只匹配年级
                    String inputGrade = numStr.substring(0, 2);
                    String dbGrade = dbNum.substring(0, 4);
                    if (dbGrade.endsWith(inputGrade)) {
                        System.out.println("=== 班级匹配成功(仅年级): " + className + " → " + dbName + " (id=" + cid + ")");
                        return cid;
                    }
                }
            }
        }

        System.out.println("=== 班级匹配失败: " + className);
        return null;
    }

    /** 中文前缀匹配，支持别名（如"计教"→"教育技术学"、"计科"→"计算机科学与技术"） */
    private boolean matchesClassPrefix(String inputPrefix, String dbClassName) {
        if (inputPrefix.isEmpty()) return false;
        // 直接包含
        if (dbClassName.contains(inputPrefix)) return true;
        // 别名映射
        if (inputPrefix.contains("计教") && dbClassName.contains("教育技术学")) return true;
        if (inputPrefix.contains("计科") && dbClassName.contains("计算机科学与技术")) return true;
        if (inputPrefix.contains("计算机") && dbClassName.contains("计算机")) return true;
        if (inputPrefix.contains("教育") && dbClassName.contains("教育")) return true;
        return false;
    }

    // ===== 下架/排课 =====

    @PostMapping("/hide")
    public ResponseEntity<Map<String, String>> hide(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        Object classIdObj = body.get("classId");
        Long classId = classIdObj != null ? ((Number) classIdObj).longValue() : null;
        Long userId = (Long) auth.getPrincipal();
        boolean admin = isAdmin(auth);

        if (classId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 classId，必须指定班级"));
        }

        if (!admin) {
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null || !classBelongsToTeacher(classId, teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }
        int affected = jdbc.update(
            "UPDATE schedule s JOIN user u ON u.id = s.user_id SET s.status = 0 " +
            "WHERE s.course_name = ? AND u.class_id = ?", courseName, classId);
        return ResponseEntity.ok(Map.of("msg", courseName + " 在指定班级已下架（" + affected + " 条记录）"));
    }

    /** 清除指定课程在指定班级的排课。
     * 支持三种模式：
     * 1. 传 cells 列表：精确删除指定单元格（week + dayOfWeek + startNode）；
     * 2. 传 week 且 cells 为空：删除该周次的全部排课；
     * 3. 都不传：删除该课程在班级的全部排课。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/clear-class-schedule")
    public ResponseEntity<Map<String, String>> clearClassSchedule(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        Object classIdObj = body.get("classId");
        Long classId = classIdObj != null ? ((Number) classIdObj).longValue() : null;
        Object weekObj = body.get("week");
        Integer week = weekObj != null ? ((Number) weekObj).intValue() : null;
        List<Map<String, Object>> cells = (List<Map<String, Object>>) body.get("cells");
        if (classId == null) {
            return ResponseEntity.ok(Map.of("error", "请指定班级"));
        }
        boolean admin = isAdmin(auth);
        if (!admin) {
            Long userId = (Long) auth.getPrincipal();
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null || !classBelongsToTeacher(classId, teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
        }

        // 模式1：精确删除单元格
        if (cells != null && !cells.isEmpty()) {
            int totalAffected = 0;
            for (Map<String, Object> cell : cells) {
                Integer cellWeek = cell.get("week") != null ? ((Number) cell.get("week")).intValue() : week;
                Object dowObj = cell.get("dayOfWeek");
                Object nodeObj = cell.get("startNode");
                if (dowObj == null || nodeObj == null) {
                    continue;
                }
                Integer dayOfWeek = ((Number) dowObj).intValue();
                Integer startNode = ((Number) nodeObj).intValue();
                if (cellWeek == null || cellWeek <= 0) {
                    continue;
                }
                totalAffected += jdbc.update(
                    "DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                    "WHERE s.course_name = ? AND u.class_id = ? AND s.day_of_week = ? AND s.start_node = ? " +
                    "AND JSON_CONTAINS(s.weeks, CAST(? AS JSON))",
                    courseName, classId, dayOfWeek, startNode, cellWeek);
            }
            return ResponseEntity.ok(Map.of("msg", "已清除 " + totalAffected + " 条排课记录"));
        }

        // 模式2：按周次清空
        if (week != null && week > 0) {
            jdbc.update("DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND JSON_CONTAINS(s.weeks, CAST(? AS JSON))",
                courseName, classId, week);
            return ResponseEntity.ok(Map.of("msg", "已清除 " + courseName + " 在该班级第 " + week + " 周的排课"));
        }

        // 模式3：清空全部
        jdbc.update("DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
            "WHERE s.course_name = ? AND u.class_id = ?", courseName, classId);
        return ResponseEntity.ok(Map.of("msg", "已清除 " + courseName + " 在该班级的全部排课"));
    }

    /** 校验班级是否归属当前教师（通过 course_class + course 关联） */
    private boolean classBelongsToTeacher(Long classId, Long teacherId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course_class cc JOIN course c ON c.id = cc.course_id " +
            "WHERE cc.class_id = ? AND c.teacher_id = ?", Integer.class, classId, teacherId);
        return count != null && count > 0;
    }

    /** 上架课程：教师已选好时间段，按指定班级上架（管理员也可以操作） */
    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/unhide")
    public ResponseEntity<Map<String, String>> unhide(@RequestBody Map<String, Object> body, Authentication auth) {
        String requestId = body.get("requestId") != null ? body.get("requestId").toString() : null;
        if (requestId != null && !requestId.isEmpty()) {
            cleanExpiredRequestIds();
            Long existed = processedRequestIds.get(requestId);
            if (existed != null && System.currentTimeMillis() - existed < REQUEST_ID_TTL_MS) {
                System.out.println("=== unhide idempotent skip: requestId=" + requestId);
                return ResponseEntity.ok(Map.of("msg", "请求已处理，请勿重复提交"));
            }
        }

        ResponseEntity<Map<String, String>> resp = doUnhide(body, auth);

        // 只有真正成功完成写操作后才缓存 requestId：
        // 若第一次请求在事务中异常，事务会回滚，此时重试不应被幂等拦截。
        if (requestId != null && !requestId.isEmpty()
                && resp.getStatusCode().is2xxSuccessful()
                && (resp.getBody() == null || !resp.getBody().containsKey("error"))) {
            processedRequestIds.put(requestId, System.currentTimeMillis());
        }
        return resp;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> doUnhide(Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        Long userId = (Long) auth.getPrincipal();
        boolean admin = isAdmin(auth);
        List<Map<String, Object>> selectedSlots = (List<Map<String, Object>>) body.get("slots");
        // 指定班级ID（可选，不传则上架所有关联班级）
        Object classIdObj = body.get("classId");
        Long targetClassId = classIdObj != null ? ((Number) classIdObj).longValue() : null;
        // 跨周移动时需要同时清除的源周单元格（可选）
        List<Map<String, Object>> clearCells = (List<Map<String, Object>>) body.get("clearCells");

        System.out.println("=== doUnhide start: courseName=" + courseName + ", classId=" + targetClassId
                + ", slots=" + (selectedSlots == null ? 0 : selectedSlots.size())
                + ", clearCells=" + (clearCells == null ? 0 : clearCells.size())
                + ", admin=" + admin);

        // 查找课程ID和课时（管理员不校验教师归属，直接按课程名查）
        List<Map<String, Object>> courseRows;
        Long teacherIdForNotify = null;
        // 按班级精确定位课程，避免同名课程冲突（同名课程可能对应不同教师/班级）
        if (admin) {
            if (targetClassId != null) {
                courseRows = jdbc.queryForList(
                    "SELECT DISTINCT c.id, c.credit, c.teacher_id FROM course c " +
                    "JOIN course_class cc ON cc.course_id = c.id " +
                    "WHERE c.course_name = ? AND cc.class_id = ? LIMIT 1",
                    courseName, targetClassId);
            } else {
                courseRows = jdbc.queryForList(
                    "SELECT id, credit, teacher_id FROM course WHERE course_name = ? LIMIT 1",
                    courseName);
            }
            if (!courseRows.isEmpty() && courseRows.get(0).get("teacher_id") != null) {
                teacherIdForNotify = ((Number) courseRows.get(0).get("teacher_id")).longValue();
            }
        } else {
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null) {
                return ResponseEntity.ok(Map.of("error", "未找到教师身份"));
            }
            teacherIdForNotify = teacherId;
            if (targetClassId != null) {
                courseRows = jdbc.queryForList(
                    "SELECT DISTINCT c.id, c.credit FROM course c " +
                    "JOIN course_class cc ON cc.course_id = c.id " +
                    "WHERE c.course_name = ? AND c.teacher_id = ? AND cc.class_id = ? LIMIT 1",
                    courseName, teacherId, targetClassId);
            } else {
                courseRows = jdbc.queryForList(
                    "SELECT id, credit FROM course WHERE course_name = ? AND teacher_id = ?",
                    courseName, teacherId);
            }
        }

        // 若前端传了 clearCells，先按单元格精确清除（用于跨周移动：删除源周记录后再写入目标周）
        if (clearCells != null && !clearCells.isEmpty()) {
            if (targetClassId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "跨周移动必须指定班级"));
            }
            int cleared = 0;
            System.out.println("=== doUnhide clearCells: courseName=" + courseName + ", classId=" + targetClassId + ", cells=" + clearCells);
            for (Map<String, Object> cell : clearCells) {
                Object dowObj = cell.get("dayOfWeek");
                Object nodeObj = cell.get("startNode");
                Object weekObj = cell.get("week");
                System.out.println("=== clearCell input: dow=" + dowObj + ", node=" + nodeObj + ", week=" + weekObj);
                if (dowObj == null || nodeObj == null) continue;
                Integer cellWeek = weekObj != null ? ((Number) weekObj).intValue() : null;
                if (cellWeek == null || cellWeek <= 0) continue;
                int node = ((Number) nodeObj).intValue();
                // 删除所有覆盖该小节的记录（支持 step>1：start_node <= node < start_node + step）
                int affected = jdbc.update(
                    "DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                    "WHERE s.course_name = ? AND u.class_id = ? AND s.day_of_week = ? " +
                    "AND s.start_node <= ? AND ? < s.start_node + s.step " +
                    "AND JSON_CONTAINS(s.weeks, CAST(? AS JSON))",
                    courseName, targetClassId, ((Number) dowObj).intValue(),
                    node, node, cellWeek);
                System.out.println("=== clearCell affected: " + affected);
                cleared += affected;
            }
            // 只清除、不上架时直接返回
            if (selectedSlots == null || selectedSlots.isEmpty()) {
                ResponseEntity<Map<String, String>> result = ResponseEntity.ok(Map.of("msg", "已清除 " + cleared + " 条排课记录"));
                System.out.println("=== doUnhide result (clear only): " + result.getBody());
                return result;
            }
        }

        if (selectedSlots != null && !selectedSlots.isEmpty()) {
            if (courseRows.isEmpty()) {
                throw new IllegalStateException("未找到课程");
            }
            Long courseId = ((Number) courseRows.get(0).get("id")).longValue();
            int totalCredit = courseRows.get(0).get("credit") != null
                ? ((Number) courseRows.get(0).get("credit")).intValue() : 2;

            // 验证课时
            int totalSelectedCredit = 0;
            for (Map<String, Object> slot : selectedSlots) {
                Object creditObj = slot.get("credit");
                totalSelectedCredit += (creditObj != null) ? ((Number) creditObj).intValue() : 2;
            }
            if (totalSelectedCredit > totalCredit) {
                throw new IllegalStateException("课时超限！该课程只有 " + totalCredit + " 课时，您选择了 " + totalSelectedCredit + " 课时");
            }

            // 查找关联班级（DISTINCT 防止 course_class 脏数据导致重复通知）
            List<Map<String, Object>> classList;
            if (targetClassId != null) {
                classList = jdbc.queryForList(
                    "SELECT DISTINCT cc.class_id, ci.class_name FROM course_class cc " +
                    "JOIN class_info ci ON ci.id = cc.class_id " +
                    "WHERE cc.course_id = ? AND cc.class_id = ?", courseId, targetClassId);
            } else {
                classList = jdbc.queryForList(
                    "SELECT DISTINCT cc.class_id, ci.class_name FROM course_class cc " +
                    "JOIN class_info ci ON ci.id = cc.class_id " +
                    "WHERE cc.course_id = ?", courseId);
            }
            if (classList.isEmpty()) {
                throw new IllegalStateException("课程未关联指定班级");
            }

            // 确定本次操作的目标周次（所有 slot 应当属于同一周；取第一个 slot 的 weeks 解析）
            Integer targetWeek = parseTargetWeek(selectedSlots.get(0));
            if (targetWeek == null) {
                throw new IllegalStateException("无法识别排课周次");
            }

            // 对每个班级创建 schedule
            int totalCreated = 0;
            for (Map<String, Object> cl : classList) {
                Long classId = ((Number) cl.get("class_id")).longValue();
                String className = (String) cl.get("class_name");

                // 只删除该班级该课程在当前周次的旧记录，保留其他周次。
                // 跨周移动时（传了 clearCells）不清除目标周旧记录，避免误删目标周已有排课；
                // 冲突检测会在后续拦截与目标周已有记录的冲突。
                if (clearCells == null || clearCells.isEmpty()) {
                    jdbc.update("DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                        "WHERE s.course_name = ? AND u.class_id = ? AND JSON_CONTAINS(s.weeks, CAST(? AS JSON))",
                        courseName, classId, targetWeek);
                }

                List<Long> studentIds = jdbc.queryForList(
                    "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);

                for (Map<String, Object> slot : selectedSlots) {
                    int dayOfWeek = ((Number) slot.get("dayOfWeek")).intValue();
                    String startTime = (String) slot.get("startTime");
                    String endTime = (String) slot.get("endTime");
                    int startNode = ((Number) slot.get("startNode")).intValue();
                    int step = ((Number) slot.get("step")).intValue();
                    String classroom = (String) slot.getOrDefault("classroom", "");
                    String semesterRaw = (String) slot.getOrDefault("semester", "");
                    String semester = (semesterRaw == null || semesterRaw.isEmpty())
                        ? requireCurrentSemester() : semesterRaw;
                    String weeksJson = (String) slot.getOrDefault("weeks", "[]");

                    System.out.println("=== doUnhide insert slot: class=" + className + ", dow=" + dayOfWeek
                            + ", node=" + startNode + ", step=" + step + ", weeks=" + weeksJson
                            + ", students=" + studentIds.size());

                    // 检测冲突：发生冲突时抛异常触发事务回滚，避免 clearCells 已删除但 slots 未写入的部分提交
                    Long conflictTeacherId = admin && !courseRows.isEmpty() && courseRows.get(0).get("teacher_id") != null
                            ? ((Number) courseRows.get(0).get("teacher_id")).longValue()
                            : teacherIdForNotify;
                    // 跨周移动/重新排课时，自动删除目标周与本 slot 时间重叠的同一课程旧记录，
                    // 避免前端漏取消导致的重复排课或冲突。
                    int removedSelfOverlap = jdbc.update(
                        "DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
                        "WHERE s.course_name = ? AND u.class_id = ? AND s.day_of_week = ? " +
                        "AND s.status = 1 AND s.start_time < ? AND s.end_time > ? AND " +
                        "JSON_CONTAINS(s.weeks, CAST(? AS JSON))",
                        courseName, classId, dayOfWeek, endTime, startTime, targetWeek);
                    System.out.println("=== doUnhide remove self overlap: class=" + className + ", dow=" + dayOfWeek
                            + ", time=" + startTime + "-" + endTime + ", week=" + targetWeek
                            + ", affected=" + removedSelfOverlap);

                    // 冲突检测：不排除当前课程，因为上面的自重叠已清理；此处只检测与其他课程/教师/教室的冲突
                    if (checkConflictDetailed(classId, conflictTeacherId, classroom, dayOfWeek, startTime, endTime, weeksJson, null)) {
                        throw new IllegalStateException("排课冲突！" + className + " 周" + dayOfWeek + " " + startTime + "-" + endTime + " 与已有课程冲突");
                    }

                    for (Long sid : studentIds) {
                        jdbc.update(
                            "INSERT INTO schedule (user_id, course_id, course_name, day_of_week, " +
                            "start_time, end_time, start_node, step, classroom, semester, weeks, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                            sid, courseId, courseName, dayOfWeek, startTime, endTime,
                            startNode, step, classroom, semester, weeksJson);
                        totalCreated++;
                    }
                }
            }
            // 排课成功后，给所有相关班级学生发送通知
            if (teacherIdForNotify != null) {
                for (Map<String, Object> cl : classList) {
                    String className = (String) cl.get("class_name");
                    notifyService.sendScheduleNotify(courseName, className, teacherIdForNotify, selectedSlots);
                }
            }

            ResponseEntity<Map<String, String>> result = ResponseEntity.ok(Map.of("msg", courseName + " 已上架（共 " + totalCreated + " 条课表记录）"));
            System.out.println("=== doUnhide result (create): " + result.getBody());
            return result;
        }

        // 没有传 slots：直接上架（必须指定班级，避免全局影响多班级同名课程）
        if (targetClassId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "未指定班级，无法直接上架"));
        }
        // 先检查是否已有真实排课记录（day_of_week>0, status=0），有则只改 status 保留排课数据
        int existingScheduled = jdbc.queryForObject(
            "SELECT COUNT(*) FROM schedule s JOIN user u ON u.id = s.user_id " +
            "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 0 AND s.day_of_week > 0",
            Integer.class, courseName, targetClassId);

        if (existingScheduled > 0) {
            // 已有排课数据，先检查冲突再改 status=1 上架
            Long conflictTeacherId = admin && !courseRows.isEmpty() && courseRows.get(0).get("teacher_id") != null
                    ? ((Number) courseRows.get(0).get("teacher_id")).longValue()
                    : teacherIdForNotify;
            List<Map<String, Object>> pendingRows = jdbc.queryForList(
                "SELECT s.day_of_week, s.start_time, s.end_time, s.start_node, s.step, s.classroom, s.weeks " +
                "FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 0 AND s.day_of_week > 0",
                courseName, targetClassId);
            for (Map<String, Object> row : pendingRows) {
                String startTimeStr = row.get("start_time") == null ? "" : row.get("start_time").toString();
                String endTimeStr = row.get("end_time") == null ? "" : row.get("end_time").toString();
                if (checkConflictDetailed(targetClassId, conflictTeacherId, (String) row.get("classroom"),
                        ((Number) row.get("day_of_week")).intValue(),
                        startTimeStr, endTimeStr,
                        (String) row.get("weeks"), courseName)) {
                    return ResponseEntity.ok(Map.of("error",
                        "上架失败：" + courseName + " 在指定班级的排课与其他课程冲突，请重新排课后再上架"));
                }
            }
            int affected = jdbc.update(
                "UPDATE schedule s JOIN user u ON u.id = s.user_id SET s.status = 1 " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 0 AND s.day_of_week > 0",
                courseName, targetClassId);
            return ResponseEntity.ok(Map.of("msg", courseName + " 在指定班级已上架（保留原有排课 " + affected + " 条）"));
        }

        // 没有排课数据，创建占位记录标记上架
        if (courseRows.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "未找到课程"));
        }
        Long courseId = ((Number) courseRows.get(0).get("id")).longValue();
        // 查找指定班级关联
        List<Map<String, Object>> classList = jdbc.queryForList(
            "SELECT cc.class_id FROM course_class cc WHERE cc.course_id = ? AND cc.class_id = ?",
            courseId, targetClassId);
        if (classList.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "课程未关联指定班级"));
        }
        int totalCreated = 0;
        Long classId = ((Number) classList.get(0).get("class_id")).longValue();
        List<Long> studentIds = jdbc.queryForList(
            "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);
        for (Long sid : studentIds) {
            jdbc.update(
                "INSERT INTO schedule (user_id, course_id, course_name, day_of_week, " +
                "start_time, end_time, start_node, step, classroom, semester, weeks, status) " +
                "VALUES (?, ?, ?, 0, '', '', 0, 0, '', ?, '[]', 1)",
                sid, courseId, courseName, requireCurrentSemester());
            totalCreated++;
        }
        return ResponseEntity.ok(Map.of("msg", courseName + " 在指定班级已上架（未排课，请在在线课程中手动排课）"));
    }

    /** 清理过期的 requestId 幂等缓存，防止内存泄漏 */
    private void cleanExpiredRequestIds() {
        long now = System.currentTimeMillis();
        processedRequestIds.entrySet().removeIf(e -> now - e.getValue() > REQUEST_ID_TTL_MS);
    }

    /**
     * 详细冲突检测：检查目标班级/教师/教室在指定时间段+周次范围内是否已有其他在线课程。
     * 传入 currentCourseName 时，会排除同名课程自身的记录，避免同一课程被自己误判为冲突
     *（常用于跨周移动：目标周已有该课程旧记录时，允许新记录替换/共存）。
     */
    private boolean checkConflictDetailed(Long classId, Long teacherId, String classroom,
                                          int dayOfWeek, String startTime, String endTime,
                                          String weeksJson, String currentCourseName) {
        // 解析目标周次数组
        List<Integer> weekList = new ArrayList<>();
        if (weeksJson != null && weeksJson.startsWith("[")) {
            String nums = weeksJson.replaceAll("[\\[\\]\\s]", "");
            for (String n : nums.split(",")) {
                try { weekList.add(Integer.parseInt(n.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        if (weekList.isEmpty()) return false;

        // 构造周次重叠条件：JSON_CONTAINS 任意一周重叠即冲突
        StringBuilder weekOverlapSql = new StringBuilder("(");
        for (int i = 0; i < weekList.size(); i++) {
            if (i > 0) weekOverlapSql.append(" OR ");
            weekOverlapSql.append("JSON_CONTAINS(s.weeks, CAST(? AS JSON))");
        }
        weekOverlapSql.append(")");

        String excludeSelfSql = (currentCourseName != null && !currentCourseName.isEmpty())
                ? " AND s.course_name != ?" : "";

        // 1. 班级冲突
        List<Map<String, Object>> classConflicts = jdbc.queryForList(
            "SELECT s.id FROM schedule s " +
            "JOIN user u ON u.id = s.user_id " +
            "WHERE u.class_id = ? AND s.day_of_week = ? AND s.status = 1 " +
            "AND s.start_time < ? AND s.end_time > ? AND " + weekOverlapSql + excludeSelfSql,
            buildConflictParams(classId, dayOfWeek, endTime, startTime, weekList, currentCourseName));
        if (!classConflicts.isEmpty()) return true;

        // 2. 教师冲突
        if (teacherId != null) {
            List<Map<String, Object>> teacherConflicts = jdbc.queryForList(
                "SELECT s.id FROM schedule s " +
                "JOIN course c ON c.id = s.course_id " +
                "WHERE c.teacher_id = ? AND s.day_of_week = ? AND s.status = 1 " +
                "AND s.start_time < ? AND s.end_time > ? AND " + weekOverlapSql + excludeSelfSql,
                buildConflictParams(teacherId, dayOfWeek, endTime, startTime, weekList, currentCourseName));
            if (!teacherConflicts.isEmpty()) return true;
        }

        // 3. 教室冲突
        if (classroom != null && !classroom.isEmpty()) {
            List<Map<String, Object>> roomConflicts = jdbc.queryForList(
                "SELECT s.id FROM schedule s " +
                "WHERE s.classroom = ? AND s.day_of_week = ? AND s.status = 1 " +
                "AND s.start_time < ? AND s.end_time > ? AND " + weekOverlapSql + excludeSelfSql,
                buildConflictParams(classroom, dayOfWeek, endTime, startTime, weekList, currentCourseName));
            if (!roomConflicts.isEmpty()) return true;
        }

        return false;
    }

    private Object[] buildConflictParams(Object keyValue, int dayOfWeek, String endTime,
                                         String startTime, List<Integer> weekList,
                                         String currentCourseName) {
        List<Object> params = new ArrayList<>();
        params.add(keyValue);
        params.add(dayOfWeek);
        params.add(endTime);
        params.add(startTime);
        params.addAll(weekList);
        if (currentCourseName != null && !currentCourseName.isEmpty()) {
            params.add(currentCourseName);
        }
        return params.toArray();
    }

    @GetMapping("/hidden")
    public ResponseEntity<List<Map<String, Object>>> hidden(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean admin = isAdmin(auth);
        if (admin) {
            // 管理员查看所有下架课程
            return ResponseEntity.ok(jdbc.queryForList(
                "SELECT DISTINCT s.course_name AS courseName, s.semester, s.day_of_week, " +
                "s.start_time, s.end_time, s.classroom, t.real_name AS teacherName " +
                "FROM schedule s " +
                "JOIN course c ON c.course_name = s.course_name " +
                "LEFT JOIN teacher t ON t.id = c.teacher_id " +
                "WHERE s.status = 0 " +
                "ORDER BY s.course_name"));
        } else {
            // 教师只看自己的
            return ResponseEntity.ok(jdbc.queryForList(
                "SELECT DISTINCT s.course_name AS courseName, s.semester, s.day_of_week, " +
                "s.start_time, s.end_time, s.classroom " +
                "FROM schedule s " +
                "JOIN course c ON c.course_name = s.course_name " +
                "JOIN teacher t ON t.id = c.teacher_id " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE s.status = 0 AND u.id = ? " +
                "ORDER BY s.course_name",
                userId));
        }
    }

    /** 移除课程：按班级移除 schedule、course_class 关联，无班级关联后再删除 course（管理员也可以操作） */
    @PostMapping("/remove")
    public ResponseEntity<Map<String, String>> remove(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        Object classIdObj = body.get("classId");
        Long classId = classIdObj != null ? ((Number) classIdObj).longValue() : null;
        Long userId = (Long) auth.getPrincipal();
        boolean admin = isAdmin(auth);

        if (classId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 classId，必须指定班级"));
        }

        // 查找 course 记录
        List<Long> courseIds;
        if (admin) {
            courseIds = jdbc.queryForList(
                "SELECT id FROM course WHERE course_name = ? LIMIT 1",
                Long.class, courseName);
        } else {
            Long teacherId = resolveTeacherId(userId);
            if (teacherId == null) {
                return ResponseEntity.ok(Map.of("error", "未找到教师身份"));
            }
            if (!classBelongsToTeacher(classId, teacherId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限"));
            }
            courseIds = jdbc.queryForList(
                "SELECT id FROM course WHERE course_name = ? AND teacher_id = ?",
                Long.class, courseName, teacherId);
        }

        if (courseIds.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "未找到该课程"));
        }

        Long courseId = courseIds.get(0);

        // 删除该班级该课程的 schedule 记录
        int deletedSchedule = jdbc.update(
            "DELETE s FROM schedule s JOIN user u ON u.id = s.user_id " +
            "WHERE s.course_name = ? AND u.class_id = ?", courseName, classId);

        // 删除该班级与 course 的关联
        jdbc.update("DELETE FROM course_class WHERE course_id = ? AND class_id = ?", courseId, classId);

        // 如果该课程已无任何班级关联，再删除 course 记录
        Integer remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course_class WHERE course_id = ?", Integer.class, courseId);
        if (remaining == null || remaining == 0) {
            jdbc.update("DELETE FROM course WHERE id = ?", courseId);
        }

        return ResponseEntity.ok(Map.of("msg", courseName + " 在指定班级已移除（" + deletedSchedule + " 条记录）"));
    }

    // ===== 内部方法 =====

    /** 解码 HTML/XML 数字字符实体（&#xxxxx; → Unicode 字符），减小 AI 输入体积 */
    private String decodeXmlEntities(String text) {
        if (text == null || !text.contains("&#")) return text;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '&' && i + 2 < text.length() && text.charAt(i + 1) == '#') {
                int end = text.indexOf(';', i);
                if (end > i + 2) {
                    try {
                        String digits = text.substring(i + 2, end);
                        int codePoint;
                        if (digits.startsWith("x") || digits.startsWith("X")) {
                            codePoint = Integer.parseInt(digits.substring(1), 16);
                        } else {
                            codePoint = Integer.parseInt(digits);
                        }
                        sb.appendCodePoint(codePoint);
                        i = end + 1;
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private Map<String, Object> validateItem(Map<String, Object> item, int row) {
        List<String> msgs = new ArrayList<>();
        String courseName = (String) item.get("courseName");
        String teacherName = (String) item.getOrDefault("teacherName", "");
        String dayOfWeek = String.valueOf(item.getOrDefault("dayOfWeek", ""));
        String startTime = (String) item.get("startTime");
        String endTime = (String) item.get("endTime");
        String startNode = String.valueOf(item.getOrDefault("startNode", ""));
        String step = String.valueOf(item.getOrDefault("step", ""));
        String classroom = (String) item.getOrDefault("classroom", "");
        String weeks = String.valueOf(item.getOrDefault("weeks", ""));
        String className = (String) item.getOrDefault("className", "");

        if (courseName == null || courseName.trim().isEmpty()) msgs.add("缺少课程名称");
        if (className == null || className.trim().isEmpty()) msgs.add("缺少对应班级");
        try { 
            int dow = Integer.parseInt(dayOfWeek.replaceAll("[^0-9]", ""));
            if (dow < 1 || dow > 7) msgs.add("星期几范围错误（仅支持周一至周日，1-7）");
        } catch (Exception e) { msgs.add("缺少或格式错误：星期几"); }
        try { LocalTime.parse(startTime, DateTimeFormatter.ofPattern("HH:mm")); } catch (Exception e) { msgs.add("缺少或格式错误：开始时间"); }
        try { LocalTime.parse(endTime, DateTimeFormatter.ofPattern("HH:mm")); } catch (Exception e) { msgs.add("缺少或格式错误：结束时间"); }
        int startNodeVal = 0;
        int stepVal = 0;
        try {
            startNodeVal = Integer.parseInt(startNode.replaceAll("[^0-9]", ""));
            if (startNodeVal < 1 || startNodeVal > 12) msgs.add("开始节次范围错误（仅支持1-12节）");
        } catch (Exception e) { msgs.add("缺少或格式错误：开始节次"); }
        try {
            stepVal = Integer.parseInt(step.replaceAll("[^0-9]", ""));
            if (stepVal < 1 || stepVal > 12) msgs.add("课时数范围错误（仅支持1-12节）");
            if (startNodeVal > 0 && stepVal > 0 && startNodeVal + stepVal - 1 > 12) msgs.add("开始节次+课时数超出1-12节范围");
        } catch (Exception e) { msgs.add("缺少或格式错误：课时数"); }
        if (weeks.isEmpty() || weeks.equals("[]") || weeks.equals("null")) {
            msgs.add("缺少周次信息");
        } else if ("[]".equals(normalizeWeeks(weeks))) {
            msgs.add("周次格式无法解析「" + weeks + "」（应为 [1,2,3] 或 1-16 等形式）");
        }
        if (classroom == null || classroom.trim().isEmpty()) msgs.add("缺少教室信息");

        if (!msgs.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("row", row);
            err.put("courseName", courseName != null ? courseName : "(未知)");
            err.put("errors", msgs);
            return err;
        }

        // 格式化后的数据
        Map<String, Object> result = new HashMap<>();
        result.put("courseName", courseName);
        result.put("teacherName", teacherName != null ? teacherName : "");
        result.put("className", className);
        result.put("dayOfWeek", Integer.parseInt(dayOfWeek.replaceAll("[^0-9]", "")));
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("startNode", Integer.parseInt(startNode.replaceAll("[^0-9]", "")));
        result.put("step", Integer.parseInt(step.replaceAll("[^0-9]", "")));
        result.put("classroom", classroom);
        result.put("weeks", normalizeWeeks(weeks));
        result.put("semester", item.getOrDefault("semester", requireCurrentSemester()));
        // 保留总课时字段，确保确认导入时按文件指定的 credit 自动补充剩余课时
        if (item.get("credit") != null) {
            result.put("credit", item.get("credit"));
        } else if (item.get("totalHours") != null) {
            result.put("totalHours", item.get("totalHours"));
        }
        return result;
    }

    /**
     * 规范化周次字段：将 AI 可能返回的范围写法（如 [1-6]、[1,3-5,8]、1-6、"[1, 2, 3]"）
     * 展开为标准 JSON 数组字符串（如 [1,2,3,4,5,6]），无空格、升序去重。
     * 无法解析时返回 "[]"，调用方据此拒绝该条数据。
     */
    private String normalizeWeeks(String weeks) {
        if (weeks == null || weeks.trim().isEmpty() || "null".equals(weeks.trim())) return "[]";
        // 统一全角分隔符/连接符，便于后续解析
        String raw = weeks.trim()
            .replace('，', ',').replace('、', ',')
            .replace('—', '-').replace('～', '-').replace('~', '-');
        // 去掉外层方括号和空白
        String inner = raw.replaceAll("^\\[|\\]$", "").trim();
        if (inner.isEmpty()) return "[]";

        Set<Integer> weekSet = new TreeSet<>();
        for (String part : inner.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.contains("-")) {
                // 范围：1-6、第1-6周、1—6 等
                String range = part.replaceAll("[^0-9\\-]", "");
                String[] bounds = range.split("-");
                if (bounds.length == 2) {
                    try {
                        int start = Integer.parseInt(bounds[0]);
                        int end = Integer.parseInt(bounds[1]);
                        for (int w = Math.min(start, end); w <= Math.max(start, end); w++) {
                            if (w > 0) weekSet.add(w);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                // 单个周次
                String num = part.replaceAll("[^0-9]", "");
                try {
                    int w = Integer.parseInt(num);
                    if (w > 0) weekSet.add(w);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (weekSet.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        Iterator<Integer> it = weekSet.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 解析 AI 返回的 JSON 数组（处理截断情况） */
    private List<Map<String, Object>> parseAIResponse(String text) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return items;
        try {
            // 尝试找到 JSON 数组
            int start = text.indexOf("[");
            if (start < 0) return items;

            // 找最后一个完整的 JSON 对象（} 结尾）
            int pos = start;
            while (pos < text.length()) {
                int objStart = text.indexOf("{", pos);
                if (objStart < 0) break;
                // 找匹配的 }
                int depth = 0;
                int objEnd = objStart;
                for (int i = objStart; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) { objEnd = i; break; } }
                }
                if (depth == 0 && objEnd > objStart) {
                    try {
                        String json = text.substring(objStart, objEnd + 1);
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map m = mapper.readValue(json, Map.class);
                        items.add(new HashMap<>(m));
                    } catch (Exception e) {
                        System.out.println("=== 单条JSON解析跳过: " + e.getMessage().substring(0, Math.min(50, e.getMessage().length())));
                    }
                    pos = objEnd + 1;
                } else {
                    pos = objStart + 1;
                }
            }
        } catch (Exception e) {
            System.out.println("=== JSON解析失败: " + e.getMessage());
        }
        return items;
    }

    private String buildExtractPrompt() {
        return "你是一个课表数据提取助手。请从文件中提取所有课程安排，返回纯JSON数组（不要markdown标记）。\n\n" +
            "学校每天最多12节课（常见为上午1-4节、下午5-8节、晚上9-12节）。请根据文件中的实际节次、开始时间、结束时间提取，不要强行套用固定时间表。\n\n" +
            "每条记录必须包含以下字段（缺一不可）：\n" +
            "- courseName: 课程名称\n" +
            "- teacherName: 授课教师姓名（如果文件中有教师姓名请提取，没有则留空字符串）\n" +
            "- className: 班级名称\n" +
            "- dayOfWeek: 星期几（数字，1=周一...7=周日，节假日补课可排周日）\n" +
            "- startTime: 开始时间（HH:mm格式，按文件中的实际时间填写）\n" +
            "- endTime: 结束时间（HH:mm格式，按文件中的实际时间填写）\n" +
            "- startNode: 开始节次（数字，1-12，按文件中的节次填写）\n" +
            "- step: 课时数（数字，1-12，表示本时间段连续占用几个小节，startNode+step-1不能超过12）\n" +
            "- credit: 该课程本学期总课时（数字，如8表示本课程在所有周次一共排8节课；无则按本次文件内该课程所有条目的 step×周次数 之和估算）\n" +
            "- classroom: 教室\n" +
            "- weeks: 周次（必须是完整展开的JSON数组，禁止写范围。例如第1-6周必须写成[1,2,3,4,5,6]，第1,3,5周必须写成[1,3,5]；单周=[1,3,5,7,9,11,13,15]，双周=[2,4,6,8,10,12,14,16]）\n" +
            "- semester: 学期（如2025-2026-2；若文件未写，从文件名或表头推断）\n\n" +
            "注意：\n" +
            "1. credit是本课程学期总课时（所有周次加起来共排多少节），不是每周课时上限；step是当前时间段的持续节数。\n" +
            "2. 如果文件中没有总课时信息，请根据课程名称和文件内实际排课周次推测（如程序设计类通常每学期8-16课时）。\n" +
            "3. teacherName字段：如果文件中出现了教师姓名，请准确提取；如果没有教师信息，返回空字符串\"\"。\n" +
            "4. weeks字段必须展开为完整的周次数组，不能返回[1-6]或[1,6]这种范围简写，否则系统无法识别中间周次。\n" +
            "5. 如果同一条课程连续占用多节课（如Java程序设计周一第3、4节），请拆分为两条记录，每条step=1，startNode分别为3和4；也可合并为一条step=2（推荐按文件一行一行提取）。\n\n" +
            "格式示例：\n" +
            "[{\"courseName\":\"高等数学\",\"teacherName\":\"张教授\",\"className\":\"计算机2301班\",\"dayOfWeek\":1,\"startTime\":\"08:10\",\"endTime\":\"08:50\",\"startNode\":1,\"step\":1,\"credit\":6,\"classroom\":\"A301\",\"weeks\":[1,2,3,4,5,6],\"semester\":\"2025-2026-2\"}]";
    }

    private boolean isImage(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".bmp");
    }

    /** XLSX 解析：使用 Apache POI 读取所有 sheet 的文本内容 */
    private String extractXlsxText(Path file) throws Exception {
        StringBuilder result = new StringBuilder();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.toFile())) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(i);
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    StringBuilder rowText = new StringBuilder();
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        String val = getCellStringValue(cell);
                        if (val != null && !val.isEmpty()) {
                            if (rowText.length() > 0) rowText.append(" | ");
                            rowText.append(val);
                        }
                    }
                    if (rowText.length() > 0) result.append(rowText).append("\n");
                }
            }
        }
        return result.toString().trim();
    }

    /**
     * 确定性解析"标准表头"xlsx（不走 AI）。
     * 表头需包含「课程名称/班级/星期/开始节次/教室/周次」等列。
     * 返回 null 表示无法识别标准表头，调用方应回退 AI 解析。
     */
    private List<Map<String, Object>> tryParseStandardXlsx(Path file) throws Exception {
        // 表头列名 → 字段名（宽松匹配，兼容多种写法；注意：更具体的名称必须先于其子串匹配）
        Map<String, String> colMap = new LinkedHashMap<>();
        colMap.put("课程名称", "courseName");
        colMap.put("授课教师", "teacherName");
        colMap.put("教师", "teacherName");
        colMap.put("班级", "className");
        colMap.put("星期几", "dayOfWeek");
        colMap.put("星期", "dayOfWeek");
        colMap.put("周几", "dayOfWeek");
        colMap.put("开始节次", "startNode");
        colMap.put("起始节次", "startNode");
        colMap.put("节次", "startNode");
        colMap.put("总课时", "credit");
        colMap.put("学分", "credit");
        colMap.put("课时", "step");
        colMap.put("持续节数", "step");
        colMap.put("节数", "step");
        colMap.put("开始时间", "startTime");
        colMap.put("结束时间", "endTime");
        colMap.put("教室", "classroom");
        colMap.put("周次信息", "weeks");
        colMap.put("周次", "weeks");
        colMap.put("学期", "semester");

        List<Map<String, Object>> items = new ArrayList<>();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.toFile())) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(i);
                // 在前 10 行内定位含「课程名称」的表头行
                org.apache.poi.ss.usermodel.Row headerRow = null;
                int headerRowIndex = -1;
                for (int r = 0; r < Math.min(10, sheet.getLastRowNum() + 1); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        String v = getCellStringValue(cell);
                        if (v != null && v.contains("课程名称")) {
                            headerRow = row;
                            headerRowIndex = r;
                            break;
                        }
                    }
                    if (headerRow != null) break;
                }
                if (headerRow == null) continue;

                // 列索引 → 字段名
                Map<Integer, String> colToField = new HashMap<>();
                for (org.apache.poi.ss.usermodel.Cell cell : headerRow) {
                    String v = getCellStringValue(cell).trim();
                    if (v.isEmpty()) continue;
                    for (Map.Entry<String, String> e : colMap.entrySet()) {
                        if (v.contains(e.getKey())) {
                            colToField.put(cell.getColumnIndex(), e.getValue());
                            break;
                        }
                    }
                }
                // 关键列缺失则视为非标准表头，回退 AI
                boolean hasKey = colToField.containsValue("courseName")
                        && colToField.containsValue("className")
                        && colToField.containsValue("dayOfWeek")
                        && colToField.containsValue("startNode")
                        && colToField.containsValue("classroom")
                        && colToField.containsValue("weeks");
                if (!hasKey) return null;

                // 逐行解析
                for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, Object> item = new HashMap<>();
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        String field = colToField.get(cell.getColumnIndex());
                        if (field == null) continue;
                        String v = getCellStringValue(cell).trim();
                        if (v.isEmpty()) continue;
                        switch (field) {
                            case "dayOfWeek" -> item.put("dayOfWeek", parseDayOfWeek(v));
                            case "startNode" -> item.put("startNode", parseIntStrict(v));
                            case "step" -> item.put("step", parseIntStrict(v));
                            case "credit" -> item.put("credit", parseIntStrict(v));
                            case "startTime" -> item.put("startTime", normalizeTime(v));
                            case "endTime" -> item.put("endTime", normalizeTime(v));
                            default -> item.put(field, v);
                        }
                    }
                    if (item.get("courseName") == null || item.get("className") == null) continue;
                    if (item.get("step") == null) item.put("step", 1);
                    // 缺时间列时按节次补默认时间
                    if (item.get("startTime") == null || item.get("endTime") == null) {
                        Object sn = item.get("startNode");
                        Object st = item.get("step");
                        int s = (sn instanceof Number n) ? n.intValue() : 1;
                        int k = (st instanceof Number n) ? n.intValue() : 1;
                        int[] t = nodeTimes(s, k);
                        if (t != null) {
                            if (item.get("startTime") == null)
                                item.put("startTime", String.format("%02d:%02d", t[0] / 60, t[0] % 60));
                            if (item.get("endTime") == null)
                                item.put("endTime", String.format("%02d:%02d", t[1] / 60, t[1] % 60));
                        }
                    }
                    items.add(item);
                }
                if (!items.isEmpty()) break; // 只取第一个含标准表头的 sheet
            }
        }
        return items.isEmpty() ? null : items;
    }

    /** 解析星期：支持 1-6 或 周一/星期一/星期1 等写法 */
    private int parseDayOfWeek(String v) {
        String[] CN = {"", "一", "二", "三", "四", "五", "六"};
        for (int i = 1; i <= 6; i++) {
            if (v.contains(String.valueOf(i)) || v.contains(CN[i])) return i;
        }
        return 1;
    }

    /** 严格取数字（容忍 "第3节"、"3." 等写法） */
    private int parseIntStrict(String v) {
        try {
            return Integer.parseInt(v.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 归一化时间：容忍 HH:mm:ss / HH点mm分 等写法，返回 HH:mm */
    private String normalizeTime(String v) {
        String t = v.replaceAll("[^0-9:]", "");
        if (t.matches("\\d{1,2}:\\d{2}")) {
            String[] p = t.split(":");
            return String.format("%02d:%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        }
        return t;
    }

    /** 节次 → 起止分钟：优先作息配置表（通用表），无配置回退内置表；超范围返回 null */
    private int[] nodeTimes(int startNode, int step) {
        try {
            LocalTime st = bellTimeService.nodeStartTime(null, null, startNode);
            LocalTime et = bellTimeService.nodeEndTime(null, null, startNode + Math.max(step, 1) - 1);
            if (st != null && et != null) {
                return new int[]{st.getHour() * 60 + st.getMinute(), et.getHour() * 60 + et.getMinute()};
            }
        } catch (Exception ignore) { }
        if (startNode < 1 || startNode > 12 || step < 1 || startNode + step - 1 > 12) return null;
        // 每节起始分钟：1=08:10, 2=09:00, 3=10:00, 4=10:50, 5=14:00, 6=14:50, 7=15:40, 8=16:30, 9=19:00, 10=19:50, 11=20:40, 12=21:30
        int[] starts = {0, 490, 540, 600, 650, 840, 890, 940, 990, 1140, 1190, 1240, 1290};
        int s = starts[startNode];
        int lastStart = starts[startNode + step - 1];
        int e = lastStart + 40;
        return new int[]{s, e};
    }

    /** 获取单元格字符串值（兼容字符串、数字、公式） */
    private String getCellStringValue(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /** DOCX 解析：使用 Apache POI 提取段落文本 */
    private String extractDocxText(Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new FileInputStream(file.toFile()))) {
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isEmpty()) sb.append(text).append("\n");
            }
            // 提取表格内容
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (text != null && !text.isEmpty()) {
                            if (rowText.length() > 0) rowText.append(" | ");
                            rowText.append(text.trim());
                        }
                    }
                    if (rowText.length() > 0) sb.append(rowText).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractPdfText(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while ((pos = content.indexOf("BT", pos)) >= 0) {
            int end = content.indexOf("ET", pos);
            if (end < 0) break;
            String block = content.substring(pos + 2, end);
            int tjPos = 0;
            while ((tjPos = block.indexOf("Tj", tjPos)) >= 0) {
                int s = block.lastIndexOf("(", tjPos);
                int e = block.indexOf(")", tjPos);
                if (s >= 0 && e > s) sb.append(block, s + 1, e);
                tjPos += 2;
            }
            pos = end + 2;
        }
        return sb.toString().trim();
    }

    /** 从 slot 的 weeks 字段解析目标周次（取第一个周次） */
    private Integer parseTargetWeek(Map<String, Object> slot) {
        String weeksJson = (String) slot.getOrDefault("weeks", "[]");
        List<Integer> weeks = parseWeekNumbers(weeksJson);
        return weeks.isEmpty() ? null : weeks.get(0);
    }

    /** 解析 weeks JSON 数组（如 "[1,2,3]"）为周数列表 */
    private List<Integer> parseWeekNumbers(String weeksJson) {
        List<Integer> list = new ArrayList<>();
        if (weeksJson == null || weeksJson.isEmpty() || "[]".equals(weeksJson)) return list;
        String stripped = weeksJson.replaceAll("[\\[\\]\\s]", "");
        if (stripped.isEmpty()) return list;
        for (String s : stripped.split(",")) {
            try { list.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return list;
    }
}
