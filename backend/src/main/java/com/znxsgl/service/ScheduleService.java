package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.dto.StudentCourseDTO;
import com.znxsgl.dto.StudentScheduleDTO;
import com.znxsgl.dto.TeacherCourseDTO;
import com.znxsgl.entity.*;
import com.znxsgl.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ScheduleService {

    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final ScheduleMapper scheduleMapper;
    private final ClassInfoMapper classInfoMapper;
    private final TeacherMapper teacherMapper;
    private final SemesterMapper semesterMapper;
    private final TeachingTaskMapper teachingTaskMapper;
    private final JdbcTemplate jdbc;
    private final SemesterService semesterService;

    public ScheduleService(UserMapper userMapper, CourseMapper courseMapper,
                           ScheduleMapper scheduleMapper, ClassInfoMapper classInfoMapper,
                           TeacherMapper teacherMapper,
                           SemesterMapper semesterMapper, TeachingTaskMapper teachingTaskMapper,
                           JdbcTemplate jdbc, SemesterService semesterService) {
        this.userMapper = userMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.classInfoMapper = classInfoMapper;
        this.teacherMapper = teacherMapper;
        this.semesterMapper = semesterMapper;
        this.teachingTaskMapper = teachingTaskMapper;
        this.jdbc = jdbc;
        this.semesterService = semesterService;
    }

    // ===== 教师：查看所教课程及班级（含下架状态） =====
    public List<TeacherCourseDTO> getTeacherCourses(Long teacherUserId) {
        User user = userMapper.selectById(teacherUserId);
        if (user == null || user.getRole() < 2) return Collections.emptyList();

        Teacher teacher = teacherMapper.selectOne(new LambdaQueryWrapper<Teacher>().eq(Teacher::getRealName, user.getRealName()));
        // 容错：如果 user 是教师角色但 teacher 表没有记录，自动创建一条
        if (teacher == null && user.getRole() == 2) {
            teacher = new Teacher();
            teacher.setRealName(user.getRealName());
            teacher.setTeacherNo(user.getStudentNo());
            teacher.setEmail(user.getEmail());
            teacher.setPhone(user.getPhone());
            teacher.setStatus(user.getStatus() != null ? user.getStatus() : 1);
            teacherMapper.insert(teacher);
        }
        if (teacher == null) return Collections.emptyList();

        // 优先从教学任务表获取该教师实际授课的课程，更精准
        List<Long> courseIds = teachingTaskMapper.selectList(
                new LambdaQueryWrapper<TeachingTask>()
                        .eq(TeachingTask::getTeacherId, teacher.getId())
                        .select(TeachingTask::getCourseId))
                .stream()
                .map(TeachingTask::getCourseId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 若教学任务表无数据，回退到 course.teacher_id（兼容旧数据）
        if (courseIds.isEmpty()) {
            List<Course> courses = courseMapper.selectList(
                    new LambdaQueryWrapper<Course>().eq(Course::getTeacherId, teacher.getId()));
            return buildCourseDTOList(courses);
        }

        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().in(Course::getId, courseIds));
        return buildCourseDTOList(courses);
    }

    // ===== 管理员：查看已上架课程及班级 =====
    public List<TeacherCourseDTO> getAllCoursesForAdmin() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().orderByDesc(Course::getId));
        List<TeacherCourseDTO> result = buildCourseDTOList(courses);
        // 管理员在章节管理页只显示已上架课程（有 status=1 的活跃排课记录）
        return result.stream()
                .filter(TeacherCourseDTO::isActive)
                .collect(Collectors.toList());
    }

    // ===== 公共：根据课程列表构建 TeacherCourseDTO =====
    // 批量查询替代逐课程 N+1 查询，避免课程多时接口过慢（如 145 门课程 × 5 条查询 ≈ 800 次 DB 往返）
    private List<TeacherCourseDTO> buildCourseDTOList(List<Course> courses) {
        if (courses.isEmpty()) return Collections.emptyList();

        List<Long> courseIds = courses.stream().map(Course::getId).collect(Collectors.toList());
        String courseIdIn = courseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 课程名可能重复（不同学期同名课程），不能直接用 course_name 关联，需通过 course_class 的 course_id 精确匹配
        String courseNameIn = courses.stream().map(Course::getCourseName)
                .map(n -> "'" + n.replace("'", "''") + "'")
                .collect(Collectors.joining(","));

        // 1. 批量加载教师姓名
        Map<Long, String> teacherNameMap = new HashMap<>();
        Set<Long> teacherIds = courses.stream().map(Course::getTeacherId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!teacherIds.isEmpty()) {
            String teacherIdIn = teacherIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbc.queryForList("SELECT id, real_name FROM teacher WHERE id IN (" + teacherIdIn + ")")
                    .forEach(row -> teacherNameMap.put(((Number) row.get("id")).longValue(), (String) row.get("real_name")));
        }

        // 2. 批量加载课程-班级关联（含学生数、所属学期）
        Map<Long, List<Map<String, Object>>> ccByCourse = new HashMap<>();
        jdbc.queryForList(
                "SELECT cc.course_id AS courseId, ci.id AS classId, ci.class_name AS className, " +
                "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.role = 1) AS studentCount, cc.semester " +
                "FROM course_class cc JOIN class_info ci ON ci.id = cc.class_id " +
                "WHERE cc.course_id IN (" + courseIdIn + ")")
                .forEach(row -> ccByCourse.computeIfAbsent(
                        ((Number) row.get("courseId")).longValue(), k -> new ArrayList<>()).add(row));

        // 3. 批量加载各班级最大排课记录 ID（用于调课定位，含下架记录，排除占位 day_of_week=0）
        Map<String, Long> scheduleIdByCourseClass = new HashMap<>();
        jdbc.queryForList(
                "SELECT s.course_name AS courseName, u.class_id AS classId, MAX(s.id) AS maxId " +
                "FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name IN (" + courseNameIn + ") AND s.day_of_week > 0 " +
                "GROUP BY s.course_name, u.class_id")
                .forEach(row -> scheduleIdByCourseClass.put(
                        row.get("courseName") + "|" + row.get("classId"),
                        ((Number) row.get("maxId")).longValue()));

        // 4. 批量判断每门课程是否活跃（存在 status=1 且用户班级关联本课程的排课记录）
        Map<Long, Long> activeCountByCourse = new HashMap<>();
        jdbc.queryForList(
                "SELECT c.id AS courseId, COUNT(DISTINCT s.id) AS cnt " +
                "FROM schedule s JOIN user u ON u.id = s.user_id " +
                "JOIN course_class cc ON cc.class_id = u.class_id " +
                "JOIN course c ON c.id = cc.course_id " +
                "WHERE c.id IN (" + courseIdIn + ") AND s.course_name = c.course_name AND s.status = 1 " +
                "GROUP BY c.id")
                .forEach(row -> activeCountByCourse.put(
                        ((Number) row.get("courseId")).longValue(), ((Number) row.get("cnt")).longValue()));

        // 5. 批量加载每门课程的排课时间信息（上架且非占位，DISTINCT 去重后按星期/时间排序）
        Map<Long, List<Map<String, Object>>> repRowsByCourse = new HashMap<>();
        jdbc.queryForList(
                "SELECT DISTINCT c.id AS courseId, s.day_of_week, s.start_node, s.step, " +
                "s.start_time, s.end_time, s.classroom, s.weeks " +
                "FROM schedule s JOIN user u ON u.id = s.user_id " +
                "JOIN course_class cc ON cc.class_id = u.class_id " +
                "JOIN course c ON c.id = cc.course_id " +
                "WHERE c.id IN (" + courseIdIn + ") AND s.course_name = c.course_name " +
                "  AND s.status = 1 AND s.day_of_week > 0 " +
                "ORDER BY c.id, s.day_of_week, s.start_time")
                .forEach(row -> repRowsByCourse.computeIfAbsent(
                        ((Number) row.get("courseId")).longValue(), k -> new ArrayList<>()).add(row));

        List<TeacherCourseDTO> result = new ArrayList<>();
        for (Course course : courses) {
            TeacherCourseDTO dto = new TeacherCourseDTO();
            dto.setCourseId(course.getId());
            dto.setCourseName(course.getCourseName());
            dto.setSemester(course.getSemester());
            dto.setTeacherId(course.getTeacherId());
            dto.setCourseType(course.getCourseType());
            dto.setDescription(course.getDescription());
            dto.setCredit(course.getCredit());
            dto.setTeacherName(teacherNameMap.get(course.getTeacherId()));

            // 班级列表（仅保留当前学期关联）
            List<TeacherCourseDTO.ClazzDTO> clazzList = new ArrayList<>();
            for (Map<String, Object> row : ccByCourse.getOrDefault(course.getId(), Collections.emptyList())) {
                if (!course.getSemester().equals(row.get("semester"))) continue;
                TeacherCourseDTO.ClazzDTO clazz = new TeacherCourseDTO.ClazzDTO();
                Long cid = ((Number) row.get("classId")).longValue();
                clazz.setClassId(cid);
                clazz.setClassName((String) row.get("className"));
                clazz.setStudentCount(((Number) row.get("studentCount")).intValue());
                Long scheduleId = scheduleIdByCourseClass.get(course.getCourseName() + "|" + cid);
                clazz.setScheduled(scheduleId != null);
                clazz.setScheduleId(scheduleId);
                clazzList.add(clazz);
            }
            dto.setClasses(clazzList);

            Long activeCount = activeCountByCourse.get(course.getId());
            dto.setActive(activeCount != null && activeCount > 0);

            List<Map<String, Object>> repRows = repRowsByCourse.getOrDefault(course.getId(), Collections.emptyList());
            if (!repRows.isEmpty()) {
                Map<String, Object> r = repRows.get(0);
                dto.setDayOfWeek(((Number) r.get("day_of_week")).intValue());
                dto.setStartNode(((Number) r.get("start_node")).intValue());
                dto.setStep(((Number) r.get("step")).intValue());
                dto.setStartTime(r.get("start_time") == null ? null : r.get("start_time").toString());
                dto.setEndTime(r.get("end_time") == null ? null : r.get("end_time").toString());
                dto.setClassroom((String) r.get("classroom"));
                dto.setWeeks((String) r.get("weeks"));
                dto.setScheduleInfo(buildScheduleInfo(repRows));
            }

            result.add(dto);
        }
        return result;
    }

    /** 构建课程卡片最后一条消息摘要 */
    private String buildLastMessagePreview(Map<String, Object> row) {
        String content = row.get("content") != null ? row.get("content").toString() : "";
        String senderRole = row.get("sender_role") != null ? row.get("sender_role").toString() : "";
        String senderName = row.get("sender_name") != null ? row.get("sender_name").toString() : "";

        String prefix;
        if ("ai".equals(senderRole)) {
            prefix = "AI：";
        } else if (senderName != null && !senderName.isEmpty()) {
            prefix = senderName + "：";
        } else {
            prefix = "";
        }

        String body;
        if (content.startsWith("[image]")) {
            body = "[图片]";
        } else if (content.startsWith("[file]")) {
            String rest = content.substring(6);
            int sep = rest.indexOf('|');
            body = "[文件] " + (sep > 0 ? rest.substring(0, sep) : rest);
        } else {
            body = content;
        }

        String preview = prefix + body;
        if (preview.length() > 36) {
            preview = preview.substring(0, 33) + "...";
        }
        return preview;
    }

    /** 将多条排课记录聚合为展示用摘要，限制条目数避免列表过长 */
    private String buildScheduleInfo(List<Map<String, Object>> rows) {
        String[] dayNames = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<String> infos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object dowObj = r.get("day_of_week");
            if (dowObj == null) continue;
            int dow = ((Number) dowObj).intValue();
            String start = r.get("start_time") == null ? "" : r.get("start_time").toString();
            String end = r.get("end_time") == null ? "" : r.get("end_time").toString();
            String key = dow + "|" + start + "|" + end;
            if (seen.add(key)) {
                infos.add(dayNames[dow] + " " + start + "-" + end);
            }
        }
        if (infos.size() <= 3) {
            return String.join("；", infos);
        }
        return String.join("；", infos.subList(0, 3)) + " 等" + infos.size() + "个时段";
    }

    // ===== 学生：按周查看课表 =====
    public List<StudentScheduleDTO> getStudentSchedule(Long userId, int week) {
        return getStudentSchedule(userId, week, null);
    }

    public List<StudentScheduleDTO> getStudentSchedule(Long userId, int week, String semester) {
        // 按学生所在班级查询生效学期（假期培训只显示已关联班级的）
        User user = userMapper.selectById(userId);
        Long classId = user != null ? user.getClassId() : null;
        List<String> activeSemesters;
        if (semester != null && !semester.trim().isEmpty()) {
            // 指定学期时先校验该学生是否有权限查看此学期
            if (!semesterVisibleToClass(semester, classId)) {
                return Collections.emptyList();
            }
            activeSemesters = Collections.singletonList(semester);
        } else {
            activeSemesters = classId != null
                    ? semesterService.getActiveSemesterNamesByClassId(classId)
                    : semesterService.getActiveSemesterNames();
        }
        if (activeSemesters.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<Schedule> qw = new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getUserId, userId)
                .in(Schedule::getSemester, activeSemesters)
                .eq(Schedule::getStatus, 1)
                .gt(Schedule::getDayOfWeek, 0);

        // 按周过滤：weeks 为 JSON 数组，使用 JSON_CONTAINS
        if (week > 0) {
            qw.apply("JSON_CONTAINS(weeks, CAST({0} AS JSON))", week);
        }

        List<Schedule> schedules;
        try {
            schedules = scheduleMapper.selectList(qw);
        } catch (Exception e) {
            log.warn("学生课表查询异常 userId={} week={} semester={}", userId, week, semester, e);
            return Collections.emptyList();
        }

        return schedules.stream().map(s -> {
            StudentScheduleDTO dto = new StudentScheduleDTO();
            dto.setScheduleId(s.getId());
            dto.setCourseId(s.getCourseId());
            dto.setCourseName(s.getCourseName());
            dto.setDayOfWeek(s.getDayOfWeek());
            dto.setStartTime(s.getStartTime());
            dto.setEndTime(s.getEndTime());
            dto.setStartNode(s.getStartNode());
            dto.setStep(s.getStep());
            dto.setClassroom(s.getClassroom());
            dto.setSemester(s.getSemester());
            dto.setWeeks(s.getWeeks());
            dto.setTeacherName("");

            // 尝试从课程表获取教师名
            if (s.getCourseId() != null) {
                Course course = courseMapper.selectById(s.getCourseId());
                if (course != null && course.getTeacherId() != null) {
                    Teacher t = teacherMapper.selectById(course.getTeacherId());
                    if (t != null) dto.setTeacherName(t.getRealName());
                }
            }
            return dto;
        }).collect(Collectors.toList());
    }

    // ===== 学生：获取可见的学期列表（正常学期 + 本班假期培训） =====
    public List<com.znxsgl.dto.SemesterDTO> getStudentSemesters(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getClassId() == null) {
            return Collections.emptyList();
        }
        Long classId = user.getClassId();

        List<com.znxsgl.dto.SemesterDTO> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // 1. 所有正常学期（NORMAL），按开始日期倒序
        List<Semester> normalSemesters = semesterMapper.selectList(
                new LambdaQueryWrapper<Semester>()
                        .eq(Semester::getSemesterType, "NORMAL")
                        .orderByDesc(Semester::getStartDate));
        for (Semester s : normalSemesters) {
            com.znxsgl.dto.SemesterDTO dto = convertToSemesterDTO(s);
            result.add(dto);
        }

        // 2. 本班关联的假期培训学期（EXTRA）
        List<Map<String, Object>> extraRows = jdbc.queryForList(
                "SELECT s.* FROM semester s " +
                "JOIN semester_class sc ON sc.semester_id = s.id " +
                "WHERE s.semester_type = 'EXTRA' AND sc.class_id = ? " +
                "ORDER BY s.start_date DESC",
                classId);
        for (Map<String, Object> row : extraRows) {
            com.znxsgl.dto.SemesterDTO dto = new com.znxsgl.dto.SemesterDTO();
            dto.setId(((Number) row.get("id")).longValue());
            dto.setName((String) row.get("name"));
            dto.setStartDate(((java.sql.Date) row.get("start_date")).toLocalDate());
            dto.setEndDate(((java.sql.Date) row.get("end_date")).toLocalDate());
            dto.setWeekCount((Integer) row.get("week_count"));
            Object isCurrentObj = row.get("is_current");
            if (isCurrentObj instanceof Boolean) {
                dto.setIsCurrent((Boolean) isCurrentObj);
            } else if (isCurrentObj instanceof Number) {
                dto.setIsCurrent(((Number) isCurrentObj).intValue() == 1);
            } else {
                dto.setIsCurrent(false);
            }
            dto.setSemesterType((String) row.get("semester_type"));
            // 计算状态
            LocalDate start = dto.getStartDate();
            LocalDate end = dto.getEndDate();
            if (today.isBefore(start)) {
                dto.setStatus("before");
            } else if (today.isAfter(end)) {
                dto.setStatus("ended");
            } else {
                dto.setStatus("ongoing");
            }
            result.add(dto);
        }

        // 3. 将当前学期置顶，确保移动端优先选中当前学期
        result.sort((a, b) -> {
            if (Boolean.TRUE.equals(a.getIsCurrent()) && !Boolean.TRUE.equals(b.getIsCurrent())) {
                return -1;
            }
            if (Boolean.TRUE.equals(b.getIsCurrent()) && !Boolean.TRUE.equals(a.getIsCurrent())) {
                return 1;
            }
            return b.getStartDate().compareTo(a.getStartDate());
        });

        return result;
    }

    /**
     * 判断指定学期对某班级是否可见：
     * - NORMAL 学期：所有班级可见
     * - EXTRA 学期：需 semester_class 关联；当前生效的假期培训学期对关联班级始终可见，便于提前查看/排课
     */
    private boolean semesterVisibleToClass(String semester, Long classId) {
        if (semester == null || semester.trim().isEmpty()) return true;
        Semester s = semesterMapper.selectOne(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getName, semester));
        if (s == null) return false;
        if ("NORMAL".equals(s.getSemesterType())) return true;
        if (classId == null) return false;

        // 当前生效的假期培训学期对关联班级始终可见（不限制日期）
        if (s.getIsCurrent() != null && s.getIsCurrent() == 1) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM semester_class WHERE semester_id = ? AND class_id = ?",
                    Integer.class, s.getId(), classId);
            return count != null && count > 0;
        }

        // 非当前假期培训学期需在日期范围内
        LocalDate today = LocalDate.now();
        if (today.isBefore(s.getStartDate()) || today.isAfter(s.getEndDate())) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM semester_class WHERE semester_id = ? AND class_id = ?",
                Integer.class, s.getId(), classId);
        return count != null && count > 0;
    }

    private com.znxsgl.dto.SemesterDTO convertToSemesterDTO(Semester s) {
        com.znxsgl.dto.SemesterDTO dto = new com.znxsgl.dto.SemesterDTO();
        dto.setId(s.getId());
        dto.setName(s.getName());
        dto.setStartDate(s.getStartDate());
        dto.setEndDate(s.getEndDate());
        dto.setWeekCount(s.getWeekCount());
        dto.setIsCurrent(s.getIsCurrent() == 1);
        dto.setSemesterType(s.getSemesterType());
        // 计算状态
        LocalDate today = LocalDate.now();
        if (today.isBefore(s.getStartDate())) {
            dto.setStatus("before");
        } else if (today.isAfter(s.getEndDate())) {
            dto.setStatus("ended");
        } else {
            dto.setStatus("ongoing");
        }
        return dto;
    }

    // ===== 学生：查看"我的课程"列表（含上下架状态） =====
    public List<StudentCourseDTO> getStudentCourses(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getClassId() == null) return Collections.emptyList();
        Long classId = user.getClassId();

        // 查询该班级关联的所有课程（通过 course_class 表 + schedule 表兜底）
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT DISTINCT c.id, c.course_name, c.semester, c.course_type, c.description, c.credit, " +
            "t.real_name AS teacher_name " +
            "FROM course c " +
            "LEFT JOIN course_class cc ON cc.course_id = c.id AND cc.class_id = ? " +
            "LEFT JOIN schedule s ON s.course_id = c.id AND s.user_id IN (SELECT id FROM user WHERE class_id = ?) " +
            "LEFT JOIN teacher t ON t.id = c.teacher_id " +
            "WHERE cc.class_id = ? OR s.id IS NOT NULL " +
            "ORDER BY c.course_name",
            classId, classId, classId);

        List<StudentCourseDTO> result = new ArrayList<>();

        // 预加载各课程已上架的章节数（用于“章节”入口显隐）
        Map<Long, Integer> chapterCountMap = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT course_id, COUNT(*) AS cnt FROM course_chapter " +
                    "WHERE deleted = 0 AND status = 1 GROUP BY course_id")) {
                chapterCountMap.put(((Number) r.get("course_id")).longValue(), ((Number) r.get("cnt")).intValue());
            }
        } catch (Exception e) {
            log.warn("查询课程章节数失败", e);
        }

        for (Map<String, Object> row : rows) {
            StudentCourseDTO dto = new StudentCourseDTO();
            dto.setCourseId(((Number) row.get("id")).longValue());
            dto.setCourseName((String) row.get("course_name"));
            dto.setTeacherName((String) row.get("teacher_name"));
            dto.setSemester((String) row.get("semester"));
            dto.setCourseType((String) row.get("course_type"));
            dto.setDescription((String) row.get("description"));
            dto.setHasChapters(chapterCountMap.getOrDefault(dto.getCourseId(), 0) > 0);

            // 检查课程是否在线（本班级有 status=1 且 day_of_week>0 即在线）
            int activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 1 AND s.day_of_week > 0",
                Integer.class, dto.getCourseName(), classId);
            dto.setActive(activeCount > 0);

            // 检查课程是否已发布/上架（本班级有 status=1 记录即已上架，含 day_of_week=0 占位）
            int publishedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 1",
                Integer.class, dto.getCourseName(), classId);
            dto.setPublished(publishedCount > 0);

            // 查询该学生在此课程的未读消息数（仅 is_read=0）
            int unread = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message " +
                "WHERE course_name = ? AND user_id = ? AND sender_role = 'teacher' AND is_read = 0",
                Integer.class, dto.getCourseName(), userId);
            dto.setUnreadCount(unread);

            // 查询该学生可见的最后一条聊天消息摘要
            try {
                List<Map<String, Object>> lastMsgs = jdbc.queryForList(
                    "SELECT content, sender_role, sender_name FROM chat_message " +
                    "WHERE course_name = ? AND sender_role != 'student' " +
                    "  AND (user_id = ? OR mention_user_id IS NULL OR mention_user_id = ?) " +
                    "ORDER BY created_at DESC, id DESC LIMIT 1",
                    dto.getCourseName(), userId, userId);
                if (!lastMsgs.isEmpty()) {
                    dto.setLastMessage(buildLastMessagePreview(lastMsgs.get(0)));
                }
            } catch (Exception e) {
                log.warn("查询课程最后一条消息失败: courseName={}, userId={}", dto.getCourseName(), userId, e);
            }

            // 检查本班级是否有排课
            int myScheduleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule s JOIN user u ON u.id = s.user_id " +
                "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 1 AND s.day_of_week > 0",
                Integer.class, dto.getCourseName(), classId);
            dto.setHasSchedule(myScheduleCount > 0);

            // 查询排课摘要（本班级的）
            if (myScheduleCount > 0) {
                List<Map<String, Object>> scheduleRows = jdbc.queryForList(
                    "SELECT DISTINCT s.day_of_week, s.start_time, s.end_time FROM schedule s " +
                    "JOIN user u ON u.id = s.user_id " +
                    "WHERE s.course_name = ? AND u.class_id = ? AND s.status = 1 AND s.day_of_week > 0 " +
                    "ORDER BY s.day_of_week LIMIT 3",
                    dto.getCourseName(), classId);
                List<String> infos = new ArrayList<>();
                String[] dayNames = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
                for (Map<String, Object> sr : scheduleRows) {
                    int dow = ((Number) sr.get("day_of_week")).intValue();
                    infos.add(dayNames[dow] + " " + sr.get("start_time") + "-" + sr.get("end_time"));
                }
                dto.setScheduleInfo(String.join("；", infos));
            }

            // 查询该课程下待完成的考试/作业（含已发布未到开始时间的，都显示入口）
            // submitStatus: 0=未开始/未提交, 1=未完成(保存了草稿/进行中), 2=已完成(已提交)
            List<Map<String, Object>> pendingExams = jdbc.queryForList(
                "SELECT e.id, e.type, e.title, e.start_time AS startTime, e.end_time AS endTime, " +
                "       CASE WHEN s.id IS NULL THEN 0 " +
                "            WHEN s.status = 'completed' THEN 2 " +
                "            ELSE 1 END AS submitStatus " +
                "FROM exam_homework e " +
                "LEFT JOIN exam_submission s ON s.exam_homework_id = e.id AND s.user_id = ? " +
                "WHERE e.course_id = ? AND e.class_id = ? AND e.status = 1 " +
                "  AND e.end_time > NOW() " +  // 只要没结束都展示（开始时间可能在未来，显示"未开始"）
                "ORDER BY CASE WHEN e.start_time <= NOW() THEN 0 ELSE 1 END, e.end_time",
                userId, dto.getCourseId(), classId);
            dto.setPendingExams(pendingExams);

            result.add(dto);
        }
        return result;
    }
}
