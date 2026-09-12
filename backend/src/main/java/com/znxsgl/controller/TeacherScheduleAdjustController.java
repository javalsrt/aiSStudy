package com.znxsgl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.znxsgl.entity.*;
import com.znxsgl.mapper.*;
import com.znxsgl.service.BellTimeService;
import com.znxsgl.service.ScheduleConflictChecker;
import com.znxsgl.service.ScheduleNotifyService;
import com.znxsgl.service.SemesterService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 教师调课接口
 *
 * 功能：
 * 1. 教师查看自己的课表（已在 ScheduleService 中）
 * 2. 教师查询"可选调课时段"列表（已过滤冲突）
 * 3. 教师确认调课 → 更新 schedule + 写入 schedule_lock（锁定，防止重排被覆盖）
 * 4. 教师取消调课锁定
 *
 * 与自动排课的关系：
 *   - 教师调课后的课会写入 schedule_lock 表
 *   - 管理员重新执行一键排课时，锁定的课作为硬约束，位置不变
 *   - 保证教师调课结果不会被自动排课覆盖
 */
@RestController
@RequestMapping("/api/teacher/schedule")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class TeacherScheduleAdjustController {

    private final ScheduleMapper scheduleMapper;
    private final ScheduleLockMapper scheduleLockMapper;
    private final TeacherMapper teacherMapper;
    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final ClassroomMapper classroomMapper;
    private final ScheduleConflictChecker conflictChecker;
    private final SemesterService semesterService;
    private final ScheduleNotifyService notifyService;
    private final TeachingTaskMapper teachingTaskMapper;
    private final JdbcTemplate jdbc;

    /**
     * 已处理调课请求的幂等键缓存。
     * key=requestId，value=处理时间戳，5 分钟后过期。
     * 配合前端生成的唯一 requestId，可防止网络重试或用户快速点击导致的重复调课/重复通知。
     */
    private final Map<String, Long> processedRequestIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REQUEST_ID_TTL_MS = 5 * 60 * 1000;

    /**
     * 已发送调课通知的操作签名缓存。
     * key=课程:班级:原时段:新时段，value=发送时间戳，5 分钟后过期。
     * 用于防止同一调课操作（无论 requestId 是否相同）触发多次通知。
     */
    private final Map<String, Long> recentNotifyOps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long NOTIFY_OP_TTL_MS = 5 * 60 * 1000;

    private final BellTimeService bellTimeService;

    /** 自动适配作息：按班级年级 + 目标周奇偶解析节次段起止，无配置回退内置 NODE_TIMES */
    private LocalTime[] bellTimes(Long classId, Integer targetWeek, int startNode, int step) {
        try {
            LocalTime[] r = bellTimeService.nodeRangeByWeek(resolveGrade(classId), targetWeek, startNode, step);
            if (r != null) return r;
        } catch (Exception e) {
            System.out.println("=== 调课作息自动适配失败，回退默认表: " + e.getMessage());
        }
        LocalTime[] first = NODE_TIMES.get(startNode);
        LocalTime[] last = NODE_TIMES.get(startNode + Math.max(step, 1) - 1);
        if (first == null) first = last;
        if (last == null) last = first;
        if (first == null || last == null) return new LocalTime[]{LocalTime.of(8, 10), LocalTime.of(8, 50)};
        return new LocalTime[]{first[0], last[1]};
    }

    /** 班级年级（如 2026级），解析失败返回 null */
    private String resolveGrade(Long classId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT grade FROM class_info WHERE id = ? LIMIT 1", classId);
            if (!rows.isEmpty() && rows.get(0).get("grade") != null) {
                return rows.get(0).get("grade").toString();
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** 节次时间表（艺术学部/汽车学部作息，与 AutoScheduleService 保持一致） */
    private static final Map<Integer, LocalTime[]> NODE_TIMES = new LinkedHashMap<>();
    static {
        NODE_TIMES.put(1, new LocalTime[]{LocalTime.of(8, 10), LocalTime.of(8, 50)});
        NODE_TIMES.put(2, new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(9, 40)});
        NODE_TIMES.put(3, new LocalTime[]{LocalTime.of(9, 50), LocalTime.of(10, 30)});
        NODE_TIMES.put(4, new LocalTime[]{LocalTime.of(10, 40), LocalTime.of(11, 20)});
        NODE_TIMES.put(5, new LocalTime[]{LocalTime.of(15, 10), LocalTime.of(15, 50)});
        NODE_TIMES.put(6, new LocalTime[]{LocalTime.of(16, 0), LocalTime.of(16, 40)});
        NODE_TIMES.put(7, new LocalTime[]{LocalTime.of(19, 50), LocalTime.of(20, 10)});
        NODE_TIMES.put(8, new LocalTime[]{LocalTime.of(20, 20), LocalTime.of(21, 0)});
    }

    public TeacherScheduleAdjustController(ScheduleMapper scheduleMapper,
                                            ScheduleLockMapper scheduleLockMapper,
                                            TeacherMapper teacherMapper,
                                            UserMapper userMapper,
                                            CourseMapper courseMapper,
                                            ClassroomMapper classroomMapper,
                                            ScheduleConflictChecker conflictChecker,
                                            SemesterService semesterService,
                                            ScheduleNotifyService notifyService,
                                            TeachingTaskMapper teachingTaskMapper,
                                            JdbcTemplate jdbc,
                                            BellTimeService bellTimeService) {
        this.bellTimeService = bellTimeService;
        this.scheduleMapper = scheduleMapper;
        this.scheduleLockMapper = scheduleLockMapper;
        this.teacherMapper = teacherMapper;
        this.userMapper = userMapper;
        this.courseMapper = courseMapper;
        this.classroomMapper = classroomMapper;
        this.conflictChecker = conflictChecker;
        this.semesterService = semesterService;
        this.notifyService = notifyService;
        this.teachingTaskMapper = teachingTaskMapper;
        this.jdbc = jdbc;
    }

    /**
     * 查询可调整的可选时段列表。
     * 教师选择一节课，系统返回这节课可以调到的所有可选时段（已过滤冲突）。
     *
     * @param scheduleId 要调整的课表记录ID
     */
    @GetMapping("/adjust-options/{scheduleId}")
    public ResponseEntity<Map<String, Object>> getAdjustOptions(
            @PathVariable Long scheduleId, Authentication auth) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "课表记录不存在"));
        }

        String semester = schedule.getSemester();
        Long teacherUserId = (Long) auth.getPrincipal();

        // 管理员可以调整所有课程，教师只能调整自己的
        if (!isAdmin(auth) && !isScheduleOwnedByTeacher(schedule, teacherUserId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限调整此课程"));
        }

        // 构建冲突上下文（加载所有已排课表）
        ScheduleConflictChecker.ConflictContext ctx =
                conflictChecker.buildContext(semester, false);

        // 先把"当前这节课"从冲突上下文中移除（因为要调走它）
        String classKey = getClassIdBySchedule(schedule).toString();
        // 管理员从课程记录获取教师ID，教师使用自己的教师ID
        Long teacherId;
        if (isAdmin(auth)) {
            Course course = courseMapper.selectById(schedule.getCourseId());
            teacherId = course != null ? course.getTeacherId() : null;
        } else {
            teacherId = getTeacherIdByUser(teacherUserId);
        }
        // 按周拆分后，scheduleId 只对应一周；调课选项只针对该周检测冲突
        List<Integer> scheduleWeeks = ScheduleConflictChecker.parseWeeks(schedule.getWeeks());
        int targetWeek = scheduleWeeks.isEmpty() ? 1 : scheduleWeeks.get(0);
        conflictChecker.unmarkOccupied(ctx, classKey, teacherId, schedule.getClassroom(),
                targetWeek, schedule.getDayOfWeek(), schedule.getStartNode(), schedule.getStep());

        // 获取该课程的教室类型偏好（从 teaching_task 查）
        String preferredRoomType = "normal";

        // 列出所有可用教室
        List<Classroom> allRooms = classroomMapper.selectList(
                new LambdaQueryWrapper<Classroom>().eq(Classroom::getIsActive, 1));

        // 遍历 7天 × 8节，找无冲突的时段（节假日补课允许排周日）
        List<Map<String, Object>> options = new ArrayList<>();
        int step = schedule.getStep() != null ? schedule.getStep() : 1;

        for (int day = 1; day <= 7; day++) {
            for (int startNode = 1; startNode + step - 1 <= 8; startNode++) {
                // 跳过与原时段相同的
                if (day == schedule.getDayOfWeek() && startNode == schedule.getStartNode()) continue;

                // 找可用教室
                for (Classroom room : allRooms) {
                    ScheduleConflictChecker.ConflictResult cr = conflictChecker.checkConflict(
                            ctx, classKey, teacherId, room.getName(), targetWeek, day, startNode, step);
                    if (!cr.conflict) {
                        Map<String, Object> opt = new LinkedHashMap<>();
                        opt.put("dayOfWeek", day);
                        opt.put("dayName", dayName(day));
                        opt.put("startNode", startNode);
                        opt.put("step", step);
                        LocalTime[] bt = bellTimes(getClassIdBySchedule(schedule), targetWeek, startNode, step);
                        opt.put("startTime", bt[0].toString());
                        opt.put("endTime", bt[1].toString());
                        opt.put("classroom", room.getName());
                        opt.put("classroomType", room.getType());
                        options.add(opt);
                        break; // 每个时段只需找到一间教室即可
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("original", Map.of(
                "scheduleId", schedule.getId(),
                "courseName", schedule.getCourseName(),
                "dayOfWeek", schedule.getDayOfWeek(),
                "dayName", dayName(schedule.getDayOfWeek()),
                "startNode", schedule.getStartNode(),
                "step", schedule.getStep(),
                "classroom", schedule.getClassroom()
        ));
        result.put("options", options);
        result.put("totalOptions", options.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 执行调课。
     * 教师选择一个新时段，系统更新 schedule 表 + 写入锁定记录。
     *
     * 请求体：{ scheduleId, dayOfWeek, startNode, classroom, reason }
     */
    @PostMapping("/adjust")
    @Transactional
    public ResponseEntity<Map<String, Object>> adjustSchedule(
            @RequestBody Map<String, Object> body, Authentication auth) {
        // 幂等控制：5 分钟内相同 requestId 视为重复请求，直接返回上次结果
        String requestId = body.get("requestId") != null ? body.get("requestId").toString() : null;
        if (requestId == null || requestId.isEmpty()) {
            // 兼容未传 requestId 的旧客户端：按业务参数生成兜底幂等键
            Object sid = body.get("scheduleId");
            Object dow = body.get("dayOfWeek");
            Object sn = body.get("startNode");
            Object cr = body.get("classroom");
            requestId = "adjust:" + sid + ":" + dow + ":" + sn + ":" + cr;
        }
        long now = System.currentTimeMillis();
        // 清理过期幂等键，避免内存无限增长
        processedRequestIds.entrySet().removeIf(e -> now - e.getValue() > REQUEST_ID_TTL_MS);
        recentNotifyOps.entrySet().removeIf(e -> now - e.getValue() > NOTIFY_OP_TTL_MS);

        // 对同一 requestId 加锁，保证并发场景下 check + put 原子性，防止重复调课/重复通知
        synchronized (requestId.intern()) {
            Long lastProcessed = processedRequestIds.get(requestId);
            System.out.println("=== 调课请求入口: requestId=" + requestId + ", principal=" + auth.getPrincipal()
                    + ", body=" + body + ", lastProcessed=" + lastProcessed);
            if (lastProcessed != null && now - lastProcessed < REQUEST_ID_TTL_MS) {
                System.out.println("=== 调课请求重复提交，已忽略: requestId=" + requestId);
                return ResponseEntity.ok(Map.of("message", "调课请求已处理，请勿重复提交"));
            }

            try {
                Long scheduleId = Long.valueOf(body.get("scheduleId").toString());
        int dayOfWeek = Integer.parseInt(body.get("dayOfWeek").toString());
        int startNode = Integer.parseInt(body.get("startNode").toString());
        String classroom = (String) body.get("classroom");
        String reason = body.get("reason") != null ? body.get("reason").toString() : "教师调课";

        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "课表记录不存在"));
        }

        Long teacherUserId = (Long) auth.getPrincipal();
        if (!isAdmin(auth) && !isScheduleOwnedByTeacher(schedule, teacherUserId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限调整此课程"));
        }

        String semester = schedule.getSemester();
        int step = schedule.getStep() != null ? schedule.getStep() : 1;
        // 教师用自己的ID，管理员从课程关联的教师获取
        Long teacherId;
        String operatorName;
        if (isAdmin(auth)) {
            // 管理员：从课程记录中获取教师ID
            Course course = courseMapper.selectById(schedule.getCourseId());
            teacherId = course != null ? course.getTeacherId() : null;
            User adminUser = userMapper.selectById(teacherUserId);
            operatorName = adminUser != null ? adminUser.getRealName() : "管理员";
        } else {
            teacherId = getTeacherIdByUser(teacherUserId);
            operatorName = getTeacherName(teacherUserId);
        }
        Long classId = getClassIdBySchedule(schedule);
        if (classId == null || classId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "无法识别该课表记录所属班级"));
        }
        String classKey = classId.toString();

        // 按周拆分后，scheduleId 只对应一周；调课只调整该周
        List<Integer> scheduleWeeks = ScheduleConflictChecker.parseWeeks(schedule.getWeeks());
        int targetWeek = scheduleWeeks.isEmpty() ? 1 : scheduleWeeks.get(0);

        // 冲突检测
        ScheduleConflictChecker.ConflictContext ctx =
                conflictChecker.buildContext(semester, false);
        // 先移除原时段（仅针对 targetWeek）
        conflictChecker.unmarkOccupied(ctx, classKey, teacherId, schedule.getClassroom(),
                targetWeek, schedule.getDayOfWeek(), schedule.getStartNode(), step);
        // 检查新时段（仅针对 targetWeek）
        ScheduleConflictChecker.ConflictResult cr = conflictChecker.checkConflict(
                ctx, classKey, teacherId, classroom, targetWeek, dayOfWeek, startNode, step);
        if (cr.conflict) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "调课失败：新时段有冲突",
                    "details", cr.details));
        }

        // 查找这门课所有相关的 schedule 记录（同班同学同一周同一节课需要一起调）
        // 同一班级 + 同一课程 + 同一原时段 + 同一周次 的所有学生的 schedule 都要调（含下架status=0和上架status=1）
        List<Long> classStudentIds = jdbc.queryForList(
                "SELECT id FROM user WHERE class_id = ?", Long.class, classId);
        List<Schedule> allSchedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getSemester, semester)
                        .eq(Schedule::getCourseId, schedule.getCourseId())
                        .eq(Schedule::getDayOfWeek, schedule.getDayOfWeek())
                        .eq(Schedule::getStartNode, schedule.getStartNode())
                        .eq(Schedule::getClassroom, schedule.getClassroom())
                        .apply("JSON_CONTAINS(weeks, {0})", schedule.getWeeks())
                        .in(Schedule::getUserId, classStudentIds));

        if (allSchedules.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未找到该班级学生的课表记录，调课失败"));
        }

        // 更新所有相关记录（自动适配目标周的单双周作息）
        LocalTime[] newTimes = bellTimes(classId, targetWeek, startNode, step);
        LocalTime newStartTime = newTimes[0];
        LocalTime newEndTime = newTimes[1];

        System.out.println("=== 调课准备更新记录: scheduleId=" + scheduleId + ", 班级=" + classId
                + ", 关联记录数=" + allSchedules.size() + ", 目标周=" + targetWeek
                + ", 新时段=周" + dayOfWeek + "第" + startNode + "节, 教室=" + classroom);

        int updatedCount = 0;
        Long firstScheduleId = null;
        for (Schedule s : allSchedules) {
            s.setDayOfWeek(dayOfWeek);
            s.setStartNode(startNode);
            s.setStartTime(newStartTime);
            s.setEndTime(newEndTime);
            s.setClassroom(classroom);
            scheduleMapper.updateById(s);
            updatedCount++;
            if (firstScheduleId == null) firstScheduleId = s.getId();

            // 每个学生的这节课都加锁
            ScheduleLock lock = new ScheduleLock();
            lock.setScheduleId(s.getId());
            lock.setSemester(semester);
            lock.setLockedBy(teacherUserId);
            lock.setLockedByName(operatorName);
            lock.setReason(reason);
            // 先删除旧锁定（如果有）
            scheduleLockMapper.delete(new LambdaQueryWrapper<ScheduleLock>()
                    .eq(ScheduleLock::getScheduleId, s.getId()));
            scheduleLockMapper.insert(lock);
        }

        // 同步更新本地 schedule 对象，确保通知内容反映调课后新时段
        schedule.setDayOfWeek(dayOfWeek);
        schedule.setStartNode(startNode);
        schedule.setStartTime(newStartTime);
        schedule.setEndTime(newEndTime);
        schedule.setClassroom(classroom);

        // 同步更新对应教学任务状态为已排课，避免管理员重新自动排课时重复生成该课程
        teachingTaskMapper.update(null,
                new LambdaUpdateWrapper<TeachingTask>()
                        .eq(TeachingTask::getCourseId, schedule.getCourseId())
                        .eq(TeachingTask::getClassId, classId)
                        .eq(TeachingTask::getSemester, semester)
                        .set(TeachingTask::getStatus, "scheduled")
                        .set(TeachingTask::getFailReason, null));

        // 发送调课通知（带操作级去重，防止同一调课操作触发多次通知）
        if (teacherId != null) {
            String notifyOpKey = schedule.getCourseName() + ":" + classId + ":"
                    + schedule.getDayOfWeek() + "-" + schedule.getStartNode() + "->"
                    + dayOfWeek + "-" + startNode + ":" + classroom + ":" + reason;
            Long lastNotify = recentNotifyOps.get(notifyOpKey);
            if (lastNotify == null || now - lastNotify >= NOTIFY_OP_TTL_MS) {
                try {
                    notifyService.sendScheduleAdjustNotify(schedule.getCourseName(), classId, teacherId, schedule, reason);
                    recentNotifyOps.put(notifyOpKey, now);
                    System.out.println("=== 调课通知已发送: notifyOpKey=" + notifyOpKey);
                } catch (Exception ex) {
                    System.out.println("=== 调课通知发送异常: notifyOpKey=" + notifyOpKey + ", error=" + ex.getMessage());
                    ex.printStackTrace();
                }
            } else {
                System.out.println("=== 调课通知被操作级去重拦截: notifyOpKey=" + notifyOpKey
                        + ", lastNotify=" + lastNotify + ", elapsedMs=" + (now - lastNotify));
            }
        } else {
            System.out.println("=== 调课通知跳过: teacherId 为空");
        }

        // 调课成功后记录幂等键，防止重复提交导致重复调课/重复通知
        processedRequestIds.put(requestId, now);
        System.out.println("=== 调课请求处理完成并记录幂等键: requestId=" + requestId);

        return ResponseEntity.ok(Map.of(
                "message", "调课成功，共调整 " + updatedCount + " 条课表记录",
                "updatedCount", updatedCount,
                "newDayOfWeek", dayOfWeek,
                "newDayName", dayName(dayOfWeek),
                "newStartNode", startNode,
                "newClassroom", classroom,
                "locked", true
        ));
            } catch (Exception e) {
                System.out.println("=== 调课处理异常: requestId=" + requestId + ", error=" + e.getMessage());
                e.printStackTrace();
                throw e;
            }
        }
    }

    /**
     * 取消调课锁定（恢复为可被自动排课覆盖状态）。
     * <p>
     * 调课时会为班级内每个学生的 schedule 记录都生成一条锁定记录，
     * 因此取消锁定需要按“课程 + 班级 + 时段”批量删除，保持数据一致。
     */
    @DeleteMapping("/unlock/{scheduleId}")
    @Transactional
    public ResponseEntity<Map<String, String>> unlockSchedule(
            @PathVariable Long scheduleId, Authentication auth) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "课表记录不存在"));
        }
        Long teacherUserId = (Long) auth.getPrincipal();
        if (!isAdmin(auth) && !isScheduleOwnedByTeacher(schedule, teacherUserId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限操作"));
        }

        Long classId = getClassIdBySchedule(schedule);
        if (classId == null || classId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "无法识别该课表记录所属班级"));
        }

        // 查询同班该课程同一原时段、同一周次的所有学生 schedule 记录ID
        List<Long> classStudentIds = jdbc.queryForList(
                "SELECT id FROM user WHERE class_id = ?", Long.class, classId);
        List<Schedule> allSchedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getSemester, schedule.getSemester())
                        .eq(Schedule::getCourseId, schedule.getCourseId())
                        .eq(Schedule::getDayOfWeek, schedule.getDayOfWeek())
                        .eq(Schedule::getStartNode, schedule.getStartNode())
                        .eq(Schedule::getClassroom, schedule.getClassroom())
                        .apply("JSON_CONTAINS(weeks, {0})", schedule.getWeeks())
                        .in(Schedule::getUserId, classStudentIds));

        List<Long> scheduleIds = allSchedules.stream()
                .map(Schedule::getId)
                .collect(Collectors.toList());
        if (scheduleIds.isEmpty()) {
            scheduleIds = Collections.singletonList(scheduleId);
        }

        scheduleLockMapper.delete(new LambdaQueryWrapper<ScheduleLock>()
                .in(ScheduleLock::getScheduleId, scheduleIds));
        return ResponseEntity.ok(Map.of("message", "已取消锁定（共 " + scheduleIds.size() + " 条记录）"));
    }

    /**
     * 查询已调课的记录（锁定列表）。教师看自己的，管理员看所有。
     */
    @GetMapping("/locked-list")
    public ResponseEntity<List<Map<String, Object>>> getLockedList(Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        String semester = semesterService.getCurrentSemesterName();
        if (semester == null) return ResponseEntity.ok(Collections.emptyList());

        List<ScheduleLock> locks;
        if (isAdmin(auth)) {
            // 管理员查看所有锁定
            locks = scheduleLockMapper.selectList(
                    new LambdaQueryWrapper<ScheduleLock>()
                            .eq(ScheduleLock::getSemester, semester)
                            .orderByDesc(ScheduleLock::getCreatedAt));
        } else {
            locks = scheduleLockMapper.selectList(
                    new LambdaQueryWrapper<ScheduleLock>()
                            .eq(ScheduleLock::getSemester, semester)
                            .eq(ScheduleLock::getLockedBy, teacherUserId)
                            .orderByDesc(ScheduleLock::getCreatedAt));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScheduleLock lock : locks) {
            Schedule s = scheduleMapper.selectById(lock.getScheduleId());
            if (s == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scheduleId", s.getId());
            item.put("courseName", s.getCourseName());
            item.put("dayOfWeek", s.getDayOfWeek());
            item.put("dayName", dayName(s.getDayOfWeek()));
            item.put("startNode", s.getStartNode());
            item.put("step", s.getStep());
            item.put("classroom", s.getClassroom());
            item.put("reason", lock.getReason());
            item.put("lockedAt", lock.getCreatedAt());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    // ===== 私有工具方法 =====

    /** 判断某课表记录是否属于当前教师 */
    private boolean isScheduleOwnedByTeacher(Schedule schedule, Long teacherUserId) {
        if (schedule.getCourseId() == null) return false;
        Long teacherId = getTeacherIdByUser(teacherUserId);
        if (teacherId == null) return false;

        Course course = null;
        try {
            course = jdbc.queryForObject(
                    "SELECT id, teacher_id FROM course WHERE id = ?",
                    (rs, row) -> {
                        Course c = new Course();
                        c.setId(rs.getLong("id"));
                        c.setTeacherId(rs.getLong("teacher_id"));
                        return c;
                    },
                    schedule.getCourseId());
        } catch (Exception e) {
            return false;
        }
        return course != null && teacherId.equals(course.getTeacherId());
    }

    /** 根据用户ID获取教师ID */
    private Long getTeacherIdByUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        Teacher teacher = teacherMapper.selectOne(
                new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, user.getRealName()));
        return teacher != null ? teacher.getId() : null;
    }

    /** 根据教师用户ID获取教师姓名 */
    private String getTeacherName(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null ? user.getRealName() : "";
    }

    /** 根据 schedule 记录获取班级ID */
    private Long getClassIdBySchedule(Schedule schedule) {
        User user = userMapper.selectById(schedule.getUserId());
        return user != null ? user.getClassId() : null;
    }

    private String dayName(int day) {
        return switch (day) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "未知";
        };
    }

    /** 判断当前登录用户是否是管理员 */
    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
