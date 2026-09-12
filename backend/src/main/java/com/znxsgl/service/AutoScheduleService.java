package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.znxsgl.entity.*;
import com.znxsgl.mapper.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 全自动排课服务（贪心算法 + 回溯）
 *
 * 算法思路：
 * 1. 预处理：
 *    - 按"难度"降序排序教学任务（约束多的先排）
 *      顺序：实训课(need lab) > 连堂课 > 专业核心课 > 公共基础课
 *    - 加载所有教室资源，按类型分组
 *    - 构建初始冲突上下文（已锁定的课作为硬约束）
 *
 * 2. 贪心分配（每个教学任务）：
 *    - 计算需要排几个"块"（weekly_hours / consecutive 向上取整）
 *    - 按优先级生成候选时段列表（首选时段在前）
 *    - 遍历每个候选时段：
 *        - 找到匹配类型的可用教室
 *        - 三维冲突检测（班级/教师/教室）
 *        - 无冲突 → 分配，更新占用矩阵
 *        - 有冲突 → 尝试下一个时段
 *    - 所有时段都失败 → 记录到失败列表
 *
 * 3. 输出：
 *    - 写入 schedule 表
 *    - 更新 teaching_task 状态
 *    - 返回统计信息 + 失败原因
 *
 * 设计原则：
 *    - 必定有解或明确报告失败原因（不会死循环）
 *    - 每个决策可解释，便于答辩讲解
 *    - 教师调课后锁定的课不被覆盖（hard constraint）
 */
@Service
public class AutoScheduleService {

    private final TeachingTaskMapper teachingTaskMapper;
    private final ClassroomMapper classroomMapper;
    private final ScheduleMapper scheduleMapper;
    private final ScheduleLockMapper scheduleLockMapper;
    private final UserMapper userMapper;
    private final SemesterMapper semesterMapper;
    private final ScheduleConflictChecker conflictChecker;
    private final JdbcTemplate jdbc;
    private final BellTimeService bellTimeService;

    /** 艺术学部/汽车学部作息时间表（节次 → 起止时间） */
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

    /** 自动适配作息：按班级年级 + 今日单双周解析节次段时间，无配置回退内置 NODE_TIMES */
    private LocalTime[] blockTimes(Long classId, int startNode, int step) {
        try {
            LocalTime[] r = bellTimeService.nodeRange(resolveGrade(classId), java.time.LocalDate.now(), startNode, step);
            if (r != null) return r;
        } catch (Exception e) {
            System.out.println("=== 作息自动适配失败，回退默认表: " + e.getMessage());
        }
        LocalTime[] first = NODE_TIMES.get(startNode);
        LocalTime[] last = NODE_TIMES.get(startNode + Math.max(step, 1) - 1);
        if (first == null) first = last;
        if (last == null) last = first;
        if (first == null || last == null) return new LocalTime[]{LocalTime.of(8, 10), LocalTime.of(8, 50)};
        return new LocalTime[]{first[0], last[1]};
    }

    /** 班级年级（如 2026级），解析失败返回 null（按默认作息处理） */
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

    public AutoScheduleService(TeachingTaskMapper teachingTaskMapper,
                                ClassroomMapper classroomMapper,
                                ScheduleMapper scheduleMapper,
                                ScheduleLockMapper scheduleLockMapper,
                                UserMapper userMapper,
                                SemesterMapper semesterMapper,
                                ScheduleConflictChecker conflictChecker,
                                JdbcTemplate jdbc,
                                BellTimeService bellTimeService) {
        this.teachingTaskMapper = teachingTaskMapper;
        this.classroomMapper = classroomMapper;
        this.scheduleMapper = scheduleMapper;
        this.scheduleLockMapper = scheduleLockMapper;
        this.userMapper = userMapper;
        this.semesterMapper = semesterMapper;
        this.conflictChecker = conflictChecker;
        this.jdbc = jdbc;
        this.bellTimeService = bellTimeService;
    }

    /**
     * 一键排课主入口。
     *
     * @param semester 学期
     * @param clearExisting 是否清空当前学期已排的非锁定课程（true=全量重排，false=只排未排的）
     * @return 排课结果统计
     */
    @Transactional
    public ScheduleResult autoSchedule(String semester, boolean clearExisting) {
        ScheduleResult result = new ScheduleResult();
        long t0 = System.currentTimeMillis();

        // 1. 加载教学任务（待排 + 失败的）
        List<TeachingTask> tasks = teachingTaskMapper.selectList(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getSemester, semester)
                        .in(TeachingTask::getStatus, "pending", "failed")
                        .orderByAsc(TeachingTask::getPriority));

        // 1.5 过滤已有锁定记录的教学任务（教师调课后已被锁定，不应被自动排课覆盖）
        Set<String> lockedTaskKeys = getLockedTaskKeys(semester);
        tasks.removeIf(t -> lockedTaskKeys.contains(t.getCourseId() + ":" + t.getClassId()));

        result.totalTasks = tasks.size();
        if (tasks.isEmpty()) {
            result.message = "没有需要排课的教学任务";
            return result;
        }

        // 2. 清空已有的非锁定课程（如果是全量重排）
        if (clearExisting) {
            clearUnlockedSchedules(semester);
        }

        // 3. 构建冲突上下文（加载已锁定的课作为硬约束）
        ScheduleConflictChecker.ConflictContext ctx =
                conflictChecker.buildContext(semester, true);

        // 4. 加载教室资源，按类型分组
        Map<String, List<Classroom>> roomsByType = loadClassroomsByType();

        // 5. 预加载班级学生ID（排课时每个学生都要写一条 schedule）
        Map<Long, List<Long>> studentsByClass = loadStudentsByClass(semester);

        // 6. 按"难度"重新排序任务（优先级 + 约束强度）
        tasks = sortTasksByDifficulty(tasks);

        // 7. 获取学期实际教学周数
        int totalWeeks = resolveSemesterWeeks(semester);

        // 8. 教室使用计数（让同类任务分散到不同教室，避免全部挤在同一间）
        Map<String, Integer> roomUsage = new HashMap<>();

        // 9. 天负载计数（让不同任务分散到不同天，避免全部挤在周一）
        Map<Integer, Integer> dayLoad = new HashMap<>();

        // 10. 贪心分配
        for (TeachingTask task : tasks) {
            boolean success = scheduleOneTask(task, ctx, roomsByType, studentsByClass, totalWeeks, roomUsage, dayLoad, result);
            if (success) {
                result.scheduled++;
                // 更新任务状态
                teachingTaskMapper.update(null,
                        new LambdaUpdateWrapper<TeachingTask>()
                                .eq(TeachingTask::getId, task.getId())
                                .set(TeachingTask::getStatus, "scheduled")
                                .set(TeachingTask::getFailReason, null));
            } else {
                result.failed++;
                // 失败原因由 scheduleOneTask 写入 result.failures
                teachingTaskMapper.update(null,
                        new LambdaUpdateWrapper<TeachingTask>()
                                .eq(TeachingTask::getId, task.getId())
                                .set(TeachingTask::getStatus, "failed")
                                .set(TeachingTask::getFailReason,
                                        result.failures.isEmpty() ? "未知原因" :
                                                result.failures.get(result.failures.size() - 1).reason));
            }
        }

        result.elapsedMs = System.currentTimeMillis() - t0;
        result.message = String.format("排课完成：共 %d 个任务，成功 %d，失败 %d，耗时 %dms",
                result.totalTasks, result.scheduled, result.failed, result.elapsedMs);
        return result;
    }

    /**
     * 排一个教学任务。可能需要排多个"块"（如每周4课时，连堂2节 = 2个块）。
     */
    private boolean scheduleOneTask(TeachingTask task,
                                     ScheduleConflictChecker.ConflictContext ctx,
                                     Map<String, List<Classroom>> roomsByType,
                                     Map<Long, List<Long>> studentsByClass,
                                     int totalWeeks,
                                     Map<String, Integer> roomUsage,
                                     Map<Integer, Integer> dayLoad,
                                     ScheduleResult result) {
        String classKey = task.getClassId().toString();
        Long teacherId = task.getTeacherId();
        int weeklyHours = task.getWeeklyHours() != null ? task.getWeeklyHours() : 2;
        int consecutive = task.getConsecutive() != null ? task.getConsecutive() : 1;
        if (consecutive < 1) consecutive = 1;
        if (consecutive > 4) consecutive = 4; // 最多4连堂

        // 计算需要几个"块"
        int blocks = (int) Math.ceil((double) weeklyHours / consecutive);

        // 获取候选时段列表（按优先级 + 天负载排序）
        List<TimeSlot> candidateSlots = generateCandidateSlots(task, dayLoad);

        // 班级人数，用于过滤容量不足的教室
        int classSize = studentsByClass.getOrDefault(task.getClassId(), Collections.emptyList()).size();

        // 获取可用教室列表（按容量过滤）
        List<Classroom> availableRooms = selectAvailableRooms(task, roomsByType, classSize);
        if (availableRooms.isEmpty() && task.getPreferredRoomType() != null
                && !task.getPreferredRoomType().equals("normal")) {
            // 专用教室不够，回退到普通教室
            availableRooms = roomsByType.getOrDefault("normal", Collections.emptyList()).stream()
                    .filter(r -> r.getCapacity() != null && r.getCapacity() >= classSize)
                    .collect(Collectors.toList());
        }
        if (availableRooms.isEmpty()) {
            result.failures.add(new FailureInfo(task, "无可用教室（类型=" + task.getPreferredRoomType() + "，班级人数=" + classSize + "）"));
            return false;
        }

        int scheduledBlocks = 0;
        Set<String> usedSlots = new HashSet<>(); // 已用时段，避免同一门课排在同一时段
        Set<Integer> usedDays = new HashSet<>(); // 已用星期，让同一任务的多块分散到不同天

        for (TimeSlot slot : candidateSlots) {
            if (scheduledBlocks >= blocks) break;

            // 跳过已分配的时段
            String slotKey = slot.dayOfWeek + "_" + slot.startNode;
            if (usedSlots.contains(slotKey)) continue;

            // 连堂长度不匹配则跳过
            if (slot.step != consecutive) continue;

            // 同一任务的不同块优先各占一天，避免全部挤在同一天
            if (usedDays.contains(slot.dayOfWeek)) continue;

            // 找一间可用教室（优先使用次数少的教室，实现负载均衡）
            Classroom room = findAvailableRoom(ctx, availableRooms, slot.dayOfWeek, slot.startNode, consecutive,
                    classKey, teacherId, roomUsage);
            if (room == null) continue;

            // 分配成功，写入 schedule（按周拆分）
            writeScheduleBlock(task, slot, room, studentsByClass, totalWeeks);

            // 更新占用矩阵：该时段在所有教学周都被占用
            for (int week = 1; week <= ctx.maxWeeks; week++) {
                conflictChecker.markOccupied(ctx, classKey, teacherId, room.getName(),
                        week, slot.dayOfWeek, slot.startNode, consecutive);
            }

            usedSlots.add(slotKey);
            usedDays.add(slot.dayOfWeek);
            dayLoad.merge(slot.dayOfWeek, slot.step, Integer::sum);
            scheduledBlocks++;
        }

        if (scheduledBlocks == 0) {
            result.failures.add(new FailureInfo(task,
                    "无法找到任何可用时段（教师=" + task.getTeacherName() +
                            "，教室类型=" + task.getPreferredRoomType() + "）"));
            return false;
        }

        if (scheduledBlocks < blocks) {
            // 部分成功（只排了一部分课时）
            result.partialScheduled++;
        }

        return true;
    }

    /**
     * 生成候选时段列表（按优先级排序）。
     *
     * 规则：
     *   - 单节（consecutive=1）：1-8 节均可
     *   - 连堂（consecutive=2）：只能从 1,3,5,7 开始，避免跨课间休息
     *   - 三连堂（consecutive=3）：只能从 1,5 开始
     *   - 四连堂（consecutive=4）：只能从 1,5 开始
     *   - 上午 = 1-4 节，下午 = 5-8 节
     *   - 首选时段优先，同优先级下按星期分散
     */
    private List<TimeSlot> generateCandidateSlots(TeachingTask task,
                                                   Map<Integer, Integer> dayLoad) {
        List<TimeSlot> slots = new ArrayList<>();
        String preferredPeriod = task.getPreferredPeriod() != null ?
                task.getPreferredPeriod() : "any";
        int consecutive = task.getConsecutive() != null ? task.getConsecutive() : 1;
        if (consecutive < 1) consecutive = 1;
        if (consecutive > 4) consecutive = 4;

        int[] days = {1, 2, 3, 4, 5};
        int[] startNodes = legalStartNodes(consecutive);

        for (int day : days) {
            for (int startNode : startNodes) {
                int period = periodOf(startNode);
                int priority;
                if ("any".equals(preferredPeriod)) {
                    priority = 2;
                } else if ("morning".equals(preferredPeriod)) {
                    priority = (period == 1) ? 1 : 3;
                } else { // afternoon
                    priority = (period == 2) ? 1 : 3;
                }
                slots.add(new TimeSlot(day, startNode, consecutive, priority));
            }
        }

        // 排序策略：
        // 1. 首选时段优先级（morning/afternoon/any）
        // 2. 天负载（已占用课时少的天优先，让不同任务分散到不同天）
        // 3. 同一任务的不同块优先各占一天（由 scheduleOneTask 中的 usedDays 保证）
        // 4. 同一天的节次从早到晚
        slots.sort(Comparator.comparingInt((TimeSlot s) -> s.priority)
                .thenComparingInt(s -> dayLoad.getOrDefault(s.dayOfWeek, 0))
                .thenComparingInt(s -> s.dayOfWeek)
                .thenComparingInt(s -> s.startNode));
        return slots;
    }

    /** 根据连堂节数返回合法的起始节次，避免跨课间休息 */
    private int[] legalStartNodes(int consecutive) {
        return switch (consecutive) {
            case 1 -> new int[]{1, 2, 3, 4, 5, 6, 7, 8};
            case 2 -> new int[]{1, 3, 5, 7};
            case 3 -> new int[]{1, 5};
            case 4 -> new int[]{1, 5};
            default -> new int[]{1, 3, 5, 7};
        };
    }

    /** 判断起始节次属于上午（1）还是下午（2） */
    private int periodOf(int startNode) {
        return startNode <= 4 ? 1 : 2;
    }

    /**
     * 选择可用的教室（按任务要求的类型和班级容量）。
     */
    private List<Classroom> selectAvailableRooms(TeachingTask task,
                                                  Map<String, List<Classroom>> roomsByType,
                                                  int classSize) {
        String roomType = task.getPreferredRoomType();
        if (roomType == null || roomType.isEmpty()) {
            roomType = "normal";
        }
        return roomsByType.getOrDefault(roomType, Collections.emptyList()).stream()
                .filter(r -> r.getCapacity() != null && r.getCapacity() >= classSize)
                .collect(Collectors.toList());
    }

    /**
     * 从候选教室中找到一间在指定时段可用的。
     * 优先选择使用次数少的教室，实现同类教室的负载均衡。
     * <p>
     * 按周拆分后，一个时段会在所有教学周重复，因此需要检查 1~maxWeeks 每一周都不冲突。
     */
    private Classroom findAvailableRoom(ScheduleConflictChecker.ConflictContext ctx,
                                         List<Classroom> rooms,
                                         int dayOfWeek, int startNode, int step,
                                         String classId, Long teacherId,
                                         Map<String, Integer> roomUsage) {
        List<Classroom> sortedRooms = rooms.stream()
                .sorted(Comparator.comparingInt(r -> roomUsage.getOrDefault(r.getName(), 0)))
                .collect(Collectors.toList());
        for (Classroom room : sortedRooms) {
            boolean anyConflict = false;
            for (int week = 1; week <= ctx.maxWeeks; week++) {
                ScheduleConflictChecker.ConflictResult cr = conflictChecker.checkConflict(
                        ctx, classId, teacherId, room.getName(), week, dayOfWeek, startNode, step);
                if (cr.conflict) {
                    anyConflict = true;
                    break;
                }
            }
            if (!anyConflict) {
                roomUsage.merge(room.getName(), 1, Integer::sum);
                return room;
            }
        }
        return null;
    }

    /**
     * 把一个排课块写入 schedule 表（按周拆分为每条记录只对应一周）。
     * 为班级每个学生在每周各写一条记录。
     */
    private void writeScheduleBlock(TeachingTask task, TimeSlot slot, Classroom room,
                                     Map<Long, List<Long>> studentsByClass, int totalWeeks) {
        List<Long> studentIds = studentsByClass.getOrDefault(task.getClassId(), Collections.emptyList());
        // 自动适配作息：按班级年级 + 当日单双周解析节次时间，无配置时回退内置默认表
        LocalTime[] range = blockTimes(task.getClassId(), slot.startNode, slot.step);
        LocalTime startTime = range[0];
        LocalTime endTime = range[1];

        for (int week = 1; week <= totalWeeks; week++) {
            String weekJson = "[" + week + "]";
            for (Long sid : studentIds) {
                Schedule s = new Schedule();
                s.setUserId(sid);
                s.setCourseId(task.getCourseId());
                s.setCourseName(task.getCourseName());
                s.setDayOfWeek(slot.dayOfWeek);
                s.setStartTime(startTime);
                s.setEndTime(endTime);
                s.setStartNode(slot.startNode);
                s.setStep(slot.step);
                s.setClassroom(room.getName());
                s.setSemester(task.getSemester());
                s.setWeeks(weekJson);
                s.setStatus(1);
                scheduleMapper.insert(s);
            }
        }
    }

    /**
     * 根据学期名称查询教学周数，查不到默认 20 周。
     */
    private int resolveSemesterWeeks(String semester) {
        Semester s = semesterMapper.selectOne(
                new LambdaQueryWrapper<Semester>().eq(Semester::getName, semester));
        if (s != null && s.getWeekCount() != null && s.getWeekCount() > 0) {
            return s.getWeekCount();
        }
        return 20;
    }

    /**
     * 生成 1~n 周的 JSON 数组字符串。
     */
    private String generateWeeksJson(int totalWeeks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= totalWeeks; i++) {
            if (i > 1) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 清空当前学期未锁定的课表（全量重排前调用）。
     */
    private void clearUnlockedSchedules(String semester) {
        // 找出锁定的 schedule_id
        Set<Long> lockedIds = scheduleLockMapper.selectList(
                new LambdaQueryWrapper<ScheduleLock>().eq(ScheduleLock::getSemester, semester))
                .stream().map(ScheduleLock::getScheduleId).collect(Collectors.toSet());

        if (lockedIds.isEmpty()) {
            // 全部删除
            jdbc.update("DELETE FROM schedule WHERE semester = ? AND status = 1", semester);
        } else {
            // 只删未锁定的
            jdbc.update("DELETE FROM schedule WHERE semester = ? AND status = 1 AND id NOT IN (" +
                    lockedIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")",
                    semester);
        }
    }

    /**
     * 获取当前学期已被锁定的教学任务 key 集合（courseId:classId）。
     * 这些任务即使状态被重置为 pending，也不应被自动排课覆盖。
     */
    private Set<String> getLockedTaskKeys(String semester) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DISTINCT s.course_id, u.class_id " +
                "FROM schedule_lock sl " +
                "JOIN schedule s ON s.id = sl.schedule_id " +
                "JOIN user u ON u.id = s.user_id " +
                "WHERE sl.semester = ?", semester);
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Long courseId = ((Number) row.get("course_id")).longValue();
            Long classId = ((Number) row.get("class_id")).longValue();
            keys.add(courseId + ":" + classId);
        }
        return keys;
    }

    /**
     * 加载所有教室，按类型分组。
     */
    private Map<String, List<Classroom>> loadClassroomsByType() {
        List<Classroom> all = classroomMapper.selectList(
                new LambdaQueryWrapper<Classroom>().eq(Classroom::getIsActive, 1));
        Map<String, List<Classroom>> map = new HashMap<>();
        for (Classroom c : all) {
            map.computeIfAbsent(c.getType(), k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    /**
     * 加载每个班级的学生ID列表。
     */
    private Map<Long, List<Long>> loadStudentsByClass(String semester) {
        Map<Long, List<Long>> map = new HashMap<>();
        List<User> students = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, 1)
                        .isNotNull(User::getClassId));
        for (User u : students) {
            map.computeIfAbsent(u.getClassId(), k -> new ArrayList<>()).add(u.getId());
        }
        return map;
    }

    /**
     * 按难度排序任务（约束越多越先排）。
     *
     * 难度公式：
     *   基础分 = priority 字段
     *   需要实训室 = -3（更先排）
     *   连堂课 = -2
     *   专业核心课 = -1
     */
    private List<TeachingTask> sortTasksByDifficulty(List<TeachingTask> tasks) {
        return tasks.stream()
                .sorted(Comparator.comparingInt(this::calculateDifficulty))
                .collect(Collectors.toList());
    }

    private int calculateDifficulty(TeachingTask task) {
        int score = task.getPriority() != null ? task.getPriority() : 5;
        // 实训室/专用教室优先排
        String roomType = task.getPreferredRoomType();
        if (roomType != null && !roomType.equals("normal") && !roomType.isEmpty()) {
            score -= 3;
        }
        // 连堂课优先排
        if (task.getConsecutive() != null && task.getConsecutive() > 1) {
            score -= 2;
        }
        return score;
    }

    // ===== 内部数据结构 =====

    /** 时段候选 */
    private static class TimeSlot {
        int dayOfWeek;
        int startNode;
        int step;
        int priority; // 越小越优先

        TimeSlot(int dayOfWeek, int startNode, int step, int priority) {
            this.dayOfWeek = dayOfWeek;
            this.startNode = startNode;
            this.step = step;
            this.priority = priority;
        }
    }

    /** 排课结果 */
    public static class ScheduleResult {
        public int totalTasks;
        public int scheduled;
        public int failed;
        public int partialScheduled; // 部分成功（课时数不够）
        public long elapsedMs;
        public String message;
        public List<FailureInfo> failures = new ArrayList<>();
    }

    /** 失败信息 */
    public static class FailureInfo {
        public Long taskId;
        public String courseName;
        public Long classId;
        public String reason;

        public FailureInfo(TeachingTask task, String reason) {
            this.taskId = task.getId();
            this.courseName = task.getCourseName();
            this.classId = task.getClassId();
            this.reason = reason;
        }
    }
}
